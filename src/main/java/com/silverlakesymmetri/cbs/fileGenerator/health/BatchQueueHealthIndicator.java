package com.silverlakesymmetri.cbs.fileGenerator.health;

import com.silverlakesymmetri.cbs.fileGenerator.service.FileGenerationService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import static com.silverlakesymmetri.cbs.fileGenerator.constants.FileGenerationConstants.HIGH_LOAD_THRESHOLD;

@Component
public class BatchQueueHealthIndicator implements HealthIndicator {

	private final FileGenerationService fileGenerationService;

	public BatchQueueHealthIndicator(FileGenerationService fileGenerationService) {
		this.fileGenerationService = fileGenerationService;
	}

	@Override
	public Health health() {
		try {
			long pending = fileGenerationService.getPendingCount();

			Health.Builder builder = Health.up();
			if (pending > HIGH_LOAD_THRESHOLD) { // Your threshold
				builder.status("DEGRADED"); // Custom status
			}

			return builder
					.withDetail("pendingJobs", pending)
					.withDetail("threshold", HIGH_LOAD_THRESHOLD)
					.build();
		} catch (Exception e) {
			return Health.down(e).build();
		}
	}
}
