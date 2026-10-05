FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
COPY examples ./examples
RUN mvn -B -ntp verify && cp target/ai-agent-service-*.jar /build/app.jar

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid app --no-create-home app
COPY --from=build --chown=app:app /build/app.jar ./app.jar
ENV SERVER_ADDRESS=0.0.0.0 PORT=8081
USER app
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
