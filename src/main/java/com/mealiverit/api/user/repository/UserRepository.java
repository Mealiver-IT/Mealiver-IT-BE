package com.mealiverit.api.user.repository;

import java.util.List;

import com.mealiverit.api.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    // 관리자 유저 검색 - ID/로그인ID/이름 부분일치를 AND로 좁힌다(빈 문자열이면 그 조건은 건너뜀).
    // name엔 인덱스가 없어 결국 풀스캔이지만, 100만 건 전체를 프론트로 내려보내 브라우저에서
    // 필터링하던 것보다는 훨씬 빠르다 - LIMIT로 결과 자체를 좁히고 네트워크로 나가는 양도 확 줄어든다.
    @Query(value = """
            SELECT * FROM users
            WHERE (:id = '' OR CAST(id AS CHAR) LIKE CONCAT('%', :id, '%'))
              AND (:loginId = '' OR LOWER(login_id) LIKE CONCAT('%', :loginId, '%'))
              AND (:name = '' OR name LIKE CONCAT('%', :name, '%'))
            ORDER BY id
            LIMIT :maxResults
            """, nativeQuery = true)
    List<User> search(@Param("id") String id, @Param("loginId") String loginId,
                       @Param("name") String name, @Param("maxResults") int maxResults);
}
