package com.mealiverit.api.user.service;

import com.mealiverit.api.user.dto.UserResponse;
import com.mealiverit.entity.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// 관리자용 유저 목록 조회. 인증/권한은 이 프로젝트 평가범위 밖(다른 admin API들과 동일)
@Service
public class UserAdminService {

    private final UserRepository userRepository;

    public UserAdminService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    // 유저 전체 목록 조회 - 페이지네이션 없이 user 테이블 전체를 마스킹해서 반환
    @Transactional(readOnly = true)
    public List<UserResponse> list() {
        return userRepository.findAll().stream()
                .map(UserResponse::of)
                .toList();
    }
}
