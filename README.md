# WeeklyTechBlog

This project collects recent news articles from several Cyprus-based sources, builds a single HTML page, and publishes it to a GitHub Gist. It can also send an email with the link to the generated blog.

## Building

Use the provided Gradle wrapper:

```sh
./gradlew build
```

This compiles the Kotlin sources and ensures dependencies are downloaded.

## Running

-Set the required environment variables before running:

- `OPENAI_API_KEY` – optional, used for generating summaries and translations. If not set, the first sentence of each article will be used as the summary.
- `EMAIL_USER` / `EMAIL_PASS` / `TO_EMAIL` – optional, enable email notifications.
- `GITHUB_TOKEN` – optional, enables pushing the generated blog via the GitHub API.

Run the aggregator with:

```sh
./gradlew run
```

The script will scrape news, generate `index.html`, push it to the configured Gist and send an email if credentials are provided.

The optional translation helper under `scripts/translate.py` requires the Python package `googletrans`:

```sh
pip install googletrans==4.0.0-rc1
```

## Repository Layout

- `src/main/kotlin` – application source
- `build.gradle.kts` – build configuration
- `seen_articles.json` – remembers which URLs were already processed


## Language Support

The generated HTML page now includes a language selector for English, Hebrew, Russian and Greek. Summaries are translated using the Python `googletrans` library when no OpenAI API key is provided. Selecting a language also changes the "Read more" links to open via Google Translate.
