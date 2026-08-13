package com.mealiverit.api.common.config;

import java.util.regex.Matcher;

/**
 * "이미 어떤 필드가 PII 값인지 아는 상태"에서 그 값 전체를 마스킹하는 순수 함수 모음.
 * API 응답 DTO의 Jackson Serializer가 이걸 그대로 호출해서 사용.
 *
 * 마스킹 강도 원칙: 원본 길이가 별표 개수로 새어나가지 않도록, 마스킹 구간은
 * 항상 고정 개수(전화번호 4개, 이메일 3개)의 '*' 로 통일한다.
 */
public final class PiiMasker {

    /** 이름: 김철수 -> 김*수 (첫+끝 글자만 노출, 가운데는 길이 무관 '*' 1개). 2자 이하면 첫 글자 + '*' */
    public static String maskName(String name) {
        if (name == null || name.isBlank()) return name;
        int len = name.length();
        if (len <= 2) {
            return name.charAt(0) + "*";
        }
        return name.charAt(0) + "*" + name.charAt(len - 1);
    }

    /** 전화번호: 010-1234-5678 / 01012345678 -> 010-****-5678 (하이픈 없어도 처리, 출력은 하이픈 고정 포맷) */
    public static String maskPhone(String phone) {
        if (phone == null) return null;
        Matcher m = PiiPatterns.PHONE.matcher(phone);
        if (!m.matches()) {
            // 형식이 안 맞으면 안전하게 숫자 전체 마스킹 (형식 미확인 데이터의 유출 방지 우선)
            return phone.replaceAll("\\d", "*");
        }
        return m.group(1) + "-****-" + m.group(3);
    }

		/** 이메일: abcdef@example.com -> ab***@example.com (local-part 앞 2글자만 노출, 나머지는 길이 무관 고정 마스킹) */
    public static String maskEmail(String email) {
        if (email == null || email.isEmpty()) {
            return email;
        }

        int atIndex = email.indexOf('@');
        if (atIndex == -1) {
            return email;
        }

        String local = email.substring(0, atIndex);
        String domain = email.substring(atIndex); // '@' 포함 그대로 유지

        String maskedLocal;
        int len = local.length();

        if (len == 1) {
            maskedLocal = "*";
        } else if (len == 2) {
            maskedLocal = local.charAt(0) + "*";
        } else {
            // 3글자 이상: 앞 1글자 + 가운데 마스킹 + 뒤 1글자
            String first = local.substring(0, 1);
            String last = local.substring(len - 1);
            String middle = "*".repeat(len - 2);
            maskedLocal = first + middle + last;
        }

        return maskedLocal + domain;
    }

    private PiiMasker() {
    }
}