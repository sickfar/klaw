FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY build/dist/klaw-engine-*.jar klaw-engine.jar
ENTRYPOINT ["java", "-Xms64m", "-Xmx512m", "-jar", "/app/klaw-engine.jar"]
