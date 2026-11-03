# syntax=docker/dockerfile:1
# Multi-stage build: no local JDK or Maven needed. Build context is bankend/ (see compose.yaml):
#   docker build -f account-service/Dockerfile .
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
# Shared library first: it changes rarely, so this layer is usually cached.
COPY libs/banking-common libs/banking-common
RUN --mount=type=cache,target=/root/.m2 \
    libs/banking-common/mvnw -q -B -f libs/banking-common/pom.xml -DskipTests install
COPY account-service account-service
RUN --mount=type=cache,target=/root/.m2 \
    account-service/mvnw -q -B -f account-service/pom.xml -DskipTests package \
    && cp account-service/target/*.jar /src/app.jar

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system --no-create-home app
COPY --from=build /src/app.jar app.jar
USER app
EXPOSE 8081
# The JRE image has no curl/wget: talk HTTP to the actuator with bash's /dev/tcp.
HEALTHCHECK --interval=10s --timeout=5s --start-period=120s --retries=12 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8081 && printf "GET /actuator/health HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && grep -q "\"status\":\"UP\"" <&3'
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
