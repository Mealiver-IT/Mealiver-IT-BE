package com.mealiverit.api.seed;

import com.mealiverit.api.common.exception.BusinessException;
import com.mealiverit.api.common.exception.ErrorCode;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Service;

// 관리자 페이지 "오염 데이터 삽입/정리" 버튼용 - dirty_data_seed.sql / dirty_data_cleanup.sql
// (verification 검증쿼리 5종 테스트 픽스처, api/src/main/resources/sql/fixtures/)을 그대로 실행한다.
// 두 스크립트 모두 DIRTY_%/dirty_user_% 이름으로 완전히 격리돼있어 실제 데이터에는 영향이 없다
// (2026-08-25 실제로 이 105개 DIRTY_* 캠페인을 안전하게 지운 적이 있음).
// unique 제약 때문에 seed를 두 번 연속 실행하면 실패하므로, 재실행하려면 먼저 cleanup을 실행해야 한다
// (스크립트 파일 자체의 주석과 동일한 제약).
@Service
public class DirtyDataAdminService {

    private final DataSource dataSource;

    public DirtyDataAdminService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void seed() {
        executeScript("sql/fixtures/dirty_data_seed.sql");
    }

    public void cleanup() {
        executeScript("sql/fixtures/dirty_data_cleanup.sql");
    }

    private void executeScript(String classpathLocation) {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(classpathLocation));
        } catch (SQLException | RuntimeException e) {
            throw new BusinessException(ErrorCode.DIRTY_DATA_SCRIPT_FAILED);
        }
    }
}
