import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import okhttp3.*
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.SocketTimeoutException
import okhttp3.MediaType.Companion.toMediaTypeOrNull
val apiKey = "sk-uMT48U4VbVbO2R0s7_1YjoAIQg4MkChPg2YdfLjLYcOxPnySaHZg5bmcyoeaG3R2hF6Ek3SLnJT3BlbkFJNTNqP_OA32586muhhKAOq78IiravEC-w4islGp9pnhuIu696Kc7oMDzQa3B8hYGKsi3N6oGUYA" // <-- hardcoded for quick test


val rssFeeds = listOf(
    "https://cyprus-mail.com/feed/",
    "https://www.sigmalive.com/rss/politics", // removed in-cyprus + cyprusnews.eu due to error
    "https://pafos.org.cy/anakoinosis/feed/" // assuming valid
)

val unwantedKeywords = listOf("war", "russia", "missile", "nato", "attack", "gaza", "ukraine", "military")

data class Article(val title: String, val link: String, val date: String, val source: String, val category: String)

fun fetchArticles(): List<Article> {
    println("🔍 Fetching articles...")
    val allArticles = mutableListOf<Article>()
    for (url in rssFeeds) {
        try {
            val doc = Jsoup.connect(url).timeout(10000).get()
            val items = doc.select("item")
            for (item in items) {
                val title = item.selectFirst("title")?.text() ?: continue
                val link = item.selectFirst("link")?.text() ?: continue
                val pubDate = item.selectFirst("pubDate")?.text() ?: ""
                val lowerTitle = title.lowercase()

                if (unwantedKeywords.none { lowerTitle.contains(it) }) {
                    val category = when {
                        listOf("villa", "property", "real estate", "apartment").any { lowerTitle.contains(it) } -> "Real Estate"
                        listOf("travel", "festival", "holiday", "beach", "trip", "hotel").any { lowerTitle.contains(it) } -> "Holidays & Travel"
                        listOf("tech", "ai", "startup", "data", "gadget", "app", "software").any { lowerTitle.contains(it) } -> "Technology"
                        else -> "Other"
                    }
                    allArticles.add(Article(title, link, pubDate, sourceFromUrl(url), category))
                }
            }
        } catch (e: IOException) {
            println("❌ Failed to fetch from $url: ${e.message}")
        }
    }
    println("✅ Total relevant articles: ${allArticles.size}")
    return allArticles
}

fun sourceFromUrl(url: String): String {
    return when {
        url.contains("cyprus-mail") -> "Cyprus Mail"
        url.contains("sigmalive") -> "SigmaLive"
        url.contains("pafos") -> "Pafos.org.cy"
        else -> "Other"
    }
}

fun summarizeWithGPT(articles: List<Article>, category: String): String {
    println("✍️ Generating blog post for category: $category (${articles.size} articles)...")
    val prompt = buildString {
        append("Summarize the following news articles under the theme '$category'. Avoid using political or violent language. Group ideas where possible and keep it concise.\n\n")
        articles.forEach {
            append("- ${it.title} (${it.source}): ${it.link}\n")
        }
    }

    val client = OkHttpClient.Builder().callTimeout(java.time.Duration.ofSeconds(60)).build()
    val mediaType = "application/json".toMediaTypeOrNull()
    val requestBody = RequestBody.create(mediaType, JSONObject(mapOf(
        "model" to "gpt-4",
        "messages" to listOf(mapOf("role" to "user", "content" to prompt))
    )).toString())

    val request = Request.Builder()
        .url("https://api.openai.com/v1/chat/completions")
        .post(requestBody)
        .addHeader("Authorization", "Bearer YOUR_API_KEY")
        .addHeader("Content-Type", "application/json")
        .build()

    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Unexpected code $response")
        val json = JSONObject(response.body!!.string())
        return json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
    }
}

fun main() {
    println("📰 Weekly Blog Fetcher Starting...")
    val articles = fetchArticles()

    if (articles.isEmpty()) {
        println("📭 No new content to summarize. Exiting.")
        return
    }

    val grouped = articles.groupBy { it.category }
    val allSummaries = StringBuilder("# Weekly Cyprus Blog – ${LocalDate.now()}\n\n")

    grouped.forEach { (category, list) ->
        try {
            val summary = summarizeWithGPT(list, category)
            allSummaries.append("## $category\n\n$summary\n\n")
        } catch (e: SocketTimeoutException) {
            println("⚠️ Timeout while summarizing $category")
        } catch (e: Exception) {
            println("❌ Error in $category summarization: ${e.message}")
        }
    }

    val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
    val outputFile = "weekly_blog_${today}.md"
    Files.write(
        Paths.get(outputFile),
        allSummaries.toString().toByteArray(),
        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
    )
    println("💾 Saved to $outputFile")

    val commitMessage = "Weekly blog post for $today"
    try {
        println("🚀 Committing and pushing to GitHub...")
        ProcessBuilder("git", "add", ".").directory(File(".")).start().waitFor()
        ProcessBuilder("git", "commit", "-m", commitMessage).directory(File(".")).start().waitFor()
        ProcessBuilder("git", "push", "origin", "main").directory(File(".")).inheritIO().start().waitFor()
        println("✅ Blog pushed to GitHub.")
    } catch (e: Exception) {
        println("❌ Failed to push to GitHub: ${e.message}")
    }
}
