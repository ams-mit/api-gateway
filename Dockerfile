# Stage 1: Build stage
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# Copy Maven POM and wrapper to leverage layer caching
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .

# Download dependencies offline
RUN ./mvnw dependency:go-offline -B

# Copy source code and package application JAR (skip tests during container build)
COPY src src
RUN ./mvnw clean package -DskipTests

# Stage 2: Minimal Runtime stage
FROM eclipse-temurin:21-jre-alpine AS runner

WORKDIR /app

# Create non-root user and group for security hardening
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# Copy compiled fat JAR from builder stage
COPY --from=builder /build/target/*.jar app.jar

# Expose default HTTP server port
EXPOSE 8080

# Configure default JVM options
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0"

# Health check using Spring Boot Actuator endpoint
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# Launch Spring Boot Application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
