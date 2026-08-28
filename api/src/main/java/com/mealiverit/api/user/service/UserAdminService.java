package com.mealiverit.api.user.service;

import com.mealiverit.api.user.dto.UserResponse;
import com.mealiverit.api.user.repository.UserRepository;
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

    // 대시보드 KPI용 - list()는 100만 건 규모에서 수십 초가 걸려 총 인원 수만 필요할 땐 이걸 쓴다.
    @Transactional(readOnly = true)
    public long count() {
        return userRepository.count();
    }

    private static final int MAX_SEARCH_RESULTS = 200;

    // 관리자 유저 목록 화면 검색 - list()(전체 fetch)를 대체. 필터가 전부 비어있으면 쿼리 자체를
    // 안 날린다(빈 조건 LIKE '%%'로 전체 스캔+반환하는 걸 막기 위함, 화면 쪽 "검색 안 함" UX와도 일치).
    @Transactional(readOnly = true)
    public List<UserResponse> search(String id, String loginId, String name) {
        String idFilter = id == null ? "" : id.trim();
        String loginIdFilter = loginId == null ? "" : loginId.trim().toLowerCase();
        String nameFilter = name == null ? "" : name.trim();

        if (idFilter.isEmpty() && loginIdFilter.isEmpty() && nameFilter.isEmpty()) {
            return List.of();
        }

        return userRepository.search(idFilter, loginIdFilter, nameFilter, MAX_SEARCH_RESULTS).stream()
                .map(UserResponse::of)
                .toList();
    }
}
