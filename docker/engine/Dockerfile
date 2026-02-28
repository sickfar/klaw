FROM eclipse-temurin:21-jre-jammy

RUN groupadd -g 10001 klaw && useradd -u 10001 -g klaw -m klaw

RUN mkdir -p /home/klaw/.local/state/klaw/run \
             /home/klaw/.local/share/klaw \
             /home/klaw/.config/klaw \
             /home/klaw/.cache/klaw \
    && chown -R klaw:klaw /home/klaw

WORKDIR /app
COPY build/dist/klaw-engine-*.jar klaw-engine.jar
USER klaw
ENTRYPOINT ["java", "-Xms64m", "-Xmx512m", "-Dlogback.configurationFile=logback-docker.xml", "-jar", "/app/klaw-engine.jar"]
