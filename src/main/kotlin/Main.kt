import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import org.json.JSONObject

data class Article(
    val title: String,
    val url: String,
    val date: ZonedDateTime,
    val source: String,
)

val client = OkHttpClient()
val gson = Gson()

fun main() {
    println("📰 Weekly Blog Fetcher Starting...")

    val allArticles = mutableListOf<Article>()

    println("🔍 Fetching Cyprus Mail RSS...")
    val cyprusMailArticles = fetchRSSFeed(
        "https://cyprus-mail.com/feed",
        source = "Cyprus Mail"
    )
    println("✅ Cyprus Mail articles: ${cyprusMailArticles.size}")
    allArticles.addAll(cyprusMailArticles)

    val newArticles = allArticles.filter {
        it.date.isAfter(ZonedDateTime.now().minusDays(7))
    }

    println("\n🆕 New articles this week: ${newArticles.size}")
    if (newArticles.isEmpty()) {
        println("\n📭 No new content to summarize. Exiting.")
        return
    }

    newArticles.forEach {
        println("\n• ${it.title}")
        println("  🔗 ${it.url}")
        println("  📅 ${it.date}")
        println("  🏷 Source: ${it.source}")
    }

    println("\n✍️ Generating blog post using GPT...")
    val blogPost = summarizeArticlesWithGPT(newArticles)
    println("\n✅ Blog post generated successfully:\n")
    println(blogPost)

    saveToFile("weekly_blog.md", blogPost)
    commitAndPushToGitHub()
}

fun fetchRSSFeed(feedUrl: String, source: String): List<Article> {
    val request = Request.Builder().url(feedUrl).build()
    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            println("❌ Failed to fetch $source: ${response.code}")
            return emptyList()
        }

        val body = response.body?.string() ?: return emptyList()
        val doc: Document = Jsoup.parse(body, "", org.jsoup.parser.Parser.xmlParser())
        val items = doc.select("item")

        return items.mapNotNull { item ->
            try {
                val title = item.selectFirst("title")?.text()?.trim() ?: return@mapNotNull null
                val link = item.selectFirst("link")?.text()?.trim() ?: return@mapNotNull null
                val pubDateText = item.selectFirst("pubDate")?.text()?.trim() ?: return@mapNotNull null

                val formatter = DateTimeFormatter.RFC_1123_DATE_TIME
                val pubDate = ZonedDateTime.parse(pubDateText, formatter)

                Article(title, link, pubDate, source)
            } catch (e: Exception) {
                null
            }
        }
    }
}

fun summarizeArticlesWithGPT(articles: List<Article>): String {
    val messages = mutableListOf<Map<String, String>>()
    messages.add(mapOf("role" to "system", "content" to "You are a professional blog writer. Summarize the week's Cyprus tech and economic news into 3 well-written paragraphs."))

    val content = buildString {
        articles.forEach {
            append("- ${it.title} (${it.source}): ${it.url}\n")
        }
    }
    messages.add(mapOf("role" to "user", "content" to content))

    val json = JSONObject()
    json.put("model", "gpt-4o")
    json.put("messages", messages)

    val requestBody = okhttp3.RequestBody.create(
        okhttp3.MediaType.parse("application/json"),
        json.toString()
    )

    val request = Request.Builder()
        .url("https://api.openai.com/v1/chat/completions")
        .post(requestBody)
        .addHeader("Authorization", "Bearer ${System.getenv("OPENAI_API_KEY")}")
        .build()

    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            throw IOException("Unexpected code $response")
        }

        val responseBody = response.body?.string() ?: throw IOException("Empty body")
        val jsonResponse = JSONObject(responseBody)
        return jsonResponse
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
    }
}

fun saveToFile(filename: String, content: String) {
    val path = Paths.get(filename)
    Files.write(path, content.toByteArray(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    println("💾 Blog post saved to $filename")
}

fun commitAndPushToGitHub() {
    val blogFile = Paths.get("weekly_blog.md")

    if (!Files.exists(blogFile)) {
        println("❌ weekly_blog.md not found.")
        return
    }

    val commands = listOf(
        "git add weekly_blog.md",
        "git commit -m \"Update blog for ${ZonedDateTime.now().toLocalDate()}\"",
        "git push origin main"
    )

    for (cmd in commands) {
        try {
            val parts = cmd.split(" ")
            val process = ProcessBuilder(parts)
                .directory(File("."))
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()

            val exitCode = process.waitFor()
            if (exitCode != 0) {
                println("❌ Command failed: $cmd")
            }
        } catch (e: Exception) {
            println("❌ Error running command '$cmd': ${e.message}")
        }
    }
}
