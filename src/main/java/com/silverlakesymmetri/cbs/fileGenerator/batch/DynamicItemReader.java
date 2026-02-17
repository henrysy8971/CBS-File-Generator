package com.silverlakesymmetri.cbs.fileGenerator.batch;

import com.silverlakesymmetri.cbs.fileGenerator.config.InterfaceConfigLoader;
import com.silverlakesymmetri.cbs.fileGenerator.config.model.InterfaceConfig;
import com.silverlakesymmetri.cbs.fileGenerator.dto.ColumnType;
import com.silverlakesymmetri.cbs.fileGenerator.dto.DynamicRecord;
import com.silverlakesymmetri.cbs.fileGenerator.dto.RecordSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.NonTransientResourceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.persistence.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
@StepScope
public class DynamicItemReader implements ItemStreamReader<DynamicRecord> {
	private static final Logger logger = LoggerFactory.getLogger(DynamicItemReader.class);
	private static final String CONTEXT_KEY_TOTAL = "dynamic.reader.totalProcessed";
	private static final String CONTEXT_KEY_LAST_ID = "dynamic.reader.lastProcessedId";

	private final int pageSize;
	private String interfaceType;
	private String queryString;
	private String keySetColumnName;
	private final EntityManager entityManager;
	private final InterfaceConfigLoader interfaceConfigLoader;
	private RecordSchema sharedSchema;
	private List<Tuple> currentPage;

	private int currentIndex = 0;
	private boolean endReached = false;

	private String lastProcessedId = null;
	private long totalProcessed = 0;
	private ColumnType keyColumnType;
	private int keySetColumnIndex = -1;
	private static final Object SCHEMA_LOCK = new Object();

	@Autowired
	public DynamicItemReader(
			InterfaceConfigLoader interfaceConfigLoader,
			EntityManager entityManager,
			@Value("${file.generation.chunk-size:1000}") int pageSize
	) {
		this.interfaceConfigLoader = interfaceConfigLoader;
		this.entityManager = entityManager;
		this.pageSize = pageSize;
		if (pageSize <= 0) {
			throw new IllegalArgumentException("pageSize must be > 0");
		}
	}

	@Value("#{jobParameters['interfaceType']}")
	public void setInterfaceType(String interfaceType) {
		this.interfaceType = interfaceType;
	}

	@PostConstruct
	public void init() {
		if (interfaceType == null || interfaceType.isEmpty()) {
			throw new IllegalArgumentException("Job Parameter 'interfaceType' is missing");
		}

		InterfaceConfig config = interfaceConfigLoader.getConfig(interfaceType);
		if (config == null) {
			throw new IllegalArgumentException("Interface configuration not found for: " + interfaceType);
		}

		this.queryString = config.getDataSourceQuery();
		this.keySetColumnName = config.getKeySetColumn();

		if (this.queryString == null) {
			throw new IllegalArgumentException("Data source query must be defined for " + interfaceType);
		}

		// VALIDATION: Check for infinite loop risk
		if (this.keySetColumnName != null && !this.queryString.contains(":lastId")) {
			throw new IllegalArgumentException(String.format(
					"Configuration Error for [%s]: 'keySetColumn' is defined as '%s', but SQL query is missing ':lastId'.",
					interfaceType, keySetColumnName
			));
		}

		logger.info("DynamicItemReader initialized for interface: {}", interfaceType);
	}

	@Override
	public DynamicRecord read() {
		try {
			if (endReached) {
				return null;
			}
			if (currentPage == null || currentIndex >= currentPage.size()) {
				fetchNextPage();
			}
			if (endReached) {
				return null;
			}
			Tuple row = currentPage.get(currentIndex++);
			if (sharedSchema == null) {
				initializeSchemaFromTuple(row);
			}
			if (keySetColumnIndex != -1) {
				lastProcessedId = parseLastProcessedId(row.get(keySetColumnIndex));
			}
			totalProcessed++;
			return convertRowToRecord(row);
		} catch (NonTransientResourceException e) {
			// Fatal database error – rethrow immediately
			logger.error("Non-transient resource failure while reading interface {}", interfaceType, e);
			throw e;
		} catch (PersistenceException e) {
			// Likely database issue – treat as fatal
			logger.error("Persistence error while reading interface {}", interfaceType, e);
			throw new NonTransientResourceException("Persistence error reading interface " + interfaceType, e);
		} catch (RuntimeException e) {
			// Programming or unexpected runtime issue
			logger.error("Unexpected runtime error while reading interface {}", interfaceType, e);
			throw e;
		} catch (Exception e) {
			// Truly unexpected checked exception
			logger.error("Unexpected checked exception while reading interface {}", interfaceType, e);
			throw new RuntimeException("Unexpected read failure", e);
		}
	}

