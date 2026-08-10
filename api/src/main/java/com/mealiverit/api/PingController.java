package com.mealiverit.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// 테스트 용 코드 입니다.
// 추후 삭제 예정

@RestController
public class PingController {

	@GetMapping("/api/ping")
	public String ping() {
		return "pong";
	}

}
