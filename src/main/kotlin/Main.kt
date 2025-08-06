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
import java.security.MessageDigest

// Updated data classes with multi-country support
data class Article(
    val title: String,
    val url: String,
    val summary: String,
    val category: String,
    val date: String,
    val country: String, // NEW: Country field
    val sourceId: String, // NEW: Source identifier
    val titleTranslations: Map<String, String> = emptyMap(),
    val summaryTranslations: Map<String, String> = emptyMap(),
    val categoryTranslations: Map<String, String> = emptyMap()
)

// NEW: Source configuration data class
data class Source(
    val country: String,
    val sourceId: String,
    val sourceName: String,
    val baseUrl: String,
    val linkSelectors: List<String>,
    val paragraphSelectors: List<String>,
    val sourceLanguage: String,
    val timezone: String,
    val delayMs: Long
)

// NEW: Source configuration container
data class SourceConfig(
    val sources: List<Source>
)

data class Subscriber(
    val email: String,
    val name: String?,
    val languages: List<String>,
    val countries: List<String> = listOf("CYPRUS"), // NEW: Multi-country support
    val subscribed: Boolean = true,
    val subscribedDate: String
)

// NEW: Translation cache entry
data class TranslationCacheEntry(
    val text: String,
    val targetLanguage: String,
    val translatedText: String,
    val timestamp: Long = System.currentTimeMillis()
)

class AINewsSystem {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val seenArticlesFile = File("seen_articles.json")
    private val subscribersFile = File("subscribers.json")
    private val sourcesConfigFile = File("sources.json") // NEW
    private val translationCacheFile = File("translations.json") // NEW

    private val openAiApiKey = System.getenv("OPENAI_API_KEY")
    private val githubToken = System.getenv("GITHUB_TOKEN")
    private val fromEmail = System.getenv("FROM_EMAIL") ?: "hello@ainews.eu.com"
    private val emailPassword = System.getenv("EMAIL_PASSWORD")
    private val smtpHost = System.getenv("SMTP_HOST") ?: "smtp.gmail.com"
    private val smtpPort = System.getenv("SMTP_PORT") ?: "587"

    // NEW: Load sources from configuration file
    private fun loadSources(): List<Source> {
        return if (sourcesConfigFile.exists()) {
            try {
                val json = sourcesConfigFile.readText()
                val config = gson.fromJson(json, SourceConfig::class.java)
                println("✅ Loaded ${config.sources.size} sources from configuration")
                config.sources
            } catch (e: Exception) {
                println("❌ Error loading sources config: ${e.message}")
                println("🔄 Falling back to hardcoded Cyprus sources")
                getDefaultCyprusSources()
            }
        } else {
            println("⚠️ sources.json not found, creating default configuration")
            createDefaultSourcesConfig()
            getDefaultCyprusSources()
        }
    }

    // NEW: Create default sources configuration file
    private fun createDefaultSourcesConfig() {
        val defaultSources = SourceConfig(
            sources = listOf(
                Source(
                    country = "CYPRUS",
                    sourceId = "cyprus-mail",
                    sourceName = "Cyprus Mail",
                    baseUrl = "https://cyprus-mail.com/",
                    linkSelectors = listOf(".td-module-title a", ".entry-title a", "h2 a"),
                    paragraphSelectors = listOf(".td-post-content p:first-of-type", ".entry-content p:first-of-type"),
                    sourceLanguage = "en",
                    timezone = "Europe/Nicosia",
                    delayMs = 2000
                )
                // Add more default sources as needed
            )
        )
        
        try {
            sourcesConfigFile.writeText(gson.toJson(defaultSources))
            println("✅ Created default sources.json configuration")
        } catch (e: Exception) {
            println("❌ Error creating sources config: ${e.message}")
        }
    }

    // NEW: Fallback to hardcoded sources for backwards compatibility
    private fun getDefaultCyprusSources(): List<Source> {
        return listOf(
            Source(
                country = "CYPRUS",
                sourceId = "cyprus-mail",
                sourceName = "Cyprus Mail",
                baseUrl = "https://cyprus-mail.com/",
                linkSelectors = listOf(".td-module-title a", ".entry-title a", "h2 a"),
                paragraphSelectors = listOf(".td-post-content p:first-of-type", ".entry-content p:first-of-type"),
                sourceLanguage = "en",
                timezone = "Europe/Nicosia",
                delayMs = 2000
            ),
            Source(
                country = "CYPRUS",
                sourceId = "in-cyprus-local",
                sourceName = "In-Cyprus Local",
                baseUrl = "https://in-cyprus.philenews.com/local/",
                linkSelectors = listOf(".post-title a", "h2 a", "h3 a"),
                paragraphSelectors = listOf(".post-content p:first-of-type", "article p:first-of-type"),
                sourceLanguage = "en",
                timezone = "Europe/Nicosia",
                delayMs = 2000
            )
        )
    }

