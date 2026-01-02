package com.silverlakesymmetri.cbs.fileGenerator.batch;

import com.silverlakesymmetri.cbs.fileGenerator.config.InterfaceConfigLoader;
import com.silverlakesymmetri.cbs.fileGenerator.config.model.InterfaceConfig;
import com.silverlakesymmetri.cbs.fileGenerator.dto.ColumnType;
import com.silverlakesymmetri.cbs.fileGenerator.dto.DynamicRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.annotation.BeforeStep;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Production-ready DynamicItemReader with paging support and full restart-state support.
 * Compatible with DynamicItemWriter for arbitrarily large datasets.
 */
@Component
@StepScope
public class DynamicItemReader implements ItemReader<DynamicRecord> {

	private static final Logger logger = LoggerFactory.getLogger(DynamicItemReader.class);

	private static final int DEFAULT_BATCH_SIZE = 1000;
	private static final String CONTEXT_KEY_PAGE = "dynamic.reader.currentPage";
	private static final String CONTEXT_KEY_TOTAL = "dynamic.reader.totalProcessed";

	@PersistenceContext
	private EntityManager entityManager;

	@Autowired
	private InterfaceConfigLoader interfaceConfigLoader;

	private Iterator<Object[]> resultIterator;
	private String interfaceType;
	private String queryString;
	private String[] columnNames;
	private ColumnType[] columnTypes;

	private int currentPage = 0;
	private long totalProcessed = 0;

	private ExecutionContext stepContext;

	@BeforeStep
	public void beforeStep(StepExecution stepExecution) {
		this.interfaceType = stepExecution.getJobParameters().getString("interfaceType");
		if (interfaceType == null || interfaceType.trim().isEmpty()) {
			throw new IllegalArgumentException("Job parameter 'interfaceType' is required");
		}

		InterfaceConfig config = interfaceConfigLoader.getConfig(interfaceType);
		if (config == null) {
			throw new IllegalArgumentException("Interface configuration not found: " + interfaceType);
		}

		this.queryString = config.getDataSourceQuery();
		if (queryString == null || queryString.trim().isEmpty()) {
			throw new IllegalArgumentException("Data source query not configured for interface: " + interfaceType);
		}

		this.stepContext = stepExecution.getExecutionContext();

		// Restore state if restarting
		this.currentPage = stepContext.getInt(CONTEXT_KEY_PAGE, 0);
		this.totalProcessed = stepContext.getLong(CONTEXT_KEY_TOTAL, 0);

		logger.info("DynamicItemReader initialized for interfaceType={} with query={}, currentPage={}, totalProcessed={}",
				interfaceType, queryString, currentPage, totalProcessed);
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

			Object[] row = resultIterator.next();
			totalProcessed++;

			// Persist state for restart
			stepContext.putInt(CONTEXT_KEY_PAGE, currentPage);
			stepContext.putLong(CONTEXT_KEY_TOTAL, totalProcessed);

			if (totalProcessed % 1000 == 0) {
				logger.info("Processed {} records for interface {}", totalProcessed, interfaceType);
			}

			return convertRowToRecord(row);
		} catch (Exception e) {
			logger.error("Error reading record for interface {}", interfaceType, e);
			throw new RuntimeException(e);
		}
	}

	/**
	 * Fetch the next batch of records from the database using paging.
	 */
	private void fetchNextBatch() {
		// Specify Tuple.class to capture column aliases
		javax.persistence.TypedQuery<javax.persistence.Tuple> query = entityManager.createQuery(queryString, javax.persistence.Tuple.class);
		query.setFirstResult(currentPage * DEFAULT_BATCH_SIZE);
		query.setMaxResults(DEFAULT_BATCH_SIZE);

		List<javax.persistence.Tuple> results = query.getResultList();
		if (results.isEmpty()) {
			resultIterator = null;
			return;
		}

		// Extract real column names from the first Tuple result
		if (columnNames == null) {
			javax.persistence.Tuple firstResult = results.get(0);
			extractColumnMetadataFromTuple(firstResult);
		}

		// Convert results to an iterator of Object arrays for the existing logic
		List<Object[]> rows = new ArrayList<>();
		for (javax.persistence.Tuple tuple : results) {
			rows.add(tuple.toArray());
		}

		resultIterator = rows.iterator();
		currentPage++;
		logger.debug("Fetched batch {} with {} records for interface {}", currentPage, rows.size(), interfaceType);
	}

	private void extractColumnMetadataFromTuple(javax.persistence.Tuple tuple) {
		List<javax.persistence.TupleElement<?>> elements = tuple.getElements();
		columnNames = new String[elements.size()];
		columnTypes = new ColumnType[elements.size()];

		for (int i = 0; i < elements.size(); i++) {
			javax.persistence.TupleElement<?> element = elements.get(i);
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
	private DynamicRecord convertRowToRecord(Object[] row) {
		DynamicRecord record = new DynamicRecord();
		for (int i = 0; i < Math.min(row.length, columnNames.length); i++) {
			record.addColumn(columnNames[i], row[i], columnTypes[i]);
		}
		return record;
	}
}
