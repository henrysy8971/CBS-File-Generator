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

			// Default to UP
			Health.Builder builder = Health.up();

			if (pending > HIGH_LOAD_THRESHOLD) { // Your threshold
				builder.status("DEGRADED")
						.withDetail("warning", "Queue load exceeds threshold");
			}

			return builder
					.withDetail("pendingJobs", pending)
					.withDetail("threshold", HIGH_LOAD_THRESHOLD)
					.withDetail("loadPercentage", String.format("%.2f%%", ((double)pending / HIGH_LOAD_THRESHOLD) * 100))
					.build();
		} catch (Exception e) {
			return Health.down(e).build();
		}
	}
}
