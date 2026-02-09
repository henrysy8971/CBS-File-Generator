package com.silverlakesymmetri.cbs.fileGenerator.retry;

import org.springframework.dao.TransientDataAccessException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Retryable(
		value = {
				TransientDataAccessException.class,
				ObjectOptimisticLockingFailureException.class
		},
		maxAttempts = 3,
		backoff = @Backoff(delay = 2000)
)
public @interface DbRetryable {
}
