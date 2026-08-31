# 개발 환경 실행 가이드

로컬에서 앱 띄우고 더미데이터 만드는 법, 리모트(학원 서버) DB 연결하는 법 정리.

## 0. 사전 준비

- JDK 21
- Docker Desktop (실행 중이어야 함 — 트레이 아이콘 확인)
- Tailscale 연결(리모트 DB 쓸 때만 필요)

## 1. 전체 빌드 (최초 1회 / pull 받은 뒤마다)

멀티모듈이라 `entity`를 먼저 로컬 저장소에 설치해야 `api`가 빌드된다.

```powershell
.\mvnw.cmd install -DskipTests
```

코드 수정 후 재실행할 땐 아래로 재컴파일부터 할 것 — 이 환경은 Maven incremental compile이 가끔 변경분을 인식 못 하고 옛날 class를 그대로 쓰는 경우가 있었다.

```powershell
.\mvnw.cmd clean compile -pl api -am
```

## 2. 로컬 DB 띄우기

```powershell
docker run -d --name mealiver-mysql -p 3307:3306 -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=mealiverit mysql:8
```

- 포트를 3306이 아니라 3307로 매핑한 이유: 로컬에 이미 MySQL이 깔려있는 사람이 많아서 충돌 방지.
- 컨테이너 이름/포트를 바꾸면 아래 설정도 같이 바꿀 것.

`api/src/main/resources/application-local.properties` 파일 만들기(없으면 생성, `.gitignore`에 등록돼 있어서 커밋 안 됨):

```properties
spring.datasource.url=jdbc:mysql://localhost:3307/mealiverit?rewriteBatchedStatements=true
spring.datasource.username=root
spring.datasource.password=root
```

`rewriteBatchedStatements=true` 꼭 붙일 것 — 대량 INSERT를 다건 `VALUES (a),(b),(c)...`로 묶어서 보내게 해줌. 이거 없으면 수십만~수백만 건 시딩이 몇 배 오래 걸린다.

## 3. 앱 실행 (로컬, 평소 개발용)

```powershell
.\mvnw.cmd spring-boot:run -pl api "-Dspring-boot.run.profiles=local"
```

Flyway가 자동으로 `V1__create_core_tables.sql` 등을 적용해서 테이블을 만든다.

## 4. 더미데이터 생성

전부 평소엔 안 돌고 각 `seed.xxx.enabled=true` 플래그를 붙였을 때만 실행됨(이 폴더의 `*SeedRunner.java`들). **아래 순서를 지켜야 한다** — 뒤 단계가 앞 단계 데이터에 의존함 (유저 → 오더 → 등급재산정 → 캠페인/쿠폰 → 발급이력).

| 순서 | 러너 | 플래그 | 하는 일 |
|---|---|---|---|
| 1 | `UserSeedRunner` | `seed.enabled=true` | 유저 생성 (기본 20,000명, `-Dseed.userCount=1000000`으로 규모 조절 가능) — 이름/전화번호는 `KoreanDummyDataGenerator`가 만듦(아래 참고) |
| 2 | `OrderSeedRunner` | `seed.orders.enabled=true` | 등급 분포(4:3:2:1)를 역산해 유저별 완료 주문 생성 |
| 3 | `MembershipTierSeedRunner` | `seed.membershipTier.enabled=true` | 주문 수 기준 등급(PRIVATE/PFC/CORPORAL/SERGEANT) 재산정 (`MembershipTierBatchJob` 호출) |
| 4 | `CampaignSeedRunner` | `seed.campaigns.enabled=true` | 캠페인 15개 + 쿠폰 1:1 시딩 (총 재고 300만) |
| 5 | `CouponIssueSeedRunner` | `seed.couponIssues.enabled=true` | 캠페인별 발급이력 시딩 (정상 케이스 위주) |

한 번에 다 돌리려면 플래그를 이어붙이면 된다:

```powershell
.\mvnw.cmd spring-boot:run -pl api "-Dspring-boot.run.profiles=local" "-Dspring-boot.run.arguments=--seed.enabled=true --seed.orders.enabled=true --seed.membershipTier.enabled=true --seed.campaigns.enabled=true --seed.couponIssues.enabled=true"
```

100만 유저 규모(과제 요구 스펙)로 하려면:

```powershell
.\mvnw.cmd spring-boot:run -pl api "-Dspring-boot.run.profiles=local" "-Dspring-boot.run.arguments=--seed.enabled=true --seed.userCount=1000000 --seed.orders.enabled=true --seed.membershipTier.enabled=true --seed.campaigns.enabled=true --seed.couponIssues.enabled=true"
```

콘솔에 각 러너의 `done: ...` 로그가 뜰 때까지 기다리는 게 원칙이지만, **5개 러너 전부 중간에 꺼져도 재실행하면 안전하게 이어서 진행한다**(재개/resume 지원 — Tailscale 등 네트워크가 불안정한 환경에서 100만 규모 시딩 도중 끊겨도 처음부터 다시 할 필요 없음, 2026-08-25):

