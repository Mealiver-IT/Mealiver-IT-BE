CREATE TABLE verification_report (
    id                       BIGINT       NOT NULL AUTO_INCREMENT,
    job_execution_id         BIGINT       NOT NULL,
    job_name                 VARCHAR(100) NOT NULL,

    started_at               DATETIME     NULL,
    ended_at                 DATETIME     NULL,
    duration_ms              BIGINT       NOT NULL DEFAULT 0,

    total_violation_count    BIGINT       NOT NULL DEFAULT 0,
    status                   VARCHAR(20)  NOT NULL,

    report_file_path         VARCHAR(500) NULL,

    created_at               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),

    CONSTRAINT uk_verification_report_job_execution
        UNIQUE (job_execution_id),

    CONSTRAINT fk_verification_report_job_execution
        FOREIGN KEY (job_execution_id)
        REFERENCES BATCH_JOB_EXECUTION (JOB_EXECUTION_ID),

    INDEX idx_verification_report_job_name_created
        (job_name, created_at)

) ENGINE = InnoDB;