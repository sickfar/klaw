FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY build/dist/klaw-gateway-*.jar klaw-gateway.jar
ENTRYPOINT ["java", "-Xms32m", "-Xmx128m", "-jar", "/app/klaw-gateway.jar"]
