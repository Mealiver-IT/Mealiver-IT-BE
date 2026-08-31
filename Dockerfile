FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
RUN ./mvnw dependency:go-offline -B
COPY src src
RUN ./mvnw package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
# 2026-08-26: 베이스 이미지 기본 타임존이 UTC라 LocalDateTime.now()가 한국 시간보다
# 9시간 느린 값을 반환하는 문제가 있었다(DATETIME 컬럼엔 타임존 정보 없이 그 값이 그대로
# 저장됨). docker-compose.yml에서도 TZ=Asia/Seoul을 넘기지만, 이 이미지를 compose 없이
# 직접 docker run으로 띄우는 경우(로컬 테스트 등)에도 같은 문제가 재현되지 않도록 이미지
# 자체에도 기본값을 박아둔다 - compose의 환경변수가 있으면 그쪽이 우선 적용된다.
ENV TZ=Asia/Seoul
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
