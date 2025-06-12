FROM gradle:8.4.0-jdk17-alpine as builder
COPY . /app
WORKDIR /app
RUN gradle build --no-daemon

FROM eclipse-temurin:17-jdk-alpine
WORKDIR /app
COPY --from=builder /app/build/libs /app
CMD ["java", "-jar", "/app/WeeklyTechBlog-1.0.jar"]
