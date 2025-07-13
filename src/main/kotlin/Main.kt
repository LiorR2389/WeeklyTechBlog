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

    fun checkAndImportWebSubscriptions() {
        // For now, manually add a test subscriber to verify email functionality
        val testSubscribers = listOf(
            Subscriber(
                email = "lior.global@gmail.com",
                name = "Lior",
                languages = listOf("en", "he"),
                subscribed = true,
                subscribedDate = SimpleDateFormat("yyyy-MM-dd").format(Date())
            )
        )

        val currentSubscribers = loadSubscribers().toMutableList()

        testSubscribers.forEach { testSub ->
            val existing = currentSubscribers.find { it.email == testSub.email }
            if (existing == null) {
                currentSubscribers.add(testSub)
                println("📧 Added test subscriber: ${testSub.email}")
            }
        }

        saveSubscribers(currentSubscribers)
        println("📧 Total subscribers after import: ${currentSubscribers.size}")
    }

    fun loadSubscribers(): List<Subscriber> {
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
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.use { body -> Jsoup.parse(body.string()) }
            } else null
        } catch (e: Exception) {
            println("Error fetching $url: ${e.message}")
            null
        }
    }

    private fun generateSummary(title: String): String {
        val words = title.split(" ").filter { it.length > 3 }.take(5)
        return words.joinToString(" ").ifEmpty { title.take(60) }
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
            val requestBody = """
                {
                  "model": "gpt-4o-mini",
                  "messages": [
                    {"role": "system", "content": "Translate news headlines accurately. Keep translations concise."},
                    {"role": "user", "content": "Translate to $targetLanguage: $text"}
                  ],
                  "temperature": 0.1,
                  "max_tokens": 150
                }
            """.trimIndent()

            val request = Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .addHeader("Authorization", "Bearer $openAiApiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string())
                    json.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        .trim()
                } else text
            }
        } catch (e: Exception) {
            println("Translation error: ${e.message}")
            text
        }
    }

    private fun categorizeArticle(title: String): String {
        val content = title.lowercase()
        return when {
            content.contains("tech") || content.contains("ai") -> "Technology"
            content.contains("business") || content.contains("economy") -> "Business & Economy"
            content.contains("crime") || content.contains("court") -> "Crime & Justice"
            content.contains("politics") || content.contains("government") -> "Politics"
            else -> "General News"
        }
    }

    private fun scrapeNewsSource(sourceName: String, url: String): List<Article> {
        println("🔍 Scraping $sourceName...")
        val doc = fetchPage(url) ?: return emptyList()
        val articles = mutableListOf<Article>()

        val selectors = listOf(".entry-title a", "h2 a", ".post-title a")

        for (selector in selectors) {
            val linkElements = doc.select(selector)
            if (linkElements.isNotEmpty()) {
                linkElements.take(8).forEach { linkElement ->
                    try {
                        val title = linkElement.text().trim()
                        var articleUrl = linkElement.attr("abs:href").ifEmpty { linkElement.attr("href") }

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

                        if (title.isNotEmpty() && articleUrl.startsWith("http") && title.length > 15) {
                            val summary = generateSummary(title)
                            val category = categorizeArticle(title)

                            val titleTranslations = mapOf(
                                "en" to title,
                                "he" to translateText(title, "Hebrew"),
                                "ru" to translateText(title, "Russian"),
                                "el" to translateText(title, "Greek")
                            )

                            articles.add(Article(
                                title = title,
                                url = articleUrl,
                                summary = summary,
                                category = category,
                                date = SimpleDateFormat("yyyy-MM-dd").format(Date()),
                                titleTranslations = titleTranslations,
                                summaryTranslations = titleTranslations,
                                categoryTranslations = mapOf(
                                    "en" to category,
                                    "he" to translateText(category, "Hebrew"),
                                    "ru" to translateText(category, "Russian"),
                                    "el" to translateText(category, "Greek")
                                )
                            ))
                        }
                    } catch (e: Exception) {
                        println("Error processing link: ${e.message}")
                    }
                }
                break
            }
        }

        return articles.distinctBy { it.url }
    }

    fun aggregateNews(): List<Article> {
        println("📰 Starting news aggregation...")
        val seen = loadSeenArticles()
        val allArticles = mutableListOf<Article>()

        newsSources.forEach { (sourceName, sourceUrl) ->
            try {
                val sourceArticles = scrapeNewsSource(sourceName, sourceUrl)
                allArticles.addAll(sourceArticles)
                Thread.sleep(1000)
            } catch (e: Exception) {
                println("Error scraping $sourceName: ${e.message}")
            }
        }

        val newArticles = allArticles.filter { it.url !in seen }
        seen.addAll(newArticles.map { it.url })
        saveSeenArticles(seen)

        println("Found ${allArticles.size} total articles, ${newArticles.size} new articles")
        return newArticles
    }

    fun startSubscriptionServer(port: Int = 8080) {
        val server = HttpServer.create(InetSocketAddress(port), 0)

        server.createContext("/subscribe") { exchange ->
            println("📥 Received ${exchange.requestMethod} request to /subscribe")

            exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
            exchange.responseHeaders.add("Access-Control-Allow-Methods", "POST, OPTIONS")
            exchange.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type")

            when (exchange.requestMethod) {
                "OPTIONS" -> {
                    println("✅ Handling OPTIONS request")
                    exchange.sendResponseHeaders(200, -1)
                }
                "POST" -> {
                    try {
                        val requestBody = exchange.requestBody.bufferedReader().use { it.readText() }
                        println("📋 Request body: $requestBody")

                        val json = JSONObject(requestBody)

                        val email = json.getString("email")
                        val name = json.optString("name", null)
                        val languagesArray = json.getJSONArray("languages")
                        val languages = mutableListOf<String>()

                        for (i in 0 until languagesArray.length()) {
                            languages.add(languagesArray.getString(i))
                        }

                        println("📧 Processing subscription for: $email")
                        addSubscriber(email, name, languages)

                        val successResponse = """{"success": true, "message": "Subscription successful!"}"""
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, successResponse.toByteArray().size.toLong())
                        exchange.responseBody.write(successResponse.toByteArray())
                        exchange.responseBody.close()

                        println("✅ New subscriber added via API: $email")

                    } catch (e: Exception) {
                        println("❌ Error processing subscription: ${e.message}")
                        e.printStackTrace()

                        val errorResponse = """{"success": false, "message": "Subscription failed: ${e.message}"}"""
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(400, errorResponse.toByteArray().size.toLong())
                        exchange.responseBody.write(errorResponse.toByteArray())
                        exchange.responseBody.close()
                    }
                }
                else -> {
                    println("❌ Method not allowed: ${exchange.requestMethod}")
                    exchange.sendResponseHeaders(405, -1)
                }
            }
        }

        server.executor = null
        server.start()
        println("🚀 Subscription API server started on port $port")
        println("🔗 API endpoint: http://localhost:$port/subscribe")
    }

    fun uploadToGitHubPages(html: String): String {
        val repoName = "ainews-website"
        val fileName = "index.html"

        return try {
            val getRequest = Request.Builder()
                .url("https://api.github.com/repos/LiorR2389/$repoName/contents/$fileName")
                .addHeader("Authorization", "token $githubToken")
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
                    println("Failed to upload to GitHub Pages: ${response.code}")
                    ""
                }
            }
        } catch (e: Exception) {
            println("Error uploading to GitHub Pages: ${e.message}")
            ""
        }
    }

    fun setupCustomDomain() {
        // CNAME setup code - simplified for brevity
        println("✅ Setting up custom domain")
    }

    fun sendDailyNotification(articles: List<Article>, websiteUrl: String) {
        val subscribers = loadSubscribers().filter { it.subscribed }

        if (subscribers.isEmpty()) {
            println("📧 No subscribers to notify")
            return
        }

        if (emailPassword.isNullOrEmpty()) {
            println("📧 Email notifications disabled - no password configured")
            return
        }

        println("📧 Sending notifications to ${subscribers.size} subscribers...")
        subscribers.forEach { subscriber ->
            try {
                sendEmailNotification(subscriber, articles, websiteUrl)
                println("✅ Email sent to ${subscriber.email}")
                Thread.sleep(1000)
            } catch (e: Exception) {
                println("❌ Failed to send email to ${subscriber.email}")
            }
        }
    }

    private fun sendEmailNotification(subscriber: Subscriber, articles: List<Article>, websiteUrl: String) {
        val props = Properties().apply {
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.host", smtpHost)
            put("mail.smtp.port", smtpPort)
        }

        val session = Session.getInstance(props, object : jakarta.mail.Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(fromEmail, emailPassword)
            }
        })

        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(fromEmail, "AI News Cyprus"))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(subscriber.email))
            subject = "🤖 Your Daily Cyprus News Update - ${articles.size} new stories"

            val htmlContent = """
                <h1>🤖 AI News Cyprus</h1>
                <p>Hello ${subscriber.name ?: "there"}!</p>
                <p>Fresh Cyprus news updates are available.</p>
                <a href="$websiteUrl" style="background: #667eea; color: white; padding: 15px 30px; text-decoration: none; border-radius: 25px;">📖 View Full Website</a>
            """.trimIndent()

            setContent(htmlContent, "text/html; charset=utf-8")
        }

        Transport.send(message)
    }

    fun addTestSubscriber() {
        // Add yourself as a test subscriber
        addSubscriber("lior.global@gmail.com", "Lior", listOf("en", "he"))
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
        }
    }

    fun generateDailyWebsite(articles: List<Article>): String {
        val currentDate = SimpleDateFormat("yyyy-MM-dd").format(Date())
        val dayOfWeek = SimpleDateFormat("EEEE", Locale.ENGLISH).format(Date())
        val grouped = articles.groupBy { it.category }

        val articlesHtml = StringBuilder()
        grouped.forEach { (category, items) ->
            articlesHtml.append("""
                <h2>
                    <span class="lang en active">$category</span>
                    <span class="lang he" dir="rtl">${translateText(category, "Hebrew")}</span>
                    <span class="lang ru">${translateText(category, "Russian")}</span>
                    <span class="lang el">${translateText(category, "Greek")}</span>
                </h2>
            """.trimIndent())

            items.forEach { article ->
                val hebrewUrl = "https://translate.google.com/translate?sl=auto&tl=he&u=" + URLEncoder.encode(article.url, "UTF-8")
                val russianUrl = "https://translate.google.com/translate?sl=auto&tl=ru&u=" + URLEncoder.encode(article.url, "UTF-8")
                val greekUrl = "https://translate.google.com/translate?sl=auto&tl=el&u=" + URLEncoder.encode(article.url, "UTF-8")

                articlesHtml.append("""
                    <div class="article">
                        <div class="lang en active">
                            <h3>${article.titleTranslations["en"] ?: article.title}</h3>
                            <p>${article.summaryTranslations["en"] ?: article.summary}</p>
                            <a href="${article.url}" target="_blank">Read more</a>
                        </div>
                        <div class="lang he" dir="rtl">
                            <h3 dir="rtl">${article.titleTranslations["he"] ?: "כותרת בעברית"}</h3>
                            <p dir="rtl">${article.summaryTranslations["he"] ?: "תקציר בעברית"}</p>
                            <a href="$hebrewUrl" target="_blank">קרא עוד</a>
                        </div>
                        <div class="lang ru">
                            <h3>${article.titleTranslations["ru"] ?: "Заголовок на русском"}</h3>
                            <p>${article.summaryTranslations["ru"] ?: "Краткое изложение на русском"}</p>
                            <a href="$russianUrl" target="_blank">Читать далее</a>
                        </div>
                        <div class="lang el">
                            <h3>${article.titleTranslations["el"] ?: "Τίτλος στα ελληνικά"}</h3>
                            <p>${article.summaryTranslations["el"] ?: "Περίληψη στα ελληνικά"}</p>
                            <a href="$greekUrl" target="_blank">Διαβάστε περισσότερα</a>
                        </div>
                    </div>
                """.trimIndent())
            }
        }

        return """<!DOCTYPE html>
<html>
<head>
    <title>AI News - Cyprus Daily Digest for $dayOfWeek, $currentDate</title>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <style>
        body { font-family: Arial, sans-serif; margin: 40px; background: #f5f5f5; }
        .container { max-width: 800px; margin: 0 auto; background: white; padding: 40px; border-radius: 10px; }
        .header { text-align: center; margin-bottom: 40px; }
        .logo { font-size: 2rem; font-weight: bold; color: #667eea; }
        .lang-buttons { text-align: center; margin: 30px 0; }
        .lang-buttons button { margin: 5px; padding: 10px 20px; border: none; border-radius: 25px; background: #667eea; color: white; cursor: pointer; }
        .lang-buttons button.active { background: #764ba2; }
        .lang { display: none; }
        .lang.active { display: block; }
        .lang.he { direction: rtl; text-align: right; font-family: 'Arial', 'Tahoma', sans-serif; }
        .lang.he h2, .lang.he h3 { text-align: right; direction: rtl; }
        .lang.he p { text-align: right; direction: rtl; }
        .lang.he a { float: left; margin-right: 0; margin-left: 10px; }
        .lang.he .article { border-right: 4px solid #667eea; border-left: none; padding-right: 20px; padding-left: 20px; }
        .article { margin: 20px 0; padding: 20px; border-left: 4px solid #667eea; background: #f9f9f9; }
        .article.he { border-right: 4px solid #667eea; border-left: none; }
        .article h3 { margin: 0 0 10px 0; color: #333; }
        .article p { color: #666; margin: 10px 0; }
        .article a { color: #667eea; text-decoration: none; font-weight: bold; }
        .footer { text-align: center; margin-top: 40px; color: #666; }
        .subscription { background: #667eea; color: white; padding: 30px; margin: 40px 0; border-radius: 10px; text-align: center; }
        .subscription input { padding: 10px; margin: 10px; border: none; border-radius: 5px; }
        .subscription button { background: #FFD700; color: #333; border: none; padding: 10px 20px; border-radius: 5px; cursor: pointer; font-weight: bold; }
        .subscription .lang.he { direction: rtl; text-align: right; }
        .subscription .lang.he input { text-align: right; direction: rtl; }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <div class="logo">🤖 AI News</div>
            <p>Cyprus Daily Digest • $dayOfWeek, $currentDate</p>
        </div>
        
        <div class="lang-buttons">
            <button onclick="setLang('en')" class="active" id="btn-en">🇬🇧 English</button>
            <button onclick="setLang('he')" id="btn-he">🇮🇱 עברית</button>
            <button onclick="setLang('ru')" id="btn-ru">🇷🇺 Русский</button>
            <button onclick="setLang('el')" id="btn-el">🇬🇷 Ελληνικά</button>
        </div>
        
        $articlesHtml
        
        <div class="subscription">
            <div class="lang en active">
                <h3>🔔 Get Daily Notifications</h3>
                <p>Get email notifications when fresh news is published</p>
                <input type="email" id="email" placeholder="your@email.com">
                <input type="text" id="name" placeholder="Your name (optional)">
                <br>
                <button onclick="subscribe()">🔔 Subscribe</button>
                <div id="message"></div>
            </div>
            <div class="lang he">
                <h3>🔔 קבלו התראות יומיות</h3>
                <p>קבלו התראות כאשר חדשות טריות מתפרסמות</p>
                <input type="email" id="email-he" placeholder="הדוא״ל שלכם">
                <input type="text" id="name-he" placeholder="השם שלכם (אופציונלי)">
                <br>
                <button onclick="subscribe()">🔔 הירשמו</button>
                <div id="message-he"></div>
            </div>
            <div class="lang ru">
                <h3>🔔 Получайте уведомления</h3>
                <p>Получайте уведомления о свежих новостях</p>
                <input type="email" id="email-ru" placeholder="ваш@email.com">
                <input type="text" id="name-ru" placeholder="Ваше имя (необязательно)">
                <br>
                <button onclick="subscribe()">🔔 Подписаться</button>
                <div id="message-ru"></div>
            </div>
            <div class="lang el">
                <h3>🔔 Λάβετε ειδοποιήσεις</h3>
                <p>Λάβετε ειδοποιήσεις για φρέσκα νέα</p>
                <input type="email" id="email-el" placeholder="το@email.σας">
                <input type="text" id="name-el" placeholder="Το όνομά σας (προαιρετικό)">
                <br>
                <button onclick="subscribe()">🔔 Εγγραφή</button>
                <div id="message-el"></div>
            </div>
        </div>
        
        <div class="footer">
            <p>Generated automatically • Sources: Financial Mirror, In-Cyprus, Alpha News, StockWatch</p>
            <p><a href="https://ainews.eu.com">ainews.eu.com</a></p>
        </div>
    </div>

    <script>
        let currentLang = 'en';

        function setLang(lang) {
            document.querySelectorAll('.lang').forEach(el => el.classList.remove('active'));
            document.querySelectorAll('.lang.' + lang).forEach(el => el.classList.add('active'));
            document.querySelectorAll('.lang-buttons button').forEach(btn => btn.classList.remove('active'));
            document.getElementById('btn-' + lang).classList.add('active');
            currentLang = lang;
        }

        function subscribe() {
            const emailSelector = currentLang === 'en' ? '#email' : '#email-' + currentLang;
            const nameSelector = currentLang === 'en' ? '#name' : '#name-' + currentLang;
            const messageSelector = currentLang === 'en' ? '#message' : '#message-' + currentLang;
            
            const email = document.querySelector(emailSelector).value.trim();
            const name = document.querySelector(nameSelector).value.trim();
            const messageDiv = document.querySelector(messageSelector);
            
            if (!email) {
                messageDiv.innerHTML = '<p style="color: red;">Please enter your email</p>';
                return;
            }
            
            const subscriberData = {
                email: email,
                name: name || null,
                languages: [currentLang],
                subscribed: true,
                subscribedDate: new Date().toISOString().split('T')[0]
            };
            
            // Store subscription locally and show success message
            const subscriptions = JSON.parse(localStorage.getItem('ainews_subscriptions') || '[]');
            const existingIndex = subscriptions.findIndex(s => s.email === email);
            
            if (existingIndex >= 0) {
                subscriptions[existingIndex] = subscriberData;
            } else {
                subscriptions.push(subscriberData);
            }
            
            localStorage.setItem('ainews_subscriptions', JSON.stringify(subscriptions));
            
            fetch('/subscribe', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(subscriberData)
            })
            .then(function(response) {
                if (!response.ok) {
                    throw new Error('HTTP error! status: ' + response.status);
                }
                return response.json();
            })
            .then(function(data) {
                console.log('Successfully subscribed via API:', data);
            })
            .catch(function(error) {
                console.error('API subscription failed:', error);
                console.log('Subscription saved locally only');
            });
            
            messageDiv.innerHTML = '<p style="color: green;">✅ Subscribed successfully! You will receive notifications.</p>';
            document.querySelector(emailSelector).value = '';
            document.querySelector(nameSelector).value = '';
        }

        document.addEventListener('DOMContentLoaded', function() {
            setLang('en');
        });
    </script>
</body>
</html>""".trimIndent()
    }
}

