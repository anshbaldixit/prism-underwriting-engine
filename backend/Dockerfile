# Multi-stage: build with the Maven wrapper, run on a slim JRE as a non-root user.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q -DskipTests package

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \n    && useradd --system --uid 10001 prism
WORKDIR /app
COPY --from=build /src/target/prism-backend-*.jar app.jar
USER prism
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=90s CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
