package com.mealiverit.api.common.config;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.regex.Matcher;

import com.mealiverit.api.common.config.PiiPatterns;

/**
 * 로그 메시지(자유 텍스트) 안에 섞여 있을 수 있는 PII를 찾아서 마스킹.
 * 정규식은 PiiPatterns(공유 상수)를 그대로 사용 — API 응답 마스킹(PiiMasker)과
 * "어떤 패턴을 PII로 볼 것인가" 기준이 어긋나지 않도록 하기 위함.
 *
 * 마스킹 강도(고정 별표 개수)도 PiiMasker와 동일하게 맞춤:
 *   전화번호 가운데 -> ****,  이메일 local-part 나머지 -> ***
 */
public class PiiMaskingConverter extends MessageConverter {

	@Override
    public String convert(ILoggingEvent event) {
        String message = event.getFormattedMessage();
        message = maskAll(PiiPatterns.EMAIL, message, PiiMasker::maskEmail);
        message = maskAll(PiiPatterns.PHONE, message, PiiMasker::maskPhone);
        return message;
    }

    private String maskAll(java.util.regex.Pattern pattern, String input,
                            java.util.function.Function<String, String> masker) {
        Matcher matcher = pattern.matcher(input);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String matched = matcher.group();
            matcher.appendReplacement(result, Matcher.quoteReplacement(masker.apply(matched)));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}