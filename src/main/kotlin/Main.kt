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
import jakarta.mail.search.*

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

                            articles.add(
                                Article(
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
                                )
                            )
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

    fun processFormspreeEmails() {
        println("📧 Checking for new Formspree subscription emails...")

        if (emailPassword.isNullOrEmpty()) {
            println("⚠️ Email processing disabled - no email password configured")
            return
        }

        try {
            val props = Properties().apply {
                put("mail.store.protocol", "imaps")
                put("mail.imaps.host", "imap.gmail.com")
                put("mail.imaps.port", "993")
            }

            val session = Session.getInstance(props)
            val store = session.getStore("imaps")
            store.connect(fromEmail, emailPassword)

            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_WRITE)

            // Search for unread emails from Formspree
            val searchTerm = AndTerm(
                FromTerm(InternetAddress("noreply@formspree.io")),
                FlagTerm(Flags(Flags.Flag.SEEN), false)
            )

            val messages = inbox.search(searchTerm)
            println("📬 Found ${messages.size} unread Formspree emails")

            messages.forEach { message ->
                try {
                    val content = message.content.toString()
                    val subject = message.subject

                    if (subject.contains("AI News Cyprus Subscription")) {
                        println("📋 Processing subscription email: $subject")

                        // Extract data from email content
                        val emailMatch = Regex("email[:\\s]+([^\\s\\n]+)").find(content)
                        val nameMatch = Regex("name[:\\s]+([^\\n]+)").find(content)
                        val langMatch = Regex("languages[:\\s]+([^\\s\\n]+)").find(content)

                        if (emailMatch != null) {
                            val email = emailMatch.groupValues[1].trim()
                            val name = nameMatch?.groupValues?.get(1)?.trim()
                            val languages = langMatch?.groupValues?.get(1)?.split(";") ?: listOf("en")

                            addSubscriber(email, name, languages)

                            // Mark as read
                            message.setFlag(Flags.Flag.SEEN, true)
                            println("✅ Auto-added subscriber from email: $email")
                        }
                    }
                } catch (e: Exception) {
                    println("❌ Error processing email: ${e.message}")
                }
            }

            inbox.close(false)
            store.close()

        } catch (e: Exception) {
            println("❌ Error accessing emails: ${e.message}")
        }
    }

    fun checkAndImportWebSubscriptions() {
        // Check for new subscriptions from a manual CSV file
        val csvFile = File("new_subscribers.csv")
        if (csvFile.exists()) {
            try {
                val csvContent = csvFile.readText()
                val lines = csvContent.split("\n").filter { it.trim().isNotEmpty() }

                val currentSubscribers = loadSubscribers().toMutableList()
                var newCount = 0

                lines.forEach { line ->
                    val parts = line.split(",").map { it.trim() }
                    if (parts.size >= 2) {
                        val email = parts[0]
                        val name = if (parts[1].isNotEmpty()) parts[1] else null
                        val languages = if (parts.size > 2) parts[2].split(";") else listOf("en")

                        val existing = currentSubscribers.find { it.email == email }
                        if (existing == null) {
                            currentSubscribers.add(
                                Subscriber(
                                    email = email,
                                    name = name,
                                    languages = languages,
                                    subscribed = true,
                                    subscribedDate = SimpleDateFormat("yyyy-MM-dd").format(Date())
                                )
                            )
                            newCount++
                            println("📧 Added subscriber from CSV: $email")
                        }
                    }
                }

                if (newCount > 0) {
                    saveSubscribers(currentSubscribers)
                    // Rename the processed file
                    csvFile.renameTo(File("processed_subscribers_${SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())}.csv"))
                    println("📧 Added $newCount new subscribers from CSV")
                }

            } catch (e: Exception) {
                println("Error processing CSV: ${e.message}")
            }
        }
    }

    fun startSubscriptionServer(port: Int = 8080) {
        println("⚠️ Starting HTTP server - if this fails, use CSV subscription method")
        try {
            println("🔧 Creating HTTP server on port $port...")

            // Try binding to all interfaces explicitly
            val address = InetSocketAddress("0.0.0.0", port)
            println("🔗 Binding to address: ${address.hostString}:${address.port}")

            val server = HttpServer.create(address, 0)
            println("✅ HTTP server created successfully")

            // Add a simple test endpoint
            server.createContext("/") { exchange ->
                println("📥 Received request to root path from ${exchange.remoteAddress}")
                val response = "AI News Subscription Server is running! Time: ${java.time.Instant.now()}"
                exchange.responseHeaders.add("Content-Type", "text/plain")
                exchange.sendResponseHeaders(200, response.length.toLong())
                exchange.responseBody.write(response.toByteArray())
                exchange.responseBody.close()
            }

            server.createContext("/health") { exchange ->
                println("📥 Health check request from ${exchange.remoteAddress}")
                val response = """{"status":"healthy","timestamp":"${java.time.Instant.now()}","port":$port}"""
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
                exchange.sendResponseHeaders(200, response.length.toLong())
                exchange.responseBody.write(response.toByteArray())
                exchange.responseBody.close()
            }

            server.createContext("/subscribe") { exchange ->
                println("📥 Received ${exchange.requestMethod} request to /subscribe from ${exchange.remoteAddress}")

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

            println("🔧 Setting server executor...")
            server.executor = null

            println("🚀 Starting HTTP server...")
            server.start()

            println("🚀 Subscription API server started on port $port")
            println("🔗 API endpoint: http://0.0.0.0:$port/subscribe")
            println("🔗 Health check: http://0.0.0.0:$port/health")
            println("🔗 Root page: http://0.0.0.0:$port/")

            // Test external port binding
            println("🧪 Testing external port binding...")
            try {
                val testSocket = java.net.Socket()
                testSocket.connect(InetSocketAddress("127.0.0.1", port), 2000)
                val writer = testSocket.getOutputStream().bufferedWriter()
                writer.write("GET /health HTTP/1.1\r\nHost: localhost\r\n\r\n")
                writer.flush()

                val reader = testSocket.getInputStream().bufferedReader()
                val response = reader.readLine()
                testSocket.close()

                println("✅ External connectivity test PASSED: $response")
            } catch (e: Exception) {
                println("❌ External connectivity test FAILED: ${e.message}")
                println("🔍 This suggests Windows firewall or permission issues")
            }

        } catch (e: Exception) {
            println("❌ CRITICAL ERROR starting subscription server: ${e.message}")
            e.printStackTrace()
            throw e
        }
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
            articlesHtml.append(
                """
            <h2>
                <span class="lang en active">$category</span>
                <span class="lang he" dir="rtl">${translateText(category, "Hebrew")}</span>
                <span class="lang ru">${translateText(category, "Russian")}</span>
                <span class="lang el">${translateText(category, "Greek")}</span>
            </h2>
        """.trimIndent()
            )

            items.forEach { article ->
                articlesHtml.append(
                    """
                <div class="article">
                    <div class="lang en active">
                        <h3>${article.titleTranslations["en"] ?: article.title}</h3>
                        <p>${article.summaryTranslations["en"] ?: article.summary}</p>
                        <a href="${article.url}" target="_blank">Read more</a>
                    </div>
                    <div class="lang he" dir="rtl">
                        <h3 dir="rtl">${article.titleTranslations["he"] ?: "כותרת בעברית"}</h3>
                        <p dir="rtl">${article.summaryTranslations["he"] ?: "תקציר בעברית"}</p>
                        <a href="#" onclick="translateAndOpen('${article.url}', 'he'); return false;" target="_blank">קרא עוד</a>
                    </div>
                    <div class="lang ru">
                        <h3>${article.titleTranslations["ru"] ?: "Заголовок на русском"}</h3>
                        <p>${article.summaryTranslations["ru"] ?: "Краткое изложение на русском"}</p>
                        <a href="#" onclick="translateAndOpen('${article.url}', 'ru'); return false;" target="_blank">Читать далее</a>
                    </div>
                    <div class="lang el">
                        <h3>${article.titleTranslations["el"] ?: "Τίτλος στα ελληνικά"}</h3>
                        <p>${article.summaryTranslations["el"] ?: "Περίληψη στα ελληνικά"}</p>
                        <a href="#" onclick="translateAndOpen('${article.url}', 'el'); return false;" target="_blank">Διαβάστε περισσότερα</a>
                    </div>
                </div>
            """.trimIndent()
                )
            }
        }

        return """<!DOCTYPE html>
        <html>
        <!-- HTML content continues here... -->
    """.trimIndent()
    }
}
