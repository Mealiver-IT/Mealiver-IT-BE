FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
COPY entity/pom.xml entity/pom.xml
COPY api/pom.xml api/pom.xml
RUN ./mvnw dependency:go-offline -B
COPY entity/src entity/src
COPY api/src api/src
RUN ./mvnw package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/api/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
