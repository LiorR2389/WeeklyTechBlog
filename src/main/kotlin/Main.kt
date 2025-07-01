import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import jakarta.mail.Authenticator // ✅ <— Add this line
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.Base64


private val mail: Any

data class Article(
    val title: String,
    val url: String,
    val summary: String,
    val category: String,
    val date: String,
    val translations: Map<String, String> = emptyMap()
)

class NewsAggregator {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val seenArticlesFile = File("seen_articles.json")

    private val openAiApiKey = System.getenv("OPENAI_API_KEY") ?: ""
    private val githubToken = System.getenv("GITHUB_TOKEN") ?: ""
    private val githubRepo = System.getenv("GITHUB_REPO") ?: "LiorR2389/WeeklyTechBlog"
    private val githubUsername = System.getenv("GITHUB_USERNAME") ?: "LiorR2389"
    private val emailPassword = System.getenv("EMAIL_PASSWORD") ?: ""
    private val fromEmail = System.getenv("FROM_EMAIL") ?: "liorre.work@gmail.com"
    private val toEmail = System.getenv("TO_EMAIL") ?: "lior.global@gmail.com"

    private val newsSources = mapOf(
        "Cyprus Mail" to "https://cyprus-mail.com",
        "In-Cyprus" to "https://in-cyprus.philenews.com",
        "Financial Mirror" to "https://www.financialmirror.com/",
        "Alpha News" to "https://www.alphanews.live/",
        "Kathimerini Cyprus" to "https://www.kathimerini.com.cy/gr/"
    )

    private fun loadSeenArticles(): MutableSet<String> {
        return if (seenArticlesFile.exists()) {
            val json = seenArticlesFile.readText()
            val type = object : TypeToken<Set<String>>() {}.type
            gson.fromJson<Set<String>>(json, type).toMutableSet()
        } else mutableSetOf()
    }

    private fun saveSeenArticles(articles: Set<String>) {
        seenArticlesFile.writeText(gson.toJson(articles))
    }

