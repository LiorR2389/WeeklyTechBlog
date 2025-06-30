// All imports remain
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
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

data class Article(
    val title: String,
    val url: String,
    val summary: String,
    val category: String,
    val date: String,
    val translations: Map<String, String> = emptyMap()
)

class NewsAggregator {
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val gson = Gson()
    private val seenArticlesFile = File("seen_articles.json")
    private val openAiApiKey = System.getenv("OPENAI_API_KEY")

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
        val targetLanguages = mapOf(
            "he" to "Hebrew",
            "ru" to "Russian",
            "el" to "Greek"
        )

        val translations = mutableMapOf<String, String>()
        translations["en"] = summary

        for ((langCode, langName) in targetLanguages) {
            val translated = callOpenAITranslation(summary, langName)
            translations[langCode] = translated
        }

        return translations
    }

    private fun callOpenAITranslation(text: String, language: String): String {
        val apiUrl = "https://api.openai.com/v1/chat/completions"
        val requestBody = """
            {
              "model": "gpt-4",
              "messages": [
                {"role": "system", "content": "You are a translation assistant."},
                {"role": "user", "content": "Translate this into $language:\n$text"}
              ],
              "temperature": 0.3
            }
        """.trimIndent()

        val request = Request.Builder()
            .url(apiUrl)
            .addHeader("Authorization", "Bearer $openAiApiKey")
            .addHeader("Content-Type", "application/json")
            .post(RequestBody.create("application/json".toMediaType(), requestBody))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                println("❌ OpenAI translation failed: ${response.code}")
                return ""
            }

            val json = JSONObject(response.body?.string())
            return json
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
        }
    }

    private fun categorizeArticle(title: String, summary: String): String {
        val t = "$title $summary".lowercase()
        return when {
            "tech" in t -> "Technology"
            else -> "General"
        }
    }

    private fun scrapeExample(): List<Article> {
        val doc = fetchPage("https://cyprus-mail.com") ?: return emptyList()
        return doc.select("article").mapNotNull {
            val title = it.select("h2").text()
            val url = it.select("a").attr("abs:href")
            if (title.length < 10 || url.isEmpty()) null else {
                val summary = generateSummary(title)
                Article(
                    title = title,
                    url = url,
                    summary = summary,
                    category = categorizeArticle(title, summary),
                    date = SimpleDateFormat("yyyy-MM-dd").format(Date()),
                    translations = translateSummary(summary)
                )
            }
        }
    }

    fun aggregateNews(): List<Article> {
        val seen = loadSeenArticles()
        val newArticles = scrapeExample().filter { it.url !in seen }
        seen.addAll(newArticles.map { it.url })
        saveSeenArticles(seen)
        return newArticles
    }

    fun generateHtmlBlog(articles: List<Article>): String {
        val currentDate = SimpleDateFormat("yyyy-MM-dd").format(Date())
        val grouped = articles.groupBy { it.category }

        val html = StringBuilder("""
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Weekly Cyprus Blog – $currentDate</title>
                <style>
                    body { font-family: Arial; max-width: 800px; margin: auto; padding: 20px; }
                    h1, h2 { color: #2c3e50; }
                    .article { margin-bottom: 15px; border-left: 4px solid #3498db; padding-left: 10px; }
                    .lang { display: none; }
                    .lang.active { display: block; }
                    .lang-buttons { margin-bottom: 20px; }
                    .lang-buttons button { margin-right: 10px; padding: 5px 10px; }
                </style>
            </head>
            <body>
                <h1>Weekly Cyprus Blog – $currentDate</h1>
                <div class="lang-buttons">
                    <button onclick="setLang('en')">English</button>
                    <button onclick="setLang('he')">עברית</button>
                    <button onclick="setLang('ru')">Русский</button>
                    <button onclick="setLang('el')">Ελληνικά</button>
                </div>
        """)

        grouped.forEach { (category, items) ->
            html.append("<h2>$category</h2>")
            items.forEach { article ->
                html.append("""
                    <div class="article">
                        <div class="article-title">${article.title}</div>
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

        val githubToken = System.getenv("GITHUB_TOKEN") ?: return ""
        val githubRepo = System.getenv("GITHUB_REPO") ?: "LiorR2389/WeeklyTechBlog"
        val githubUsername = System.getenv("GITHUB_USERNAME") ?: "LiorR2389"

        val apiUrl = "https://api.github.com/repos/$githubRepo/contents/$filename"
        val requestBody = JSONObject().apply {
            put("message", "Add weekly blog $date")
            put("content", Base64.getEncoder().encodeToString(htmlContent.toByteArray()))
        }

        val request = Request.Builder()
            .url(apiUrl)
            .addHeader("Authorization", "token $githubToken")
            .addHeader("Accept", "application/vnd.github.v3+json")
            .put(RequestBody.create("application/json".toMediaType(), requestBody.toString()))
            .build()

        val response = client.newCall(request).execute()
        if (response.isSuccessful) {
            return "https://$githubUsername.github.io/${githubRepo.split("/")[1]}/$filename"
        }

        return ""
    }

    fun sendEmail(blogUrl: String) {
        val fromEmail = "liorre.work@gmail.com"
        val toEmail = "lior.global@gmail.com"
        val emailPassword = System.getenv("EMAIL_PASSWORD") ?: return

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

        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(fromEmail))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail))
            subject = "🌐 Weekly Cyprus Blog is Ready"
            setText("View your multilingual Cyprus blog here:\n\n$blogUrl\n\n— Generated automatically.")
        }

        Transport.send(message)
        println("📧 Email sent to $toEmail")
    }
}

fun main() {
    val aggregator = NewsAggregator()
    val articles = aggregator.aggregateNews()

    if (articles.isEmpty()) {
        println("No new articles found.")
        return
    }

    val html = aggregator.generateHtmlBlog(articles)
    val blogUrl = aggregator.saveAndPush(html)
    if (blogUrl.isNotEmpty()) aggregator.sendEmail(blogUrl)

    println("✅ Multilingual blog complete: $blogUrl")
}
