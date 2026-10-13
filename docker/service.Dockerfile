# One Dockerfile for every Spring Boot module. docker-compose passes the module path, e.g.
#   docker build -f docker/service.Dockerfile --build-arg MODULE=services/auth-service .
# Build context is the repo root so the reactor can resolve sibling modules (common-lib, event-contracts).

FROM maven:3.9-eclipse-temurin-21 AS build
ARG MODULE
WORKDIR /src
COPY pom.xml .
COPY platform platform
COPY infrastructure infrastructure
COPY services services
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl ${MODULE} -am -DskipTests package \
 && cp ${MODULE}/target/*-SNAPSHOT.jar /app.jar \
 && java -Djarmode=tools -jar /app.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --home /app app \
 && mkdir -p /data/keys /data/resumes && chown -R app:app /data
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER app
ENV TZ=UTC \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Duser.timezone=UTC"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
