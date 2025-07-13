FROM eclipse-temurin:17-jdk-alpine

# Install gradle
RUN apk add --no-cache gradle

COPY . /app
WORKDIR /app

RUN gradle build --no-daemon EXPOSE 8080

CMD ["gradle", "run", "--no-daemon"]