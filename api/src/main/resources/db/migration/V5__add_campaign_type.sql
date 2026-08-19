-- FCFS(선착순) 캠페인과 MEMBERSHIP_BENEFIT(계급별 월간 혜택) 캠페인을 구분하기 위한 컬럼.
-- 기존 캠페인은 전부 선착순이었으므로 DEFAULT 'FCFS'로 일괄 채운다.
ALTER TABLE campaign
    ADD COLUMN campaign_type VARCHAR(20) NOT NULL DEFAULT 'FCFS';