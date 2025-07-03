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

rkgzzy-codex/add-language-selection-and-translation-support
If summaries remain in English, ensure the package is installed or provide an
`OPENAI_API_KEY` so the script can fall back to OpenAI's translation API.

If Gradle cannot find `python3`, set a `PYTHON3` environment variable pointing
to the interpreter. The Kotlin code uses this variable when invoking the
translation helper.

=======
9z72xl-codex/add-language-selection-and-translation-support
If summaries remain in English, ensure the package is installed or provide an
`OPENAI_API_KEY` so the script can fall back to OpenAI's translation API.

Dev
Dev
## Repository Layout

- `src/main/kotlin` – application source
- `build.gradle.kts` – build configuration
- `seen_articles.json` – remembers which URLs were already processed


## Language Support

rkgzzy-codex/add-language-selection-and-translation-support
The generated HTML page now includes a language selector for English, Hebrew, Russian and Greek. Summaries are translated using the Python `googletrans` library when no OpenAI API key is provided. Selecting a language also changes the "Read more" links to open via Google Translate.

9z72xl-codex/add-language-selection-and-translation-support
The generated HTML page now includes a language selector for English, Hebrew, Russian and Greek. Summaries are translated using the Python `googletrans` library when no OpenAI API key is provided. Selecting a language also changes the "Read more" links to open via Google Translate.
Dev
Dev
