CREATE TABLE verification_report_check (
    id BIGINT NOT NULL AUTO_INCREMENT,

    verification_report_id BIGINT NOT NULL,

    check_type VARCHAR(50) NOT NULL,
    violation_count BIGINT NOT NULL DEFAULT 0,

    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),

    CONSTRAINT fk_report_check_report
        FOREIGN KEY (verification_report_id)
        REFERENCES verification_report (id)
        ON DELETE CASCADE,

    UNIQUE KEY uk_report_check_type
        (verification_report_id, check_type),

    INDEX idx_report_check_type
        (check_type)
) ENGINE = InnoDB;