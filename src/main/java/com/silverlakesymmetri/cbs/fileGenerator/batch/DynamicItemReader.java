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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.persistence.EntityManager;
import javax.persistence.Query;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

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
	private Iterator<Map<String, Object>> resultIterator;
	private Long lastProcessedId = null;
	private long totalProcessed = 0;
	private String keySetColumnName = null;
	private String actualKeySetColumnName = null;

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

		if (interfaceConfig.getKeysetColumn() != null) {
			this.keySetColumnName = interfaceConfig.getKeysetColumn();
		}
	}

	@Override
	public DynamicRecord read() {
		try {
			if (resultIterator == null || !resultIterator.hasNext()) {
				fetchNextBatch();
			}

			if (resultIterator == null || !resultIterator.hasNext()) {
				logger.info("End of data reached. Total records processed={}", totalProcessed);
				return null;
			}

			Map<String, Object> rowMap = resultIterator.next();
			DynamicRecord record = convertRowToRecord(rowMap);

			if (actualKeySetColumnName != null) {
				Object idValue = rowMap.get(actualKeySetColumnName);
				if (idValue instanceof Number) {
					lastProcessedId = ((Number) idValue).longValue();
				} else if (idValue instanceof String) {
					try {
						lastProcessedId = Long.parseLong((String) idValue);
					} catch (NumberFormatException e) {
						logger.warn("Key set column is not a valid number: {}", idValue);
					}
				}
			}

			totalProcessed++;

			if (totalProcessed % pageSize == 0) {
				logger.info("Processed {} records for interface {}", totalProcessed, interfaceType);
			}

			return record;
		} catch (Exception e) {
			logger.error("Error reading record for interface {}", interfaceType, e);
			throw new RuntimeException(e);
		}
	}

	/**
	 * Fetch the next batch of records from the database using paging.
	 */
	private void fetchNextBatch() {
		Query query = entityManager.createNativeQuery(queryString);
		query.unwrap(org.hibernate.Query.class).setResultTransformer(org.hibernate.transform.AliasToEntityMapResultTransformer.INSTANCE);

		query.setMaxResults(pageSize);

		if (lastProcessedId != null && queryString.contains(":lastId")) {
			query.setParameter("lastId", lastProcessedId);
		}

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> results = (List<Map<String, Object>>) query.getResultList();

		if (results == null || results.isEmpty()) {
			resultIterator = null;
			return;
		}

		// Initialize schema ONLY ONCE upon the first batch
		if (sharedSchema == null) {
			initializeSchemaFromMap(results.get(0));
		}

		logger.debug("Fetched {} records for interface {} after lastProcessedId={}", results.size(), interfaceType, lastProcessedId);
		resultIterator = results.iterator();
	}

	private void initializeSchemaFromMap(Map<String, Object> firstRow) {
		List<String> names = new ArrayList<>(firstRow.keySet());
		List<ColumnType> types = new ArrayList<>();

		for (String name : names) {
			types.add(ColumnType.fromJavaValue(firstRow.get(name)));
		}

		this.sharedSchema = new RecordSchema(names, types);

		if (keySetColumnName != null) {
			int idx = sharedSchema.getIndex(keySetColumnName);

			if (idx != -1) {
				// Get the actual case-sensitive name from the schema
				this.actualKeySetColumnName = sharedSchema.getName(idx);
				logger.info("Key set column mapped to DB field: {}", actualKeySetColumnName);
			} else {
				logger.warn("Key set column [{}] not found in result set!", keySetColumnName);
			}
		}

		logger.info("Initialized shared schema for interface {} with {} columns",
				interfaceType, sharedSchema.size());
	}

	private DynamicRecord convertRowToRecord(Map<String, Object> rowMap) {
		DynamicRecord record = new DynamicRecord(sharedSchema);
		String[] names = sharedSchema.getNames();

		// Set values by index (much faster than string-based map lookup)
		for (int i = 0; i < sharedSchema.size(); i++) {
			record.setValue(i, rowMap.get(names[i]));
		}

		return record;
	}

	@Override
	public void open(ExecutionContext executionContext) {
		this.totalProcessed = executionContext.getLong(CONTEXT_KEY_TOTAL, 0L);
		this.lastProcessedId = executionContext.containsKey(CONTEXT_KEY_LAST_ID)
				? executionContext.getLong(CONTEXT_KEY_LAST_ID)
				: null;

		logger.info("Opening DynamicItemReader. Restart={}, lastProcessedId={}, totalProcessed={}",
				executionContext.containsKey(CONTEXT_KEY_LAST_ID),
				lastProcessedId,
				totalProcessed);
	}

	@Override
	public void update(ExecutionContext executionContext) {
		executionContext.putLong(CONTEXT_KEY_TOTAL, totalProcessed);
		if (lastProcessedId != null) {
			executionContext.putLong(CONTEXT_KEY_LAST_ID, lastProcessedId);
		}
	}

	@Override
	public void close() {
		// Cleanup resources like the resultIterator or temporary files
		this.resultIterator = null;
		logger.info("DynamicItemReader closed. Total records read: {}", totalProcessed);
	}
}
