# syntax=docker/dockerfile:1

# Build 단계: JDK와 Maven Wrapper로 실행 가능한 JAR를 만든다.
FROM eclipse-temurin:25-jdk-noble@sha256:589ff4cc3f71aab462e7048a47a0d10edf57fbccde3fceea2281e610bf5880b4 AS build
WORKDIR /workspace

COPY --chmod=0755 mvnw ./mvnw
COPY .mvn/wrapper/maven-wrapper.properties ./.mvn/wrapper/maven-wrapper.properties
COPY pom.xml ./pom.xml
RUN ./mvnw -B -ntp dependency:go-offline

COPY src/main ./src/main
# Image Build는 패키징을 수행한다. 전체 Test는 Docker Engine이 있는 별도 환경에서 실행한다.
RUN ./mvnw -B -ntp -DskipTests package

# 실행 단계: Build 도구와 Source 대신 Java 실행 환경과 완성된 JAR를 사용한다.
FROM eclipse-temurin:25-jre-noble@sha256:d9a39a23634650173f1e2bbc176227af9728587ecf0f4b62d53e9355cd7a19ab AS runtime
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/target/ai-helpdesk-learning-lab-1.0.0-SNAPSHOT.jar ./helpdesk.jar

USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/helpdesk.jar"]
