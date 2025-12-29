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
import javax.persistence.Query;
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
		Query query = entityManager.createQuery(queryString);
		query.setFirstResult(currentPage * DEFAULT_BATCH_SIZE);
		query.setMaxResults(DEFAULT_BATCH_SIZE);

		List<?> results = query.getResultList();
		if (results.isEmpty()) {
			resultIterator = null;
			return;
		}

		List<Object[]> rows = new ArrayList<>(results.size());
		for (Object result : results) {
			Object[] row = (result instanceof Object[]) ? (Object[]) result : new Object[]{result};
			rows.add(row);

			// Extract column metadata from first row only
			if (columnNames == null) {
				extractColumnMetadata(row);
			}
		}

		resultIterator = rows.iterator();
		currentPage++;
		logger.debug("Fetched batch {} with {} records for interface {}", currentPage, rows.size(), interfaceType);
	}

	/**
	 * Extract column names and types from first row.
	 */
	private void extractColumnMetadata(Object[] row) {
		columnNames = new String[row.length];
		columnTypes = new ColumnType[row.length];

		for (int i = 0; i < row.length; i++) {
			columnNames[i] = "column_" + (i + 1);
			columnTypes[i] = ColumnType.fromJavaValue(row[i]);
		}

		logger.debug("Extracted {} columns for interface {}", columnNames.length, interfaceType);
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
