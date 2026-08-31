package com.mealiverit.api.common.config;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import com.mealiverit.api.common.config.PiiMaskingConverter;
import com.mealiverit.api.common.config.PiiMasker;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PiiMaskingConverterTest {

    private PiiMaskingConverter converter;

    @BeforeEach
    void setUp() {
        converter = new PiiMaskingConverter();
    }

    private ILoggingEvent eventWithMessage(String message) {
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getFormattedMessage()).thenReturn(message);
        return event;
    }

    // ---------- 이메일 마스킹 ----------

    @Test
    void 로그메시지에_이메일이_포함되면_마스킹된다() {
        ILoggingEvent event = eventWithMessage("사용자 조회: minju@example.com 로그인 시도");

        String result = converter.convert(event);

        System.out.println("[이메일 마스킹]");
        System.out.println("원본 : 사용자 조회: minju@example.com 로그인 시도");
        System.out.println("결과 : " + result);

        assertThat(result).doesNotContain("minju@example.com");
        assertThat(result).contains("***");
        assertThat(result).contains("@example.com");
    }

    @Test
    void 로그메시지에_이메일이_여러개면_모두_마스킹된다() {
        String message = "발신: a@test.com 수신: b@test.com";
        ILoggingEvent event = eventWithMessage(message);

        String result = converter.convert(event);

        System.out.println("[이메일 여러 개 마스킹]");
        System.out.println("원본 : " + message);
        System.out.println("결과 : " + result);

        assertThat(result).doesNotContain("a@test.com");
        assertThat(result).doesNotContain("b@test.com");
    }

    // ---------- 전화번호 마스킹 ----------

    @Test
    void 로그메시지에_전화번호가_포함되면_가운데가_마스킹된다() {
        String message = "연락처: 010-1234-5678 등록완료";
        ILoggingEvent event = eventWithMessage(message);

        String result = converter.convert(event);

        System.out.println("[전화번호 마스킹]");
        System.out.println("원본 : " + message);
        System.out.println("결과 : " + result);

        assertThat(result).contains("010-****-5678");
        assertThat(result).doesNotContain("1234-5678");
    }

    @Test
    void 하이픈없는_전화번호도_마스킹된다() {
        String message = "연락처: 01012345678 등록완료";
        ILoggingEvent event = eventWithMessage(message);

        String result = converter.convert(event);

        System.out.println("[하이픈 없는 전화번호 마스킹]");
        System.out.println("원본 : " + message);
        System.out.println("결과 : " + result);

        assertThat(result).doesNotContain("01012345678");
    }

    // ---------- 복합 케이스 ----------

    @Test
    void 이메일과_전화번호가_한_메시지에_같이_있어도_둘다_마스킹된다() {
        String message = "회원가입: minju@example.com / 010-1234-5678";
        ILoggingEvent event = eventWithMessage(message);

        String result = converter.convert(event);

        System.out.println("[이메일 + 전화번호 복합 마스킹]");
        System.out.println("원본 : " + message);
        System.out.println("결과 : " + result);

        assertThat(result)
            .doesNotContain("minju@example.com")
            .doesNotContain("1234-5678");
    }

    // ---------- PII 없는 메시지 ----------

    @Test
    void PII가_없는_메시지는_그대로_반환된다() {
        String plain = "쿠폰 발급 요청 처리 완료: campaignId=123, status=ISSUED";
        ILoggingEvent event = eventWithMessage(plain);

        String result = converter.convert(event);

        System.out.println("[PII 없는 메시지]");
        System.out.println("원본 : " + plain);
        System.out.println("결과 : " + result);

        assertThat(result).isEqualTo(plain);
    }

    @Test
    void 빈_메시지는_예외없이_빈문자열을_반환한다() {
        ILoggingEvent event = eventWithMessage("");

        String result = converter.convert(event);

        System.out.println("[빈 메시지]");
        System.out.println("원본 : \"\"");
        System.out.println("결과 : \"" + result + "\"");

        assertThat(result).isEmpty();
    }

    // ---------- API 응답 마스킹(PiiMasker)과의 일관성 ----------

    @Test
    void 전화번호_마스킹_포맷은_PiiMasker와_동일하다() {
        String message = "010-1234-5678";
        ILoggingEvent event = eventWithMessage(message);

        String result = converter.convert(event);
        String expectedFromPiiMasker = PiiMasker.maskPhone("010-1234-5678");

        System.out.println("[PiiMasker와 로그 마스킹 결과 비교]");
        System.out.println("원본 : " + message);
        System.out.println("로그 결과 : " + result);
        System.out.println("PiiMasker 결과 : " + expectedFromPiiMasker);

        assertThat(result).isEqualTo(expectedFromPiiMasker);
    }
}