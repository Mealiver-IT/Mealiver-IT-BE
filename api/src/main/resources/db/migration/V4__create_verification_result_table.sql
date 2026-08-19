-- 05_시스템설계.txt 1.2절: ItemWriter가 위반 건만 적재하는 대상 테이블.
-- job_execution_id로 BATCH_JOB_EXECUTION과 연결해 "언제 돈 검증에서 뭐가 걸렸는지" 추적 가능.
CREATE TABLE verification_result (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    job_execution_id BIGINT       NOT NULL,
    check_type       VARCHAR(50)  NOT NULL,
    reference_id     VARCHAR(100) NOT NULL,
    detail           TEXT         NOT NULL,
    detected_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_verification_result_job_execution FOREIGN KEY (job_execution_id)
        REFERENCES BATCH_JOB_EXECUTION (JOB_EXECUTION_ID),
    INDEX idx_verification_result_job (job_execution_id),
    INDEX idx_verification_result_check_type (check_type)
) ENGINE = InnoDB;