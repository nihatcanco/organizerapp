# Builds Organizer from source and runs it:
#
#     docker build -t organizer .
#     docker run -d --name organizer --restart unless-stopped -p 8080:8080 -v organizer-data:/data organizer
#
# The database, and the key that encrypts the saved API key, are kept in /data; mount a volume there so they
# survive updates. Add -e ORGANIZER_HTTPS=true to answer over HTTPS instead of HTTP.

FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /src
COPY --chmod=755 gradlew ./
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY src src
# The cache keeps Gradle and the libraries between builds. Compiling in Gradle's own process saves starting another.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process buildFatJar

FROM eclipse-temurin:21-jre-noble
# The Jenkinsfile finds the images left over from earlier deploys by the title.
LABEL org.opencontainers.image.title=organizer \
      org.opencontainers.image.source=https://github.com/nihatcanco/organizerapp \
      org.opencontainers.image.licenses=MIT

RUN useradd --system --home-dir /app --shell /usr/sbin/nologin organizer \
    && mkdir -p /app /data \
    && chown organizer:organizer /data

COPY --from=build /src/build/libs/organizer.jar /app/organizer.jar
COPY --chmod=755 docker/backup.sh /app/backup.sh

ENV ORGANIZER_DB=/data/organizer.db
WORKDIR /app
USER organizer
VOLUME /data
EXPOSE 8080

# Photos are decoded with Java's image classes; headless keeps them from starting a desktop UI.
ENTRYPOINT ["java", "-Djava.awt.headless=true", "-jar", "/app/organizer.jar"]
