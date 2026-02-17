package com.silverlakesymmetri.cbs.fileGenerator.dto;

public enum ColumnType {
	STRING,
	INTEGER,
	DECIMAL,
	BOOLEAN,
	TIMESTAMP;

	public static ColumnType fromJavaValue(Object value) {
		if (value == null) return STRING;
		if (value instanceof Integer || value instanceof Long) return INTEGER;
		if (value instanceof Number) return DECIMAL;
		if (value instanceof Boolean) return BOOLEAN;
		if (value instanceof java.util.Date ||
				value instanceof java.time.temporal.Temporal) return TIMESTAMP;
		return STRING;
	}

	public boolean isNumeric() {
		return this == INTEGER || this == DECIMAL;
	}
}
