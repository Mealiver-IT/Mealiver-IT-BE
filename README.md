# Mealiver-IT (밀리버릿)

배달앱(배민 스타일)에서 매일 오전 11시에 여는 **선착순 할인 쿠폰 발급**을 소재로 한 동시성·정합성 백엔드 프로젝트입니다. U+ 백엔드 과제 "대규모 트래픽 선착순 쿠폰 발급 시스템"으로, 팀명은 **태진아**입니다.

핵심 목표는 단순합니다 — 재고 10,000장에 20,000명이 동시에 요청해도 **초과 발급 0건, 1인 최대 1매**를 보장하는 것, 그리고 발급/사용/취소/만료 이력 300만 건 전체에 대해 재고와 이력이 어긋나지 않음을 스스로 검증할 수 있는 것.

## 왜 이 문제인가

짧은 코드로도 시작할 수 있지만 정확히 풀기는 어려운 문제입니다. 처리량이나 응답 속도는 평가 대상이 아니고, 동시성 제어의 정확성과 정합성을 스스로 검증하는 능력이 핵심 평가축(기술성+수행능력 60%)입니다.

## 기술 스택

- Java 21, Spring Boot
- MySQL, Redis
- Docker Compose
- Maven 멀티모듈 (`entity` + `api`)

## 핵심 기능

- **선착순 쿠폰 발급** — 동시성 제어 전략은 MVP(비관적 락)에서 Redis 이중 카운터 방식으로 하드닝
- **쿠폰 상태 머신** — `ISSUED → USED / CANCELED / EXPIRED`, 역행 불가 상태전이는 거부
- **Idempotency** — 동일한 발급/사용/취소/만료 요청이 중복·동시에 들어와도 결과는 1회만 반영
- **멤버십 등급 시스템** — 이등병/일병/상병/병장 4단계, 완료 주문 수 기준 매월 1일 자동 재산정 배치
- **등급별 차등 할인** — 발급 시점 등급 기준 이등병·일병 10% / 상병 30% / 병장 50%, 이후 등급이 바뀌어도 이미 발급된 쿠폰의 할인율은 불변
- **정합성 자기검증** — 300만 건 발급이력 전체를 대상으로, 같은 데이터로 재실행하면 같은 결과가 나오는 결정론적 검증 쿼리/배치
- **더미데이터** — 가상 유저 100만 명 + 발급이력 300만 건 규모로 생성·적재

## 모듈 구조

```
mealiver-it-be/
├── entity/   # JPA 엔티티 전용 모듈 (user, campaign, coupon, order, membership)
└── api/      # API 서버 모듈 — entity에 의존
    └── src/main/java/com/mealiverit/api/
        ├── common/     # 전역 예외 처리, 공통 응답 포맷
        ├── batch/       # 멤버십 등급 재산정 배치
        └── seed/        # 더미데이터 시더 (유저/오더/등급/캠페인/발급이력)
```

## 시작하기

로컬 환경 세팅, DB 실행, 더미데이터 생성 방법은 [`api/src/main/java/com/mealiverit/api/seed/README.md`](api/src/main/java/com/mealiverit/api/seed/README.md)에 정리되어 있습니다.

빠른 시작:

```powershell
.\mvnw.cmd install -DskipTests
docker run -d --name mealiver-mysql -p 3307:3306 -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=mealiverit mysql:8
.\mvnw.cmd spring-boot:run -pl api "-Dspring-boot.run.profiles=local"
```

## 기획/설계 문서

과제 요구사항부터 아키텍처, 시스템 설계, 개발 표준까지 전체 기획 문서는 [`docs/planning/`](docs/planning)에 정리되어 있습니다. 처음 보는 사람은 [`docs/planning/README_먼저읽기.txt`](docs/planning/README_먼저읽기.txt)부터 읽는 걸 권장합니다.

## 팀

팀명: 태진아 — U+ 백엔드 과제 "대규모 트래픽 선착순 쿠폰 발급 시스템"
