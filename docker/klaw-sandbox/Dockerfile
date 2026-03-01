FROM python:3.12-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
        bash \
        curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd -g 65534 sandbox && useradd -u 65534 -g sandbox -m sandbox
USER sandbox
WORKDIR /home/sandbox

ENTRYPOINT ["sleep", "infinity"]
