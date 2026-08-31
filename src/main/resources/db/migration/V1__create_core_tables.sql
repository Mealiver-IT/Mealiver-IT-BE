-- 04_아키텍처.txt 1~2절, 06_ERD.dbml.txt 기준.
-- enum성 컬럼(status, discount_type 등)은 VARCHAR로 둔다 — JPA @Enumerated(EnumType.STRING) 매핑과
-- Hibernate ddl-auto=validate 검증 시 네이티브 MySQL ENUM보다 충돌 위험이 없다.

-- =========================================================
-- users
-- =========================================================
CREATE TABLE users (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    login_id           VARCHAR(64)  NOT NULL,
    name               VARCHAR(50)  NOT NULL,
    phone              VARCHAR(20)  NOT NULL,
    email              VARCHAR(100) NOT NULL,
    membership_tier    VARCHAR(20)  NOT NULL DEFAULT 'PRIVATE',
    tier_calculated_at DATETIME     NULL,
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_users_login_id UNIQUE (login_id)
) ENGINE = InnoDB;

-- =========================================================
-- orders — 계급 산정용 결제완료 이력 (실제 주문 UI는 프론트 정적 mockup)
-- =========================================================
CREATE TABLE orders (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    user_id      BIGINT        NOT NULL,
    order_amount DECIMAL(10,2) NOT NULL,
    paid_amount  DECIMAL(10,2) NOT NULL,
    status       VARCHAR(20)   NOT NULL,
    ordered_at   DATETIME      NOT NULL,
    completed_at DATETIME      NULL,
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES users (id),
    INDEX idx_orders_tier_calc (user_id, status, completed_at)
) ENGINE = InnoDB;

-- =========================================================
-- membership_tier_log — 계급 재산정 배치 감사로그
-- =========================================================
CREATE TABLE membership_tier_log (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    user_id        BIGINT      NOT NULL,
    from_tier      VARCHAR(20) NULL,
    to_tier        VARCHAR(20) NOT NULL,
    order_count    INT         NOT NULL,
    calculated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_tier_log_user FOREIGN KEY (user_id) REFERENCES users (id),
    INDEX idx_tier_log_user (user_id)
) ENGINE = InnoDB;

-- =========================================================
-- campaign
-- =========================================================
CREATE TABLE campaign (
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    name                VARCHAR(100) NOT NULL,
    total_stock         INT         NOT NULL,
    remaining_stock     INT         NOT NULL,
    open_at             DATETIME    NULL,
    close_at            DATETIME    NULL,
    status              VARCHAR(20) NOT NULL,
    min_membership_tier VARCHAR(20) NULL,
    version             BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) ENGINE = InnoDB;

-- =========================================================
-- coupon — 캠페인이 발급하는 쿠폰 정책 마스터
-- =========================================================
CREATE TABLE coupon (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    campaign_id         BIGINT        NOT NULL,
    discount_type       VARCHAR(20)   NOT NULL,
    discount_value      DECIMAL(10,4) NOT NULL,
    min_order_amount    DECIMAL(10,2) NULL,
    max_discount_amount DECIMAL(10,2) NULL,
    valid_hours         INT           NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_coupon_campaign FOREIGN KEY (campaign_id) REFERENCES campaign (id),
    CONSTRAINT chk_coupon_valid_hours CHECK (valid_hours >= 1)
) ENGINE = InnoDB;

-- =========================================================
-- coupon_issue — 발급 이력. 상태 관리 핵심 테이블(300만 건 대상)
-- =========================================================
CREATE TABLE coupon_issue (
    id                      BIGINT        NOT NULL AUTO_INCREMENT,
    campaign_id             BIGINT        NOT NULL,
    user_id                 BIGINT        NOT NULL,
    coupon_code             VARCHAR(64)   NOT NULL,
    discount_type           VARCHAR(20)   NOT NULL,
    discount_value          DECIMAL(10,4) NOT NULL,
    max_discount_amount     DECIMAL(10,2) NULL,
    issued_membership_tier  VARCHAR(20)   NOT NULL,
    status                  VARCHAR(20)   NOT NULL,
    idempotency_key         VARCHAR(100)  NOT NULL,
    issued_at               DATETIME      NOT NULL,
    valid_until             DATETIME      NOT NULL,
    used_at                 DATETIME      NULL,
    canceled_at             DATETIME      NULL,
    expired_at              DATETIME      NULL,
    version                 BIGINT        NOT NULL DEFAULT 0,
    created_at              DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_ci_campaign FOREIGN KEY (campaign_id) REFERENCES campaign (id),
    CONSTRAINT fk_ci_user FOREIGN KEY (user_id) REFERENCES users (id),
    -- 1인 1매 보장의 최종 방어선: 어떤 동시성 전략을 쓰든 이 제약이 없으면 초과발급 가능
    CONSTRAINT uk_campaign_user UNIQUE (campaign_id, user_id),
    -- 발급 요청 중복(재시도, 더블클릭, 네트워크 재전송) 방지
    CONSTRAINT uk_idempotency_key UNIQUE (idempotency_key),
    -- 쿠폰 코드 자체의 유일성
    CONSTRAINT uk_coupon_code UNIQUE (coupon_code),
    INDEX idx_ci_campaign (campaign_id),
    -- 만료 배치가 WHERE status='ISSUED' AND valid_until < NOW()로 스캔할 때 사용. 없으면 300만 건 풀스캔
    INDEX idx_ci_status_valid_until (status, valid_until)
) ENGINE = InnoDB;

-- =========================================================
-- coupon_state_log — 상태전이 감사로그. 검증 배치가 "이력 vs 카운터" 비교할 근거
-- =========================================================
CREATE TABLE coupon_state_log (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    coupon_issue_id  BIGINT      NOT NULL,
    from_status      VARCHAR(20) NULL,
    to_status        VARCHAR(20) NOT NULL,
    request_id       VARCHAR(100) NOT NULL,
    created_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_state_log_coupon_issue FOREIGN KEY (coupon_issue_id) REFERENCES coupon_issue (id),
    -- 상태전이 요청의 멱등성 최종 방어선(FR-CPS-005): 동일 request_id 재시도/중복 요청이
    -- 두 번째 로그로 남는 것을 DB가 물리적으로 거부한다
    CONSTRAINT uk_state_log_request UNIQUE (request_id),
    INDEX idx_state_log_coupon_issue (coupon_issue_id, id)
) ENGINE = InnoDB;
