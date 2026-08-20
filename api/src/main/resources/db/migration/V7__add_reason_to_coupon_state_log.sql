-- 상태전이 감사로그에 왜 바뀌었는지 사유를 남기기 위한 컬럼 추가 (멘토 피드백: 상태전이 세분화).
-- 기존 로우는 사유를 소급 판정할 수 없으므로 UNKNOWN으로 채우고, 새 코드는 절대 UNKNOWN을 안 쓴다.
ALTER TABLE coupon_state_log
    ADD COLUMN reason VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN';