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
    val titleTranslations: Map<String, String> = emptyMap(),
    val summaryTranslations: Map<String, String> = emptyMap()
)

class NewsAggregator {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val seenArticlesFile = File("seen_articles.json")
    private val seenArticlesBackupFile = File("seen_articles_backup.json")

    // Environment variables
    private val openAiApiKey = System.getenv("OPENAI_API_KEY")
    private val githubToken = System.getenv("GITHUB_TOKEN")
    private val githubRepo = System.getenv("GITHUB_REPO") ?: "LiorR2389/WeeklyTechBlog"
    private val githubUsername = System.getenv("GITHUB_USERNAME") ?: "LiorR2389"
    private val emailPassword = System.getenv("EMAIL_PASSWORD")
    private val fromEmail = System.getenv("FROM_EMAIL") ?: "liorre.work@gmail.com"
    private val toEmail = System.getenv("TO_EMAIL") ?: "lior.global@gmail.com"

    // News sources with better selectors
    private val newsSources = mapOf(
        "Cyprus Mail News" to "https://cyprus-mail.com/category/news/",
        "Cyprus Mail Business" to "https://cyprus-mail.com/category/business/",
        "In-Cyprus Local" to "https://in-cyprus.philenews.com/local/",
        "In-Cyprus Opinion" to "https://in-cyprus.philenews.com/opinion/",
        "Financial Mirror Cyprus" to "https://www.financialmirror.com/category/cyprus/",
        "Financial Mirror Business" to "https://www.financialmirror.com/category/business/",
        "Alpha News Cyprus" to "https://www.alphanews.live/cyprus/",
        "Kathimerini Cyprus" to "https://www.kathimerini.com.cy/gr/kypros/"
    )

    private fun loadSeenArticles(): MutableSet<String> {
        return if (seenArticlesFile.exists()) {
            try {
                val json = seenArticlesFile.readText()
                val type = object : TypeToken<Set<String>>() {}.type
                gson.fromJson<Set<String>>(json, type).toMutableSet()
            } catch (e: Exception) {
                println("Error loading seen articles: ${e.message}")
                mutableSetOf()
            }
        } else mutableSetOf()
    }

    private fun saveSeenArticles(articles: Set<String>) {
        try {
            if (seenArticlesFile.exists()) {
                seenArticlesFile.copyTo(seenArticlesBackupFile, overwrite = true)
            }
            seenArticlesFile.writeText(gson.toJson(articles))
        } catch (e: Exception) {
            println("Error saving seen articles: ${e.message}")
        }
    }

