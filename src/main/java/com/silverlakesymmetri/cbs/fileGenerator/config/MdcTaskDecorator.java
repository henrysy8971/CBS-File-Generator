package com.silverlakesymmetri.cbs.fileGenerator.config;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

public class MdcTaskDecorator implements TaskDecorator {
	@Override
	public Runnable decorate(Runnable runnable) {
		// Capture the MDC map from the main thread
		Map<String, String> contextMap = MDC.getCopyOfContextMap();
		return () -> {
			try {
				if (contextMap != null) MDC.setContextMap(contextMap);
				runnable.run();
			} finally {
				MDC.clear();
			}
		};
	}
}
