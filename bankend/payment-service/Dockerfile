# syntax=docker/dockerfile:1
# Multi-stage build: no local JDK or Maven needed. Build context is bankend/ (see compose.yaml):
#   docker build -f payment-service/Dockerfile .
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY libs/banking-common libs/banking-common
COPY payment-service payment-service
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    libs/banking-common/mvnw -q -B -f libs/banking-common/pom.xml -DskipTests install \
    && payment-service/mvnw -q -B -f payment-service/pom.xml -DskipTests package \
    && cp payment-service/target/*.jar /src/app.jar

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system --no-create-home app
COPY --from=build /src/app.jar app.jar
USER app
EXPOSE 8085
# The JRE image has no curl/wget: talk HTTP to the actuator with bash's /dev/tcp.
HEALTHCHECK --interval=10s --timeout=5s --start-period=120s --retries=12 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8085 && printf "GET /actuator/health HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && grep -q "\"status\":\"UP\"" <&3'
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
