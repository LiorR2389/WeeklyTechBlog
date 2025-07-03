FROM eclipse-temurin:17-jdk-alpine

# Install gradle and git for pushing updates
RUN apk add --no-cache gradle git

# Environment variables will be injected by Render
ARG OPENAI_API_KEY
ARG EMAIL_USER
ARG EMAIL_PASS
ARG TO_EMAIL

ENV OPENAI_API_KEY=${OPENAI_API_KEY}
ENV EMAIL_USER=${EMAIL_USER}
ENV EMAIL_PASS=${EMAIL_PASS}
ENV TO_EMAIL=${TO_EMAIL}

COPY . /app
WORKDIR /app

RUN gradle build --no-daemon

CMD ["gradle", "run", "--no-daemon"]