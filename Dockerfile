# Day 10 — production multi-stage build.
# WHY two stages: the build needs Maven + the full JDK + the whole dependency cache, but the
# runtime only needs a JRE and the finished jar. Splitting them means the shipped image never
# carries the build toolchain, so it's far smaller and has a much smaller attack surface.

# --- stage 1: build the jar (Maven + JDK 21) ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
# Day 17: this is now a multi-module reactor. Copy the reactor POM and every module POM first
# (Maven parses the whole reactor even when building one module) and pre-fetch just the
# order-service dependencies. This layer is cached and only re-runs when a pom.xml changes, so
# day-to-day source edits don't re-download the world. We deploy ONLY the order API here, so we
# build it with `-pl order-service -am` (this module + anything it depends on) — the inventory
# service is a separate deployable and stays out of this image.
COPY pom.xml .
COPY order-service/pom.xml order-service/pom.xml
COPY inventory-service/pom.xml inventory-service/pom.xml
RUN mvn -q -B -pl order-service -am dependency:go-offline
# Now the source. -DskipTests keeps the image build fast; tests run in CI, not here.
COPY order-service/src ./order-service/src
RUN mvn -q -B -pl order-service -am clean package -DskipTests
# Unpack Spring Boot's layered jar so the runtime stage can copy each layer separately
# (dependencies change rarely, application code often) for better Docker layer caching.
RUN java -Djarmode=layertools -jar order-service/target/*.jar extract --destination target/extracted

# --- stage 2: lean runtime (JRE 21 only, non-root) ---
FROM eclipse-temurin:21-jre
WORKDIR /app

# Run as an unprivileged user, never root. If the process is ever compromised it has no
# package-manager or filesystem privileges inside the container.
RUN groupadd --system spring && useradd --system --gid spring spring

# Copy the Spring Boot layers in cache-friendly order: the layers that change least often
# (dependencies, then loader) first, application classes last.
COPY --from=build /app/target/extracted/dependencies/ ./
COPY --from=build /app/target/extracted/spring-boot-loader/ ./
COPY --from=build /app/target/extracted/snapshot-dependencies/ ./
COPY --from=build /app/target/extracted/application/ ./

USER spring

# Render injects $PORT — Spring Boot must bind to it. Default to 8080 for local `docker run`.
ENV PORT=8080
EXPOSE 8080

# Launch via the layered-jar launcher (not `java -jar app.jar`, since the jar is unpacked).
ENTRYPOINT ["sh","-c","java org.springframework.boot.loader.launch.JarLauncher --server.port=${PORT}"]
