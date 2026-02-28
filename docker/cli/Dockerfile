FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends \
    docker.io \
  && rm -rf /var/lib/apt/lists/*

RUN groupadd -g 10001 klaw && useradd -u 10001 -g klaw -m klaw

RUN mkdir -p /home/klaw/.local/state/klaw/run \
             /home/klaw/.local/share/klaw \
             /home/klaw/.config/klaw \
    && chown -R klaw:klaw /home/klaw

COPY docker/compose/docker-compose.prod.json /app/docker-compose.json
COPY build/dist/klaw-linuxX64 /usr/local/bin/klaw
RUN chmod +x /usr/local/bin/klaw
USER klaw
ENTRYPOINT ["klaw"]
