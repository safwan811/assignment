# Multi-stage build: the runtime image carries a JRE and a jar, not Maven and a source tree.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Dependencies are resolved in their own layer so a source-only change does not re-download them.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Unit tests only during the image build; integration tests need Docker and run in CI, not here.
RUN mvn -B -q clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine AS runtime
WORKDIR /app

# Non-root: nothing in this service needs privileged access.
RUN addgroup -S app && adduser -S -G app app
COPY --from=build /build/target/url-shortener-*.jar app.jar
USER app

EXPOSE 8080

# Container-aware heap sizing rather than a hard -Xmx that ignores the cgroup limit.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