    private fun fetchPage(url: String): Document? {
        return try {
            val request = Request.Builder().url(url).addHeader("User-Agent", "Mozilla/5.0").build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) Jsoup.parse(response.body?.string() ?: "") else null
        } catch (e: Exception) {
            null
        }
    }

    private fun generateSummary(title: String): String {
        return when {
            title.contains("tech", true) -> "Technology sector advancement with implications for Cyprus digital economy."
            else -> "General news story relevant to Cyprus current affairs."
        }
    }

    private fun translateSummary(summary: String): Map<String, String> {
        return mapOf(
            "en" to summary,
            "he" to "חדשות מקפריסין - $summary",
            "ru" to "Новости Кипра - $summary",
            "el" to "Ειδήσεις Κύπρου - $summary"
        )
    }

    private fun categorizeArticle(title: String, summary: String): String {
        val text = "$title $summary".lowercase()
        return when {
            "tech" in text -> "Technology"
            "economy" in text || "business" in text -> "Economy"
            "property" in text || "real estate" in text -> "Real Estate"
            "travel" in text || "holiday" in text || "tourism" in text -> "Tourism"
            else -> "General"
        }
    }

    private fun scrapeNewsSource(name: String, url: String): List<Article> {
        val doc = fetchPage(url) ?: return emptyList()
        val articles = mutableListOf<Article>()
        val selectors = listOf("article", ".post", ".story", "h2", "h3")

        for (selector in selectors) {
            val elements = doc.select(selector)
            elements.take(15).forEach { el ->
                try {
                    val title = el.text().trim()
                    val link = el.select("a").firstOrNull()?.absUrl("href") ?: ""
                    if (title.length > 10 && link.startsWith("http")) {
                        val summary = generateSummary(title)
                        val translations = translateSummary(summary)
                        articles.add(
                            Article(
                                title = title,
                                url = link,
                                summary = summary,
                                category = categorizeArticle(title, summary),
                                date = SimpleDateFormat("yyyy-MM-dd").format(Date()),
                                translations = translations
                            )
                        )
                    }
                } catch (_: Exception) {}
            }
            if (articles.isNotEmpty()) break
        }

        return articles.distinctBy { it.url }
    }

    fun aggregateNews(): List<Article> {
        val seen = loadSeenArticles()
        val all = mutableListOf<Article>()

        for ((name, url) in newsSources) {
            try {
                println("🔍 Scraping $name")
                val articles = scrapeNewsSource(name, url)
                all.addAll(articles)
                Thread.sleep(1000)
            } catch (e: Exception) {
                println("❌ Failed $name: ${e.message}")
            }
        }

        val newArticles = all.filter { it.url !in seen }
        seen.addAll(newArticles.map { it.url })
        saveSeenArticles(seen)

        println("✅ Found ${newArticles.size} new articles.")
        return newArticles
    }

    fun generateHtmlBlog(articles: List<Article>): String {
        val date = SimpleDateFormat("yyyy-MM-dd").format(Date())
        val grouped = articles.groupBy { it.category }

        val html = StringBuilder("""
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <title>Weekly Cyprus Blog – $date</title>
            <style>
                body { font-family: sans-serif; max-width: 800px; margin: auto; padding: 20px; }
                .lang-buttons button { margin: 5px; }
                .lang { display: none; }
                .lang.active { display: block; }
                .article { margin-bottom: 20px; padding-left: 10px; border-left: 4px solid #3498db; }
            </style>
        </head>
        <body>
            <h1>Weekly Cyprus Blog – $date</h1>
            <div class="lang-buttons">
                <button onclick="setLang('en')">English</button>
                <button onclick="setLang('he')">עברית</button>
                <button onclick="setLang('ru')">Русский</button>
                <button onclick="setLang('el')">Ελληνικά</button>
            </div>
        """)

        grouped.forEach { (cat, group) ->
            html.append("<h2>$cat</h2>")
            group.forEach { article ->
                html.append("""
                    <div class="article">
                        <div class="title">${article.title}</div>
                        <div class="lang en active">${article.translations["en"]}</div>
                        <div class="lang he">${article.translations["he"]}</div>
                        <div class="lang ru">${article.translations["ru"]}</div>
                        <div class="lang el">${article.translations["el"]}</div>
                        <a href="${article.url}" target="_blank">Read more</a>
                    </div>
                """)
            }
        }

        html.append("""
            <script>
                function setLang(lang) {
                    document.querySelectorAll('.lang').forEach(el => el.classList.remove('active'));
                    document.querySelectorAll('.lang.' + lang).forEach(el => el.classList.add('active'));
                }
            </script>
        </body>
        </html>
        """)
        return html.toString()
    }

    fun saveAndPush(htmlContent: String): String {
        val date = SimpleDateFormat("yyyyMMdd").format(Date())
        val filename = "weekly_blog_$date.html"
        File(filename).writeText(htmlContent)
        return pushToGitHub(filename, htmlContent)
    }

    private fun pushToGitHub(filename: String, htmlContent: String): String {
        return try {
            val apiUrl = "https://api.github.com/repos/$githubRepo/contents/$filename"
            val encodedContent = Base64.getEncoder().encodeToString(htmlContent.toByteArray())

            val body = JSONObject().apply {
                put("message", "Add weekly blog ${SimpleDateFormat("yyyy-MM-dd").format(Date())}")
                put("content", encodedContent)
                put("branch", "gh-pages")
            }

            val request = Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "token $githubToken")
                .addHeader("Accept", "application/vnd.github.v3+json")
                .put(RequestBody.create("application/json".toMediaType(), body.toString()))
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val url = "https://$githubUsername.github.io/${githubRepo.split("/")[1]}/$filename"
                println("✅ Uploaded to GitHub Pages: $url")
                return url
            } else {
                println("❌ Failed GitHub push: ${response.code}")
                ""
            }
        } catch (e: Exception) {
            println("❌ Exception in push: ${e.message}")
            ""
        }
    }

    fun sendEmail(blogUrl: String, count: Int) {
        if (emailPassword.isEmpty()) return
        val props = Properties().apply {
            put("mail.smtp.host", "smtp.gmail.com")
            put("mail.smtp.port", "587")
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
        }

        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(fromEmail, emailPassword)
            }
        })

        try {
            val msg = MimeMessage(session).apply {
                setFrom(InternetAddress(fromEmail))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail))
                subject = "📬 Weekly Cyprus Blog – $count articles"
                setText("Your blog is live:\n$blogUrl")
            }

            Transport.send(msg)
            println("📧 Email sent to $toEmail")
        } catch (e: Exception) {
            println("❌ Email error: ${e.message}")
        }
    }
}

fun main() {
    val blog = NewsAggregator()
    val articles = blog.aggregateNews()
    if (articles.isNotEmpty()) {
        val html = blog.generateHtmlBlog(articles)
        val url = blog.saveAndPush(html)
        if (url.isNotEmpty()) blog.sendEmail(url, articles.size)
    } else {
        println("ℹ️ No new articles found.")
    }
}
