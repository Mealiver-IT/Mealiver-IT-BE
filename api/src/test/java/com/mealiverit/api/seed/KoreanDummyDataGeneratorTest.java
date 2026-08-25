package com.mealiverit.api.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.mealiverit.api.common.config.PiiPatterns;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KoreanDummyDataGeneratorTest {

    @Test
    void randomName_returnsHangulOnlyNameFromThePool() {
        Random random = new Random(1);

        String name = KoreanDummyDataGenerator.randomName(random);

        assertThat(name).isNotBlank();
        assertThat(name).matches("^[가-힣]+$");
    }

    @Test
    void randomName_repeatedCalls_produceMoreThanOneDistinctValue() {
        Random random = new Random(1);
        Set<String> names = new HashSet<>();

        for (int i = 0; i < 50; i++) {
            names.add(KoreanDummyDataGenerator.randomName(random));
        }

        assertThat(names).hasSizeGreaterThan(1);
    }

    @Test
    void randomPhone_matchesTheSameRegexPiiMaskerExpects() {
        Random random = new Random(1);

        String phone = KoreanDummyDataGenerator.randomPhone(random, new HashSet<>());

        assertThat(PiiPatterns.PHONE.matcher(phone).matches()).isTrue();
        assertThat(phone).startsWith("010-");
    }

    @Test
    void randomPhone_maskedByPiiMasker_revealsPrefixAndSuffixOnly() {
        Random random = new Random(1);

        String phone = KoreanDummyDataGenerator.randomPhone(random, new HashSet<>());
        String masked = com.mealiverit.api.common.config.PiiMasker.maskPhone(phone);

        assertThat(masked).isEqualTo("010-****-" + phone.substring(phone.length() - 4));
    }

    @Test
    void randomPhone_neverRepeatsAValueAlreadyInUsedPhones() {
        Random random = new Random(1);
        Set<String> usedPhones = new HashSet<>();

        for (int i = 0; i < 2_000; i++) {
            String phone = KoreanDummyDataGenerator.randomPhone(random, usedPhones);
            assertThat(usedPhones).contains(phone);
        }

        assertThat(usedPhones).hasSize(2_000); // 전부 서로 달라야 함 - 중복이었다면 add()가 false를 반환해 재시도했을 것
    }

    @Test
    void randomPhone_treatsPreSeededUsedPhonesAsTaken() {
        Random random = new Random(42);
        Set<String> usedPhones = new HashSet<>();
        // 실제 호출 전에 이미 나올 법한 값을 왕창 예약해둬서, 구현이 이 집합을 실제로 검사하는지 확인.
        for (int a = 0; a < 10_000; a++) {
            for (int b = 0; b < 10; b++) {
                usedPhones.add("010-%04d-%04d".formatted(a, b));
            }
        }
        int before = usedPhones.size();

        String phone = KoreanDummyDataGenerator.randomPhone(random, usedPhones);

        assertThat(usedPhones).hasSize(before + 1);
        assertThat(phone).doesNotMatch("^010-\\d{4}-000[0-9]$"); // b가 0~9인 예약 구간과 겹치면 안 됨
    }
}