- `UserSeedRunner`: `users` 테이블 행 수를 다음 시작 인덱스로 삼아 `user{그 수}`부터 이어서 INSERT (`uk_users_login_id` 위반 없이 안전). 이미 목표치만큼 다 있으면 아무것도 안 하고 skip 로그만 남김. (예: 15,000명 넣다가 죽고 목표를 40,000으로 늘려 재실행해도 `user15000`부터 정확히 이어짐 — 직접 검증함.)
- `OrderSeedRunner`: 이미 커밋된 주문 중 가장 큰 `user_id`를 기준으로, 그 이하 유저는 (주문 0건으로 끝났든 아니든) 이미 처리된 것으로 보고 건너뜀. 이등병 버킷은 주문 0건도 정상 결과라 "주문이 있으면 처리됨"으로는 안 되고 이 방식이어야 함(직접 검증: 3,000명 중 user2000까지 처리 후 강제종료 → 재실행 시 정확히 `user2000`부터 이어져 전원 커버, 중복 없음).
- `CampaignSeedRunner`: 캠페인명이 전부 고정 문자열이라, 이미 존재하는 이름은 건너뛰고 없는 것만 채움(직접 검증).
- `CouponIssueSeedRunner`: 캠페인마다 즉시 커밋되기 때문에 중간에 꺼도 이미 처리된 캠페인은 안전하게 남아있고, 재실행하면 처리 안 된 캠페인부터 이어서 진행한다(완료된 캠페인은 자동 스킵, full 캠페인이 중간에 끊긴 경우엔 모자란 만큼만 이어서 채움).
- `MembershipTierSeedRunner`: 별도 재개 로직 없이도 원래부터 안전함 — `MembershipTierBatchJob`이 매번 orders 기준으로 전체를 재계산하는 멱등 연산이라, 몇 번을 다시 돌려도 같은 결과로 수렴하고 `membership_tier_log`에 중복 로그도 안 쌓임(실제 전이가 있을 때만 로그). 다만 처음부터 전체 재계산이라 시간을 아끼는 효과는 없음.

전부 다 껐다 켜서 재실행해도 되는지 소규모(3,000명 스케일)로 직접 4개 러너 전체 파이프라인 완주 → 재실행(전부 skip 확인) → `OrderSeedRunner`만 강제 중단 시뮬레이션 후 재실행까지 실제로 검증했다.

`seed.enabled=true`만 쓸 때 유저 수가 50,000명 이하면 프로젝트 루트에 `users_<n>.json`(k6 부하테스트용 userId + idempotencyKey 목록)도 같이 생긴다. 그 이상이면 생략됨.

시딩 속도는 과제 채점 비대상 항목이라 최적화에 크게 신경 안 써도 된다.

**이름/전화번호 (2026-08-25 변경)**: `UserSeedRunner`는 이제 datafaker(미국식)가 아니라 `KoreanDummyDataGenerator`로 이름/전화번호를 만든다.
- 이름: `resources/seed/korean_names.txt`에 미리 생성해둔 한국식 성명 6만 개 중 랜덤 추출 (agemor/korean-name-generator, MIT — 자모 인접행렬 통계 모델로 생성, 고정 조합이 아니라 다양함)
- 전화번호: `010-XXXX-XXXX` 형식 — `PiiMasker.maskPhone()`이 원래 기대하던 포맷이라 관리자 화면에서 마스킹도 의도대로(`010-****-5678`) 나옴. 이전 미국식(`(555) 123-4567`)은 이 정규식에 안 걸려서 전체 마스킹(`(***) ***-****`)되던 부작용이 있었음.
- **이미 시딩된 DB(로컬이든 리모트든)의 기존 유저는 자동으로 안 바뀐다** — 다음에 새로 시딩할 때부터 적용됨. 기존 데이터까지 바꾸려면 6번 섹션대로 밀고 다시 시딩하거나, 아래 `UpdateKoreanUserDataRunner`로 name/phone만 갱신.

**기존 유저 name/phone만 백필하기**: 이미 완주된 DB(재개 로직이 "목표치 이미 있음"으로 skip해버려서 `UserSeedRunner` 재실행으로는 안 바뀜)에 한국식 이름/전화번호를 넣고 싶으면 `UpdateKoreanUserDataRunner`를 쓴다. id/login_id/email/등급 등 다른 컬럼과 orders/coupon_issue 등 참조 관계는 전혀 안 건드리고 name/phone만 CASE WHEN 청크(1,000건)로 UPDATE한다.

```powershell
.\mvnw.cmd spring-boot:run -pl api "-Dspring-boot.run.profiles=remote" "-Dspring-boot.run.arguments=--seed.updateKoreanUserData.enabled=true"
```

