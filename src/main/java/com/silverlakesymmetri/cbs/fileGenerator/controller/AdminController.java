package com.silverlakesymmetri.cbs.fileGenerator.controller;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

	@Autowired
	private JobLauncher jobLauncher;

	@Autowired
	private Job cleanupJob;

	@PostMapping("/cleanup")
	public String triggerCleanup() throws Exception {
		JobParameters params = new JobParametersBuilder()
				.addLong("time", System.currentTimeMillis())
				.toJobParameters();

		jobLauncher.run(cleanupJob, params);
		return "Cleanup job triggered successfully";
	}
}
