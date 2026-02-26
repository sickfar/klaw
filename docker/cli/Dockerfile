FROM debian:bookworm-slim
COPY build/dist/klaw-linuxX64 /usr/local/bin/klaw
RUN chmod +x /usr/local/bin/klaw
ENTRYPOINT ["klaw"]