	@Retryable(
			value = {javax.persistence.QueryTimeoutException.class, DataAccessResourceFailureException.class},
			maxAttempts = 3,
			backoff = @Backoff(delay = 1000, multiplier = 2))
	private void fetchNextPage() {
		Query query = entityManager.createNativeQuery(queryString, Tuple.class);

		/*
		 * Configure JDBC fetch size independently of page size to optimize memory
		 * usage and network performance when querying Oracle.
		 *
		 * Setting fetch_size equal to pageSize may cause the JDBC driver to load the
		 * entire result set chunk into memory at once, which can increase heap usage,
		 * GC pressure, and network packet size for large pages.
		 *
		 * Instead, we calculate a bounded fetch size:
		 *   - Minimum: 100 rows (avoid excessive round-trips for small pages)
		 *   - Maximum: 500 rows (prevent large memory/network spikes)
		 *   - Otherwise: pageSize / 5 (balanced subset of the requested page)
		 *
		 * This approach provides controlled batching between the database and the
		 * application while still respecting the overall pageSize limit enforced by
		 * setMaxResults().
		 *
		 * Note:
		 *   - fetch_size affects how many rows are retrieved per round-trip.
		 *   - setMaxResults() limits the total number of rows returned.
		 *   - Values are tuned based on typical Oracle performance recommendations
		 *     (100–500 rows per fetch).
		 */
		int fetchSize = Math.max(100, Math.min(pageSize / 5, 500));
		query.setHint("javax.persistence.jdbc.fetch_size", fetchSize);
		query.setMaxResults(pageSize);

		// Handle Parameter Binding
		if (queryString.contains(":lastId")) {
			Object param = null;

			if (lastProcessedId != null) {
				param = lastProcessedId;
				// Convert to Long if the schema detected it as a Number previously
				if (keyColumnType != null && keyColumnType == ColumnType.DECIMAL) {
					try {
						param = new java.math.BigDecimal(lastProcessedId);
					} catch (NumberFormatException e) {
						throw new NonTransientResourceException("Invalid numeric lastProcessedId=" + lastProcessedId, e);
					}
				}
			}
			// If lastProcessedId is null, we bind NULL (First Page)
			query.setParameter("lastId", param);
		}

		@SuppressWarnings("unchecked")
		List<Tuple> tuples = query.getResultList();

		if (tuples == null || tuples.isEmpty()) {
			endReached = true;
			currentPage = null;
			return;
		}

		// Initialize schema once, thread-safely
		synchronized (SCHEMA_LOCK) {
			if (sharedSchema == null) {
				initializeSchemaFromTuple(tuples.get(0));
			}
		}

		this.currentPage = tuples;
		this.currentIndex = 0;
		logger.debug("Fetched {} rows after lastProcessedId={}", currentPage.size(), lastProcessedId);
	}

