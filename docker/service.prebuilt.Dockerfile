# Production images from jars that deploy/setup.sh builds once, in a single Maven run for every module.
# (docker/service.Dockerfile compiles inside each image, which on a 2-CPU server means 13 Maven builds.)
#   docker build -f docker/service.prebuilt.Dockerfile --build-arg MODULE=services/auth-service .

FROM eclipse-temurin:21-jre AS extract
ARG MODULE
WORKDIR /x
COPY ${MODULE}/target/*-SNAPSHOT.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --home /app app \
 && mkdir -p /data/keys /data/resumes && chown -R app:app /data
WORKDIR /app
COPY --from=extract /extracted/dependencies/ ./
COPY --from=extract /extracted/spring-boot-loader/ ./
COPY --from=extract /extracted/snapshot-dependencies/ ./
COPY --from=extract /extracted/application/ ./
USER app
ENV TZ=UTC \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Duser.timezone=UTC"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
