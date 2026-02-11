package com.silverlakesymmetri.cbs.fileGenerator.dto;

import java.util.*;

/**
 * Shared metadata for all records in a batch.
 * Prevents repeating strings and map overhead in every row.
 */
public class RecordSchema {
	private final String[] names;
	private final ColumnType[] types;
	private final Map<String, Integer> nameToIndex;
	private final Set<String> immutableKeySet;

	public RecordSchema(List<String> columnNames, List<ColumnType> columnTypes) {
		if (columnNames.size() != columnTypes.size()) {
			throw new IllegalArgumentException("Column names and types must have the same size");
		}

		this.names = columnNames.toArray(new String[0]);
		this.types = columnTypes.toArray(new ColumnType[0]);
		Map<String, Integer> tmpMap = new HashMap<>();

		for (int i = 0; i < names.length; i++) {
			String name = names[i];
			if (name == null) {
				throw new IllegalArgumentException("Column name cannot be null at index " + i);
			} else {
				tmpMap.put(name.toLowerCase(Locale.ROOT), i);
			}
		}
		this.nameToIndex = Collections.unmodifiableMap(tmpMap);
		this.immutableKeySet = Collections.unmodifiableSet(new LinkedHashSet<>(columnNames));
	}

	public int getIndex(String name) {
		if (name == null) return -1;
		Integer idx = nameToIndex.get(name.toLowerCase(Locale.ROOT));
		return (idx != null) ? idx : -1;
	}

	public int size() {
		return names.length;
	}

	public String getName(int i) {
		if (i < 0 || i >= names.length) {
			throw new IndexOutOfBoundsException(
					"Index " + i + " out of bounds for names array of size " + names.length
			);
		}
		return names[i];
	}

	public ColumnType getType(int i) {
		if (i < 0 || i >= types.length) {
			throw new IndexOutOfBoundsException(
					"Index " + i + " out of bounds for types array of size " + types.length
			);
		}
		return types[i];
	}

	public String[] getNames() {
		return Arrays.copyOf(names, names.length);
	}

	public Set<String> getKeySet() {
		return immutableKeySet;
	}
}
