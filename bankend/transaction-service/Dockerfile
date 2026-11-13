# syntax=docker/dockerfile:1
# Multi-stage build: no local JDK or Maven needed. Build context is bankend/ (see compose.yaml):
#   docker build -f transaction-service/Dockerfile .
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY libs/banking-common libs/banking-common
COPY transaction-service transaction-service
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    libs/banking-common/mvnw -q -B -f libs/banking-common/pom.xml -DskipTests install \
    && transaction-service/mvnw -q -B -f transaction-service/pom.xml -DskipTests package \
    && cp transaction-service/target/*.jar /src/app.jar

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system --no-create-home app
COPY --from=build /src/app.jar app.jar
USER app
EXPOSE 8082
# The JRE image has no curl/wget: talk HTTP to the actuator with bash's /dev/tcp.
HEALTHCHECK --interval=10s --timeout=30s --start-period=120s --retries=12 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8082 && printf "GET /actuator/health HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && grep -q "\"status\":\"UP\"" <&3'
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