	private void initializeSchemaFromTuple(Tuple tuple) {
		List<TupleElement<?>> elements = tuple.getElements();
		int columnCount = elements.size();

		List<String> names = new ArrayList<>(columnCount);
		List<ColumnType> types = new ArrayList<>(columnCount);

		// 1. Extract Metadata from TupleElements
		for (int i = 0; i < columnCount; i++) {
			TupleElement<?> element = elements.get(i);
			String alias = element.getAlias();

			// Fallback for drivers that don't return aliases for Native Queries
			if (alias == null || alias.trim().isEmpty()) {
				alias = "column_" + i;
			}

			// RecordSchema uses lowercase keys for case-insensitive matching
			names.add(alias.toLowerCase(Locale.ROOT));

			// Map raw Java types (String, BigDecimal, etc.) to our ColumnType enum
			types.add(ColumnType.fromJavaValue(tuple.get(i)));
		}

		// 2. Create the immutable Shared Schema
		this.sharedSchema = new RecordSchema(names, types);

		// 3. Resolve KeySet Configuration (The most important part for Paging)
		if (keySetColumnName != null) {
			// Lookup the index based on the JSON configuration name
			int idx = sharedSchema.getIndex(keySetColumnName);

			if (idx != -1) {
				this.keySetColumnIndex = idx;
				// We capture the ACTUAL case-sensitive alias used by Hibernate/DB
				// This ensures tuple.get(actualKeySetColumnName) works every time.
				String actualKeySetColumnName = elements.get(idx).getAlias();

				// Fallback for null aliases
				if (actualKeySetColumnName == null || actualKeySetColumnName.trim().isEmpty()) {
					actualKeySetColumnName = "column_" + idx;
				}

				this.keySetColumnName = actualKeySetColumnName;
				// Identify the type, so we know how to bind the ":lastId" parameter
				this.keyColumnType = sharedSchema.getType(idx);

				logger.info("KeySet Column Resolved - Configuration: [{}], Database Alias: [{}], Index: [{}], Type: [{}]",
						keySetColumnName, actualKeySetColumnName, idx, keyColumnType);
			} else {
				// If this happens, the batch will loop infinitely on the first page!
				logger.error("CRITICAL CONFIGURATION ERROR: KeySet column '{}' not found in SQL results for interface '{}'. " +
								"Verify that your SELECT statement includes this column.",
						keySetColumnName, interfaceType);
			}
		}

		logger.info("Successfully initialized shared schema for interface [{}] with {} columns",
				interfaceType, sharedSchema.size());
	}

	/**
	 * Safely parse lastProcessedId from the row value.
	 * Supports Number -> Long and String -> String/Long.
	 */
	private String parseLastProcessedId(Object idValue) {
		if (idValue == null) return null;
		if (idValue instanceof Number) return new java.math.BigDecimal(idValue.toString()).toPlainString();
		String s = idValue.toString().trim();
		return s.isEmpty() ? null : s;
	}

	private DynamicRecord convertRowToRecord(Tuple tuple) {
		DynamicRecord record = new DynamicRecord(sharedSchema);
		String[] names = sharedSchema.getNames();

		// Set values by index (much faster than string-based map lookup)
		for (int i = 0; i < sharedSchema.size(); i++) {
			record.setValue(i, tuple.get(names[i]));
		}

		return record;
	}

	@Override
	public void open(ExecutionContext executionContext) {
		// Restore total processed
		this.totalProcessed = executionContext.getLong(CONTEXT_KEY_TOTAL, 0L);

		// Restore lastProcessedId safely
		if (executionContext.containsKey(CONTEXT_KEY_LAST_ID)) {
			Object lastId = executionContext.get(CONTEXT_KEY_LAST_ID);
			this.lastProcessedId = parseLastProcessedId(lastId); // Use centralized parser
		} else {
			this.lastProcessedId = null;
		}

		logger.info("Opening DynamicItemReader. Restart={}, lastProcessedId={}, totalProcessed={}",
				executionContext.containsKey(CONTEXT_KEY_LAST_ID),
				lastProcessedId,
				totalProcessed);
	}

	@Override
	public void update(ExecutionContext executionContext) {
		executionContext.putLong(CONTEXT_KEY_TOTAL, totalProcessed);

		if (lastProcessedId != null) {
			executionContext.putString(CONTEXT_KEY_LAST_ID, lastProcessedId);
		}
	}

	@Override
	public void close() {
		logger.info("DynamicItemReader closed. Total records read: {}", totalProcessed);
	}


	@Recover
	public void recoverPageFetch(Exception e) {
		logger.error("Failed to fetch page after retries", e);
		throw new RuntimeException("Cannot recover from database error", e);
	}
}
