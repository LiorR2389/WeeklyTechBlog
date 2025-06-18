FROM eclipse-temurin:17-jdk-alpine

COPY . /app
WORKDIR /app

RUN ./gradlew build --no-daemon

CMD ["./gradlew", "run", "--no-daemon"]