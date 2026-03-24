# === Stage 1: Build ===
# Gradle compiles Java source and packages a fat JAR (Spring Boot bootJar).
# Using JDK image because Gradle needs the full compiler toolchain.
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app

# Copy Gradle wrapper first (cached layer -- changes rarely)
COPY gradle/ gradle/
COPY gradlew build.gradle.kts settings.gradle.kts ./
RUN chmod +x gradlew

# Download dependencies (cached layer -- only re-runs when build.gradle.kts changes)
RUN ./gradlew dependencies --no-daemon

# Copy source and build (this layer rebuilds when source changes)
COPY src/ src/
RUN ./gradlew bootJar --no-daemon -x test

# === Stage 2: Run ===
# Slim JRE image -- no compiler, no Gradle, no source code.
# ~300MB vs ~800MB for the JDK image.
FROM eclipse-temurin:21-jre
WORKDIR /app

# Copy only the built JAR from the builder stage
COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8080

# Profile set via SPRING_PROFILES_ACTIVE env var (docker-compose.yml or fly.toml)
ENTRYPOINT ["java", "-jar", "app.jar"]
