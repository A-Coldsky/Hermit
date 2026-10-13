FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY . .
RUN ./mvnw -B -pl hermit-reef -am package -DskipTests

FROM eclipse-temurin:17-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 hermit \
    && useradd --system --uid 10001 --gid 10001 --home-dir /home/hermit --create-home --shell /usr/sbin/nologin hermit \
    && mkdir -p /data \
    && chown hermit:hermit /data
WORKDIR /app
COPY --from=build --chown=hermit:hermit /src/hermit-reef/target/hermit-reef-*.jar /app/reef.jar
USER hermit
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/reef.jar"]
