FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /app

# Cache Maven dependencies in a separate layer
COPY pom.xml .
RUN apk add --no-cache maven && \
    mvn dependency:go-offline -B

# Build the application (source changes don't bust the dependency cache)
COPY src ./src
RUN mvn clean package -DskipTests -o

# --- Runtime image ---
FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache ca-certificates curl && \
    addgroup -g 1000 appgroup && adduser -u 1000 -G appgroup -D appuser && \
    mkdir -p /app && chown -R appuser:appgroup /app

WORKDIR /app
COPY --from=build --chown=appuser:appgroup /app/target/*.jar app.jar

USER appuser

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -sf http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-jar", "app.jar", \
    "--spring.profiles.active=docker"]
