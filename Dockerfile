FROM gradle:8.11.1-jdk17

COPY . /app
WORKDIR /app

RUN gradle build --no-daemon
EXPOSE 8080

CMD ["gradle", "run", "--no-daemon"]