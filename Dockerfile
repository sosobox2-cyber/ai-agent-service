# JDK 17과 Maven으로 테스트를 포함한 실행 JAR을 생성한다.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
COPY examples ./examples
RUN mvn -B -ntp verify && cp target/ai-agent-service-*.jar /build/app.jar

# 실행 이미지는 JRE와 JAR만 포함하고 전용 일반 사용자로 서버를 시작한다.
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid app --no-create-home app \
    && mkdir -p /app/logs /app/data/evaluation && chown -R app:app /app/logs /app/data
COPY --from=build --chown=app:app /build/app.jar ./app.jar
ENV SERVER_ADDRESS=0.0.0.0 PORT=8081
USER app
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
