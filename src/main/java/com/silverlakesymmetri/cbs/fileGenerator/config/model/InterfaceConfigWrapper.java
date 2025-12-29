package com.silverlakesymmetri.cbs.fileGenerator.config.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.Map;

public class InterfaceConfigWrapper {
	/**
	 * Key = interface type
	 * Value = interface configuration
	 */
	private final Map<String, InterfaceConfig> interfaces;

	@JsonCreator
	public InterfaceConfigWrapper(
			@JsonProperty("interfaces")
			Map<String, InterfaceConfig> interfaces) {

		this.interfaces = interfaces != null
				? interfaces
				: Collections.emptyMap();
	}

	/**
	 * For Jackson (if needed)
	 */
	protected InterfaceConfigWrapper() {
		this.interfaces = Collections.emptyMap();
	}

	public Map<String, InterfaceConfig> getInterfaces() {
		return interfaces;
	}

	/**
	 * Convenience accessor with validation.
	 */
	public InterfaceConfig getInterface(String interfaceType) {
		InterfaceConfig config = interfaces.get(interfaceType);
		if (config == null) {
			throw new IllegalArgumentException(
					"No interface configuration found for: " + interfaceType);
		}
		return config;
	}

	public boolean isEmpty() {
		return interfaces.isEmpty();
	}

	@Override
	public String toString() {
		return "InterfaceConfigWrapper{" +
				"interfaces=" + interfaces.keySet() +
				'}';
	}
}
