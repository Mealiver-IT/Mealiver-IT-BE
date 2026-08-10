# Mealiver-IT 개발 환경 실행 가이드

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

## 2. 로컬 DB 띄우기

```powershell
docker run -d --name mealiver-mysql -p 3307:3306 -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=mealiverit mysql:8
```

- 포트를 3306이 아니라 3307로 매핑한 이유: 로컬에 이미 MySQL이 깔려있는 사람이 많아서 충돌 방지.
- 컨테이너 이름/포트를 바꾸면 아래 설정도 같이 바꿀 것.

`api/src/main/resources/application-local.properties` 파일 만들기(없으면 생성, `.gitignore`에 등록돼 있어서 커밋 안 됨):

```properties
spring.datasource.url=jdbc:mysql://localhost:3307/mealiverit
spring.datasource.username=root
spring.datasource.password=root
```

## 3. 앱 실행 (로컬, 평소 개발용)

```powershell
.\mvnw.cmd spring-boot:run -pl api "-Dspring-boot.run.profiles=local"
```

Flyway가 자동으로 `V1__create_core_tables.sql` 등을 적용해서 테이블을 만든다.

## 4. 더미데이터(유저 20,000명) 생성

평소엔 안 돌고 `seed.enabled=true`를 붙였을 때만 실행됨(`UserSeedRunner`).

```powershell
.\mvnw.cmd spring-boot:run -pl api "-Dspring-boot.run.profiles=local" "-Dspring-boot.run.arguments=--seed.enabled=true"
```

콘솔에 `done: 20000 users inserted + users_20000.json written`이 뜰 때까지 **끝까지 기다릴 것** — 중간에 끄면 일부만 들어간다. 완료되면 프로젝트 루트에 `users_20000.json`도 같이 생기는데, 이건 k6 부하테스트용(userId + idempotencyKey 목록)이다.

## 5. 데이터 확인

**CLI로**:
```powershell
docker exec -it mealiver-mysql mysql -u root -proot
```
```sql
USE mealiverit;
SELECT COUNT(*) FROM users;
SELECT login_id, COUNT(*) FROM users GROUP BY login_id HAVING COUNT(*) > 1;  -- 0건이어야 정상
```

**GUI로**: DBeaver 등 설치해서 `localhost:3307`, `root`/`root`, DB `mealiverit`로 접속.

## 6. 다 밀고 다시 하기 (초기화)

`orders`가 `users`를 FK로 참조해서 그냥 TRUNCATE하면 에러 난다. FK 체크 잠깐 끄고 지울 것:

```sql
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE users;
-- 다른 테이블도 지우려면 여기 추가: TRUNCATE TABLE orders; 등
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
$env:DB_URL = "jdbc:mysql://100.125.247.64:3306/mealiver"
$env:DB_USER = "<팀 채널에서 확인>"
$env:DB_PASSWORD = "<팀 채널에서 확인>"

.\mvnw.cmd spring-boot:run -pl api "-Dspring-boot.run.profiles=remote"
```

더미데이터까지 리모트에 넣으려면 마지막에 `"-Dspring-boot.run.arguments=--seed.enabled=true"` 붙이기 — 단, **공용 DB라 실행 전에 Adminer(`http://100.125.247.64:8081`, 서버 필드에 `mysql` 입력, 로그인 정보는 팀 채널 참고)로 `users` 건수부터 확인**. 이미 데이터가 있는데 또 20,000명 넣으면 `login_id` 중복으로 절반 넘게 실패한다.

## 자주 만나는 에러

| 증상 | 원인 | 해결 |
|---|---|---|
| `Could not find artifact com.mealiverit:entity:jar` | entity 모듈이 로컬 저장소에 없음 | `.\mvnw.cmd install -DskipTests` 먼저 실행 |
| `Unable to find a suitable main class` | `-pl api -am`로 실행(run 골에 -am 쓰면 안 됨) | install은 `-am` 써도 되지만, `run`은 절대 `-am` 빼고 `-pl api`만 |
| `ports are not available: ... 3306` | 로컬에 다른 MySQL/컨테이너가 3306 점유 중 | 다른 포트(3307 등)로 매핑, `application-local.properties`도 같이 수정 |
| `Cannot truncate a table referenced in a foreign key constraint` | `orders`가 `users`를 FK로 참조 | `SET FOREIGN_KEY_CHECKS=0;` 후 TRUNCATE |
| `docker: ... dockerDesktopLinuxEngine` | Docker Desktop이 안 켜져 있음 | Docker Desktop 실행하고 트레이 아이콘 안정될 때까지 대기 |
