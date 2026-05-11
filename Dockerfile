FROM eclipse-temurin:11-jdk-focal AS build
WORKDIR /app

# Install sbt launcher (actual sbt version is controlled by project/build.properties)
RUN apt-get update && apt-get install -y curl gnupg apt-transport-https && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x99E82A75642AC823" | \
      gpg --no-default-keyring --keyring gnupg-ring:/etc/apt/trusted.gpg.d/scalasbt.gpg --import && \
    chmod 644 /etc/apt/trusted.gpg.d/scalasbt.gpg && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | tee /etc/apt/sources.list.d/sbt.list && \
    apt-get update && apt-get install -y sbt

COPY . .
RUN sbt stage

FROM eclipse-temurin:11-jre-focal
WORKDIR /app
COPY --from=build /app/target/universal/stage .

ENV PLAY_SECRET=changeme
EXPOSE 9000
ENTRYPOINT ["bin/minimal-cqrs", "-Dconfig.resource=docker.conf"]
