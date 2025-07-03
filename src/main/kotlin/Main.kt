import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.mail.*
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import kotlin.system.exitProcess
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class Article(
    val title: String,
    val url: String,
    val summary: String,
    val category: String,
    val date: String,
    val translations: Map<String, String> = emptyMap()
)

class NewsAggregator {
    private val seenFile = File("seen_articles.json")
    private val seen = loadSeenArticles().toMutableSet()

    private fun loadSeenArticles(): Set<String> {
        return if (seenFile.exists()) Json.decodeFromString(seenFile.readText()) else emptySet()
    }

    private fun saveSeenArticles() {
        seenFile.writeText(Json.encodeToString(seen.toList()))
    }

    private fun fetchPage(url: String): Document? {
        return try {
            Jsoup.connect(url).userAgent("Mozilla").timeout(10000).get()
        } catch (e: Exception) {
            println("❌ Failed to fetch $url")
            null
        }
    }

    private fun translateSummaryFromContent(text: String): Map<String, String> {
        val content = text.take(2000)
        val response = openAiTranslate(content)
        return response ?: mapOf(
            "en" to "General news story relevant to Cyprus current affairs.",
            "he" to "חדשות כלליות מקפריסין.",
            "ru" to "Актуальные новости Кипра.",
            "el" to "Γενικές ειδήσεις που σχετίζονται με την Κύπρο."
        )
    }

    private fun openAiTranslate(content: String): Map<String, String>? {
        val apiKey = System.getenv("OPENAI_API_KEY")
        if (apiKey.isNullOrBlank()) {
            println("ℹ️ No OpenAI API key provided – skipping translation")
            return null
        }

        return try {
            val prompt = """
                Summarize the following news text in a single sentence and provide translations in Hebrew, Russian, and Greek.

                News content:
                $content
            """.trimIndent()

            val jsonBody = Json.encodeToString(
                mapOf(
                    "model" to "gpt-4o",
                    "messages" to listOf(
                        mapOf("role" to "system", "content" to "You are a news translator."),
                        mapOf("role" to "user", "content" to prompt)
                    )
                )
            )

            val conn = URL("https://api.openai.com/v1/chat/completions").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(jsonBody.toByteArray()) }

            val response = conn.inputStream.bufferedReader().readText()
            val match = Regex("\"content\":\"(.*?)\"").find(response)?.groupValues?.get(1)?.replace("\\n", "\n")

            if (match != null) {
                mapOf(
                    "en" to match.lines().getOrNull(0)?.trim().orEmpty(),
                    "he" to match.lines().getOrNull(1)?.trim().orEmpty(),
                    "ru" to match.lines().getOrNull(2)?.trim().orEmpty(),
                    "el" to match.lines().getOrNull(3)?.trim().orEmpty()
                )
            } else {
                null
            }
        } catch (e: Exception) {
            println("❌ OpenAI translation error: ${e.message}")
            null
        }
    }

    private fun scrapeNewsSource(name: String, url: String): List<Article> {
        println("🔍 Scraping $name")
        val doc = fetchPage(url) ?: return emptyList()
        val links = doc.select("a[href]")
            .map { it.absUrl("href") }
            .filter { it.contains("/2025") || it.contains("/article") || it.contains("/news") }
            .distinct()
            .take(10)

        println("🔗 [$name] Found ${links.size} candidate links")

        val articles = mutableListOf<Article>()
        links.forEach { link ->
            if (seen.contains(link)) return@forEach
            try {
                val page = Jsoup.connect(link).get()
                val title = page.title().take(140)
                val articleText = page.select("p").joinToString(" ") { it.text() }.take(2000)

                if (articleText.isBlank()) {
                    println("⚠️ Empty article body for $link – skipping")
                    return@forEach
                }

                val translations = translateSummaryFromContent(articleText)
                println("✅ Scraped: $title")

                articles.add(
                    Article(
                        title = title,
                        url = link,
                        summary = translations["en"] ?: "",
                        category = "General",
                        date = SimpleDateFormat("yyyy-MM-dd").format(Date()),
                        translations = translations
                    )
                )
                seen.add(link)
            } catch (e: Exception) {
                println("❌ Error scraping $link: ${e.message}")
            }
        }

        return articles
    }

    fun aggregateNews(): List<Article> {
        val sources = listOf(
            "Cyprus Mail" to "https://cyprus-mail.com/",
            "In-Cyprus" to "https://in-cyprus.philenews.com/",
            "Financial Mirror" to "https://www.financialmirror.com/",
            "Alpha News" to "https://www.alphanews.live/",
            "Kathimerini Cyprus" to "https://www.kathimerini.com.cy/"
        )
        return sources.flatMap { scrapeNewsSource(it.first, it.second) }.also { saveSeenArticles() }
    }

    fun generateHtmlBlog(articles: List<Article>): String {
        val date = SimpleDateFormat("yyyy-MM-dd").format(Date())
        val builder = StringBuilder()
        builder.append("<html><head><meta charset='utf-8'><title>Weekly Cyprus Blog – $date</title></head><body>")
        builder.append("<h1>Weekly Cyprus Blog – $date</h1>")
        articles.forEach {
            builder.append("<h2>${it.title}</h2>")
            builder.append("<p>${it.translations["en"]}</p>")
            builder.append("<p><strong>HE:</strong> ${it.translations["he"]}</p>")
            builder.append("<p><strong>RU:</strong> ${it.translations["ru"]}</p>")
            builder.append("<p><strong>EL:</strong> ${it.translations["el"]}</p>")
            builder.append("<p><a href='${it.url}' target='_blank'>Read more</a></p><hr>")
        }
        builder.append("</body></html>")
        return builder.toString()
    }

    fun saveAndPush(html: String): String {
        val filename = "index.html"
        File(filename).writeText(html)

        try {
            Runtime.getRuntime().exec("git add $filename").waitFor()
            Runtime.getRuntime().exec("git commit -m \"Weekly update\"").waitFor()
            Runtime.getRuntime().exec("git push").waitFor()
            println("✅ Pushed blog to GitHub")
        } catch (e: Exception) {
            println("❌ Failed GitHub push: ${e.message}")
        }

        return "https://gist.githack.com/LiorR2389/2a6605238211f6e141dc126e16f8fbfa/raw/index.html"
    }

    fun sendEmail(url: String, count: Int) {
        val props = Properties().apply {
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.host", "smtp.gmail.com")
            put("mail.smtp.port", "587")
        }

        val emailUser = System.getenv("EMAIL_USER")
        val emailPass = System.getenv("EMAIL_PASS")

        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(emailUser, emailPass)
            }
        })

        try {
            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(emailUser))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(emailUser))
                subject = "🌐 Weekly Cyprus Blog – $count new articles"
                setText("View the latest blog:\n\n$url")
            }

            Transport.send(message)
            println("📬 Email sent successfully!")
        } catch (e: Exception) {
            println("❌ Failed to send email: ${e.message}")
        }
    }
}

fun main() {
    val force = true // ✅ override mode for debug/testing

    val blog = NewsAggregator()
    val initialArticles = blog.aggregateNews()

    val finalArticles = if (initialArticles.isNotEmpty()) initialArticles else if (force) blog.aggregateNews() else emptyList()

    if (finalArticles.isNotEmpty()) {
        val html = blog.generateHtmlBlog(finalArticles)
        val url = blog.saveAndPush(html)
        blog.sendEmail(url, finalArticles.size)
    } else {
        println("ℹ️ No new articles found.")
    }
}
