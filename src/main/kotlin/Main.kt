import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.Base64
import java.net.URLEncoder
import java.util.Properties
import jakarta.mail.*
import jakarta.mail.internet.*
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpExchange
import java.net.InetSocketAddress
import kotlin.concurrent.thread

data class Article(
    val title: String,
    val url: String,
    val summary: String,
    val category: String,
    val date: String,
    val titleTranslations: Map<String, String> = emptyMap(),
    val summaryTranslations: Map<String, String> = emptyMap(),
    val categoryTranslations: Map<String, String> = emptyMap()
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

    private val openAiApiKey = System.getenv("OPENAI_API_KEY")
    private val githubToken = System.getenv("GITHUB_TOKEN")
    private val fromEmail = System.getenv("FROM_EMAIL") ?: "hello@ainews.eu.com"
    private val emailPassword = System.getenv("EMAIL_PASSWORD")
    private val smtpHost = System.getenv("SMTP_HOST") ?: "smtp.gmail.com"
    private val smtpPort = System.getenv("SMTP_PORT") ?: "587"

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

    fun addSubscriber(email: String, name: String?, languages: List<String>) {
        val subscribers = loadSubscribers().toMutableList()
        val existingSubscriber = subscribers.find { it.email == email }

        if (existingSubscriber == null) {
            val newSubscriber = Subscriber(
                email = email,
                name = name,
                languages = languages,
                subscribed = true,
                subscribedDate = SimpleDateFormat("yyyy-MM-dd").format(Date())
            )
            subscribers.add(newSubscriber)
            saveSubscribers(subscribers)
            println("✅ Added new subscriber: $email")
        } else {
            println("⚠️ Subscriber already exists: $email")
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
        val words = title.split(" ")

        return when {
            content.contains("ai") || content.contains("artificial intelligence") ||
                    content.contains("tech") || content.contains("digital") ||
                    content.contains("startup") || content.contains("software") -> {
                val keyWords = words.filter { it.length > 3 }.take(4).joinToString(" ")
                "Technology development: $keyWords"
            }
            content.contains("business") || content.contains("economy") ||
                    content.contains("financial") || content.contains("bank") ||
                    content.contains("market") || content.contains("investment") -> {
                val keyWords = words.filter { it.length > 3 }.take(4).joinToString(" ")
                "Economic update: $keyWords"
            }
            content.contains("crime") || content.contains("arrest") ||
                    content.contains("police") || content.contains("court") ||
                    content.contains("fraud") -> {
                val keyWords = words.filter { it.length > 3 }.take(4).joinToString(" ")
                "Legal development: $keyWords"
            }
            else -> {
                val keyWords = words.filter { it.length > 3 }.take(5).joinToString(" ")
                keyWords.ifEmpty { title.take(60) + if (title.length > 60) "..." else "" }
            }
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
                .post(requestBody.toRequestBody("application/json".toMediaType()))
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

    private fun translateCategory(category: String): Map<String, String> {
        val translations = mutableMapOf<String, String>()
        translations["en"] = category

        val categoryTranslations = mapOf(
            "Technology" to mapOf(
                "he" to "טכנולוגיה",
                "ru" to "Технологии",
                "el" to "Τεχνολογία"
            ),
            "Business & Economy" to mapOf(
                "he" to "עסקים וכלכלה",
                "ru" to "Бизнес и экономика",
                "el" to "Επιχειρήσεις και Οικονομία"
            ),
            "Real Estate" to mapOf(
                "he" to "נדלן",
                "ru" to "Недвижимость",
                "el" to "Ακίνητα"
            ),
            "Holidays & Travel" to mapOf(
                "he" to "חופשות ונסיעות",
                "ru" to "Отдых и путешествия",
                "el" to "Διακοπές και Ταξίδια"
            ),
            "Politics" to mapOf(
                "he" to "פוליטיקה",
                "ru" to "Политика",
                "el" to "Πολιτική"
            ),
            "Crime & Justice" to mapOf(
                "he" to "פשע וצדק",
                "ru" to "Преступность и правосудие",
                "el" to "Έγκλημα και Δικαιοσύνη"
            ),
            "General News" to mapOf(
                "he" to "חדשות כלליות",
                "ru" to "Общие новости",
                "el" to "Γενικά Νέα"
            )
        )

        categoryTranslations[category]?.let { predefinedTranslations ->
            translations.putAll(predefinedTranslations)
        }

        return translations
    }

    private fun categorizeArticle(title: String, summary: String): String {
        val content = "$title $summary".lowercase()
        return when {
            content.contains("ai ") || content.contains("artificial intelligence") ||
                    content.contains("tech startup") || content.contains("software") ||
                    content.contains("digital innovation") || content.contains("blockchain") ||
                    content.contains("cryptocurrency") || content.contains("tech company") ||
                    content.contains("app ") || content.contains("platform") ||
                    content.contains("cyber") || content.contains("data") -> "Technology"

            content.contains("business") || content.contains("economy") || content.contains("economic") ||
                    content.contains("financial") || content.contains("bank") || content.contains("banking") ||
                    content.contains("market") || content.contains("euro") || content.contains("million") ||
                    content.contains("billion") || content.contains("gdp") || content.contains("investment") ||
                    content.contains("cclei") || content.contains("imd") || content.contains("stock") ||
                    content.contains("company") || content.contains("corp") || content.contains("revenue") ||
                    content.contains("profit") || content.contains("sales") || content.contains("finance") ||
                    content.contains("holding") || content.contains("acquisition") || content.contains("merger") ||
                    content.contains("enterprise") || content.contains("startup") || content.contains("fund") ||
                    content.contains("capital") || content.contains("shares") || content.contains("earnings") ||
                    content.contains("trade") || content.contains("import") || content.contains("export") -> "Business & Economy"

            content.contains("real estate") || content.contains("property") || content.contains("properties") ||
                    content.contains("housing") || content.contains("construction") || content.contains("building") ||
                    content.contains("development") || content.contains("apartment") || content.contains("home") -> "Real Estate"

            content.contains("politics") || content.contains("political") || content.contains("government") ||
                    content.contains("parliament") || content.contains("minister") || content.contains("mp ") ||
                    content.contains("mep ") || content.contains("president") || content.contains("election") ||
                    content.contains("talks") || content.contains("negotiation") || content.contains("policy") ||
                    content.contains("law") || content.contains("bill") || content.contains("vote") ||
                    content.contains("democracy") || content.contains("eu ") || content.contains("european") -> "Politics"

            content.contains("crime") || content.contains("criminal") || content.contains("arrest") ||
                    content.contains("police") || content.contains("court") || content.contains("judge") ||
                    content.contains("fraud") || content.contains("theft") || content.contains("scam") ||
                    content.contains("illegal") || content.contains("trial") || content.contains("sentence") ||
                    content.contains("prison") || content.contains("jail") || content.contains("investigation") ||
                    content.contains("victim") || content.contains("suspect") -> "Crime & Justice"

            content.contains("travel") || content.contains("tourism") || content.contains("tourist") ||
                    content.contains("holiday") || content.contains("vacation") || content.contains("hotel") ||
                    content.contains("resort") || content.contains("festival") || content.contains("culture") ||
                    content.contains("cultural") || content.contains("heritage") || content.contains("museum") ||
                    content.contains("art") || content.contains("music") || content.contains("entertainment") -> "Holidays & Travel"

            else -> "General News"
        }
    }

    private fun scrapeNewsSource(sourceName: String, url: String): List<Article> {
        println("🔍 Scraping $sourceName...")
        val doc = fetchPage(url) ?: return emptyList()

        val articles = mutableListOf<Article>()

        val selectors = when {
            sourceName.contains("In-Cyprus", true) -> listOf(
                ".entry-title a",
                ".post-title a",
                "h2.entry-title a",
                "article h2 a"
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
                            val category = categorizeArticle(title, summary)
                            val categoryTranslations = translateCategory(category)

                            articles.add(Article(
                                title = title,
                                url = articleUrl,
                                summary = summary,
                                category = category,
                                date = SimpleDateFormat("yyyy-MM-dd").format(Date()),
                                titleTranslations = titleTranslations,
                                summaryTranslations = summaryTranslations,
                                categoryTranslations = categoryTranslations
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

    fun startSubscriptionServer(port: Int = 8080) {
        val server = HttpServer.create(InetSocketAddress(port), 0)

        server.createContext("/subscribe") { exchange ->
            exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
            exchange.responseHeaders.add("Access-Control-Allow-Methods", "POST, OPTIONS")
            exchange.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type")

            when (exchange.requestMethod) {
                "OPTIONS" -> {
                    exchange.sendResponseHeaders(200, -1)
                }
                "POST" -> {
                    try {
                        val requestBody = exchange.requestBody.bufferedReader().use { it.readText() }
                        val json = JSONObject(requestBody)

                        val email = json.getString("email")
                        val name = json.optString("name", null)
                        val languagesArray = json.getJSONArray("languages")
                        val languages = mutableListOf<String>()

                        for (i in 0 until languagesArray.length()) {
                            languages.add(languagesArray.getString(i))
                        }

                        addSubscriber(email, name, languages)

                        val response = """{"success": true, "message": "Subscription successful!"}"""
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
                        exchange.responseBody.write(response.toByteArray())
                        exchange.responseBody.close()

                        println("✅ New subscriber added via API: $email")

                    } catch (e: Exception) {
                        val response = """{"success": false, "message": "Subscription failed: ${e.message}"}"""
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(400, response.toByteArray().size.toLong())
                        exchange.responseBody.write(response.toByteArray())
                        exchange.responseBody.close()

                        println("❌ Subscription error: ${e.message}")
                    }
                }
                else -> {
                    exchange.sendResponseHeaders(405, -1)
                }
            }
        }

        server.executor = null
        server.start()
        println("🚀 Subscription API server started on port $port")
        println("📡 API endpoint: http://localhost:$port/subscribe")
    }

    fun uploadToGitHubPages(html: String): String {
        val repoName = "ainews-website"
        val fileName = "index.html"

        return try {
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
                .put(requestBody.toString().toRequestBody("application/json".toMediaType()))
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
                .put(requestBody.toString().toRequestBody("application/json".toMediaType()))
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

    fun sendDailyNotification(articles: List<Article>, websiteUrl: String) {
        val subscribers = loadSubscribers().filter { it.subscribed }

        if (subscribers.isEmpty()) {
            println("📧 No subscribers to notify")
            return
        }

        if (emailPassword.isNullOrEmpty()) {
            println("📧 Email password not configured - notifications disabled")
            println("📧 Would notify ${subscribers.size} subscribers about ${articles.size} articles")
            return
        }

        println("📧 Sending notifications to ${subscribers.size} subscribers...")

        subscribers.forEach { subscriber ->
            try {
                sendEmailNotification(subscriber, articles, websiteUrl)
                println("✅ Email sent to ${subscriber.email}")
                Thread.sleep(1000)
            } catch (e: Exception) {
                println("❌ Failed to send email to ${subscriber.email}: ${e.message}")
            }
        }

        println("📧 Daily notifications complete!")
    }

    private fun sendEmailNotification(subscriber: Subscriber, articles: List<Article>, websiteUrl: String) {
        val props = Properties().apply {
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.host", smtpHost)
            put("mail.smtp.port", smtpPort)
            put("mail.smtp.ssl.protocols", "TLSv1.2")
        }

        val session = Session.getInstance(props, object : jakarta.mail.Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(fromEmail, emailPassword)
            }
        })

        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(fromEmail, "AI News Cyprus"))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(subscriber.email))

            val subjectTranslations = mapOf(
                "en" to "🤖 Your Daily Cyprus News Update - ${articles.size} new stories",
                "he" to "🤖 עדכון החדשות היומי שלך מקפריסין - ${articles.size} סיפורים חדשים",
                "ru" to "🤖 Ваши ежедневные новости Кипра - ${articles.size} новых историй",
                "el" to "🤖 Η καθημερινή ενημέρωσή σας για την Κύπρο - ${articles.size} νέες ιστορίες"
            )

            subject = subjectTranslations[subscriber.languages.firstOrNull()]
                ?: subjectTranslations["en"]!!

            val htmlContent = generateEmailHtml(subscriber, articles, websiteUrl)
            setContent(htmlContent, "text/html; charset=utf-8")
        }

        Transport.send(message)
    }

    private fun generateEmailHtml(subscriber: Subscriber, articles: List<Article>, websiteUrl: String): String {
        val currentDate = SimpleDateFormat("yyyy-MM-dd").format(Date())

        val articlesHtml = StringBuilder()
        articles.take(5).forEach { article ->
            val primaryLang = subscriber.languages.firstOrNull() ?: "en"
            val title = article.titleTranslations[primaryLang] ?: article.title
            val summary = article.summaryTranslations[primaryLang] ?: article.summary

            articlesHtml.append("""
                <div style="border-left: 4px solid #667eea; padding: 15px; margin: 15px 0; background: #f8f9fa;">
                    <h3 style="margin: 0 0 10px 0; color: #333; font-size: 1.1rem;">${escapeHtml(title)}</h3>
                    <p style="margin: 0 0 10px 0; color: #666; font-style: italic;">${escapeHtml(summary)}</p>
                    <a href="${article.url}" style="color: #667eea; text-decoration: none; font-weight: 600;">Read more →</a>
                </div>
            """.trimIndent())
        }

        return """<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>AI News Cyprus - Daily Update</title>
</head>
<body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; line-height: 1.6; margin: 0; padding: 20px; background: #f5f5f5;">
    <div style="max-width: 600px; margin: 0 auto; background: white; border-radius: 10px;">
        <div style="background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; padding: 30px; text-align: center; border-radius: 10px 10px 0 0;">
            <h1 style="margin: 0; font-size: 2rem;">🤖 AI News</h1>
            <p style="margin: 5px 0 0 0; opacity: 0.9;">Cyprus Daily Digest</p>
        </div>
        <div style="padding: 30px;">
            <h2 style="margin: 0 0 20px 0; color: #333;">Hello ${subscriber.name ?: "there"}!</h2>
            <p style="margin: 0 0 25px 0; color: #666;">Here are your fresh Cyprus news updates for $currentDate:</p>
            
            $articlesHtml
            
            <div style="text-align: center; margin: 30px 0;">
                <a href="$websiteUrl" style="display: inline-block; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; text-decoration: none; padding: 15px 30px; border-radius: 25px; font-weight: 600;">📖 View Full Website</a>
            </div>
        </div>
        <div style="border-top: 1px solid #ddd; padding-top: 20px; margin-top: 30px; text-align: center; color: #666; font-size: 0.9rem;">
            <p>This is your daily notification from <a href="$websiteUrl" style="color: #667eea;">ainews.eu.com</a></p>
        </div>
    </div>
</body>
</html>""".trimIndent()
    }

    fun generateDailyWebsite(articles: List<Article>): String {
        val currentDate = SimpleDateFormat("yyyy-MM-dd").format(Date())
        val dayOfWeek = SimpleDateFormat("EEEE", Locale.ENGLISH).format(Date())
        val grouped = articles.groupBy { it.category }
        val articlesHtml = generateArticlesHtml(grouped)

        return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>AI News - Cyprus Daily Digest for ${dayOfWeek}, ${currentDate}</title>
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
h2 .lang {
    display: none;
}
h2 .lang.active {
    display: inline;
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
<div class="date-info">${dayOfWeek}, ${currentDate}</div>
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
</div>

<div class="lang-buttons">
<button onclick="setLang('en')" class="active" id="btn-en">🇬🇧 English</button>
<button onclick="setLang('he')" id="btn-he">🇮🇱 עברית</button>
<button onclick="setLang('ru')" id="btn-ru">🇷🇺 Русский</button>
<button onclick="setLang('el')" id="btn-el">🇬🇷 Ελληνικά</button>
</div>

${articlesHtml}

<div class="newsletter-signup">
<div class="lang en active">
<h3>🔔 Get Daily Notifications</h3>
<p>Get a simple email notification every morning when fresh news is published on ainews.eu.com</p>
<div class="signup-form">
<input type="email" id="email" placeholder="your@email.com" required>
<input type="text" id="name" placeholder="Your name (optional)">
<div class="language-checkboxes">
<div class="language-option">
<input type="checkbox" id="lang-en" value="en" checked> 🇬🇧 English
</div>
<div class="language-option">
<input type="checkbox" id="lang-he" value="he"> 🇮🇱 עברית
</div>
<div class="language-option">
<input type="checkbox" id="lang-ru" value="ru"> 🇷🇺 Русский
</div>
<div class="language-option">
<input type="checkbox" id="lang-el" value="el"> 🇬🇷 Ελληνικά
</div>
</div>
<button onclick="subscribe()">🔔 Notify Me Daily</button>
<div id="success-message" class="success-message"></div>
<div id="error-message" class="error-message"></div>
</div>
<p style="font-size: 0.9rem; opacity: 0.8; margin-top: 20px;">
✅ Just notifications, not newsletters<br>
✅ Unsubscribe anytime
</p>
</div>

<div class="lang he">
<h3>🔔 קבלו התראות יומיות</h3>
<p>קבלו התראה פשוטה כל בוקר כאשר חדשות טריות מתפרסמות ב-ainews.eu.com</p>
<div class="signup-form">
<input type="email" id="email-he" placeholder="הדוא״ל שלכם" required>
<input type="text" id="name-he" placeholder="השם שלכם (אופציונלי)">
<div class="language-checkboxes">
<div class="language-option">
<input type="checkbox" id="lang-en-he" value="en"> 🇬🇧 English
</div>
<div class="language-option">
<input type="checkbox" id="lang-he-he" value="he" checked> 🇮🇱 עברית
</div>
<div class="language-option">
<input type="checkbox" id="lang-ru-he" value="ru"> 🇷🇺 Русский
</div>
<div class="language-option">
<input type="checkbox" id="lang-el-he" value="el"> 🇬🇷 Ελληνικά
</div>
</div>
<button onclick="subscribe()">🔔 הודיעו לי יומית</button>
<div id="success-message-he" class="success-message"></div>
<div id="error-message-he" class="error-message"></div>
</div>
<p style="font-size: 0.9rem; opacity: 0.8; margin-top: 20px;">
✅ רק התראות, לא ניוזלטרים<br>
✅ ביטול מנוי בכל עת
</p>
</div>

<div class="lang ru">
<h3>🔔 Получайте ежедневные уведомления</h3>
<p>Получайте простое уведомление каждое утро, когда свежие новости публикуются на ainews.eu.com</p>
<div class="signup-form">
<input type="email" id="email-ru" placeholder="ваш@email.com" required>
<input type="text" id="name-ru" placeholder="Ваше имя (необязательно)">
<div class="language-checkboxes">
<div class="language-option">
<input type="checkbox" id="lang-en-ru" value="en"> 🇬🇧 English
</div>
<div class="language-option">
<input type="checkbox" id="lang-he-ru" value="he"> 🇮🇱 עברית
</div>
<div class="language-option">
<input type="checkbox" id="lang-ru-ru" value="ru" checked> 🇷🇺 Русский
</div>
<div class="language-option">
<input type="checkbox" id="lang-el-ru" value="el"> 🇬🇷 Ελληνικά
</div>
</div>
<button onclick="subscribe()">🔔 Уведомлять меня ежедневно</button>
<div id="success-message-ru" class="success-message"></div>
<div id="error-message-ru" class="error-message"></div>
</div>
<p style="font-size: 0.9rem; opacity: 0.8; margin-top: 20px;">
✅ Только уведомления, не рассылки<br>
✅ Отписаться в любое время
</p>
</div>

<div class="lang el">
<h3>🔔 Λάβετε καθημερινές ειδοποιήσεις</h3>
<p>Λάβετε μια απλή ειδοποίηση κάθε πρωί όταν νέα νέα δημοσιεύονται στο ainews.eu.com</p>
<div class="signup-form">
<input type="email" id="email-el" placeholder="το@email.σας" required>
<input type="text" id="name-el" placeholder="Το όνομά σας (προαιρετικό)">
<div class="language-checkboxes">
<div class="language-option">
<input type="checkbox" id="lang-en-el" value="en"> 🇬🇧 English
</div>
<div class="language-option">
<input type="checkbox" id="lang-he-el" value="he"> 🇮🇱 עברית
</div>
<div class="language-option">
<input type="checkbox" id="lang-ru-el" value="ru"> 🇷🇺 Русский
</div>
<div class="language-option">
<input type="checkbox" id="lang-el-el" value="el" checked> 🇬🇷 Ελληνικά
</div>
</div>
<button onclick="subscribe()">🔔 Ειδοποιήστε με καθημερινά</button>
<div id="success-message-el" class="success-message"></div>
<div id="error-message-el" class="error-message"></div>
</div>
<p style="font-size: 0.9rem; opacity: 0.8; margin-top: 20px;">
✅ Μόνο ειδοποιήσεις, όχι newsletters<br>
✅ Κατάργηση εγγραφής ανά πάσα στιγμή
</p>
</div>
</div>

<div class="footer">
<p>Generated automatically • Updated ${SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date())}</p>
<p>Sources: Financial Mirror, In-Cyprus, Alpha News, StockWatch • Powered by AI Translation</p>
<p><a href="https://ainews.eu.com" style="color: #667eea;">ainews.eu.com</a> - Your daily Cyprus news digest</p>
</div>
</div>

<script>
let currentLang = 'en';

function setLang(lang) {
    document.querySelectorAll('.lang').forEach(el => {
        el.classList.remove('active');
    });

    document.querySelectorAll('.lang.' + lang).forEach(el => {
        el.classList.add('active');
    });

    document.querySelectorAll('.lang-buttons button').forEach(btn => {
        btn.classList.remove('active');
    });
    document.getElementById('btn-' + lang).classList.add('active');

    currentLang = lang;
}

function subscribe() {
    const activeButton = document.querySelector('.lang-buttons button.active');
    const currentLanguage = activeButton ? activeButton.id.replace('btn-', '') : 'en';

    const emailSelector = currentLanguage === 'en' ? '#email' : '#email-' + currentLanguage;
    const nameSelector = currentLanguage === 'en' ? '#name' : '#name-' + currentLanguage;
    const successSelector = currentLanguage === 'en' ? '#success-message' : '#success-message-' + currentLanguage;
    const errorSelector = currentLanguage === 'en' ? '#error-message' : '#error-message-' + currentLanguage;

    const emailElement = document.querySelector(emailSelector);
    const nameElement = document.querySelector(nameSelector);
    const successElement = document.querySelector(successSelector);
    const errorElement = document.querySelector(errorSelector);

    const email = emailElement ? emailElement.value.trim() : document.querySelector('#email').value.trim();
    const name = nameElement ? nameElement.value.trim() : document.querySelector('#name').value.trim();
    const successDiv = successElement || document.querySelector('#success-message');
    const errorDiv = errorElement || document.querySelector('#error-message');

    document.querySelectorAll('.success-message, .error-message').forEach(div => {
        div.style.display = 'none';
    });

    const errorMessages = {
        'en': {
            'email_required': 'Please enter your email address.',
            'email_invalid': 'Please enter a valid email address.',
            'language_required': 'Please select at least one language.'
        },
        'he': {
            'email_required': 'אנא הזינו את כתובת הדוא״ל שלכם.',
            'email_invalid': 'אנא הזינו כתובת דוא״ל תקינה.',
            'language_required': 'אנא בחרו לפחות שפה אחת.'
        },
        'ru': {
            'email_required': 'Пожалуйста, введите ваш email адрес.',
            'email_invalid': 'Пожалуйста, введите корректный email адрес.',
            'language_required': 'Пожалуйста, выберите хотя бы один язык.'
        },
        'el': {
            'email_required': 'Παρακαλώ εισάγετε τη διεύθυνση email σας.',
            'email_invalid': 'Παρακαλώ εισάγετε μια έγκυρη διεύθυνση email.',
            'language_required': 'Παρακαλώ επιλέξτε τουλάχιστον μία γλώσσα.'
        }
    };

    const successMessages = {
        'en': '🎉 Success! You will receive daily notifications at ' + email + ' when ainews.eu.com updates!',
        'he': '🎉 הצלחה! תקבלו התראות יומיות ב-' + email + ' כאשר ainews.eu.com מתעדכן!',
        'ru': '🎉 Успех! Вы будете получать ежедневные уведомления на ' + email + ' когда ainews.eu.com обновляется!',
        'el': '🎉 Επιτυχία! Θα λαμβάνετε καθημερινές ειδοποιήσεις στο ' + email + ' όταν ενημερώνεται το ainews.eu.com!'
    };

    if (!email) {
        errorDiv.textContent = errorMessages[currentLanguage]['email_required'];
        errorDiv.style.display = 'block';
        return;
    }

    const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
    if (!emailRegex.test(email)) {
        errorDiv.textContent = errorMessages[currentLanguage]['email_invalid'];
        errorDiv.style.display = 'block';
        return;
    }

    const selectedLanguages = [];
    const activeForm = document.querySelector('.lang.' + currentLanguage + ' .language-checkboxes');
    if (activeForm) {
        activeForm.querySelectorAll('input:checked').forEach(checkbox => {
            selectedLanguages.push(checkbox.value);
        });
    } else {
        document.querySelectorAll('.language-option input:checked').forEach(checkbox => {
            selectedLanguages.push(checkbox.value);
        });
    }

    if (selectedLanguages.length === 0) {
        errorDiv.textContent = errorMessages[currentLanguage]['language_required'];
        errorDiv.style.display = 'block';
        return;
    }

    const subscriberData = {
        email: email,
        name: name || null,
        languages: selectedLanguages,
        subscribed: true,
        subscribedDate: new Date().toISOString().split('T')[0]
    };

    // Send subscription data to API
    fetch('/subscribe', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify(subscriberData)
    })
    .then(response => response.json())
    .then(data => {
        if (data.success) {
            successDiv.textContent = successMessages[currentLanguage];
            successDiv.style.display = 'block';

            // Clear form
            if (emailElement) emailElement.value = '';
            if (nameElement) nameElement.value = '';

            if (activeForm) {
                activeForm.querySelectorAll('input').forEach(checkbox => {
                    checkbox.checked = checkbox.value === currentLanguage;
                });
            }
        } else {
            errorDiv.textContent = data.message || errorMessages[currentLanguage]['email_required'];
            errorDiv.style.display = 'block';
        }
    })
    .catch(error => {
        console.error('Subscription error:', error);
        errorDiv.textContent = 'Network error. Please try again.';
        errorDiv.style.display = 'block';
    });
}

document.addEventListener('DOMContentLoaded', function() {
    setLang('en');
});
</script>
</body>
</html>""".trimIndent()
    }

    private fun generateArticlesHtml(grouped: Map<String, List<Article>>): String {
        val html = StringBuilder()

        grouped.forEach { (category, items) ->
            val categoryTranslations = items.firstOrNull()?.categoryTranslations ?: emptyMap()

            html.append("""
<h2>
<span class="lang en active">${escapeHtml(categoryTranslations["en"] ?: category)}</span>
<span class="lang he">${escapeHtml(categoryTranslations["he"] ?: category)}</span>
<span class="lang ru">${escapeHtml(categoryTranslations["ru"] ?: category)}</span>
<span class="lang el">${escapeHtml(categoryTranslations["el"] ?: category)}</span>
</h2>""")

            items.forEach { article ->
                val hebrewUrl = "https://translate.google.com/translate?sl=auto&tl=he&u=" + URLEncoder.encode(article.url, "UTF-8")
                val russianUrl = "https://translate.google.com/translate?sl=auto&tl=ru&u=" + URLEncoder.encode(article.url, "UTF-8")
                val greekUrl = "https://translate.google.com/translate?sl=auto&tl=el&u=" + URLEncoder.encode(article.url, "UTF-8")

                html.append("""
<div class="article">
<div class="lang en active">
<div class="article-title">${escapeHtml(article.titleTranslations["el"] ?: "Τίτλος στα ελληνικά")}</div>
<div class="article-summary">${escapeHtml(article.summaryTranslations["el"] ?: "Περίληψη στα ελληνικά")}</div>
<a href="$greekUrl" class="article-link" target="_blank">Διαβάστε περισσότερα</a>
</div>
</div>
""".trimIndent())
            }
        }

        return html.toString()
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}

fun main() {
    println("🤖 Starting AI News Daily Update...")
    println("📅 ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}")

    val system = AINewsSystem()

    val openAiKey = System.getenv("OPENAI_API_KEY")
    val githubToken = System.getenv("GITHUB_TOKEN")
    val fromEmail = System.getenv("FROM_EMAIL")
    val emailPassword = System.getenv("EMAIL_PASSWORD")

    println(if (openAiKey != null) "✅ OPENAI_API_KEY configured" else "❌ OPENAI_API_KEY missing")
    println(if (githubToken != null) "✅ GITHUB_TOKEN configured" else "❌ GITHUB_TOKEN missing")
    println(if (fromEmail != null) "✅ FROM_EMAIL configured" else "✅ FROM_EMAIL using default")
    println(if (emailPassword != null) "✅ EMAIL_PASSWORD configured" else "⚠️ EMAIL_PASSWORD missing - email notifications disabled")

    // Start subscription API server in background
    thread {
        system.startSubscriptionServer(8080)
    }

    try {
        println("📰 Aggregating daily news...")
        val articles = system.aggregateNews()

        if (articles.isNotEmpty()) {
            println("🌐 Generating daily website...")
            val website = system.generateDailyWebsite(articles)

            println("☁️ Uploading to hosting...")
            val currentDate = SimpleDateFormat("yyyyMMdd").format(Date())

            val localFile = File("ainews_$currentDate.html")
            localFile.writeText(website)
            println("💾 Website saved locally as ${localFile.name}")

            system.setupCustomDomain()

            val websiteUrl = system.uploadToGitHubPages(website)
            if (websiteUrl.isNotEmpty()) {
                println("🚀 Website uploaded to GitHub Pages: $websiteUrl")
                println("🌐 Custom domain: https://ainews.eu.com")

                println("📧 Sending daily notifications...")
                system.sendDailyNotification(articles, "https://ainews.eu.com")

                println("✅ AI News daily update complete!")
                println("🌐 Website: https://ainews.eu.com")
                println("📊 Articles processed: ${articles.size}")
                println("📧 Notifications ready")
                println("🔔 Subscription API running on port 8080")
            } else {
                println("❌ Failed to upload website")
            }
        } else {
            println("⚠️ No new articles found today")
        }
    } catch (e: Exception) {
        println("❌ Error in daily update: ${e.message}")
        e.printStackTrace()
    }

    // Keep the program running to serve the API
    println("🔄 Program will continue running to serve subscription API...")
    println("🛑 Press Ctrl+C to stop")

    // Prevent program from exiting
    while (true) {
        Thread.sleep(60000) // Sleep for 1 minute intervals
    }
    val html = """
<div class="lang en">
    <div class="article-title">${escapeHtml(article.titleTranslations["en"] ?: article.title)}</div>
    <div class="article-summary">${escapeHtml(article.summaryTranslations["en"] ?: article.summary)}</div>
    <a href="${escapeHtml(article.url)}" class="article-link" target="_blank">Read more</a>
</div>

<div class="lang he">
    <div class="article-title">${escapeHtml(article.titleTranslations["he"] ?: "כותרת בעברית")}</div>
    <div class="article-summary">${escapeHtml(article.summaryTranslations["he"] ?: "תקציר בעברית")}</div>
    <a href="${escapeHtml(hebrewUrl ?: article.url)}" class="article-link" target="_blank">קרא עוד</a>
</div>

<div class="lang ru">
    <div class="article-title">${escapeHtml(article.titleTranslations["ru"] ?: "Заголовок на русском")}</div>
    <div class="article-summary">${escapeHtml(article.summaryTranslations["ru"] ?: "Краткое изложение на русском")}</div>
    <a href="${escapeHtml(russianUrl ?: article.url)}" class="article-link" target="_blank">Читать далее</a>
</div>

<div class="lang el">
    <div class="article-title">${escapeHtml(article.titleTranslations["el"] ?: "Τίτλος στα ελληνικά")}</div>
    <div class="article-summary">${escapeHtml(article.summaryTranslations["el"] ?: "Περίληψη στα ελληνικά")}</div>
    <a href="${escapeHtml(greekUrl ?: article.url)}" class="article-link" target="_blank">Διαβάστε περισσότερα</a>
</div>
""".trimIndent()
}
