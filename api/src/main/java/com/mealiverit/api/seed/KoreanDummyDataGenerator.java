package com.mealiverit.api.seed;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.Set;

// UserSeedRunner용 한국식 더미 이름/전화번호 생성. datafaker 기본(en) 로케일은 미국식 이름/전화번호만
// 내놓고, datafaker의 ko 로케일도 성/이름 각각 20개 고정 조합(400가지)뿐이라 대량 시딩엔 다양성이
// 부족하다(PiiMasker.maskPhone()도 애초에 010-1234-5678 형식을 기대하도록 짜여 있었음 - 미국식
// 전화번호와는 애초에 안 맞았음). 이름 풀은 agemor/korean-name-generator(MIT license,
// https://github.com/agemor/korean-name-generator)의 자모 인접행렬 통계 모델로 성별 균형 있게
// 미리 생성한 6만 개를 seed/korean_names.txt로 번들해두고 여기서 랜덤으로 뽑아 쓴다.
public final class KoreanDummyDataGenerator {

    private static final String NAME_POOL_RESOURCE = "/seed/korean_names.txt";
    private static final List<String> NAME_POOL = loadNamePool();

    private KoreanDummyDataGenerator() {
    }

    public static String randomName(Random random) {
        return NAME_POOL.get(random.nextInt(NAME_POOL.size()));
    }

    // PiiPatterns.PHONE / PiiMasker.maskPhone()이 기대하는 010-XXXX-XXXX 형식.
    // phone 컬럼엔 DB unique 제약은 없지만(V1 마이그레이션 참고), 전화번호는 실제로도 한 사람당
    // 하나뿐인 값이라 더미데이터에서도 중복을 허용하지 않는다(이름은 흔한 동명이인이 있을 수 있어
    // 중복 허용 - randomName()과 다른 정책). usedPhones에 이미 생성된 값을 계속 누적해서 넘겨야
    // 호출 전체에 걸쳐 유일성이 보장된다 - 기존 DB에 이미 있는 값도 호출 전에 미리 넣어둘 것.
    // 010-XXXX-XXXX 조합은 1억 개라 유저 수백만 명 규모에서도 재시도 몇 번이면 바로 찾는다.
    public static String randomPhone(Random random, Set<String> usedPhones) {
        String phone;
        do {
            phone = "010-%04d-%04d".formatted(random.nextInt(10_000), random.nextInt(10_000));
        } while (!usedPhones.add(phone));
        return phone;
    }

    private static List<String> loadNamePool() {
        try (InputStream in = KoreanDummyDataGenerator.class.getResourceAsStream(NAME_POOL_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("classpath resource not found: " + NAME_POOL_RESOURCE);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                List<String> names = reader.lines().filter(line -> !line.isBlank()).toList();
                if (names.isEmpty()) {
                    throw new IllegalStateException("name pool is empty: " + NAME_POOL_RESOURCE);
                }
                return names;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load " + NAME_POOL_RESOURCE, e);
        }
    }
}