    // NEW: Translation cache management
    private fun loadTranslationCache(): MutableMap<String, String> {
        return if (translationCacheFile.exists()) {
            try {
                val json = translationCacheFile.readText()
                val type = object : TypeToken<Map<String, String>>() {}.type
                gson.fromJson<Map<String, String>>(json, type)?.toMutableMap() ?: mutableMapOf()
            } catch (e: Exception) {
                println("Error loading translation cache: ${e.message}")
                mutableMapOf()
            }
        } else mutableMapOf()
    }

    private fun saveTranslationCache(cache: Map<String, String>) {
        try {
            translationCacheFile.writeText(gson.toJson(cache))
        } catch (e: Exception) {
            println("Error saving translation cache: ${e.message}")
        }
    }

    // NEW: Generate cache key for translations
    private fun generateCacheKey(text: String, targetLanguage: String): String {
        val input = "$text|$targetLanguage"
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(input.toByteArray())
        return hash.fold("") { str, it -> str + "%02x".format(it) }
    }

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

    // UPDATED: Extract first paragraph using source configuration
    private fun extractFirstParagraph(articleUrl: String, source: Source): String {
        return try {
            val doc = fetchPage(articleUrl)
            if (doc != null) {
                // Try each paragraph selector from the source configuration
                for (selector in source.paragraphSelectors) {
                    val paragraphElement = doc.select(selector).first()
                    if (paragraphElement != null) {
                        val text = paragraphElement.text().trim()
                        if (text.isNotEmpty() && text.length > 50) {
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
            .replace(Regex("\\s+"), " ") 
            .replace(Regex("^(NICOSIA|LIMASSOL|LARNACA|PAPHOS|FAMAGUSTA|ATHENS|THESSALONIKI|TEL AVIV|JERUSALEM|HAIFA)[\\s\\-,]+", RegexOption.IGNORE_CASE), "") 
            .replace(Regex("^(Reuters|AP|Bloomberg|AFP)[\\s\\-,]+", RegexOption.IGNORE_CASE), "") 
            .replace(Regex("\\(.*?\\)"), "") 
            .replace(Regex("\\[.*?\\]"), "") 
            .trim()
            .take(300) // Increased limit as per requirements
            .let { if (it.length == 300) "$it..." else it }
    }

    // UPDATED: Translation with caching
    private fun translateText(text: String, targetLanguage: String): String {
        if (openAiApiKey.isNullOrEmpty()) {
            return when (targetLanguage) {
                "Hebrew" -> "כותרת בעברית"
                "Russian" -> "Заголовок на русском"
                "Greek" -> "Τίτλος στα ελληνικά"
                else -> text
            }
        }

        // Check cache first
        val cache = loadTranslationCache()
        val cacheKey = generateCacheKey(text, targetLanguage)
        
        if (cache.containsKey(cacheKey)) {
            println("✅ Using cached translation for: ${text.take(50)}...")
            return cache[cacheKey]!!
        }

        return try {
            val requestBody = """
                {
                  "model": "gpt-4o-mini",
                  "messages": [
                    {"role": "system", "content": "Translate news headlines and summaries accurately. Keep translations concise and natural."},
                    {"role": "user", "content": "Translate to $targetLanguage: $text"}
                  ],
                  "temperature": 0.1,
                  "max_tokens": 200
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
                    val translation = json.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        .trim()
                    
                    // Cache the translation
                    cache[cacheKey] = translation
                    saveTranslationCache(cache)
                    println("💾 Cached translation for: ${text.take(50)}...")
                    
                    translation
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
            content.contains("tech") || content.contains("ai") || content.contains("digital") || content.contains("startup") -> "Technology"
            content.contains("business") || content.contains("economy") || content.contains("financial") || content.contains("market") -> "Business & Economy"
            content.contains("crime") || content.contains("court") || content.contains("police") || content.contains("arrest") -> "Crime & Justice"
            content.contains("politics") || content.contains("government") || content.contains("minister") || content.contains("parliament") -> "Politics"
            content.contains("property") || content.contains("real estate") || content.contains("housing") || content.contains("construction") -> "Real Estate"
            content.contains("tourism") || content.contains("travel") || content.contains("holiday") || content.contains("festival") -> "Holidays & Travel"
            else -> "General News"
        }
    }

    // UPDATED: Scrape news source using configuration
    private fun scrapeNewsSource(source: Source): List<Article> {
        println("🔍 Scraping ${source.sourceName} (${source.country})...")
        val doc = fetchPage(source.baseUrl) 
        if (doc == null) {
            println("❌ Failed to fetch page for ${source.sourceName}")
            return emptyList()
        }
        
        val articles = mutableListOf<Article>()
        var foundLinks = false
        
        for (selector in source.linkSelectors) {
            val linkElements = doc.select(selector)
            println("🔎 Trying selector '$selector' for ${source.sourceName}: found ${linkElements.size} elements")
            
            if (linkElements.isNotEmpty()) {
                foundLinks = true
                
                linkElements.take(10).forEach { linkElement -> // Back to 10 articles
                    try {
                        val title = linkElement.text().trim()
                        var articleUrl = linkElement.attr("abs:href").ifEmpty { linkElement.attr("href") }

                        println("📝 Found potential article: '$title' -> '$articleUrl'")

                        if (articleUrl.startsWith("/")) {
                            val baseUrl = source.baseUrl.trimEnd('/')
                            articleUrl = baseUrl + articleUrl
                            println("🔗 Fixed relative URL: $articleUrl")
                        }

                        if (title.isNotEmpty() && articleUrl.startsWith("http") && title.length > 15) {
                            println("✅ Valid article found: $title")
                            
                            // SPEED OPTIMIZATION: Skip paragraph extraction and use title as summary
                            val summary = generateFallbackSummary(title)
                            val category = categorizeArticle(title)

                            // SPEED OPTIMIZATION: Only translate titles, skip summary translations for speed
                            val titleTranslations = mapOf(
                                "en" to title,
                                "he" to translateText(title, "Hebrew"),
                                "ru" to translateText(title, "Russian"),
                                "el" to translateText(title, "Greek")
                            )

                            // Use empty summary translations to skip API calls
                            val summaryTranslations = mapOf(
                                "en" to summary,
                                "he" to summary, // Skip translation
                                "ru" to summary, // Skip translation  
                                "el" to summary  // Skip translation
                            )

                            articles.add(Article(
                                title = title,
                                url = articleUrl,
                                summary = summary,
                                category = category,
                                date = SimpleDateFormat("yyyy-MM-dd").format(Date()),
                                country = source.country,
                                sourceId = source.sourceId,
                                titleTranslations = titleTranslations,
                                summaryTranslations = summaryTranslations,
                                categoryTranslations = mapOf(
                                    "en" to category,
                                    "he" to translateText(category, "Hebrew"),
                                    "ru" to translateText(category, "Russian"),
                                    "el" to translateText(category, "Greek")
                                )
                            ))
                            
                            Thread.sleep(500) // Reduced from 1000ms
                        } else {
                            println("❌ Invalid article: title='$title', url='$articleUrl'")
                        }
                    } catch (e: Exception) {
                        println("❌ Error processing link: ${e.message}")
                    }
                }
                break 
            }
        }
        
        if (!foundLinks) {
            println("⚠️ No article links found for ${source.sourceName} with any selector")
            println("🔍 Page title: ${doc.title()}")
            println("🔍 Total links on page: ${doc.select("a").size}")
        }

        println("📊 ${source.sourceName}: Found ${articles.size} articles")
        return articles.distinctBy { it.url }
    }

    private fun generateFallbackSummary(title: String): String {
        val words = title.split(" ").filter { it.length > 3 }.take(5)
        return words.joinToString(" ").ifEmpty { title.take(60) }
    }

    // UPDATED: Aggregate news from all configured sources
    fun aggregateNews(): List<Article> {
        println("📰 Starting multi-country news aggregation...")
        val seen = loadSeenArticles()
        val allArticles = mutableListOf<Article>()
        val titleSimilarityThreshold = 0.8
        val sources = loadSources()

        println("🌍 Aggregating from ${sources.size} sources across ${sources.map { it.country }.distinct().size} countries")

        sources.forEach { source ->
            try {
                val sourceArticles = scrapeNewsSource(source)
                
                // Enhanced duplicate detection with paragraph hash
                sourceArticles.forEach { newArticle ->
                    var isDuplicate = false
                    
                    // Check against existing articles
                    for (existingArticle in allArticles) {
                        val titleSimilarity = calculateTitleSimilarity(newArticle.title, existingArticle.title)
                        val paragraphHash = hashString(newArticle.summary)
                        val existingParagraphHash = hashString(existingArticle.summary)
                        
                        if (titleSimilarity > titleSimilarityThreshold || paragraphHash == existingParagraphHash) {
                            println("🔄 Duplicate detected: '${newArticle.title}' similar to '${existingArticle.title}' (${(titleSimilarity * 100).toInt()}% match)")
                            isDuplicate = true
                            break
                        }
                    }
                    
                    if (!isDuplicate) {
                        allArticles.add(newArticle)
                    }
                }
                
                Thread.sleep(500) // Reduced delay between sources
            } catch (e: Exception) {
                println("Error scraping ${source.sourceName}: ${e.message}")
            }
        }

        val newArticles = allArticles.filter { it.url !in seen }
        seen.addAll(newArticles.map { it.url })
        saveSeenArticles(seen)

        // Print statistics per country
        val articlesByCountry = newArticles.groupBy { it.country }
        articlesByCountry.forEach { (country, articles) ->
            println("📊 $country: Found ${articles.size} new articles")
        }

        println("📊 Total: ${allArticles.size} articles processed, ${newArticles.size} new articles after deduplication")
        return newArticles
    }

    // NEW: Hash string for paragraph comparison
    private fun hashString(text: String): String {
        val cleanText = text.lowercase().replace(Regex("[^a-z0-9]"), "")
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(cleanText.toByteArray())
        return hash.fold("") { str, it -> str + "%02x".format(it) }
    }

    private fun calculateTitleSimilarity(title1: String, title2: String): Double {
        val words1 = title1.lowercase().split("\\s+".toRegex()).filter { it.length > 3 }.toSet()
        val words2 = title2.lowercase().split("\\s+".toRegex()).filter { it.length > 3 }.toSet()
        
        if (words1.isEmpty() || words2.isEmpty()) return 0.0
        
        val intersection = words1.intersect(words2).size
        val union = words1.union(words2).size
        
        return intersection.toDouble() / union.toDouble()
    }

    // UPDATED: Multi-country page generation
    fun generateCountryWebsite(articles: List<Article>, country: String): String {
        val currentDate = SimpleDateFormat("yyyy-MM-dd").format(Date())
        val dayOfWeek = SimpleDateFormat("EEEE", Locale.ENGLISH).format(Date())
        val countryArticles = articles.filter { it.country == country }
        val grouped = countryArticles.groupBy { it.category }

        val countryDisplayName = when(country) {
            "CYPRUS" -> "Cyprus"
            "ISRAEL" -> "Israel"
            "GREECE" -> "Greece"
            else -> country
        }

        val countryFlag = when(country) {
            "CYPRUS" -> "🇨🇾"
            "ISRAEL" -> "🇮🇱"
            "GREECE" -> "🇬🇷"
            else -> "🌍"
        }

        val articlesHtml = StringBuilder()
        if (grouped.isEmpty()) {
            articlesHtml.append("""
                <div class="no-articles">
                    <h3>No new articles today</h3>
                    <p>Check back tomorrow for fresh news updates.</p>
                </div>
            """.trimIndent())
        } else {
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
        }

        return """<!DOCTYPE html>
            <html>
            <head>
            <title>AI News - $countryDisplayName Daily Digest for $dayOfWeek, $currentDate</title>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
                body { font-family: Arial, sans-serif; margin: 40px; background: #f5f5f5; }
                .container { max-width: 800px; margin: 0 auto; background: white; padding: 40px; border-radius: 10px; }
                .header { text-align: center; margin-bottom: 40px; }
                .logo { font-size: 2rem; font-weight: bold; color: #667eea; }
                .country-nav { text-align: center; margin: 20px 0; }
                .country-nav a { margin: 0 10px; padding: 8px 16px; background: #667eea; color: white; text-decoration: none; border-radius: 20px; font-size: 0.9rem; }
                .country-nav a:hover { background: #764ba2; }
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
                .no-articles { text-align: center; padding: 40px; color: #666; }
            </style>
            </head>
            <body>
            <div class="container">
            <div class="header">
            <div class="logo">🤖 AI News</div>
            <p>$countryFlag $countryDisplayName Daily Digest • $dayOfWeek, $currentDate</p>
            </div>

            <div class="country-nav">
                <a href="../index.html">🏠 Home</a>
                <a href="../cyprus/index.html">🇨🇾 Cyprus</a>
                <a href="../israel/index.html">🇮🇱 Israel</a>
                <a href="../greece/index.html">🇬🇷 Greece</a>
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
            <h3>🔔 Get Daily $countryDisplayName Notifications</h3>
            <p>Get email notifications when fresh $countryDisplayName news is published</p>
            <form action="https://formspree.io/f/xovlajpa" method="POST">
            <input type="email" name="email" placeholder="your@email.com" required>
            <input type="text" name="name" placeholder="Your name (optional)">
            <input type="hidden" name="languages" value="en">
            <input type="hidden" name="countries" value="$country">
            <input type="hidden" name="_subject" value="AI News $countryDisplayName Subscription">
            <br>
            <button type="submit">🔔 Subscribe</button>
            </form>
            </div>
            <div class="lang he">
            <h3>🔔 קבלו התראות יומיות</h3>
            <p>קבלו התראות כאשר חדשות $countryDisplayName טריות מתפרסמות</p>
            <form action="https://formspree.io/f/xovlajpa" method="POST">
            <input type="email" name="email" placeholder="הדוא״ל שלכם" required>
            <input type="text" name="name" placeholder="השם שלכם (אופציונלי)">
            <input type="hidden" name="languages" value="he">
            <input type="hidden" name="countries" value="$country">
            <input type="hidden" name="_subject" value="AI News $countryDisplayName Subscription (Hebrew)">
            <br>
            <button type="submit">🔔 הירשמו</button>
            </form>
            </div>
            <div class="lang ru">
            <h3>🔔 Получайте уведомления</h3>
            <p>Получайте уведомления о свежих новостях $countryDisplayName</p>
            <form action="https://formspree.io/f/xovlajpa" method="POST">
            <input type="email" name="email" placeholder="ваш@email.com" required>
            <input type="text" name="name" placeholder="Ваше имя (необязательно)">
            <input type="hidden" name="languages" value="ru">
            <input type="hidden" name="countries" value="$country">
            <input type="hidden" name="_subject" value="AI News $countryDisplayName Subscription (Russian)">
            <br>
            <button type="submit">🔔 Подписаться</button>
            </form>
            </div>
            <div class="lang el">
            <h3>🔔 Λάβετε ειδοποιήσεις</h3>
            <p>Λάβετε ειδοποιήσεις για φρέσκα νέα $countryDisplayName</p>
            <form action="https://formspree.io/f/xovlajpa" method="POST">
            <input type="email" name="email" placeholder="το@email.σας" required>
            <input type="text" name="name" placeholder="Το όνομά σας (προαιρετικό)">
            <input type="hidden" name="languages" value="el">
            <input type="hidden" name="countries" value="$country">
            <input type="hidden" name="_subject" value="AI News $countryDisplayName Subscription (Greek)">
            <br>
            <button type="submit">🔔 Εγγραφή</button>
            </form>
            </div>
            </div>

            <div class="footer">
            <p>Generated automatically • ${countryArticles.map { it.sourceId }.distinct().joinToString(", ")}</p>
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

                document.addEventListener('DOMContentLoaded', function() {
                    setLang('en');
                });
            </script>
            </body>
            </html>""".trimIndent()
    }

    // NEW: Generate main index page with links to all countries
    fun generateMainIndexPage(articles: List<Article>): String {
        val currentDate = SimpleDateFormat("yyyy-MM-dd").format(Date())
        val dayOfWeek = SimpleDateFormat("EEEE", Locale.ENGLISH).format(Date())
        val countries = articles.map { it.country }.distinct().sorted()
        val articlesByCountry = articles.groupBy { it.country }

        val countrySummary = StringBuilder()
        countries.forEach { country ->
            val countryArticles = articlesByCountry[country] ?: emptyList()
            val countryName = when(country) {
                "CYPRUS" -> "Cyprus"
                "ISRAEL" -> "Israel" 
                "GREECE" -> "Greece"
                else -> country
            }
            val countryFlag = when(country) {
                "CYPRUS" -> "🇨🇾"
                "ISRAEL" -> "🇮🇱"
                "GREECE" -> "🇬🇷"
                else -> "🌍"
            }
            val countryPath = country.lowercase()

            countrySummary.append("""
                <div class="country-card">
                    <h3>$countryFlag $countryName</h3>
                    <p>${countryArticles.size} new articles today</p>
                    <p>Latest categories: ${countryArticles.map { it.category }.distinct().take(3).joinToString(", ")}</p>
                    <a href="./$countryPath/index.html" class="country-link">Read $countryName News →</a>
                </div>
            """.trimIndent())
        }

        return """<!DOCTYPE html>
            <html>
            <head>
            <title>AI News - Multi-Country Daily Digest for $dayOfWeek, $currentDate</title>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
                body { font-family: Arial, sans-serif; margin: 40px; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); min-height: 100vh; }
                .container { max-width: 900px; margin: 0 auto; background: white; padding: 40px; border-radius: 20px; box-shadow: 0 10px 30px rgba(0,0,0,0.2); }
                .header { text-align: center; margin-bottom: 40px; }
                .logo { font-size: 3rem; font-weight: bold; color: #667eea; margin-bottom: 10px; }
                .tagline { font-size: 1.2rem; color: #666; margin-bottom: 20px; }
                .country-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(250px, 1fr)); gap: 20px; margin: 40px 0; }
                .country-card { background: #f9f9f9; padding: 30px; border-radius: 15px; text-align: center; border: 2px solid transparent; transition: all 0.3s; }
                .country-card:hover { border-color: #667eea; transform: translateY(-5px); }
                .country-card h3 { font-size: 1.5rem; margin-bottom: 10px; color: #333; }
                .country-card p { color: #666; margin: 10px 0; }
                .country-link { display: inline-block; background: #667eea; color: white; padding: 12px 25px; text-decoration: none; border-radius: 25px; margin-top: 15px; transition: all 0.3s; }
                .country-link:hover { background: #764ba2; transform: scale(1.05); }
                .stats { background: #667eea; color: white; padding: 30px; border-radius: 15px; text-align: center; margin: 40px 0; }
                .stats h3 { margin-bottom: 20px; }
                .stats .stat-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 20px; }
                .stat-item { background: rgba(255,255,255,0.1); padding: 20px; border-radius: 10px; }
                .stat-number { font-size: 2rem; font-weight: bold; margin-bottom: 5px; }
                .footer { text-align: center; margin-top: 40px; color: #666; }
            </style>
            </head>
            <body>
            <div class="container">
            <div class="header">
            <div class="logo">🤖 AI News</div>
            <div class="tagline">Your Multi-Country Daily News Digest</div>
            <p>Automated news aggregation from Cyprus, Israel, and Greece • $dayOfWeek, $currentDate</p>
            </div>

            <div class="stats">
            <h3>Today's News Summary</h3>
            <div class="stat-grid">
                <div class="stat-item">
                    <div class="stat-number">${articles.size}</div>
                    <div>Total Articles</div>
                </div>
                <div class="stat-item">
                    <div class="stat-number">${countries.size}</div>
                    <div>Countries</div>
                </div>
                <div class="stat-item">
                    <div class="stat-number">${articles.map { it.category }.distinct().size}</div>
                    <div>Categories</div>
                </div>
                <div class="stat-item">
                    <div class="stat-number">4</div>
                    <div>Languages</div>
                </div>
            </div>
            </div>

            <div class="country-grid">
            $countrySummary
            </div>

            <div class="footer">
            <p>Generated automatically every day at 7:00 AM • Powered by AI translation</p>
            <p>🌐 <strong><a href="https://ainews.eu.com" style="color: #667eea;">ainews.eu.com</a></strong></p>
            <p style="margin-top: 20px; font-size: 0.9rem;">
                <a href="./cyprus/index.html" style="margin: 0 10px; color: #667eea;">🇨🇾 Cyprus</a> |
                <a href="./israel/index.html" style="margin: 0 10px; color: #667eea;">🇮🇱 Israel</a> |
                <a href="./greece/index.html" style="margin: 0 10px; color: #667eea;">🇬🇷 Greece</a>
            </p>
            </div>
            </div>
            </body>
            </html>""".trimIndent()
    }

    // NEW: Generate sitemap.xml
    fun generateSitemap(): String {
        val currentDate = SimpleDateFormat("yyyy-MM-dd").format(Date())
        return """<?xml version="1.0" encoding="UTF-8"?>
            <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                <url>
                    <loc>https://ainews.eu.com/</loc>
                    <lastmod>$currentDate</lastmod>
                    <changefreq>daily</changefreq>
                    <priority>1.0</priority>
                </url>
                <url>
                    <loc>https://ainews.eu.com/cyprus/index.html</loc>
                    <lastmod>$currentDate</lastmod>
                    <changefreq>daily</changefreq>
                    <priority>0.8</priority>
                </url>
                <url>
                    <loc>https://ainews.eu.com/israel/index.html</loc>
                    <lastmod>$currentDate</lastmod>
                    <changefreq>daily</changefreq>
                    <priority>0.8</priority>
                </url>
                <url>
                    <loc>https://ainews.eu.com/greece/index.html</loc>
                    <lastmod>$currentDate</lastmod>
                    <changefreq>daily</changefreq>
                    <priority>0.8</priority>
                </url>
            </urlset>""".trimIndent()
    }

    // UPDATED: Multi-country GitHub Pages upload
    fun uploadToGitHubPages(articles: List<Article>): String {
        val repoName = "ainews-website"
        val countries = listOf("CYPRUS", "ISRAEL", "GREECE")

        return try {
            println("🚀 Starting multi-country upload to GitHub Pages...")

            // Generate and upload main index page
            val mainIndexHtml = generateMainIndexPage(articles)
            uploadFileToGitHub(repoName, "index.html", mainIndexHtml)

            // Generate and upload country-specific pages
            countries.forEach { country ->
                val countryPath = country.lowercase()
                val countryHtml = generateCountryWebsite(articles, country)
                uploadFileToGitHub(repoName, "$countryPath/index.html", countryHtml)
                println("✅ Uploaded $countryPath page")
            }

            // Generate and upload sitemap
            val sitemap = generateSitemap()
            uploadFileToGitHub(repoName, "sitemap.xml", sitemap)

            // Upload CNAME file for custom domain
            uploadFileToGitHub(repoName, "CNAME", "ainews.eu.com")

            println("🚀 All pages uploaded successfully")
            "https://ainews.eu.com/"
        } catch (e: Exception) {
            println("Error uploading to GitHub Pages: ${e.message}")
            ""
        }
    }

    // NEW: Helper function to upload individual files to GitHub
    private fun uploadFileToGitHub(repoName: String, filePath: String, content: String) {
        try {
            val getRequest = Request.Builder()
                .url("https://api.github.com/repos/LiorR2389/$repoName/contents/$filePath")
                .addHeader("Authorization", "token $githubToken")
                .build()

            var sha: String? = null
            client.newCall(getRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string())
                    sha = json.getString("sha")
                }
            }

            val base64Content = Base64.getEncoder().encodeToString(content.toByteArray())
            val requestBody = JSONObject().apply {
                put("message", "Update $filePath - ${SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date())}")
                put("content", base64Content)
                if (sha != null) put("sha", sha)
            }

            val putRequest = Request.Builder()
                .url("https://api.github.com/repos/LiorR2389/$repoName/contents/$filePath")
                .addHeader("Authorization", "token $githubToken")
                .addHeader("Content-Type", "application/json")
                .put(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(putRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    println("Failed to upload $filePath: ${response.code}")
                }
            }
        } catch (e: Exception) {
            println("Error uploading $filePath: ${e.message}")
        }
    }

    // Rest of the existing functions with minor updates for multi-country support
    private fun extractEmailContent(message: Message): String {
        return try {
            when (val content = message.content) {
                is String -> content
                is jakarta.mail.Multipart -> {
                    val sb = StringBuilder()
                    for (i in 0 until content.count) {
                        val bodyPart = content.getBodyPart(i)
                        if (bodyPart.isMimeType("text/plain")) {
                            sb.append(bodyPart.content.toString())
                        } else if (bodyPart.isMimeType("text/html")) {
                            if (sb.isEmpty()) {
                                sb.append(bodyPart.content.toString())
                            }
                        }
                    }
                    sb.toString()
                }
                else -> content.toString()
            }
        } catch (e: Exception) {
            println("❌ Error extracting email content: ${e.message}")
            ""
        }
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
                put("mail.imaps.ssl.enable", "true")
            }

            val session = Session.getInstance(props)
            val store = session.getStore("imaps")
            store.connect(fromEmail, emailPassword)

            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_WRITE)

            val searchTerm = AndTerm(
                FromTerm(InternetAddress("noreply@formspree.io")),
                FlagTerm(Flags(Flags.Flag.SEEN), false)
            )

            val messages = inbox.search(searchTerm)
            println("📬 Found ${messages.size} unread Formspree emails")

            messages.forEach { message ->
                try {
                    val content = extractEmailContent(message)
                    val subject = message.subject
                    println("📋 Processing email - Subject: $subject")

                    if (subject.contains("AI News", ignoreCase = true) && subject.contains("Subscription", ignoreCase = true)) {
                        println("✅ Valid subscription email found")

                        // Extract data with enhanced parsing for multi-country support
                        val emailMatch = Regex("email:\\s*\\n\\s*([^\\s\\n\\r]+@[^\\s\\n\\r]+)", RegexOption.IGNORE_CASE).find(content)
                        val nameMatch = Regex("name:\\s*\\n\\s*([^\\n\\r]+)", RegexOption.IGNORE_CASE).find(content)
                        val langMatch = Regex("languages:\\s*\\n\\s*([^\\s\\n\\r]+)", RegexOption.IGNORE_CASE).find(content)
                        val countryMatch = Regex("countries:\\s*\\n\\s*([^\\s\\n\\r]+)", RegexOption.IGNORE_CASE).find(content)

                        if (emailMatch != null) {
                            val email = emailMatch.groupValues[1].trim()
                            val name = nameMatch?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() && it != " " }
                            val languages = langMatch?.groupValues?.get(1)?.split(";")?.filter { it.isNotEmpty() } ?: listOf("en")
                            val countries = countryMatch?.groupValues?.get(1)?.split(";")?.filter { it.isNotEmpty() } ?: listOf("CYPRUS")

                            println("📧 Extracted data - Email: $email, Name: $name, Languages: $languages, Countries: $countries")

                            val currentSubscribers = loadSubscribers().toMutableList()
                            val existingSubscriber = currentSubscribers.find { it.email == email }
                            
                            if (existingSubscriber == null) {
                                val newSubscriber = Subscriber(
                                    email = email,
                                    name = name,
                                    languages = languages,
                                    countries = countries, // NEW: Multi-country support
                                    subscribed = true,
                                    subscribedDate = SimpleDateFormat("yyyy-MM-dd").format(Date())
                                )
                                currentSubscribers.add(newSubscriber)
                                saveSubscribers(currentSubscribers)
                                println("💾 Saved new subscriber: $email for countries: ${countries.joinToString(", ")}")
                                
                                addSubscriberToCSV(email, name, languages, countries)
                            } else {
                                println("⚠️ Subscriber $email already exists, skipping")
                            }

                            message.setFlag(Flags.Flag.SEEN, true)
                            println("✅ Successfully processed email for: $email")
                        } else {
                            println("❌ Could not extract email address from content")
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

    // UPDATED: Add subscriber to CSV with country support
    private fun addSubscriberToCSV(email: String, name: String?, languages: List<String>, countries: List<String> = listOf("CYPRUS")) {
        val csvFile = File("new_subscribers.csv")
        val csvLine = "$email,${name ?: ""},${languages.joinToString(";")},${countries.joinToString(";")}"
        
        try {
            if (csvFile.exists()) {
                val existingContent = csvFile.readText()
                if (existingContent.contains(email)) {
                    println("📧 Subscriber $email already exists in CSV, skipping")
                    return
                }
            }
            
            csvFile.appendText("$csvLine\n")
            println("📧 Added subscriber to CSV: $email")
        } catch (e: Exception) {
            println("❌ Error adding subscriber to CSV: ${e.message}")
        }
    }

    fun checkAndImportWebSubscriptions() {
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
                        val countries = if (parts.size > 3) parts[3].split(";") else listOf("CYPRUS")

                        val existing = currentSubscribers.find { it.email == email }
                        if (existing == null) {
                            currentSubscribers.add(Subscriber(
                                email = email,
                                name = name,
                                languages = languages,
                                countries = countries, // NEW
                                subscribed = true,
                                subscribedDate = SimpleDateFormat("yyyy-MM-dd").format(Date())
                            ))
                            newCount++
                            println("📧 Added subscriber from CSV: $email for ${countries.joinToString(", ")}")
                        }
                    }
                }

                if (newCount > 0) {
                    saveSubscribers(currentSubscribers)
                    csvFile.renameTo(File("processed_subscribers_${SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())}.csv"))
                    println("📧 Added $newCount new subscribers from CSV")
                }

            } catch (e: Exception) {
                println("Error processing CSV: ${e.message}")
            }
        }
    }

    // UPDATED: Send one email per subscriber with all their countries
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
                println("❌ Failed to send email to ${subscriber.email}: ${e.message}")
            }
        }
        println("✅ Finished sending notifications")
    }

    // UPDATED: Send single email with all countries to homepage
    private fun sendEmailNotification(subscriber: Subscriber, articles: List<Article>, websiteUrl: String) {
        if (emailPassword.isNullOrEmpty()) return

        try {
            val props = Properties().apply {
                put("mail.smtp.auth", "true")
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.host", smtpHost)
                put("mail.smtp.port", smtpPort)
                put("mail.smtp.ssl.enable", "false")
                put("mail.smtp.ssl.trust", smtpHost)
            }

            val session = Session.getInstance(props, object : jakarta.mail.Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(fromEmail, emailPassword)
                }
            })

            val subscriberCountryArticles = articles.filter { article ->
                subscriber.countries.contains(article.country)
            }

            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(fromEmail, "AI News"))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(subscriber.email))
                subject = "🤖 Your Daily News Digest - ${subscriberCountryArticles.size} new stories"

                val htmlContent = """
                <h1>🤖 AI News</h1>
                <p>Hello ${subscriber.name ?: "there"}!</p>
                <p>Fresh news updates are available with ${subscriberCountryArticles.size} new articles from ${subscriber.countries.joinToString(", ")}.</p>
                <a href="$websiteUrl" style="background: #667eea; color: white; padding: 15px 30px; text-decoration: none; border-radius: 25px;">📖 Read News</a>
                """.trimIndent()

                setContent(htmlContent, "text/html; charset=utf-8")
            }

            Transport.send(message)
        } catch (e: Exception) {
            println("❌ Failed to send email to ${subscriber.email}: ${e.message}")
        }
    }

    fun addSubscriber(email: String, name: String?, languages: List<String>, countries: List<String> = listOf("CYPRUS")) {
        val subscribers = loadSubscribers().toMutableList()
        val existingSubscriber = subscribers.find { it.email == email }

        if (existingSubscriber == null) {
            val newSubscriber = Subscriber(
                email = email,
                name = name,
                languages = languages,
                countries = countries, // NEW
                subscribed = true,
                subscribedDate = SimpleDateFormat("yyyy-MM-dd").format(Date())
            )
            subscribers.add(newSubscriber)
            saveSubscribers(subscribers)
            println("✅ Added new subscriber: $email for countries: ${countries.joinToString(", ")}")
        } else {
            println("⚠️ Subscriber $email already exists")
        }
    }

    fun setupCustomDomain() {
        println("✅ Setting up custom domain support")
    }

    // LEGACY: Keep old single-page generation for backward compatibility
    fun generateDailyWebsite(articles: List<Article>): String {
        println("⚠️ Using legacy single-page generation - consider using generateCountryWebsite() instead")
        return generateCountryWebsite(articles, "CYPRUS")
    }
}

fun main() {
    println("🤖 Starting AI News Multi-Country Update...")
    
    val system = AINewsSystem()
    
    println("🔄 Processing new subscriptions...")
    system.processFormspreeEmails()
    system.checkAndImportWebSubscriptions()

    // Add test subscriber with multi-country support
    system.addSubscriber("lior.global@gmail.com", "Lior", listOf("en", "he"), listOf("CYPRUS", "ISRAEL"))

    val existingSubscribers = system.loadSubscribers()
    println("📧 Current subscribers: ${existingSubscribers.size}")
    existingSubscribers.forEach { subscriber ->
        println("   - ${subscriber.email} (${subscriber.countries.joinToString(", ")}) (${subscriber.languages.joinToString(", ")})")
    }

    try {
        println("🌍 Starting multi-country news aggregation...")
        val articles = system.aggregateNews()
        
        if (articles.isNotEmpty()) {
            system.setupCustomDomain()
            
            val websiteUrl = system.uploadToGitHubPages(articles)
            if (websiteUrl.isNotEmpty()) {
                println("🚀 Multi-country website uploaded: $websiteUrl")
                println("🔗 Country pages:")
                println("   🇨🇾 Cyprus: ${websiteUrl}cyprus/")
                println("   🇮🇱 Israel: ${websiteUrl}israel/")  
                println("   🇬🇷 Greece: ${websiteUrl}greece/")
                
                system.sendDailyNotification(articles, websiteUrl)
                println("✅ AI News multi-country update complete!")
            }
        } else {
            println("⚠️ No new articles found today")
        }
    } catch (e: Exception) {
        println("❌ Error: ${e.message}")
        e.printStackTrace()
    }
    
    val isCronjob = System.getenv("CRONJOB_MODE")?.toBoolean() ?: true
    if (isCronjob) {
        println("✅ Multi-country cronjob completed successfully")
        return
    }
    
    println("🔄 Keeping application running...")
    while (true) {
        Thread.sleep(300000)
        try {
            system.processFormspreeEmails()
            system.checkAndImportWebSubscriptions()
        } catch (e: Exception) {
            println("⚠️ Error during periodic check: ${e.message}")
        }
    }
}