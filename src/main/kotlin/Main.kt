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
import java.net.URLEncoder

data class Article(
    val title: String,
    val url: String,
    val summary: String,
    val category: String,
    val date: String,
    val titleTranslations: Map<String, String> = emptyMap(),
    val summaryTranslations: Map<String, String> = emptyMap()
)

data class Subscriber(
    val email: String,
    val name: String?,
    val languages: List<String>,
    val subscribed: Boolean = true,
    val subscribedDate: String
)

class AINewsSystem {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val seenArticlesFile = File("seen_articles.json")
    private val subscribersFile = File("subscribers.json")

    // Environment variables
    private val openAiApiKey = System.getenv("OPENAI_API_KEY")
    private val githubToken = System.getenv("GITHUB_TOKEN")
    private val emailPassword = System.getenv("EMAIL_PASSWORD")
    private val fromEmail = System.getenv("FROM_EMAIL") ?: "hello@ainews.eu.com"

    // Updated news sources (removing broken Cyprus Mail)
    private val newsSources = mapOf(
        "Financial Mirror Cyprus" to "https://www.financialmirror.com/category/cyprus/",
        "Financial Mirror Business" to "https://www.financialmirror.com/category/business/",
        "In-Cyprus Local" to "https://in-cyprus.philenews.com/local/",
        "In-Cyprus Opinion" to "https://in-cyprus.philenews.com/opinion/",
        "Alpha News Cyprus" to "https://www.alphanews.live/cyprus/",
        "StockWatch Cyprus" to "https://www.stockwatch.com.cy/en/news"
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
            seenArticlesFile.writeText(gson.toJson(articles))
        } catch (e: Exception) {
            println("Error saving seen articles: ${e.message}")
        }
    }

    private fun loadSubscribers(): List<Subscriber> {
        return if (subscribersFile.exists()) {
            try {
                val json = subscribersFile.readText()
                val type = object : TypeToken<List<Subscriber>>() {}.type
                gson.fromJson<List<Subscriber>>(json, type) ?: emptyList()
            } catch (e: Exception) {
                println("Error loading subscribers: ${e.message}")
                emptyList()
            }
        } else emptyList()
    }

    private fun saveSubscribers(subscribers: List<Subscriber>) {
        try {
            subscribersFile.writeText(gson.toJson(subscribers))
        } catch (e: Exception) {
            println("Error saving subscribers: ${e.message}")
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
                response.body?.use { body ->
                    Jsoup.parse(body.string())
                }
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
                "Hebrew" -> "כותרת בעברית"
                "Russian" -> "Заголовок на русском"
                "Greek" -> "Τίτλος στα ελληνικά"
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
            sourceName.contains("StockWatch", true) -> listOf(
                ".article-title a",
                "h2 a",
                ".news-title a"
            )
            else -> listOf("h2 a", "h3 a", ".entry-title a")
        }

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

                        if (articleUrl.startsWith("/")) {
                            val baseUrl = when {
                                sourceName.contains("cyprus-mail", true) -> "https://cyprus-mail.com"
                                sourceName.contains("in-cyprus", true) -> "https://in-cyprus.philenews.com"
                                sourceName.contains("financial", true) -> "https://www.financialmirror.com"
                                sourceName.contains("alpha", true) -> "https://www.alphanews.live"
                                sourceName.contains("stockwatch", true) -> "https://www.stockwatch.com.cy"
                                else -> ""
                            }
                            articleUrl = baseUrl + articleUrl
                        }

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

        return articles.distinctBy { it.url }.take(8)
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

    fun uploadToGitHubPages(html: String): String {
        val repoName = "ainews-website"
        val fileName = "index.html"

        return try {
            // Get the current file (if exists) to get its SHA
            val getRequest = Request.Builder()
                .url("https://api.github.com/repos/LiorR2389/$repoName/contents/$fileName")
                .addHeader("Authorization", "token $githubToken")
                .addHeader("Accept", "application/vnd.github.v3+json")
                .build()

            var sha: String? = null
            client.newCall(getRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string())
                    sha = json.getString("sha")
                }
            }

            // Create/Update the file
            val content = Base64.getEncoder().encodeToString(html.toByteArray())
            val requestBody = JSONObject().apply {
                put("message", "Update AI News - ${SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date())}")
                put("content", content)
                if (sha != null) put("sha", sha)
            }

            val putRequest = Request.Builder()
                .url("https://api.github.com/repos/LiorR2389/$repoName/contents/$fileName")
                .addHeader("Authorization", "token $githubToken")
                .addHeader("Content-Type", "application/json")
                .put(RequestBody.create("application/json".toMediaType(), requestBody.toString()))
                .build()

            client.newCall(putRequest).execute().use { response ->
                if (response.isSuccessful) {
                    "https://liorr2389.github.io/$repoName/"
                } else {
                    println("❌ Failed to upload to GitHub Pages: ${response.code}")
                    ""
                }
            }
        } catch (e: Exception) {
            println("❌ Error uploading to GitHub Pages: ${e.message}")
            ""
        }
    }

    fun setupCustomDomain() {
        val repoName = "ainews-website"
        val fileName = "CNAME"
        val cnameContent = "ainews.eu.com"

        try {
            // Get the current CNAME file (if exists) to get its SHA
            val getRequest = Request.Builder()
                .url("https://api.github.com/repos/LiorR2389/$repoName/contents/$fileName")
                .addHeader("Authorization", "token $githubToken")
                .addHeader("Accept", "application/vnd.github.v3+json")
                .build()

            var sha: String? = null
            client.newCall(getRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string())
                    sha = json.getString("sha")
                }
            }

            // Create/Update the CNAME file
            val content = Base64.getEncoder().encodeToString(cnameContent.toByteArray())
            val requestBody = JSONObject().apply {
                put("message", "Setup custom domain: ainews.eu.com")
                put("content", content)
                if (sha != null) put("sha", sha)
            }

            val putRequest = Request.Builder()
                .url("https://api.github.com/repos/LiorR2389/$repoName/contents/$fileName")
                .addHeader("Authorization", "token $githubToken")
                .addHeader("Content-Type", "application/json")
                .put(RequestBody.create("application/json".toMediaType(), requestBody.toString()))
                .build()

            client.newCall(putRequest).execute().use { response ->
                if (response.isSuccessful) {
                    println("✅ CNAME file created for ainews.eu.com")
                } else {
                    println("❌ Failed to create CNAME file: ${response.code}")
                }
            }
        } catch (e: Exception) {
            println("❌ Error creating CNAME file: ${e.message}")
        }
    }

    fun generateDailyWebsite(articles: List<Article>): String {
        val currentDate = SimpleDateFormat("yyyy-MM-dd").format(Date())
        val dayOfWeek = SimpleDateFormat("EEEE", Locale.ENGLISH).format(Date())
        val grouped = articles.groupBy { it.category }

        val html = StringBuilder("""
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>AI News - Cyprus Daily Digest for $dayOfWeek, $currentDate</title>
                <meta name="description" content="Your daily Cyprus AI-powered news digest in 4 languages. Updated every morning at 7 AM.">
                <style>
                    body { 
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                        line-height: 1.6; 
                        margin: 0;
                        padding: 20px; 
                        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                        min-height: 100vh;
                    }
                    .container {
                        max-width: 900px;
                        margin: 0 auto;
                        background: white;
                        padding: 40px;
                        border-radius: 20px;
                        box-shadow: 0 20px 40px rgba(0,0,0,0.1);
                    }
                    .header {
                        text-align: center;
                        margin-bottom: 40px;
                        padding-bottom: 30px;
                        border-bottom: 3px solid #667eea;
                    }
                    .logo { 
                        font-size: 3rem;
                        font-weight: 700;
                        margin-bottom: 10px;
                        background: linear-gradient(45deg, #667eea, #764ba2);
                        -webkit-background-clip: text;
                        -webkit-text-fill-color: transparent;
                        background-clip: text;
                    }
                    .tagline {
                        color: #666;
                        font-size: 1.2rem;
                        margin-bottom: 20px;
                        font-style: italic;
                    }
                    .date-info {
                        color: #666;
                        font-size: 1.1rem;
                        margin-bottom: 20px;
                    }
                    .update-time {
                        background: #667eea;
                        color: white;
                        padding: 8px 16px;
                        border-radius: 20px;
                        font-size: 0.9rem;
                        font-weight: 600;
                    }
                    .lang-buttons { 
                        text-align: center;
                        margin: 30px 0; 
                        padding: 20px;
                        background: #f8f9fa;
                        border-radius: 15px;
                    }
                    .lang-buttons button { 
                        margin: 5px; 
                        padding: 10px 20px; 
                        border: none;
                        border-radius: 25px;
                        background: #667eea;
                        color: white;
                        cursor: pointer;
                        font-weight: 600;
                        transition: all 0.3s;
                        font-size: 0.9rem;
                    }
                    .lang-buttons button:hover { 
                        background: #5a6fd8;
                        transform: translateY(-2px);
                    }
                    .lang-buttons button.active { 
                        background: #764ba2;
                        color: white;
                    }
                    h2 { 
                        color: #333; 
                        margin: 40px 0 20px 0; 
                        font-size: 1.8rem;
                        padding-left: 15px;
                        border-left: 4px solid #667eea;
                    }
                    .article { 
                        margin-bottom: 20px; 
                        padding: 20px; 
                        border-left: 4px solid #667eea; 
                        background: #f8f9fa;
                        border-radius: 0 10px 10px 0;
                        transition: transform 0.2s;
                    }
                    .article:hover {
                        transform: translateX(5px);
                    }
                    .article-title { 
                        font-weight: 600; 
                        margin-bottom: 10px; 
                        color: #333;
                        font-size: 1.1rem;
                        line-height: 1.4;
                    }
                    .article-summary { 
                        color: #666; 
                        margin-bottom: 12px;
                        font-style: italic;
                    }
                    .article-link { 
                        color: #667eea; 
                        text-decoration: none; 
                        font-weight: 600;
                        padding: 8px 16px;
                        background: rgba(102, 126, 234, 0.1);
                        border-radius: 20px;
                        display: inline-block;
                        transition: all 0.3s;
                    }
                    .article-link:hover { 
                        background: #667eea;
                        color: white;
                        transform: translateY(-1px);
                    }
                    .lang { 
                        display: none; 
                    }
                    .lang.active { 
                        display: block; 
                    }
                    .newsletter-signup {
                        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                        color: white;
                        padding: 40px;
                        margin: 50px 0;
                        border-radius: 20px;
                        text-align: center;
                    }
                    .newsletter-signup h3 {
                        font-size: 2rem;
                        margin-bottom: 15px;
                    }
                    .newsletter-signup p {
                        margin-bottom: 25px;
                        opacity: 0.9;
                        font-size: 1.1rem;
                    }
                    .signup-form {
                        max-width: 400px;
                        margin: 0 auto;
                    }
                    .signup-form input {
                        width: 100%;
                        padding: 15px;
                        margin: 10px 0;
                        border: none;
                        border-radius: 10px;
                        font-size: 1rem;
                        box-sizing: border-box;
                    }
                    .signup-form button {
                        background: #FFD700;
                        color: #333;
                        border: none;
                        padding: 15px 30px;
                        border-radius: 25px;
                        font-weight: 700;
                        font-size: 1.1rem;
                        cursor: pointer;
                        transition: transform 0.3s;
                        margin-top: 15px;
                        width: 100%;
                    }
                    .signup-form button:hover {
                        transform: translateY(-2px);
                    }
                    .footer {
                        text-align: center;
                        margin-top: 50px;
                        padding-top: 30px;
                        border-top: 1px solid #ddd;
                        color: #666;
                    }
                    .stats {
                        display: flex;
                        justify-content: center;
                        gap: 30px;
                        margin: 30px 0;
                        flex-wrap: wrap;
                    }
                    .stat {
                        text-align: center;
                    }
                    .stat-number {
                        font-size: 1.5rem;
                        font-weight: 700;
                        color: #667eea;
                    }
                    .stat-label {
                        font-size: 0.9rem;
                        color: #666;
                    }
                    .language-checkboxes {
                        display: grid;
                        grid-template-columns: repeat(2, 1fr);
                        gap: 10px;
                        margin: 20px 0;
                        text-align: left;
                    }
                    .language-option {
                        display: flex;
                        align-items: center;
                        background: rgba(255,255,255,0.1);
                        padding: 10px;
                        border-radius: 8px;
                    }
                    .language-option input {
                        width: auto;
                        margin-right: 8px;
                    }
                    .success-message {
                        background: #28a745;
                        color: white;
                        padding: 15px;
                        border-radius: 10px;
                        margin-top: 20px;
                        display: none;
                    }
                    .error-message {
                        background: #dc3545;
                        color: white;
                        padding: 15px;
                        border-radius: 10px;
                        margin-top: 20px;
                        display: none;
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <div class="logo">🤖 AI News</div>
                        <div class="tagline">Cyprus Daily Digest • Powered by AI</div>
                        <div class="date-info">$dayOfWeek, $currentDate</div>
                        <div class="update-time">Updated at 7:00 AM Cyprus Time</div>
                    </div>
                    
                    <div class="stats">
                        <div class="stat">
                            <div class="stat-number">${articles.size}</div>
                            <div class="stat-label">Stories Today</div>
                        </div>
                        <div class="stat">
                            <div class="stat-number">4</div>
                            <div class="stat-label">Languages</div>
                        </div>
                        <div class="stat">
                            <div class="stat-number">6</div>
                            <div class="stat-label">Sources</div>
                        </div>