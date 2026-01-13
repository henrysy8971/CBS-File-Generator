package com.silverlakesymmetri.cbs.fileGenerator.dto;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared metadata for all records in a batch.
 * Prevents repeating strings and map overhead in every row.
 */
public class RecordSchema {
	private final String[] names;
	private final ColumnType[] types;
	private final Map<String, Integer> nameToIndex;

	public RecordSchema(List<String> columnNames, List<ColumnType> columnTypes) {
		if (columnNames.size() != columnTypes.size()) {
			throw new IllegalArgumentException("Column names and types must have the same size");
		}

		this.names = columnNames.toArray(new String[0]);
		this.types = columnTypes.toArray(new ColumnType[0]);
		this.nameToIndex = new HashMap<>();

		for (int i = 0; i < names.length; i++) {
			String name = names[i];
			if (name != null) {
				nameToIndex.put(name.toLowerCase(), i);
			}
		}
	}

	public int getIndex(String name) {
		if (name == null) return -1;
		Integer idx = nameToIndex.get(name.toLowerCase());
		return (idx != null) ? idx : -1;
	}

	public int size() {
		return names.length;
	}

	public String getName(int i) {
		return names[i];
	}

	public ColumnType getType(int i) {
		return types[i];
	}

	public String[] getNames() {
		return names;
	}
}
