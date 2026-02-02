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

			boolean running = scheduler.isStarted();
			boolean standby = scheduler.isInStandbyMode();

			Health.Builder builder = running ? Health.up() : Health.down();
			return builder
					.withDetail("running", running)
					.withDetail("inStandby", standby)
					.build();
		} catch (Exception e) {
			return Health.down(e).build();
		}
	}
}