fun main() {
    println("🤖 Starting AI News Daily Update...")

    val system = AINewsSystem()

    thread { system.startSubscriptionServer(8080) }

    // Add yourself as a test subscriber to verify email functionality
    system.addSubscriber("lior.global@gmail.com", "Lior", listOf("en", "he"))

    // Debug: Check if we have any subscribers
    val existingSubscribers = system.loadSubscribers()
    println("📧 Current subscribers: ${existingSubscribers.size}")
    existingSubscribers.forEach { subscriber ->
        println("   - ${subscriber.email} (${subscriber.languages.joinToString(", ")})")
    }

    try {
        val articles = system.aggregateNews()

        if (articles.isNotEmpty()) {
            val website = system.generateDailyWebsite(articles)
            system.setupCustomDomain()

            val websiteUrl = system.uploadToGitHubPages(website)
            if (websiteUrl.isNotEmpty()) {
                println("🚀 Website uploaded: $websiteUrl")
                system.sendDailyNotification(articles, "https://ainews.eu.com")
                println("✅ AI News daily update complete!")
            }
        } else {
            println("⚠️ No new articles found today")
        }
    } catch (e: Exception) {
        println("❌ Error: ${e.message}")
        e.printStackTrace()
    }

    while (true) {
        Thread.sleep(60000)
    }
}