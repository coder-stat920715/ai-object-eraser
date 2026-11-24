# syntax=docker/dockerfile:1

# ---------- Stage 1: build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package \
 && cp target/ai-object-eraser-*.jar /workspace/app.jar

# ---------- Stage 2: runtime ----------
# Debian/Ubuntu (glibc) base: ONNX Runtime's native libraries do not run on Alpine/musl.
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --create-home --uid 10001 eraser \
 && mkdir /models && chown eraser /models
WORKDIR /app
COPY --from=build /workspace/app.jar /app/app.jar
USER eraser

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Djava.awt.headless=true" \
    AI_ERASER_PROVIDER=demo \
    AI_ERASER_ONNX_MODEL_PATH=/models/lama_fp32.onnx

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
