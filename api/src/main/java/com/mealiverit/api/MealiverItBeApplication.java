package com.mealiverit.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.batch.autoconfigure.BatchJobLauncherAutoConfiguration;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling: MembershipTierBatchJob(매월 1일) 등 @Scheduled 배치가 실제로 동작하려면 필요.
// @EnableRetry: 상태전이(markUsed/markCanceled) 낙관적 락 충돌 시 @Retryable 재시도가 동작하려면 필요.
// @EnableAsync: CouponIssuedNotificationListener의 @Async 알림 발송이 동작하려면 필요.
@SpringBootApplication(exclude = BatchJobLauncherAutoConfiguration.class)
@EnableScheduling
@EnableRetry
@EnableAsync
public class MealiverItBeApplication {

	public static void main(String[] args) {
		SpringApplication.run(MealiverItBeApplication.class, args);
	}

}