    private fun fetchPage(url: String): Document? {
        return try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .addHeader("Accept-Language", "en-US,en;q=0.5")
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Jsoup.parse(response.body?.string() ?: "")
            } else {
                println("Failed to fetch $url: ${response.code}")
                null
            }
        } catch (e: Exception) {
            println("Error fetching $url: ${e.message}")
            null
        }
    }

    private fun generateSummary(title: String): String {
        val content = title.lowercase()
        return when {
            content.contains("tech") || content.contains("ai") ||
                    content.contains("digital") || content.contains("innovation") ||
                    content.contains("startup") || content.contains("software") ->
                "Technology advancement in Cyprus with potential economic impact."
            content.contains("business") || content.contains("economy") ||
                    content.contains("financial") || content.contains("bank") ||
                    content.contains("market") || content.contains("euro") ||
                    content.contains("gdp") || content.contains("investment") ||
                    content.contains("cclei") || content.contains("imd") ->
                "Economic development affecting Cyprus business environment."
            content.contains("real estate") || content.contains("property") ||
                    content.contains("housing") || content.contains("construction") ->
                "Real estate market update impacting Cyprus property sector."
            content.contains("travel") || content.contains("tourism") ||
                    content.contains("holiday") || content.contains("hotel") ||
                    content.contains("festival") || content.contains("culture") ->
                "Tourism and cultural development in Cyprus region."
            content.contains("politics") || content.contains("government") ||
                    content.contains("parliament") || content.contains("minister") ||
                    content.contains("talks") || content.contains("negotiation") ->
                "Political development with implications for Cyprus governance."
            content.contains("crime") || content.contains("arrest") ||
                    content.contains("police") || content.contains("court") ||
                    content.contains("fraud") || content.contains("theft") ->
                "Legal matter affecting Cyprus public safety and justice."
            else -> "General news story relevant to Cyprus current affairs."
        }
    }

    private fun translateText(text: String, targetLanguage: String): String {
        if (openAiApiKey.isNullOrEmpty()) {
            return when (targetLanguage) {
                "he" -> "כותרת בעברית"
                "ru" -> "Заголовок на русском"
                "el" -> "Τίτλος στα ελληνικά"
                else -> text
            }
        }

        return try {
            val apiUrl = "https://api.openai.com/v1/chat/completions"
            val requestBody = """
                {
                  "model": "gpt-4o-mini",
                  "messages": [
                    {"role": "system", "content": "You are a professional news translator. Translate headlines accurately while maintaining journalistic tone. Keep translations concise and natural."},
                    {"role": "user", "content": "Translate this news headline to $targetLanguage:\n\n$text"}
                  ],
                  "temperature": 0.1,
                  "max_tokens": 150
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
                    return text
                }

                val json = JSONObject(response.body?.string())
                val translatedText = json
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()

                return translatedText
            }
        } catch (e: Exception) {
            println("❌ Translation error: ${e.message}")
            text
        }
    }

    private fun translateSummary(summary: String): Map<String, String> {
        val translations = mutableMapOf<String, String>()
        translations["en"] = summary

        val targetLanguages = mapOf(
            "he" to "Hebrew",
            "ru" to "Russian",
            "el" to "Greek"
        )

        for ((langCode, langName) in targetLanguages) {
            val translated = translateText(summary, langName)
            translations[langCode] = translated
            Thread.sleep(200)
        }

        return translations
    }

    private fun translateTitle(title: String): Map<String, String> {
        val translations = mutableMapOf<String, String>()
        translations["en"] = title

        val targetLanguages = mapOf(
            "he" to "Hebrew",
            "ru" to "Russian",
            "el" to "Greek"
        )

        for ((langCode, langName) in targetLanguages) {
            val translated = translateText(title, langName)
            translations[langCode] = translated
            Thread.sleep(200)
        }

        return translations
    }

    private fun callOpenAITranslation(text: String, language: String): String {
        return try {
            val apiUrl = "https://api.openai.com/v1/chat/completions"
            val requestBody = """
                {
                  "model": "gpt-4o-mini",
                  "messages": [
                    {"role": "system", "content": "You are a professional news translator. Translate accurately while maintaining journalistic tone. Keep it brief and professional."},
                    {"role": "user", "content": "Translate this news summary to $language (keep it under 100 characters):\n\n$text"}
                  ],
                  "temperature": 0.2,
                  "max_tokens": 100
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
                    println("❌ OpenAI translation failed for $language: ${response.code}")
                    return ""
                }

                val json = JSONObject(response.body?.string())
                val translatedText = json
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()

                return translatedText
            }
        } catch (e: Exception) {
            println("❌ Translation error for $language: ${e.message}")
            ""
        }
    }

    private fun categorizeArticle(title: String, summary: String): String {
        val content = "$title $summary".lowercase()
        return when {
            content.contains("tech") || content.contains("ai") ||
                    content.contains("digital") || content.contains("innovation") ||
                    content.contains("startup") || content.contains("software") -> "Technology"
            content.contains("business") || content.contains("economy") ||
                    content.contains("financial") || content.contains("bank") ||
                    content.contains("market") || content.contains("euro") ||
                    content.contains("gdp") || content.contains("investment") ||
                    content.contains("cclei") || content.contains("imd") -> "Business & Economy"
            content.contains("real estate") || content.contains("property") ||
                    content.contains("housing") || content.contains("construction") -> "Real Estate"
            content.contains("travel") || content.contains("tourism") ||
                    content.contains("holiday") || content.contains("hotel") ||
                    content.contains("festival") || content.contains("culture") -> "Holidays & Travel"
            content.contains("politics") || content.contains("government") ||
                    content.contains("parliament") || content.contains("minister") ||
                    content.contains("talks") || content.contains("negotiation") -> "Politics"
            content.contains("crime") || content.contains("arrest") ||
                    content.contains("police") || content.contains("court") ||
                    content.contains("fraud") || content.contains("theft") -> "Crime & Justice"
            else -> "General News"
        }
    }

    private fun scrapeNewsSource(sourceName: String, url: String): List<Article> {
        println("🔍 Scraping $sourceName...")
        val doc = fetchPage(url) ?: return emptyList()

        val articles = mutableListOf<Article>()

        // Improved selectors for each source
        val selectors = when {
            sourceName.contains("Cyprus Mail", true) -> listOf(
                ".post-item .post-title a",
                "article .entry-title a",
                ".news-item h2 a",
                "h2 a[href*='/2025/']",
                "h3 a[href*='/2025/']"
            )
            sourceName.contains("In-Cyprus", true) -> listOf(
                ".entry-title a",
                ".post-title a",
                "h2.entry-title a",
                "article h2 a",
                ".post-content h3 a"
            )
            sourceName.contains("Financial Mirror", true) -> listOf(
                ".entry-title a",
                "h2.entry-title a",
                ".post-title a",
                "article h2 a"
            )
            sourceName.contains("Alpha News", true) -> listOf(
                ".entry-title a",
                ".post-title a",
                "h2 a",
                "article h3 a"
            )
            sourceName.contains("Kathimerini", true) -> listOf(
                ".entry-title a",
                "h2 a",
                ".article-title a",
                ".post-title a"
            )
            else -> listOf("h2 a", "h3 a", ".entry-title a")
        }

        // First try to get articles directly by link selectors
        for (selector in selectors) {
            val linkElements = doc.select(selector)
            if (linkElements.size > 0) {
                println("  ✅ Found ${linkElements.size} article links with selector: $selector")

                linkElements.take(12).forEach { linkElement ->
                    try {
                        val title = linkElement.text().trim()
                        var articleUrl = linkElement.attr("abs:href").ifEmpty {
                            linkElement.attr("href")
                        }

                        // Clean and validate URL
                        if (articleUrl.startsWith("/")) {
                            val baseUrl = when {
                                sourceName.contains("cyprus-mail", true) -> "https://cyprus-mail.com"
                                sourceName.contains("in-cyprus", true) -> "https://in-cyprus.philenews.com"
                                sourceName.contains("financial", true) -> "https://www.financialmirror.com"
                                sourceName.contains("alpha", true) -> "https://www.alphanews.live"
                                sourceName.contains("kathimerini", true) -> "https://www.kathimerini.com.cy"
                                else -> ""
                            }
                            articleUrl = baseUrl + articleUrl
                        }

                        // Filter out unwanted content and validate
                        if (title.isNotEmpty() &&
                            articleUrl.startsWith("http") &&
                            title.length > 15 &&
                            !title.contains("newsletter", true) &&
                            !title.contains("subscription", true) &&
                            !title.contains("top stories", true) &&
                            !title.contains("delivered straight", true) &&
                            !title.contains("read more", true) &&
                            !articleUrl.contains("mailto:") &&
                            !articleUrl.contains("javascript:") &&
                            (articleUrl.contains("/2025/") || articleUrl.contains("/2024/"))) {

                            println("  📰 Found article: ${title.take(50)}...")

                            val summary = generateSummary(title)
                            val titleTranslations = translateTitle(title)
                            val summaryTranslations = translateSummary(summary)

                            articles.add(Article(
                                title = title,
                                url = articleUrl,
                                summary = summary,
                                category = categorizeArticle(title, summary),
                                date = SimpleDateFormat("yyyy-MM-dd").format(Date()),
                                titleTranslations = titleTranslations,
                                summaryTranslations = summaryTranslations
                            ))
                        }
                    } catch (e: Exception) {
                        println("    ⚠️ Error processing link: ${e.message}")
                    }
                }

                if (articles.isNotEmpty()) {
                    println("  📰 Successfully extracted ${articles.size} articles from $sourceName")
                    break
                }
            }
        }

        if (articles.isEmpty()) {
            println("  ❌ No articles found for $sourceName")
        }

        return articles.distinctBy { it.url }.take(10) // Limit to 10 per source
    }

    fun aggregateNews(): List<Article> {
        println("📰 Starting news aggregation from ${newsSources.size} sources...")
        val seen = loadSeenArticles()
        val allArticles = mutableListOf<Article>()

        newsSources.forEach { (sourceName, sourceUrl) ->
            try {
                val sourceArticles = scrapeNewsSource(sourceName, sourceUrl)
                allArticles.addAll(sourceArticles)
                Thread.sleep(1000)
            } catch (e: Exception) {
                println("❌ Error scraping $sourceName: ${e.message}")
            }
        }

        val newArticles = allArticles.filter { it.url !in seen }

        seen.addAll(newArticles.map { it.url })
        saveSeenArticles(seen)

        println("📊 Found ${allArticles.size} total articles, ${newArticles.size} new articles")
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
                    body { 
                        font-family: Arial, sans-serif; 
                        line-height: 1.6; 
                        max-width: 800px; 
                        margin: 0 auto; 
                        padding: 20px; 
                        background-color: #f8f9fa;
                    }
                    .container {
                        background: white;
                        padding: 30px;
                        border-radius: 10px;
                        box-shadow: 0 2px 10px rgba(0,0,0,0.1);
                    }
                    h1 { 
                        color: #2c3e50; 
                        border-bottom: 3px solid #3498db; 
                        padding-bottom: 10px; 
                        text-align: center;
                    }
                    h2 { 
                        color: #34495e; 
                        margin-top: 30px; 
                        font-size: 24px;
                    }
                    .lang-buttons { 
                        text-align: center;
                        margin: 20px 0; 
                        padding: 15px;
                        background: #ecf0f1;
                        border-radius: 8px;
                    }
                    .lang-buttons button { 
                        margin: 5px; 
                        padding: 8px 16px; 
                        border: none;
                        border-radius: 5px;
                        background: #3498db;
                        color: white;
                        cursor: pointer;
                        font-weight: bold;
                        transition: background 0.3s;
                    }
                    .lang-buttons button:hover { 
                        background: #2980b9; 
                    }
                    .lang-buttons button.active { 
                        background: #e74c3c; 
                    }
                    .article { 
                        margin-bottom: 15px; 
                        padding: 15px; 
                        border-left: 4px solid #3498db; 
                        background: #f8f9fa;
                        border-radius: 0 5px 5px 0;
                    }
                    .article-title { 
                        font-weight: bold; 
                        margin-bottom: 8px; 
                        color: #2c3e50;
                        font-size: 16px;
                    }
                    .article-summary { 
                        color: #666; 
                        margin-bottom: 8px;
                        font-style: italic;
                    }
                    .article-link { 
                        color: #3498db; 
                        text-decoration: none; 
                        font-size: 14px;
                        font-weight: bold;
                    }
                    .article-link:hover { 
                        text-decoration: underline; 
                    }
                    .lang { 
                        display: none; 
                    }
                    .lang.active { 
                        display: block; 
                    }
                    .footer {
                        text-align: center;
                        margin-top: 40px;
                        padding-top: 20px;
                        border-top: 1px solid #bdc3c7;
                        color: #7f8c8d;
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <h1>Weekly Cyprus Blog – $currentDate</h1>
                    <div class="lang-buttons">
                        <button onclick="setLang('en')" class="active" id="btn-en">🇬🇧 English</button>
                        <button onclick="setLang('he')" id="btn-he">🇮🇱 עברית</button>
                        <button onclick="setLang('ru')" id="btn-ru">🇷🇺 Русский</button>
                        <button onclick="setLang('el')" id="btn-el">🇬🇷 Ελληνικά</button>
                    </div>
        """.trimIndent())

        grouped.forEach { (category, items) ->
            html.append("\n                    <h2>$category</h2>")
            items.forEach { article ->
                // Create Google Translate URLs for each language
                val hebrewUrl = "https://translate.google.com/translate?sl=auto&tl=he&u=${java.net.URLEncoder.encode(article.url, "UTF-8")}"
                val russianUrl = "https://translate.google.com/translate?sl=auto&tl=ru&u=${java.net.URLEncoder.encode(article.url, "UTF-8")}"
                val greekUrl = "https://translate.google.com/translate?sl=auto&tl=el&u=${java.net.URLEncoder.encode(article.url, "UTF-8")}"

                html.append("""
                    <div class="article">
                        <div class="lang en active">
                            <div class="article-title">${article.titleTranslations["en"] ?: article.title}</div>
                            <div class="article-summary">${article.summaryTranslations["en"] ?: article.summary}</div>
                            <a href="${article.url}" class="article-link" target="_blank">Read more</a>
                        </div>
                        <div class="lang he">
                            <div class="article-title">${article.titleTranslations["he"] ?: "כותרת בעברית"}</div>
                            <div class="article-summary">${article.summaryTranslations["he"] ?: "תקציר בעברית"}</div>
                            <a href="$hebrewUrl" class="article-link" target="_blank">קרא עוד</a>
                        </div>
                        <div class="lang ru">
                            <div class="article-title">${article.titleTranslations["ru"] ?: "Заголовок на русском"}</div>
                            <div class="article-summary">${article.summaryTranslations["ru"] ?: "Краткое изложение на русском"}</div>
                            <a href="$russianUrl" class="article-link" target="_blank">Читать далее</a>
                        </div>
                        <div class="lang el">
                            <div class="article-title">${article.titleTranslations["el"] ?: "Τίτλος στα ελληνικά"}</div>
                            <div class="article-summary">${article.summaryTranslations["el"] ?: "Περίληψη στα ελληνικά"}</div>
                            <a href="$greekUrl" class="article-link" target="_blank">Διαβάστε περισσότερα</a>
                        </div>
                    </div>
                """.trimIndent())
            }
        }

        html.append("""
                    <div class="footer">
                        <p>Generated automatically • Updated ${SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date())}</p>
                        <p>Sources: Cyprus Mail, In-Cyprus, Financial Mirror, Alpha News, Kathimerini • Powered by AI Translation</p>
                    </div>
                </div>
                <script>
                    function setLang(lang) {
                        document.querySelectorAll('.lang-buttons button').forEach(btn => btn.classList.remove('active'));
                        document.getElementById('btn-' + lang).classList.add('active');
                        document.querySelectorAll('.lang').forEach(el => el.classList.remove('active'));
                        document.querySelectorAll('.lang.' + lang).forEach(el => el.classList.add('active'));
                        localStorage.setItem('preferred-language', lang);
                    }
                    
                    window.onload = function() {
                        const savedLang = localStorage.getItem('preferred-language') || 'en';
                        setLang(savedLang);
                    };
                </script>
            </body>
            </html>
        """.trimIndent())

        return html.toString()
    }

    fun saveAndPush(htmlContent: String): String {
        val date = SimpleDateFormat("yyyyMMdd").format(Date())
        val filename = "weekly_blog_$date.html"

        try {
            File(filename).writeText(htmlContent)
            println("💾 Blog saved locally as $filename")

            if (!githubToken.isNullOrEmpty()) {
                return pushToGitHub(filename, htmlContent)
            } else {
                println("⚠️ GitHub token not found, skipping GitHub upload")
                return ""
            }
        } catch (e: Exception) {
            println("❌ Error saving blog: ${e.message}")
            return ""
        }
    }

    private fun pushToGitHub(filename: String, htmlContent: String): String {
        return try {
            val apiUrl = "https://api.github.com/gists"
            val requestBody = JSONObject().apply {
                put("description", "Weekly Cyprus Blog ${SimpleDateFormat("yyyy-MM-dd").format(Date())}")
                put("public", true)
                put("files", JSONObject().apply {
                    put(filename, JSONObject().apply {
                        put("content", htmlContent)
                    })
                })
            }

            val request = Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "token $githubToken")
                .addHeader("Accept", "application/vnd.github.v3+json")
                .post(RequestBody.create("application/json".toMediaType(), requestBody.toString()))
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val responseJson = JSONObject(response.body?.string())
                val gistUrl = responseJson.getString("html_url")

                // Get the raw HTML URL for direct viewing
                val gistId = gistUrl.split("/").last()
                val rawUrl = "https://gist.githack.com/LiorR2389/$gistId/raw/$filename"

                println("🚀 Blog uploaded as GitHub Gist: $gistUrl")
                println("📖 Direct blog view: $rawUrl")
                return rawUrl
            } else {
                println("❌ GitHub Gist upload failed: ${response.code}")
                return ""
            }
        } catch (e: Exception) {
            println("❌ GitHub upload error: ${e.message}")
            ""
        }
    }

    fun sendEmail(blogUrl: String, articleCount: Int) {
        if (emailPassword.isNullOrEmpty()) {
            println("⚠️ Email password not found, skipping email")
            return
        }

        try {
            val props = Properties().apply {
                put("mail.smtp.host", "smtp.gmail.com")
                put("mail.smtp.port", "587")
                put("mail.smtp.auth", "true")
                put("mail.smtp.starttls.enable", "true")
            }

            val session = Session.getInstance(props, object : jakarta.mail.Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(fromEmail, emailPassword)
                }
            })

            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(fromEmail))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail))
                subject = "🇨🇾 Weekly Cyprus Blog Ready - $articleCount New Articles"

                val emailBody = if (blogUrl.isNotEmpty()) {
                    """
                    🇨🇾 Your Weekly Cyprus Blog is Ready!
                    
                    📊 $articleCount new articles processed
                    🌍 Available in 4 languages: English, Hebrew, Russian, Greek
                    🤖 Powered by AI translation
                    
                    📖 Read your blog: $blogUrl
                    
                    Features:
                    • Real-time language switching
                    • Categorized news sections
                    • AI-powered translations
                    • Mobile-friendly design
                    
                    Generated automatically on ${SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date())}
                    
                    —
                    Cyprus News Aggregator
                    """.trimIndent()
                } else {
                    """
                    🇨🇾 Weekly Cyprus Blog Generated
                    
                    📊 $articleCount new articles processed
                    💾 Blog saved locally (GitHub upload failed)
                    
                    Please check your local files for the latest blog.
                    
                    Generated on ${SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date())}
                    """.trimIndent()
                }

                setText(emailBody)
            }

            Transport.send(message)
            println("📧 Email sent successfully to $toEmail")
        } catch (e: Exception) {
            println("❌ Email sending failed: ${e.message}")
        }
    }
}

fun main() {
    println("🚀 Starting Weekly Cyprus Blog Generator...")
    println("📅 ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}")

    val aggregator = NewsAggregator()

    val requiredEnvVars = listOf("OPENAI_API_KEY", "GITHUB_TOKEN", "EMAIL_PASSWORD", "FROM_EMAIL", "TO_EMAIL")
    requiredEnvVars.forEach { envVar ->
        val value = System.getenv(envVar)
        if (value.isNullOrEmpty()) {
            println("⚠️ $envVar not set")
        } else {
            println("✅ $envVar configured")
        }
    }

    try {
        val articles = aggregator.aggregateNews()

        if (articles.isEmpty()) {
            println("ℹ️ No new articles found.")
            return
        }

        println("📝 Generating multilingual HTML blog...")
        val html = aggregator.generateHtmlBlog(articles)

        println("💾 Saving and uploading blog...")
        val blogUrl = aggregator.saveAndPush(html)

        println("📧 Sending notification email...")
        aggregator.sendEmail(blogUrl, articles.size)

        println("✅ Weekly Cyprus Blog generation complete!")
        if (blogUrl.isNotEmpty()) {
            println("🌐 Blog URL: $blogUrl")
        }

    } catch (e: Exception) {
        println("❌ Error: ${e.message}")
        e.printStackTrace()
    }
}