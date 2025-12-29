package com.silverlakesymmetri.cbs.fileGenerator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableBatchProcessing
@EnableScheduling
@EnableAsync
@ComponentScan(basePackages = "com.silverlakesymmetri.cbs")
public class FileGeneratorApplication {
    public static void main(String[] args) {
        SpringApplication.run(FileGeneratorApplication.class, args);
    }
}
