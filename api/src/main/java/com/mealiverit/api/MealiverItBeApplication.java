package com.mealiverit.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EntityScan("com.mealiverit.entity")
@EnableJpaRepositories("com.mealiverit.entity")
public class MealiverItBeApplication {

	public static void main(String[] args) {
		SpringApplication.run(MealiverItBeApplication.class, args);
	}

}
