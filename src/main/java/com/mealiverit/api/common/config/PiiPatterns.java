package com.mealiverit.api.common.config;

import java.util.regex.Pattern;

/**
 * PII 정규식 패턴 단일 출처(single source of truth).
 * API 응답 마스킹(PiiMasker)과 로그 마스킹(PiiMaskingConverter) 양쪽에서 이 패턴을 공유한다.
 * 패턴이 바뀌면 여기 한 곳만 고치면 됨.
 */
public final class PiiPatterns {

    /**
     * 전화번호. 하이픈 유무 모두 허용(로그에는 사용자가 원본 그대로 찍힐 수 있어서 유연하게 잡아야 함).
     * 휴대폰(010/011/016/017/018/019)과 유선(02, 031~064 등 지역번호) 둘 다 커버.
     *   010-1234-5678 / 01012345678 / 02-123-4567 / 031-1234-5678 등
     */
    public static final Pattern PHONE = Pattern.compile(
            "(01[016789]|0[2-6][0-9]?)-?(\\d{3,4})-?(\\d{4})"
    );

    /** 이메일. local-part 첫 글자 이후 나머지 + 도메인을 분리해서 캡처. */
    public static final Pattern EMAIL = Pattern.compile(
            "([a-zA-Z0-9._%+-])([a-zA-Z0-9._%+-]*)(@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})"
    );

    private PiiPatterns() {
    }
}