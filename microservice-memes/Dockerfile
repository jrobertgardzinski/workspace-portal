# Runtime image for the meme service. The Spring Boot executable jar is built beforehand on the
# host (`mvn -pl memes-infrastructure -am package -DskipTests`). Build context is the repo root.
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY memes-infrastructure/target/memes-infrastructure-1.0.0-SNAPSHOT.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
