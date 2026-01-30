package com.silverlakesymmetri.cbs.fileGenerator.dto;

import java.util.*;

public class DynamicRecord extends AbstractMap<String, Object> {
	private final RecordSchema schema;
	private final Object[] values;

	public DynamicRecord(RecordSchema schema) {
		this.schema = schema;
		this.values = new Object[schema.size()];
	}

	public void setValue(String name, Object value) {
		int idx = schema.getIndex(name);
		if (idx != -1) {
			values[idx] = value;
		}
	}

	public void setValue(int index, Object value) {
		if (index >= 0 && index < values.length) {
			values[index] = value;
		}
	}

	@Override
	public Object put(String key, Object value) {
		int idx = schema.getIndex(key);
		if (idx != -1) {
			Object old = values[idx];
			values[idx] = value;
			return old;
		}
		return null;
	}

	@Override
	public Object get(Object key) {
		if (!(key instanceof String)) return null;
		int idx = schema.getIndex((String) key);
		return (idx != -1) ? values[idx] : null;
	}

	@Override
	public int size() {
		return schema.size();
	}

	@Override
	public boolean containsKey(Object key) {
		if (!(key instanceof String)) return false;
		return schema.getIndex((String) key) != -1;
	}

	@Override
	public Set<String> keySet() {
		return schema.getKeySet();
	}

	public ColumnType getType(String name) {
		int idx = schema.getIndex(name);
		return (idx != -1) ? schema.getType(idx) : ColumnType.STRING;
	}

	/**
	 * Implementing AbstractMap requires implementing entrySet.
	 * BeanIO and Writers use this to iterate.
	 */
	@Override
	public Set<Entry<String, Object>> entrySet() {
		return new AbstractSet<Entry<String, Object>>() {
			@Override
			public Iterator<Entry<String, Object>> iterator() {
				return new Iterator<Entry<String, Object>>() {
					private int index = 0;

					@Override
					public boolean hasNext() {
						return index < schema.size();
					}

					@Override
					public Entry<String, Object> next() {
						if (!hasNext()) throw new NoSuchElementException();
						int i = index++;
						return new SimpleImmutableEntry<>(schema.getName(i), values[i]);
					}
				};
			}

			@Override
			public int size() {
				return schema.size();
			}
		};
	}

	public Map<String, Object> asMap() {
		return this;
	}
}