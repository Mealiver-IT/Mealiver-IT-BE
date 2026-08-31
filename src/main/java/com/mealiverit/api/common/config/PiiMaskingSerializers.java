package com.mealiverit.api.common.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.mealiverit.api.common.config.PiiMasker;


import java.io.IOException;

/**
 * API 응답 DTO 필드용 Jackson Serializer. 실제 마스킹 로직은 PiiMasker에 위임 —
 * 여기서는 Jackson 인터페이스 어댑팅만 담당.
 */
public class PiiMaskingSerializers {

    public static class NameMasking extends JsonSerializer<String> {
        @Override
        public void serialize(String value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeString(PiiMasker.maskName(value));
        }
    }

    public static class PhoneMasking extends JsonSerializer<String> {
        @Override
        public void serialize(String value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeString(PiiMasker.maskPhone(value));
        }
    }

    public static class EmailMasking extends JsonSerializer<String> {
        @Override
        public void serialize(String value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeString(PiiMasker.maskEmail(value));
        }
    }
}