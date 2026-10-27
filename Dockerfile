# Runs the jar built by `gradlew buildFatJar` (the Jenkinsfile builds and tests it first):
#
#     ./gradlew buildFatJar
#     docker build -t organizer .
#     docker run -d --name organizer --restart unless-stopped -p 8080:8080 -v organizer-data:/data organizer
#
# The database, and the key that encrypts the saved API key, are kept in /data; mount a volume there so they
# survive updates.
FROM eclipse-temurin:21-jre-noble
# The Jenkinsfile finds the images left over from earlier deploys by this label.
LABEL org.opencontainers.image.title=organizer

RUN useradd --system --home-dir /app --shell /usr/sbin/nologin organizer \
    && mkdir -p /app /data \
    && chown organizer:organizer /data

COPY build/libs/organizer.jar /app/organizer.jar
COPY --chmod=755 docker/backup.sh /app/backup.sh

ENV ORGANIZER_DB=/data/organizer.db
WORKDIR /app
USER organizer
VOLUME /data
EXPOSE 8080

# Photos are decoded with Java's image classes; headless keeps them from starting a desktop UI.
ENTRYPOINT ["java", "-Djava.awt.headless=true", "-jar", "/app/organizer.jar"]
