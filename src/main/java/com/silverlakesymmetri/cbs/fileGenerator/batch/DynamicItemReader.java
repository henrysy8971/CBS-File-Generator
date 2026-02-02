package com.silverlakesymmetri.cbs.fileGenerator.batch;

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
import org.springframework.stereotype.Component;

import javax.persistence.EntityManager;
import javax.persistence.Query;
import javax.persistence.Tuple;
import javax.persistence.TupleElement;
import java.util.ArrayList;
import java.util.List;

@Component
@StepScope
public class DynamicItemReader implements ItemStreamReader<DynamicRecord> {
	private static final Logger logger = LoggerFactory.getLogger(DynamicItemReader.class);
	private static final String CONTEXT_KEY_TOTAL = "dynamic.reader.totalProcessed";
	private static final String CONTEXT_KEY_LAST_ID = "dynamic.reader.lastProcessedId";

	private final int pageSize;
	private final String interfaceType;
	private final String queryString;
	private final EntityManager entityManager;
	private RecordSchema sharedSchema;
	private List<Tuple> currentPage;

	private int currentIndex = 0;
	private boolean endReached = false;

	private String lastProcessedId = null;
	private long totalProcessed = 0;
	private final String keySetColumnName;
	private String actualKeySetColumnName = null;
	private ColumnType keyColumnType;
	private int keySetColumnIndex = -1; // Store the index once

	@Autowired
	public DynamicItemReader(
			InterfaceConfig interfaceConfig,
			EntityManager entityManager,
			@Value("${file.generation.chunk-size:1000}") int pageSize
	) {
		this.entityManager = entityManager;
		this.pageSize = pageSize;

		if (interfaceConfig == null) {
			throw new IllegalArgumentException("InterfaceConfig cannot be null");
		}

		if (interfaceConfig.getName() == null) {
			throw new IllegalArgumentException("Interface Name must be defined");
		}

		if (interfaceConfig.getDataSourceQuery() == null) {
			throw new IllegalArgumentException("Data source query must be defined");
		}

		this.interfaceType = interfaceConfig.getName();
		this.queryString = interfaceConfig.getDataSourceQuery();
		this.keySetColumnName = interfaceConfig.getKeysetColumn();
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
			throw e; // do not retry
		} catch (Exception e) {
			logger.error("Transient read error for interface {}", interfaceType, e);
			throw new RuntimeException(e); // retryable
		}
	}

	private void fetchNextPage() {
		Query query = entityManager.createNativeQuery(queryString, Tuple.class);
		query.setHint("javax.persistence.jdbc.fetch_size", pageSize);
		query.setMaxResults(pageSize);

		if (lastProcessedId != null && queryString.contains(":lastId")) {
			Object param = lastProcessedId;
			if (keyColumnType != null && keyColumnType == ColumnType.DECIMAL) {
				try {
					param = Long.valueOf(lastProcessedId);
				} catch (NumberFormatException e) {
					throw new NonTransientResourceException("Invalid numeric lastProcessedId=" + lastProcessedId, e);
				}
			}
			query.setParameter("lastId", param);
		}

		@SuppressWarnings("unchecked")
		List<Tuple> tuples = query.getResultList();

		if (tuples == null || tuples.isEmpty()) {
			endReached = true;
			currentPage = null;
			return;
		}

		// Initialize schema from the first TUPLE if not already done
		if (sharedSchema == null) {
			initializeSchemaFromTuple(tuples.get(0));
		}

		currentIndex = 0;
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
			names.add(alias.toLowerCase());

			// Map raw Java types (String, BigDecimal, etc.) to our ColumnType enum
			types.add(ColumnType.fromJavaValue(tuple.get(i)));
		}

		// 2. Create the immutable Shared Schema
		this.sharedSchema = new RecordSchema(names, types);

		// 3. Resolve Keyset Configuration (The most important part for Paging)
		if (keySetColumnName != null) {
			// Lookup the index based on the JSON configuration name
			int idx = sharedSchema.getIndex(keySetColumnName.toLowerCase());

			if (idx != -1) {
				this.keySetColumnIndex = idx;
				// We capture the ACTUAL case-sensitive alias used by Hibernate/DB
				// This ensures tuple.get(actualKeySetColumnName) works every time.
				this.actualKeySetColumnName = elements.get(idx).getAlias();

				// Fallback for null aliases
				if (this.actualKeySetColumnName == null) {
					this.actualKeySetColumnName = "column_" + idx;
				}

				// Identify the type, so we know how to bind the ":lastId" parameter
				this.keyColumnType = sharedSchema.getType(idx);

				logger.info("Keyset Column Resolved - Configuration: [{}], Database Alias: [{}], Index: [{}], Type: [{}]",
						keySetColumnName, actualKeySetColumnName, idx, keyColumnType);
			} else {
				// If this happens, the batch will loop infinitely on the first page!
				logger.error("CRITICAL CONFIGURATION ERROR: Keyset column '{}' not found in SQL results for interface '{}'. " +
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
		if (idValue instanceof Number) return String.valueOf(((Number) idValue).longValue());
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
}
