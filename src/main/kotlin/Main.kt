import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import jakarta.mail.Authenticator
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
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val seenArticlesFile = File("seen_articles.json")

    private val githubToken = System.getenv("GITHUB_TOKEN") ?: ""
    private val githubRepo = System.getenv("GITHUB_REPO") ?: "LiorR2389/WeeklyTechBlog"
    private val githubUsername = System.getenv("GITHUB_USERNAME") ?: "LiorR2389"
    private val emailPassword = System.getenv("EMAIL_PASSWORD") ?: ""
    private val fromEmail = System.getenv("FROM_EMAIL") ?: "liorre.work@gmail.com"
    private val toEmail = System.getenv("TO_EMAIL") ?: "lior.global@gmail.com"
    private val openAiApiKey = System.getenv("OPENAI_API_KEY") ?: ""

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

    private fun translateSummaryFromContent(articleText: String): Map<String, String> {
        val langs = mapOf("en" to "English", "he" to "Hebrew", "ru" to "Russian", "el" to "Greek")
        val translations = mutableMapOf<String, String>()

        langs.forEach { (code, langName) ->
            val prompt = """
                Summarize the following news article in one sentence in $langName, with a journalistic tone:
                $articleText
            """.trimIndent()
            translations[code] = callOpenAi(prompt)
        }

        return translations
    }

    private fun callOpenAi(prompt: String): String {
        val apiUrl = "https://api.openai.com/v1/chat/completions"
        val requestBody = """
            {
              "model": "gpt-4",
              "messages": [{"role": "user", "content": "$prompt"}],
              "temperature": 0.3
            }
        """.trimIndent()

        val request = Request.Builder()
            .url(apiUrl)
            .addHeader("Authorization", "Bearer $openAiApiKey")
            .addHeader("Content-Type", "application/json")
            .post(RequestBody.create("application/json".toMediaType(), requestBody))
            .build()

        client.newCall(request).execute().use { res ->
            val json = JSONObject(res.body?.string() ?: return "")
            return json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content").trim()
        }
    }

    private fun scrapeNewsSource(name: String, url: String): List<Article> {
        val doc = fetchPage(url) ?: return emptyList()
        val links = doc.select("a[href]").map { it.absUrl("href") }.filter { it.contains("2025") }.distinct()
        val articles = mutableListOf<Article>()

        links.take(8).forEach { link ->
            try {
                val page = Jsoup.connect(link).get()
                val title = page.title().take(140)
                val articleText = page.select("p").joinToString(" ") { it.text() }.take(2000)
                val translations = translateSummaryFromContent(articleText)

                articles.add(
                    Article(title, link, translations["en"] ?: "", "General",
                        SimpleDateFormat("yyyy-MM-dd").format(Date()), translations)
                )
            } catch (_: Exception) {}
        }

        return articles
    }

    fun aggregateNews(): List<Article> {
        val seen = loadSeenArticles()
        val all = mutableListOf<Article>()
        newsSources.forEach { (name, url) -> all.addAll(scrapeNewsSource(name, url)) }
        val newArticles = all.filter { it.url !in seen }
        seen.addAll(newArticles.map { it.url }); saveSeenArticles(seen)
        return newArticles
    }

    fun generateHtmlBlog(articles: List<Article>): String {
        val date = SimpleDateFormat("yyyy-MM-dd").format(Date())
        val grouped = articles.groupBy { it.category }
        val html = StringBuilder("""
            <!DOCTYPE html><html><head>
            <meta charset="UTF-8"><title>Weekly Cyprus Blog – $date</title>
            <script>
                function setLang(lang) {
                    document.querySelectorAll('.lang').forEach(el => el.classList.remove('active'));
                    document.querySelectorAll('.lang.' + lang).forEach(el => el.classList.add('active'));
                    localStorage.setItem('lang', lang);
                }
                window.onload = function() {
                    const urlLang = new URLSearchParams(location.search).get('lang');
                    const saved = urlLang || localStorage.getItem('lang') || 'en';
                    setLang(saved);
                }
            </script>
            <style>
                body { font-family: sans-serif; max-width: 800px; margin: auto; padding: 20px; }
                .lang { display: none; } .lang.active { display: block; }
                .lang-buttons button { margin: 5px; }
                .article { margin: 10px 0; padding-left: 10px; border-left: 4px solid #3498db; }
            </style></head><body>
            <h1>Weekly Cyprus Blog – $date</h1>
            <div class="lang-buttons">
                <button onclick="setLang('en')">English</button>
                <button onclick="setLang('he')">עברית</button>
                <button onclick="setLang('ru')">Русский</button>
                <button onclick="setLang('el')">Ελληνικά</button>
            </div>
        """)
        grouped.forEach { (_, items) ->
            items.forEach { a ->
                html.append("""
                    <div class="article">
                        <div><strong>${a.title}</strong></div>
                        <div class="lang en active">${a.translations["en"]}</div>
                        <div class="lang he">${a.translations["he"]}</div>
                        <div class="lang ru">${a.translations["ru"]}</div>
                        <div class="lang el">${a.translations["el"]}</div>
                        <a href="${a.url}" target="_blank">Read more</a>
                    </div>
                """)
            }
        }
        html.append("</body></html>")
        return html.toString()
    }

    fun saveAndPush(html: String): String {
        val date = SimpleDateFormat("yyyyMMdd").format(Date())
        val filename = "weekly_blog_$date.html"
        File(filename).writeText(html)
        return pushToGitHub(filename, html).ifEmpty { fallbackToGist(filename, html) }
    }

    private fun pushToGitHub(file: String, content: String): String {
        val api = "https://api.github.com/repos/$githubRepo/contents/$file"
        val body = JSONObject().apply {
            put("message", "Push blog $file")
            put("content", Base64.getEncoder().encodeToString(content.toByteArray()))
            put("branch", "gh-pages")
        }
        val req = Request.Builder().url(api)
            .addHeader("Authorization", "token $githubToken")
            .put(RequestBody.create("application/json".toMediaType(), body.toString())).build()
        val res = client.newCall(req).execute()
        return if (res.isSuccessful)
            "https://$githubUsername.github.io/${githubRepo.split("/")[1]}/$file"
        else ""
    }

    private fun fallbackToGist(file: String, content: String): String {
        val api = "https://api.github.com/gists"
        val body = JSONObject().apply {
            put("description", "Fallback blog")
            put("public", true)
            put("files", JSONObject().apply {
                put("index.html", JSONObject().apply { put("content", content) })
            })
        }
        val req = Request.Builder().url(api)
            .addHeader("Authorization", "token $githubToken")
            .post(RequestBody.create("application/json".toMediaType(), body.toString())).build()
        val res = client.newCall(req).execute()
        val gistId = JSONObject(res.body?.string()).optString("id")
        return "https://gist.githack.com/$githubUsername/$gistId/raw/index.html"
    }

    fun sendEmail(blogUrl: String, count: Int) {
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
            subject = "🌐 Weekly Cyprus Blog – $count new articles"
            setText("Your multilingual blog is live:\n\n$blogUrl")
        }
        Transport.send(message)
    }
}

fun main() {
    val blog = NewsAggregator()
    val articles = blog.aggregateNews()
    val override = true // set to false in production

    val finalArticles = if (articles.isNotEmpty()) articles else blog.aggregateNews()

    if (articles.isNotEmpty() || override) {
        val html = blog.generateHtmlBlog(finalArticles)
        val url = blog.saveAndPush(html)
        blog.sendEmail(url, finalArticles.size)
    } else println("ℹ️ No new articles found.")
}

