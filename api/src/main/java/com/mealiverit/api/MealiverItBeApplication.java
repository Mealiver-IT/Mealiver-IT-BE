package com.mealiverit.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling: MembershipTierBatchJob(매월 1일) 등 @Scheduled 배치가 실제로 동작하려면 필요.
@SpringBootApplication
@EntityScan("com.mealiverit.entity")
@EnableJpaRepositories("com.mealiverit.entity")
@EnableScheduling
public class MealiverItBeApplication {

	public static void main(String[] args) {
		SpringApplication.run(MealiverItBeApplication.class, args);
	}

}
