package com.silverlakesymmetri.cbs.fileGenerator.batch;

import com.silverlakesymmetri.cbs.fileGenerator.config.model.InterfaceConfig;
import com.silverlakesymmetri.cbs.fileGenerator.dto.ColumnType;
import com.silverlakesymmetri.cbs.fileGenerator.dto.DynamicRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.persistence.*;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

@Component
@StepScope
public class DynamicItemReader implements ItemStreamReader<DynamicRecord> {
	private static final Logger logger = LoggerFactory.getLogger(DynamicItemReader.class);

	// State keys for the ExecutionContext
	private static final String CONTEXT_KEY_TOTAL = "dynamic.reader.totalProcessed";
	private static final String CONTEXT_KEY_LAST_ID = "dynamic.reader.lastProcessedId";

	private final int pageSize;
	private final InterfaceConfig interfaceConfig;
	private final String interfaceType;
	private final String queryString;
	private final EntityManager entityManager;

	// Stored state
	private String[] columnNames;
	private ColumnType[] columnTypes;
	private Iterator<Tuple> resultIterator;
	private Long lastProcessedId = null;
	private long totalProcessed = 0;

	@Autowired
	public DynamicItemReader(
			InterfaceConfig interfaceConfig,
			@Value("${file.generation.chunk-size:1000}") int pageSize, EntityManager entityManager
	) {
		this.interfaceConfig = interfaceConfig;
		this.pageSize = pageSize;
		this.entityManager = entityManager;

		if (interfaceConfig == null) throw new IllegalArgumentException("InterfaceConfig cannot be null");
		if (interfaceConfig.getName() == null)
			throw new IllegalArgumentException("Interface Name must be defined");
		if (interfaceConfig.getKeysetColumn() == null)
			throw new IllegalArgumentException("Keyset column must be defined");
		if (interfaceConfig.getDataSourceQuery() == null)
			throw new IllegalArgumentException("Data source query must be defined");

		this.interfaceType = interfaceConfig.getName();
		this.queryString = interfaceConfig.getDataSourceQuery();
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

			Tuple tuple = resultIterator.next();
			DynamicRecord record = convertRowToRecord(tuple);

			// Update keyset
			Object idValue = tuple.get(interfaceConfig.getKeysetColumn());
			if (!(idValue instanceof Number)) {
				throw new IllegalStateException("Keyset column must be numeric");
			}
			lastProcessedId = ((Number) idValue).longValue();

			// Increment total processed
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
		TypedQuery<Tuple> query = entityManager.createQuery(queryString, Tuple.class);
		query.setMaxResults(pageSize);

		if (lastProcessedId != null && queryString.contains(":lastId")) {
			query.setParameter("lastId", lastProcessedId);
		}

		List<Tuple> results = query.getResultList();

		if (results.isEmpty()) {
			resultIterator = null;
			return;
		}

		// Extract column metadata only once
		if (columnNames == null) {
			extractColumnMetadataFromTuple(results.get(0));
		}

		logger.debug("Fetched {} records for interface {} after lastProcessedId={}", results.size(), interfaceType, lastProcessedId);
		resultIterator = results.isEmpty() ? null : results.iterator();
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

	private void extractColumnMetadataFromTuple(Tuple tuple) {
		List<TupleElement<?>> elements = tuple.getElements();
		columnNames = new String[elements.size()];
		columnTypes = new ColumnType[elements.size()];

		for (int i = 0; i < elements.size(); i++) {
			TupleElement<?> element = elements.get(i);
			// This captures the "AS alias" from your JPQL
			columnNames[i] = element.getAlias();
			columnTypes[i] = ColumnType.fromJavaValue(tuple.get(i));
		}

		logger.debug("Extracted actual aliases: {} for interface {}",
				java.util.Arrays.toString(columnNames), interfaceType);
	}

	/**
	 * Convert a row to a DynamicRecord.
	 */
	private DynamicRecord convertRowToRecord(Tuple tuple) {
		DynamicRecord record = new DynamicRecord();
		for (int i = 0; i < columnNames.length; i++) {
			record.addColumn(columnNames[i], tuple.get(columnNames[i]), columnTypes[i]);
		}
		return record;
	}
}