2026-08-25에 Tailscale 원격 DB(100만 200명)에 실제로 적용 완료 — 진행률 로그(`progress: N/1000200`)로 지켜봤고, 실행 후 orders(1,025만)/tier_log(60만)/campaign(121)/coupon_issue(291만) 건수가 실행 전과 정확히 동일함을 확인함(참조 관계 무손상).

## 5. 데이터 확인

**CLI로**:
```powershell
docker exec -it mealiver-mysql mysql -u root -proot
```
```sql
USE mealiverit;
SELECT COUNT(*) FROM users;
SELECT login_id, COUNT(*) FROM users GROUP BY login_id HAVING COUNT(*) > 1;  -- 0건이어야 정상
SELECT membership_tier, COUNT(*) FROM users GROUP BY membership_tier;
SELECT COUNT(*) FROM orders;
SELECT COUNT(*) FROM campaign;
SELECT id, total_stock, remaining_stock, status FROM campaign ORDER BY id;
SELECT COUNT(*) FROM coupon_issue;
```

**GUI로**: DBeaver 등 설치해서 `localhost:3307`, `root`/`root`, DB `mealiverit`로 접속.

## 6. 다 밀고 다시 하기 (초기화)

FK로 서로 참조하는 테이블이 많아서 그냥 TRUNCATE하면 에러 난다. FK 체크 잠깐 끄고 지울 것 (지우는 순서 상관없이 한 번에 꺼두면 됨):

```sql
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE coupon_state_log;
TRUNCATE TABLE coupon_issue;
TRUNCATE TABLE campaign;
TRUNCATE TABLE coupon;
TRUNCATE TABLE membership_tier_log;
TRUNCATE TABLE orders;
TRUNCATE TABLE users;
SET FOREIGN_KEY_CHECKS = 1;
```

컨테이너째로 완전히 밀고 싶으면:
```powershell
docker rm -f mealiver-mysql
```
그리고 2번부터 다시.

## 7. 리모트(학원 서버) DB 연결

로컬용과 별개로 `api/src/main/resources/application-remote.properties`가 이미 준비돼 있음(환경변수 참조, 비밀번호는 파일에 없음). 실행 전 팀 채널에서 실제 `DB_USER`/`DB_PASSWORD` 값을 확인해서 아래처럼 환경변수로 넣을 것 — **절대 코드에 직접 적지 말 것**.

```powershell
$env:DB_URL = "jdbc:mysql://100.125.247.64:3306/mealiver?rewriteBatchedStatements=true"
$env:DB_USER = "<팀 채널에서 확인>"
$env:DB_PASSWORD = "<팀 채널에서 확인>"

.\mvnw.cmd spring-boot:run -pl api "-Dspring-boot.run.profiles=remote"
```

더미데이터까지 리모트에 넣으려면 위 4번 표의 플래그를 이어붙이기 — 단, **공용 DB라 실행 전에 Adminer(`http://100.125.247.64:8081`, 서버 필드에 `mysql` 입력, 로그인 정보는 팀 채널 참고)로 각 테이블 건수부터 확인**. 이미 데이터가 있는데 또 시딩하면 유니크 제약(`login_id`, `uk_campaign_user` 등) 위반으로 실패한다. 100만 유저 규모로 밀어넣기 전엔 팀 채널에 먼저 공지할 것 — 다른 팀원이 지금 있는 2만 규모 데이터로 작업 중일 수 있음.

## 자주 만나는 에러

| 증상 | 원인 | 해결 |
|---|---|---|
| `Could not find artifact com.mealiverit:entity:jar` | entity 모듈이 로컬 저장소에 없음 | `.\mvnw.cmd install -DskipTests` 먼저 실행 |
| `Unable to find a suitable main class` | `-pl api -am`로 실행(run 골에 -am 쓰면 안 됨) | install은 `-am` 써도 되지만, `run`은 절대 `-am` 빼고 `-pl api`만 |
| `ports are not available: ... 3306` | 로컬에 다른 MySQL/컨테이너가 3306 점유 중 | 다른 포트(3307 등)로 매핑, `application-local.properties`도 같이 수정 |
| `Cannot truncate a table referenced in a foreign key constraint` | FK로 참조되는 테이블 | `SET FOREIGN_KEY_CHECKS=0;` 후 TRUNCATE |
| `docker: ... dockerDesktopLinuxEngine` | Docker Desktop이 안 켜져 있음 | Docker Desktop 실행하고 트레이 아이콘 안정될 때까지 대기 |
| 코드 고쳤는데 동작이 그대로임 | Maven incremental compile이 변경분을 못 잡음 | `.\mvnw.cmd clean compile -pl api -am` 후 재실행 |
| 대량 UPDATE가 몇 시간씩 걸림 | `rewriteBatchedStatements`는 INSERT에만 적용되고 UPDATE엔 효과 없음 | `CASE WHEN ... END` 청크 방식으로 묶어서 UPDATE (`MembershipTierBatchJob` 참고) |
