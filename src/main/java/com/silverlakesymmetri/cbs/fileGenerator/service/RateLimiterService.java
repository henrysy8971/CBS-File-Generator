package com.silverlakesymmetri.cbs.fileGenerator.service;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Bucket4j;
import io.github.bucket4j.Refill;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RateLimiterService {
	private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

	public boolean tryConsume(String key) {
		Bucket bucket = buckets.computeIfAbsent(key, this::createNewBucket);
		return bucket.tryConsume(1);
	}

	private Bucket createNewBucket(String key) {
		// Limit: 10 requests per minute
		// You can make this configurable via @Value if needed
		Bandwidth limit = Bandwidth.classic(10, Refill.greedy(10, Duration.ofMinutes(1)));
		return Bucket4j.builder().addLimit(limit).build();
	}
}
