package com.silverlakesymmetri.cbs.fileGenerator.dto;

import java.util.Objects;

public final class DynamicColumn {
	private final String name;
	private final Object value;
	private final ColumnType type;

	public DynamicColumn(String name, Object value, ColumnType type) {
		this.name = Objects.requireNonNull(name);
		this.type = Objects.requireNonNull(type);
		this.value = value;
	}

	public String getName() {
		return this.name;
	}

	public Object getValue() {
		return this.value;
	}

	public ColumnType getType() {
		return this.type;
	}
}
