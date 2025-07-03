# WeeklyTechBlog

This project collects recent news articles from several Cyprus-based sources, builds a single HTML page, and publishes it to a GitHub Gist. It can also send an email with the link to the generated blog.

## Building

Use the provided Gradle wrapper:

```sh
./gradlew build
```

This compiles the Kotlin sources and ensures dependencies are downloaded.

## Running

Set the required environment variables before running:

- `OPENAI_API_KEY` – optional, used for generating summaries and translations.
- `EMAIL_USER` / `EMAIL_PASS` / `TO_EMAIL` – optional, enable email notifications.

Run the aggregator with:

```sh
./gradlew run
```

The script will scrape news, generate `index.html`, push it to the configured Gist and send an email if credentials are provided.

### Git setup

Before running for the first time, configure a git remote pointing at your Gist or GitHub Pages repository:

```sh
git remote add origin https://gist.github.com/<gist-id>.git
```

The script expects this remote to be present when pushing the generated HTML.

## Repository Layout

- `src/main/kotlin` – application source
- `build.gradle.kts` – build configuration
- `seen_articles.json` – remembers which URLs were already processed

