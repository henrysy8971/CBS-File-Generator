package com.silverlakesymmetri.cbs.fileGenerator.health;

import org.quartz.Scheduler;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class QuartzHealthIndicator implements HealthIndicator {

	private final Scheduler scheduler;

	public QuartzHealthIndicator(Scheduler scheduler) {
		this.scheduler = scheduler;
	}

	@Override
	public Health health() {
		try {
			if (scheduler == null) {
				return Health.down().withDetail("error", "Scheduler not initialized").build();
			}

			boolean isShutdown = scheduler.isShutdown();
			boolean isStarted = scheduler.isStarted();
			boolean inStandby = scheduler.isInStandbyMode();

			// Determine status: DOWN if shutdown, otherwise UP (even if in standby)
			Health.Builder builder = isShutdown ? Health.down() : Health.up();

			return builder
					.withDetail("schedulerName", scheduler.getSchedulerName())
					.withDetail("schedulerInstanceId", scheduler.getSchedulerInstanceId()) // Helpful for clustering
					.withDetail("started", isStarted)
					.withDetail("inStandby", inStandby)
					.withDetail("isShutdown", isShutdown)
					.build();
		} catch (Exception e) {
			return Health.down(e).build();
		}
	}
}
