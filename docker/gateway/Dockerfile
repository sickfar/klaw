FROM eclipse-temurin:21-jre-alpine

RUN addgroup -g 10001 klaw && adduser -u 10001 -G klaw -D klaw

RUN mkdir -p /home/klaw/.local/state/klaw/run \
             /home/klaw/.local/share/klaw \
             /home/klaw/.config/klaw \
    && chown -R klaw:klaw /home/klaw

WORKDIR /app
COPY build/dist/klaw-gateway-*.jar klaw-gateway.jar
USER klaw
ENTRYPOINT ["java", "-Xms32m", "-Xmx128m", "-Dlogback.configurationFile=logback-docker.xml", "-jar", "/app/klaw-gateway.jar"]
