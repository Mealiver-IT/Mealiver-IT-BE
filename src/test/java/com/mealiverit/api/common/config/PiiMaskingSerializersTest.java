package com.mealiverit.api.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import com.mealiverit.api.common.config.PiiMaskingSerializers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class PiiMaskingSerializersTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    static class SampleDto {
        @JsonSerialize(using = PiiMaskingSerializers.NameMasking.class)
        public String name;

        @JsonSerialize(using = PiiMaskingSerializers.PhoneMasking.class)
        public String phone;

        @JsonSerialize(using = PiiMaskingSerializers.EmailMasking.class)
        public String email;

        SampleDto(String name, String phone, String email) {
            this.name = name;
            this.phone = phone;
            this.email = email;
        }
    }

    // ---------- NameMasking ----------

    @ParameterizedTest
    @CsvSource({
        "이,     '이*'",
        "이가,   '이*'",
        "김철수, '김*수'",
        "정민주, '정*주'"
    })
    void 이름_직렬화시_길이별로_마스킹된다(String input, String expected) throws Exception {
        SampleDto dto = new SampleDto(input, null, null);

        String json = objectMapper.writeValueAsString(dto);

        System.out.println("[이름 마스킹]");
        System.out.println("원본 : " + input);
        System.out.println("기대값 : " + expected);
        System.out.println("JSON 결과 : " + json);
        System.out.println();

        assertThat(json).contains("\"name\":\"" + expected + "\"");
    }

    @Test
    void 이름_직렬화시_원본_전체가_노출되지_않는다() throws Exception {
        SampleDto dto = new SampleDto("정민주", null, null);

        String json = objectMapper.writeValueAsString(dto);

        System.out.println("[이름 원본 노출 여부]");
        System.out.println("원본 : 정민주");
        System.out.println("JSON 결과 : " + json);
        System.out.println();

        assertThat(json).doesNotContain("\"정민주\"");
    }

    // ---------- PhoneMasking ----------

    @ParameterizedTest
    @CsvSource({
        "010-1234-5678, '010-****-5678'",
        "01012345678,   '010-****-5678'"
    })
    void 전화번호_직렬화시_정상포맷은_중간이_마스킹된다(String input, String expected) throws Exception {
        SampleDto dto = new SampleDto(null, input, null);

        String json = objectMapper.writeValueAsString(dto);

        System.out.println("[전화번호 마스킹]");
        System.out.println("원본 : " + input);
        System.out.println("기대값 : " + expected);
        System.out.println("JSON 결과 : " + json);
        System.out.println();

        assertThat(json).contains("\"phone\":\"" + expected + "\"");
    }

    @Test
    void 전화번호_포맷이_안맞으면_숫자전체가_마스킹된다() throws Exception {
        SampleDto dto = new SampleDto(null, "1234", null);

        String json = objectMapper.writeValueAsString(dto);

        System.out.println("[잘못된 전화번호 형식]");
        System.out.println("원본 : 1234");
        System.out.println("JSON 결과 : " + json);
        System.out.println();

        assertThat(json).contains("\"phone\":\"****\"");
    }

    @Test
    void 전화번호_null이면_null_그대로_직렬화된다() throws Exception {
        SampleDto dto = new SampleDto(null, null, null);

        String json = objectMapper.writeValueAsString(dto);

        System.out.println("[전화번호 null 처리]");
        System.out.println("원본 : null");
        System.out.println("JSON 결과 : " + json);
        System.out.println();

        assertThat(json).contains("\"phone\":null");
    }

    @Test
    void 전화번호_직렬화시_원본번호_전체가_노출되지_않는다() throws Exception {
        SampleDto dto = new SampleDto(null, "010-1234-5678", null);

        String json = objectMapper.writeValueAsString(dto);

        System.out.println("[전화번호 원본 노출 여부]");
        System.out.println("원본 : 010-1234-5678");
        System.out.println("JSON 결과 : " + json);
        System.out.println();

        assertThat(json).doesNotContain("1234-5678");
    }

    // ---------- EmailMasking ----------

    @ParameterizedTest
    @CsvSource({
        "a@test.com,               '*@test.com'",
        "ab@test.com,              'a*@test.com'",
        "abc@test.com,             'a*c@test.com'",
        "minju@example.com,        'm***u@example.com'",
        "johndoe123@company.co.kr, 'j********3@company.co.kr'"
    })
    void 이메일_직렬화시_로컬파트_길이별로_마스킹된다(String input, String expected) throws Exception {
        SampleDto dto = new SampleDto(null, null, input);

        String json = objectMapper.writeValueAsString(dto);

        System.out.println("[이메일 마스킹]");
        System.out.println("원본 : " + input);
        System.out.println("기대값 : " + expected);
        System.out.println("JSON 결과 : " + json);
        System.out.println();

        assertThat(json).contains("\"email\":\"" + expected + "\"");
    }

    @Test
    void 이메일_직렬화시_도메인부분은_그대로_노출된다() throws Exception {
        SampleDto dto = new SampleDto(null, null, "minju@example.com");

        String json = objectMapper.writeValueAsString(dto);

        System.out.println("[이메일 도메인 노출 여부]");
        System.out.println("원본 : minju@example.com");
        System.out.println("JSON 결과 : " + json);
        System.out.println();

        assertThat(json).contains("@example.com");
    }

    @Test
    void 이메일_at없는_형식이면_원본그대로_직렬화된다() throws Exception {
        SampleDto dto = new SampleDto(null, null, "invalid-email");

        String json = objectMapper.writeValueAsString(dto);

        System.out.println("[잘못된 이메일 형식]");
        System.out.println("원본 : invalid-email");
        System.out.println("JSON 결과 : " + json);
        System.out.println();

        assertThat(json).contains("\"email\":\"invalid-email\"");
    }

    @ParameterizedTest
    @NullAndEmptySource
    void 이메일이_null이거나_빈문자열이면_예외없이_직렬화된다(String input) {
        SampleDto dto = new SampleDto(null, null, input);

        System.out.println("[이메일 null/빈 문자열 처리]");
        System.out.println("원본 : " + input);
        System.out.println();

        assertThatCode(() -> objectMapper.writeValueAsString(dto))
            .doesNotThrowAnyException();
    }

    // ---------- 복합 케이스 ----------

    @Test
    void 세필드_모두_동시에_직렬화해도_각각_올바르게_마스킹된다() throws Exception {
        SampleDto dto = new SampleDto(
            "정민주",
            "010-1234-5678",
            "minju@example.com"
        );

        String json = objectMapper.writeValueAsString(dto);

        System.out.println("[전체 필드 마스킹]");
        System.out.println("원본 :");
        System.out.println("  이름     = 정민주");
        System.out.println("  전화번호 = 010-1234-5678");
        System.out.println("  이메일   = minju@example.com");
        System.out.println("JSON 결과 : " + json);
        System.out.println();

        assertThat(json)
            .contains("\"name\":\"정*주\"")
            .contains("\"phone\":\"010-****-5678\"")
            .contains("\"email\":\"m***u@example.com\"")
            .doesNotContain("정민주")
            .doesNotContain("1234-5678")
            .doesNotContain("minju@example.com");
    }
}