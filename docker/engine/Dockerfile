FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl gnupg gosu \
    && install -m 0755 -d /etc/apt/keyrings \
    && curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu jammy stable" \
       > /etc/apt/sources.list.d/docker.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends docker-ce-cli \
    && apt-get purge -y gnupg \
    && apt-get autoremove -y \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd -g 10001 klaw && useradd -u 10001 -g klaw -m klaw

RUN mkdir -p /home/klaw/.local/state/klaw/run \
             /home/klaw/.local/share/klaw \
             /home/klaw/.config/klaw \
             /home/klaw/.cache/klaw \
    && chown -R klaw:klaw /home/klaw

WORKDIR /app
COPY build/dist/klaw-engine-*.jar klaw-engine.jar
COPY docker/engine/entrypoint.sh /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
CMD ["java", "-Xms64m", "-Xmx512m", "-Dlogback.configurationFile=logback-docker.xml", "-jar", "/app/klaw-engine.jar"]
