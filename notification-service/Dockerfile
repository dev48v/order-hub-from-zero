# notification-service — production multi-stage image (Day 42, Phase 6 DEPLOY).
# ---------------------------------------------------------------------------
# A pure event-driven CONSUMER — subscribes to lifecycle facts and sends templated customer notifications (gated OFF by default).
#
# WHY TWO STAGES: the BUILD needs Maven + the full JDK 21 + the whole dependency
# cache; the RUNTIME needs only a JRE and the finished jar. Splitting them keeps
# the shipped image small and free of the build toolchain (smaller attack
# surface). Build this from the REPOSITORY ROOT so the whole reactor is in the
# context:  docker build -f notification-service/Dockerfile -t orderhub/notification-service .
# (docker-compose.yml wires exactly that: context: . , dockerfile: notification-service/Dockerfile.)

# ── stage 1: build the jar (Maven + JDK 21) ────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copy the reactor POM and EVERY module POM first. Maven parses the whole
# reactor even to build ONE module, and this layer is cached — it only re-runs
# when a pom.xml changes, so ordinary source edits don't re-download the world.
COPY pom.xml .
COPY config-server/pom.xml        config-server/pom.xml
COPY eureka-server/pom.xml        eureka-server/pom.xml
COPY api-gateway/pom.xml          api-gateway/pom.xml
COPY order-service/pom.xml        order-service/pom.xml
COPY inventory-service/pom.xml    inventory-service/pom.xml
COPY payment-service/pom.xml      payment-service/pom.xml
COPY shipping-service/pom.xml     shipping-service/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml

# Pre-fetch just THIS module's dependencies (this module + anything it needs,
# via -am). Cached until a pom changes.
RUN mvn -q -B -pl notification-service -am dependency:go-offline

# Now the source for this module only, then build it. -DskipTests keeps the
# image build fast; the full test suite runs in the reactor build / CI, not here.
COPY notification-service/src ./notification-service/src
RUN mvn -q -B -pl notification-service -am clean package -DskipTests

# Unpack Spring Boot's LAYERED jar so the runtime stage can copy each layer
# separately (dependencies change rarely, app code often) for better caching.
RUN java -Djarmode=layertools -jar notification-service/target/*.jar extract --destination target/extracted

# ── stage 2: lean runtime (JRE 21 only, non-root) ──────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app

# curl is used by the docker-compose HEALTHCHECK to probe this service; install
# it minimally and clean the apt lists so the layer stays small.
RUN apt-get update     && apt-get install -y --no-install-recommends curl     && rm -rf /var/lib/apt/lists/*

# Never run as root: a compromised process then has no package-manager or
# privileged filesystem access inside the container.
RUN groupadd --system spring && useradd --system --gid spring spring

# Copy the Spring Boot layers in cache-friendly order (least-changing first).
COPY --from=build /app/target/extracted/dependencies/ ./
COPY --from=build /app/target/extracted/spring-boot-loader/ ./
COPY --from=build /app/target/extracted/snapshot-dependencies/ ./
COPY --from=build /app/target/extracted/application/ ./

USER spring

# JAVA_OPTS is honoured at launch — container-aware defaults; override per env.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"
# $PORT lets a platform (or compose) re-map the port; defaults to this service's port.
ENV PORT=8085
EXPOSE 8085

# Launch via the layered-jar launcher (the jar is UNPACKED, so not `java -jar`).
ENTRYPOINT ["sh","-c","java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher --server.port=${PORT}"]
