FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends \
    docker.io \
  && rm -rf /var/lib/apt/lists/*
COPY build/dist/klaw-linuxX64 /usr/local/bin/klaw
RUN chmod +x /usr/local/bin/klaw
ENTRYPOINT ["klaw"]
