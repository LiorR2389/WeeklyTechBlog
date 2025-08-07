FROM gradle:8.11.1-jdk17

# Copy everything first
COPY . /app
WORKDIR /app

# Make gradlew executable and use it instead of gradle
RUN chmod +x ./gradlew
RUN ./gradlew build --no-daemon

EXPOSE 8080

CMD ["./gradlew", "run", "--no-daemon"]