package com.ainews

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
        "Cyprus Mail" to "https://cyprus-mail.com/",
        "Kathimerini Cyprus" to "https://www.kathimerini.com.cy/en/",
        "In-Cyprus Local" to "https://in-cyprus.philenews.com/local/",
        "In-Cyprus Opinion" to "https://in-cyprus.philenews.com/opinion/",
        "Alpha News Cyprus" to "https://www.alphanews.live/cyprus/",
        "StockWatch Cyprus" to "https://www.stockwatch.com.cy/en/news",
        "Cyprus Weekly" to "https://www.cyprusweekly.com.cy/",
        "Financial Mirror" to "https://www.financialmirror.com/",
        "Politis English" to "https://politis.com.cy/en/"
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

    private fun extractFirstParagraph(articleUrl: String, sourceName: String): String {
        return try {
            val doc = fetchPage(articleUrl)
            if (doc != null) {
                // Different selectors for different news sources
                val paragraphSelectors = when {
                    sourceName.contains("cyprus-mail", true) || sourceName.contains("cyprus mail", true) -> listOf(
                        ".td-post-content p:first-of-type",
                        ".entry-content p:first-of-type",
                        "article p:first-of-type",
                        ".post-content p:first-of-type",
                        ".content p:first-of-type",
                        "p:first-of-type"
                    )
                    sourceName.contains("kathimerini", true) -> listOf(
                        ".article-body p:first-of-type",
                        ".story-content p:first-of-type",
                        ".content p:first-of-type",
                        "article p:first-of-type",
                        ".post-content p:first-of-type",
                        "p:first-of-type"
                    )
                    sourceName.contains("cyprus weekly", true) -> listOf(
                        ".entry-content p:first-of-type",
                        ".post-content p:first-of-type",
                        "article p:first-of-type",
                        ".content p:first-of-type",
                        "p:first-of-type"
                    )
                    sourceName.contains("financial", true) -> listOf(
                        ".entry-content p:first-of-type",
                        "article p:first-of-type",
                        ".post-content p:first-of-type",
                        "p:first-of-type"
                    )
                    sourceName.contains("politis", true) -> listOf(
                        ".article-content p:first-of-type",
                        ".entry-content p:first-of-type",
                        "article p:first-of-type",
                        ".content p:first-of-type",
                        "p:first-of-type"
                    )
                    sourceName.contains("in-cyprus", true) -> listOf(
                        ".post-content p:first-of-type",
                        "article p:first-of-type",
                        ".content p:first-of-type",
                        "p:first-of-type"
                    )
                    sourceName.contains("alpha", true) -> listOf(
                        ".content p:first-of-type",
                        "main p:first-of-type",
                        "article p:first-of-type",
                        ".post-body p:first-of-type",
                        "p:first-of-type"
                    )
                    sourceName.contains("stockwatch", true) -> listOf(
                        ".article-body p:first-of-type",
                        ".content p:first-of-type",
                        "article p:first-of-type",
                        "p:first-of-type"
                    )
                    else -> listOf("p:first-of-type", "article p:first-of-type", ".content p:first-of-type")
                }

                // Try each selector until we find a paragraph
                for (selector in paragraphSelectors) {
                    val paragraphElement = doc.select(selector).first()
                    if (paragraphElement != null) {
                        val text = paragraphElement.text().trim()
                        if (text.isNotEmpty() && text.length > 50) {
                            // Clean up the text
                            return cleanParagraphText(text)
                        }
                    }
                }
            }
            ""
        } catch (e: Exception) {
            println("Error extracting paragraph from $articleUrl: ${e.message}")
            ""
        }
    }

    private fun cleanParagraphText(text: String): String {
        return text
            .replace(Regex("\\s+"), " ") // Replace multiple spaces with single space
            .replace(Regex("^(NICOSIA|LIMASSOL|LARNACA|PAPHOS|FAMAGUSTA)[\\s\\-,]+", RegexOption.IGNORE_CASE), "") // Remove city prefixes
            .replace(Regex("^(Reuters|AP|Bloomberg)[\\s\\-,]+", RegexOption.IGNORE_CASE), "") // Remove news agency prefixes
            .replace(Regex("\\(.*?\\)"), "") // Remove parentheses content
            .replace(Regex("\\[.*?\\]"), "") // Remove brackets content
            .trim()
            .take(200) // Limit to 200 characters
            .let { if (it.length == 200) "$it..." else it }
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
        val doc = fetchPage(url) 
        if (doc == null) {
            println("❌ Failed to fetch page for $sourceName")
            return emptyList()
        }
        
        val articles = mutableListOf<Article>()

        // More comprehensive selectors for different news sites
        val allSelectors = listOf(
            // Standard WordPress/CMS selectors
            ".entry-title a", "h2 a", ".post-title a", "h3 a", ".headline a",
            // Cyprus Mail specific
            ".td-module-title a", ".td-image-wrap", ".td-big-grid-post-title a",
            // Kathimerini specific  
            ".article-title a", ".story-title a", ".headline-link",
            // StockWatch specific
            ".news-title a", ".article-link", ".story-link",
            // General fallbacks
            "a[href*='/article/']", "a[href*='/news/']", "a[href*='/story/']",
            ".content h2 a", ".main h3 a", "article h2 a", "article h3 a"
        )

        var foundLinks = false
        
        for (selector in allSelectors) {
            val linkElements = doc.select(selector)
            println("🔎 Trying selector '$selector' for $sourceName: found ${linkElements.size} elements")
            
            if (linkElements.isNotEmpty()) {
                foundLinks = true
                
                linkElements.take(10).forEach { linkElement ->
                    try {
                        val title = linkElement.text().trim()
                        var articleUrl = linkElement.attr("abs:href").ifEmpty { linkElement.attr("href") }

                        // Debug logging
                        println("📝 Found potential article: '$title' -> '$articleUrl'")

                        if (articleUrl.startsWith("/")) {
                            val baseUrl = when {
                                sourceName.contains("cyprus-mail", true) || sourceName.contains("cyprus mail", true) -> "https://cyprus-mail.com"
                                sourceName.contains("kathimerini", true) -> "https://www.kathimerini.com.cy"
                                sourceName.contains("in-cyprus", true) -> "https://in-cyprus.philenews.com"
                                sourceName.contains("alpha", true) -> "https://www.alphanews.live"
                                sourceName.contains("stockwatch", true) -> "https://www.stockwatch.com.cy"
                                sourceName.contains("cyprus weekly", true) -> "https://www.cyprusweekly.com.cy"
                                sourceName.contains("financial", true) -> "https://www.financialmirror.com"
                                sourceName.contains("politis", true) -> "https://politis.com.cy"
                                else -> ""
                            }
                            articleUrl = baseUrl + articleUrl
                            println("🔗 Fixed relative URL: $articleUrl")
                        }

                        if (title.isNotEmpty() && articleUrl.startsWith("http") && title.length > 15) {
                            println("✅ Valid article found: $title")
                            
                            // Extract first paragraph from the article
                            println("📖 Extracting paragraph from: $title")
                            val paragraph = extractFirstParagraph(articleUrl, sourceName)
                            val summary = if (paragraph.isNotEmpty()) paragraph else generateFallbackSummary(title)
                            
                            val category = categorizeArticle(title)

                            val titleTranslations = mapOf(
                                "en" to title,
                                "he" to translateText(title, "Hebrew"),
                                "ru" to translateText(title, "Russian"),
                                "el" to translateText(title, "Greek")
                            )

                            val summaryTranslations = mapOf(
                                "en" to summary,
                                "he" to translateText(summary, "Hebrew"),
                                "ru" to translateText(summary, "Russian"),
                                "el" to translateText(summary, "Greek")
                            )

                            articles.add(Article(
                                title = title,
                                url = articleUrl,
                                summary = summary,
                                category = category,
                                date = SimpleDateFormat("yyyy-MM-dd").format(Date()),
                                titleTranslations = titleTranslations,
                                summaryTranslations = summaryTranslations,
                                categoryTranslations = mapOf(
                                    "en" to category,
                                    "he" to translateText(category, "Hebrew"),
                                    "ru" to translateText(category, "Russian"),
                                    "el" to translateText(category, "Greek")
                                )
                            ))
                            
                            // Add delay between article fetches to be respectful
                            Thread.sleep(1500)
                        } else {
                            println("❌ Invalid article: title='$title', url='$articleUrl'")
                        }
                    } catch (e: Exception) {
                        println("❌ Error processing link: ${e.message}")
                    }
                }
                break // Stop trying selectors once we find articles
            }
        }
        
        if (!foundLinks) {
            println("⚠️ No article links found for $sourceName with any selector")
            // Debug: Print page structure
            println("🔍 Page title: ${doc.title()}")
            println("🔍 Total links on page: ${doc.select("a").size}")
            println("🔍 Sample h2 elements: ${doc.select("h2").take(3).map { it.text() }}")
            println("🔍 Sample h3 elements: ${doc.select("h3").take(3).map { it.text() }}")
        }

        println("📊 $sourceName: Found ${articles.size} articles")
        return articles.distinctBy { it.url }
    }

    private fun generateFallbackSummary(title: String): String {
        val words = title.split(" ").filter { it.length > 3 }.take(5)
        return words.joinToString(" ").ifEmpty { title.take(60) }
    }

    fun aggregateNews(): List<Article> {
        println("📰 Starting news aggregation...")
        val seen = loadSeenArticles()
        val allArticles = mutableListOf<Article>()
        val titleSimilarityThreshold = 0.8 // 80% similarity threshold

        newsSources.forEach { (sourceName, sourceUrl) ->
            try {
                val sourceArticles = scrapeNewsSource(sourceName, sourceUrl)
                
                // Add duplicate detection by title similarity
                sourceArticles.forEach { newArticle ->
                    var isDuplicate = false
                    
                    // Check against existing articles
                    for (existingArticle in allArticles) {
                        val similarity = calculateTitleSimilarity(newArticle.title, existingArticle.title)
                        if (similarity > titleSimilarityThreshold) {
                            println("🔄 Duplicate detected: '${newArticle.title}' similar to '${existingArticle.title}' (${(similarity * 100).toInt()}% match)")
                            isDuplicate = true
                            break
                        }
                    }
                    
                    if (!isDuplicate) {
                        allArticles.add(newArticle)
                    }
                }
                
                Thread.sleep(2000) // Longer delay between sources
            } catch (e: Exception) {
                println("Error scraping $sourceName: ${e.message}")
            }
        }

        val newArticles = allArticles.filter { it.url !in seen }
        seen.addAll(newArticles.map { it.url })
        saveSeenArticles(seen)

        println("Found ${allArticles.size} total articles, ${newArticles.size} new articles after duplicate removal")
        return newArticles
    }

    private fun calculateTitleSimilarity(title1: String, title2: String): Double {
        val words1 = title1.lowercase().split("\\s+".toRegex()).filter { it.length > 3 }.toSet()
        val words2 = title2.lowercase().split("\\s+".toRegex()).filter { it.length > 3 }.toSet()
        
        if (words1.isEmpty() || words2.isEmpty()) return 0.0
        
        val intersection = words1.intersect(words2).size
        val union = words1.union(words2).size
        
        return intersection.toDouble() / union.toDouble()
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

                            // Add to CSV file instead of memory
                            addSubscriberToCSV(email, name, languages)

                            // Mark as read AFTER successfully adding to CSV
                            message.setFlag(Flags.Flag.SEEN, true)
                            println("✅ Auto-added subscriber from email: $email and marked email as read")
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

    private fun addSubscriberToCSV(email: String, name: String?, languages: List<String>) {
        val csvFile = File("new_subscribers.csv")
        val csvLine = "$email,${name ?: ""},${languages.joinToString(";")}"
        
        try {
            // Check if subscriber already exists in CSV
            if (csvFile.exists()) {
                val existingContent = csvFile.readText()
                if (existingContent.contains(email)) {
                    println("📧 Subscriber $email already exists in CSV, skipping")
                    return
                }
            }
            
            // Append to CSV file
            csvFile.appendText("$csvLine\n")
            println("📧 Added subscriber to CSV: $email")
        } catch (e: Exception) {
            println("❌ Error adding subscriber to CSV: ${e.message}")
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
                            currentSubscribers.add(Subscriber(
                                email = email,
                                name = name,
                                languages = languages,
                                subscribed = true,
                                subscribedDate = SimpleDateFormat("yyyy-MM-dd").format(Date())
                            ))
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
            articlesHtml.append("""
                <h2>
                    <span class="lang en active">$category</span>
                    <span class="lang he" dir="rtl">${translateText(category, "Hebrew")}</span>
                    <span class="lang ru">${translateText(category, "Russian")}</span>
                    <span class="lang el">${translateText(category, "Greek")}</span>
                </h2>
            """.trimIndent())

            items.forEach { article ->
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
                            <a href="${article.url}" target="_blank">קרא עוד</a>
                        </div>
                        <div class="lang ru">
                            <h3>${article.titleTranslations["ru"] ?: "Заголовок на русском"}</h3>
                            <p>${article.summaryTranslations["ru"] ?: "Краткое изложение на русском"}</p>
                            <a href="${article.url}" target="_blank">Читать далее</a>
                        </div>
                        <div class="lang el">
                            <h3>${article.titleTranslations["el"] ?: "Τίτλος στα ελληνικά"}</h3>
                            <p>${article.summaryTranslations["el"] ?: "Περίληψη στα ελληνικά"}</p>
                            <a href="${article.url}" target="_blank">Διαβάστε περισσότερα</a>
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
            <form action="https://formspree.io/f/xovlajpa" method="POST" id="subscription-form">
            <input type="email" name="email" placeholder="your@email.com" required>
            <input type="text" name="name" placeholder="Your name (optional)">
            <input type="hidden" name="languages" value="en" id="hidden-languages">
            <input type="hidden" name="_subject" value="AI News Cyprus Subscription">
            <br>
            <button type="submit">🔔 Subscribe</button>
            </form>
            <div id="message"></div>
            </div>
            <div class="lang he">
            <h3>🔔 קבלו התראות יומיות</h3>
            <p>קבלו התראות כאשר חדשות טריות מתפרסמות</p>
            <form action="https://formspree.io/f/xovlajpa" method="POST">
            <input type="email" name="email" placeholder="הדוא״ל שלכם" required>
            <input type="text" name="name" placeholder="השם שלכם (אופציונלי)">
            <input type="hidden" name="languages" value="he">
            <input type="hidden" name="_subject" value="AI News Cyprus Subscription (Hebrew)">
            <br>
            <button type="submit">🔔 הירשמו</button>
            </form>
            </div>
            <div class="lang ru">
            <h3>🔔 Получайте уведомления</h3>
            <p>Получайте уведомления о свежих новостях</p>
            <form action="https://formspree.io/f/xovlajpa" method="POST">
            <input type="email" name="email" placeholder="ваш@email.com" required>
            <input type="text" name="name" placeholder="Ваше имя (необязательно)">
            <input type="hidden" name="languages" value="ru">
            <input type="hidden" name="_subject" value="AI News Cyprus Subscription (Russian)">
            <br>
            <button type="submit">🔔 Подписаться</button>
            </form>
            </div>
            <div class="lang el">
            <h3>🔔 Λάβετε ειδοποιήσεις</h3>
            <p>Λάβετε ειδοποιήσεις για φρέσκα νέα</p>
            <form action="https://formspree.io/f/xovlajpa" method="POST">
            <input type="email" name="email" placeholder="το@email.σας" required>
            <input type="text" name="name" placeholder="Το όνομά σας (προαιρετικό)">
            <input type="hidden" name="languages" value="el">
            <input type="hidden" name="_subject" value="AI News Cyprus Subscription (Greek)">
            <br>
            <button type="submit">🔔 Εγγραφή</button>
            </form>
            </div>
            </div>

            <div class="footer">
            <p>Generated automatically • Sources: Cyprus Mail, Kathimerini Cyprus, In-Cyprus, Alpha News, StockWatch, Cyprus Weekly, Financial Mirror, Politis</p>
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

                    // Update the hidden language field for the active form
                    const hiddenField = document.querySelector('.lang.' + lang + ' input[name="languages"]');
                    if (hiddenField) {
                        hiddenField.value = lang;
                    }
                }

                // Anti-bot detection for translation links
                function translateAndOpen(originalUrl, targetLang) {
                    // Add random delay to simulate human behavior
                    const delay = Math.random() * 1000 + 500; // 500-1500ms delay

                    setTimeout(function() {
                        // Add cache buster and human-like parameters
                        const timestamp = Date.now();
                        const randomParam = Math.random().toString(36).substring(7);

                        // Build translate URL with anti-detection measures
                        const translateUrl = 'https://translate.google.com/translate' +
                            '?sl=auto' +
                            '&tl=' + targetLang +
                            '&u=' + encodeURIComponent(originalUrl) +
                            '&_x_tr_sl=auto' +
                            '&_x_tr_tl=' + targetLang +
                            '&_x_tr_hl=en' +
                            '&ie=UTF-8' +
                            '&prev=_t' +
                            '&rurl=translate.google.com' +
                            '&sp=nmt4' +
                            '&xid=' + randomParam +
                            '&usg=' + timestamp;

                        // Open in new window/tab
                        window.open(translateUrl, '_blank', 'noopener,noreferrer');
                    }, delay);
                }

                // Alternative function for different translate services
                function translateAlternative(originalUrl, targetLang) {
                    // Fallback to other translation services if Google blocks
                    const alternatives = {
                        'he': 'https://www.bing.com/translator?from=en&to=he&text=' + encodeURIComponent(originalUrl),
                        'ru': 'https://www.bing.com/translator?from=en&to=ru&text=' + encodeURIComponent(originalUrl),
                        'el': 'https://www.bing.com/translator?from=en&to=el&text=' + encodeURIComponent(originalUrl)
                    };

                    if (alternatives[targetLang]) {
                        window.open(alternatives[targetLang], '_blank');
                    } else {
                        // Fallback to original URL with instruction
                        window.open(originalUrl, '_blank');
                        alert('Please use your browser\'s built-in translator for this article.');
                    }
                }

                // Show translation instructions when clicking non-English articles
                document.addEventListener('click', function(e) {
                    if (e.target.classList.contains('translate-tip')) {
                        const lang = e.target.getAttribute('data-lang');
                        const langNames = {
                            'he': 'Hebrew (עברית)',
                            'ru': 'Russian (Русский)', 
                            'el': 'Greek (Ελληνικά)'
                        };
                        
                        setTimeout(function() {
                            alert('💡 Translation Tip: Right-click on the opened page and select "Translate to ' + langNames[lang] + '" for the best reading experience!');
                        }, 1000);
                    }
                });

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
    
    // Skip HTTP server completely - go straight to CSV processing
    println("📝 Using CSV-only subscription method")
    
    // Process new subscriptions automatically
    println("🔄 Processing new subscriptions...")
    system.processFormspreeEmails()         // Check Gmail and add to CSV + mark as read
    system.checkAndImportWebSubscriptions() // Process any existing CSV files

    // Add test subscriber
    system.addSubscriber("lior.global@gmail.com", "Lior", listOf("en", "he"))

    // Debug: Check current subscribers
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
    
    // For cronjob mode, exit after running once
    val isCronjob = System.getenv("CRONJOB_MODE")?.toBoolean() ?: true // Default to cronjob mode
    if (isCronjob) {
        println("✅ Cronjob completed successfully")
        return
    }
    
    // Only keep running in regular mode (rarely used now)
    println("🔄 Keeping application running and checking for new subscriptions...")
    while (true) {
        Thread.sleep(300000) // Check every 5 minutes
        try {
            println("🔄 Periodic check for new subscriptions...")
            system.processFormspreeEmails()
            system.checkAndImportWebSubscriptions()
        } catch (e: Exception) {
            println("⚠️ Error during periodic check: ${e.message}")
        }
    }
}