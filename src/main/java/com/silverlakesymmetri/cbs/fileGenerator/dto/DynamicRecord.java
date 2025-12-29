package com.silverlakesymmetri.cbs.fileGenerator.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class DynamicRecord {

	private final LinkedHashMap<String, DynamicColumn> columns = new LinkedHashMap<>();

	public void addColumn(String name, Object value, ColumnType type) {
		if (columns.containsKey(name)) {
			throw new IllegalArgumentException("Duplicate column: " + name);
		}
		columns.put(name, new DynamicColumn(name, value, type));
	}

	public Object getValue(String name) {
		DynamicColumn col = columns.get(name);
		return col != null ? col.getValue() : null;
	}

	public ColumnType getType(String name) {
		DynamicColumn col = columns.get(name);
		return col != null ? col.getType() : ColumnType.STRING;
	}

	public Map<String, Object> asValueMap() {
		LinkedHashMap<String, Object> map = new LinkedHashMap<>();
		columns.values().forEach(c -> map.put(c.getName(), c.getValue()));
		return map;
	}

	public Set<String> getColumnNames() {
		return Collections.unmodifiableSet(columns.keySet());
	}

	public int size() {
		return columns.size();
	}

	public void updateValue(String name, Object newValue) {
		DynamicColumn existing = columns.get(name);
		if (existing == null) {
			throw new IllegalArgumentException("Column does not exist: " + name);
		}

		columns.put(
				name,
				new DynamicColumn(name, newValue, existing.getType())
		);
	}
}
