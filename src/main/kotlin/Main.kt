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
    private val sourcesConfigFile = File("sources.json")
    private val translationCacheFile = File("translations.json")

    private val openAiApiKey = System.getenv("OPENAI_API_KEY")
    private val githubToken = System.getenv("GITHUB_TOKEN")
    private val fromEmail = System.getenv("FROM_EMAIL") ?: "hello@ainews.eu.com"
    private val emailPassword = System.getenv("EMAIL_PASSWORD")
    private val smtpHost = System.getenv("SMTP_HOST") ?: "smtp.gmail.com"
    private val smtpPort = System.getenv("SMTP_PORT") ?: "587"

    // NEW: GitHub-based subscriber storage
    private val subscribersRepoName = "ainews-website"
    private val subscribersFilePath = "data/subscribers.json"

    // Load sources from existing sources.json file
    private fun loadSources(): List<Source> {
        return if (sourcesConfigFile.exists()) {
            try {
                val json = sourcesConfigFile.readText()
                val config = gson.fromJson(json, SourceConfig::class.java)
                println("✅ Loaded ${config.sources.size} sources from sources.json")
                config.sources
            } catch (e: Exception) {
                println("❌ Error loading sources.json: ${e.message}")
                emptyList()
            }
        } else {
            println("❌ sources.json file not found!")
            emptyList()
        }
    }

    fun processFormspreeEmails() {
        if (emailPassword.isNullOrEmpty()) {
            println("📧 Email processing disabled - no EMAIL_PASSWORD configured")
            return
        }

        try {
            println("📧 Checking for new subscription emails...")
            
            val props = Properties().apply {
                put("mail.store.protocol", "imaps")
                put("mail.imaps.host", "imap.gmail.com")
                put("mail.imaps.port", "993")
                put("mail.imaps.ssl.enable", "true")
            }

            val session = Session.getDefaultInstance(props)
            val store = session.getStore("imaps")
            store.connect("imap.gmail.com", fromEmail, emailPassword)

            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_WRITE)

            // Search for unread emails from Formspree
            val searchTerm = AndTerm(
                AndTerm(
                    FromTerm(InternetAddress("noreply@formspree.io")),
                    SubjectTerm("AI News")
                ),
                FlagTerm(Flags(Flags.Flag.SEEN), false)
            )

            val messages = inbox.search(searchTerm)
            println("📧 Found ${messages.size} unread subscription emails")

            messages.forEach { message ->
                try {
                    val content = extractEmailContent(message)
                    parseSubscriptionEmail(content)
                    
                    // Mark as read
                    message.setFlag(Flags.Flag.SEEN, true)
                    println("✅ Processed subscription email")
                } catch (e: Exception) {
                    println("❌ Error processing email: ${e.message}")
                }
            }

            inbox.close(false)
            store.close()
            
        } catch (e: Exception) {
            println("❌ Error accessing email: ${e.message}")
        }
    }


    fun checkAndImportWebSubscriptions() {
        try {
            println("📧 Checking for new CSV subscribers...")
            val csvFile = File("new_subscribers.csv")
            
            if (!csvFile.exists() || csvFile.length() == 0L) {
                println("📧 No new subscribers CSV found")
                return
            }

            val csvContent = csvFile.readText().trim()
            if (csvContent.isEmpty()) {
                println("📧 CSV file is empty")
                return
            }

            val lines = csvContent.split('\n').filter { it.isNotBlank() }
            println("📧 Found ${lines.size} potential new subscribers in CSV")

            val currentSubscribers = loadSubscribers().toMutableList()
            var newSubscribersCount = 0

            lines.forEach { line ->
                try {
                    val parts = line.split(',').map { it.trim() }
                    if (parts.size >= 3) {
                        val email = parts[0]
                        val name = if (parts[1].isNotEmpty()) parts[1] else null
                        val languages = if (parts[2].isNotEmpty()) parts[2].split(';') else listOf("en")
                        val countries = if (parts.size > 3 && parts[3].isNotEmpty()) parts[3].split(';') else listOf("CYPRUS")

                        // Check if subscriber already exists
                        if (currentSubscribers.none { it.email.equals(email, ignoreCase = true) }) {
                            val newSubscriber = Subscriber(
                                email = email,
                                name = name,
                                languages = languages,
                                countries = countries,
                                subscribed = true,
                                subscribedDate = SimpleDateFormat("yyyy-MM-dd").format(Date())
                            )
                            currentSubscribers.add(newSubscriber)
                            newSubscribersCount++
                            println("✅ Added new subscriber: $email")
                        } else {
                            println("⚠️ Subscriber $email already exists")
                        }
                    }
                } catch (e: Exception) {
                    println("❌ Error parsing CSV line '$line': ${e.message}")
                }
            }

            if (newSubscribersCount > 0) {
                saveSubscribers(currentSubscribers)
                println("💾 Added $newSubscribersCount new subscribers")
                
                // Clear the CSV file after processing
                csvFile.writeText("")
                println("🗑️ Cleared processed CSV file")
            } else {
                println("📧 No new subscribers to add")
            }

        } catch (e: Exception) {
            println("❌ Error importing web subscriptions: ${e.message}")
        }
    }


   private fun parseSubscriptionEmail(content: String) {
        try {
            // Extract email, name, languages, and countries from Formspree email format
            val emailRegex = Regex("email[:\\s]+([^\\s\\n]+@[^\\s\\n]+)")
            val nameRegex = Regex("name[:\\s]+([^\\n]+)")
            val languagesRegex = Regex("languages[:\\s]+([^\\n]+)")
            val countriesRegex = Regex("countries[:\\s]+([^\\n]+)")

            val emailMatch = emailRegex.find(content)
            val nameMatch = nameRegex.find(content)
            val languagesMatch = languagesRegex.find(content)
            val countriesMatch = countriesRegex.find(content)

            if (emailMatch != null) {
                val email = emailMatch.groupValues[1].trim()
                val name = nameMatch?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
                val languages = languagesMatch?.groupValues?.get(1)?.trim()?.split(",") ?: listOf("en")
                val countries = countriesMatch?.groupValues?.get(1)?.trim()?.split(",") ?: listOf("CYPRUS")

                addSubscriber(email, name, languages, countries)
            } else {
                println("❌ Could not extract email from subscription")
            }
        } catch (e: Exception) {
            println("❌ Error parsing subscription email: ${e.message}")
        }
    }

    // Translation cache management
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

    // Generate cache key for translations
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
        return try {
            println("📧 Loading subscribers from GitHub...")
            
            val githubSubscribers = loadSubscribersFromGitHub()
            if (githubSubscribers.isNotEmpty()) {
                println("✅ Loaded ${githubSubscribers.size} subscribers from GitHub")
                return githubSubscribers
            }
            
            println("⚠️ No subscribers found in GitHub, checking local file...")
            if (subscribersFile.exists()) {
                val json = subscribersFile.readText()
                val type = object : TypeToken<List<Subscriber>>() {}.type
                val localSubscribers = gson.fromJson<List<Subscriber>>(json, type) ?: emptyList()
                
                if (localSubscribers.isNotEmpty()) {
                    println("📤 Migrating ${localSubscribers.size} local subscribers to GitHub...")
                    saveSubscribersToGitHub(localSubscribers)
                }
                
                localSubscribers
            } else {
                println("📧 No subscribers found anywhere, starting fresh")
                emptyList()
            }
        } catch (e: Exception) {
            println("❌ Error loading subscribers: ${e.message}")
            emptyList()
        }
    }

    private fun loadSubscribersFromGitHub(): List<Subscriber> {
        if (githubToken.isNullOrEmpty()) {
            println("⚠️ No GitHub token, cannot load subscribers from GitHub")
            return emptyList()
        }
        
        return try {
            val request = Request.Builder()
                .url("https://api.github.com/repos/LiorR2389/$subscribersRepoName/contents/$subscribersFilePath")
                .addHeader("Authorization", "token $githubToken")
                .addHeader("Accept", "application/vnd.github.v3+json")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string())
                    val base64Content = json.getString("content")
                    val decodedContent = String(Base64.getDecoder().decode(base64Content.replace("\n", "")))
                    
                    val type = object : TypeToken<List<Subscriber>>() {}.type
                    gson.fromJson<List<Subscriber>>(decodedContent, type) ?: emptyList()
                } else if (response.code == 404) {
                    println("📧 Subscribers file not found in GitHub (this is normal for first run)")
                    emptyList()
                } else {
                    println("❌ Failed to load subscribers from GitHub: ${response.code}")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            println("❌ Error loading subscribers from GitHub: ${e.message}")
            emptyList()
        }
    }

    private fun saveSubscribers(subscribers: List<Subscriber>) {
        try {
            saveSubscribersToGitHub(subscribers)
            subscribersFile.writeText(gson.toJson(subscribers))
            println("💾 Saved ${subscribers.size} subscribers to both GitHub and local backup")
        } catch (e: Exception) {
            println("❌ Error saving subscribers: ${e.message}")
        }
    }

    private fun saveSubscribersToGitHub(subscribers: List<Subscriber>) {
        if (githubToken.isNullOrEmpty()) {
            println("⚠️ No GitHub token, cannot save subscribers to GitHub")
            return
        }
        
        try {
            val getRequest = Request.Builder()
                .url("https://api.github.com/repos/LiorR2389/$subscribersRepoName/contents/$subscribersFilePath")
                .addHeader("Authorization", "token $githubToken")
                .addHeader("Accept", "application/vnd.github.v3+json")
                .build()

            var sha: String? = null
            client.newCall(getRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string())
                    sha = json.getString("sha")
                    println("📧 Found existing subscribers file in GitHub")
                } else if (response.code == 404) {
                    println("📧 Creating new subscribers file in GitHub")
                } else {
                    println("⚠️ Unexpected response when checking for existing subscribers file: ${response.code}")
                }
            }

            val subscribersJson = gson.toJson(subscribers)
            val base64Content = Base64.getEncoder().encodeToString(subscribersJson.toByteArray())
            
            val requestBodyJson = JSONObject()
            requestBodyJson.put("message", "Update subscribers - ${SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date())}")
            requestBodyJson.put("content", base64Content)
            if (sha != null) {
                requestBodyJson.put("sha", sha!!)
            }

            val putRequest = Request.Builder()
                .url("https://api.github.com/repos/LiorR2389/$subscribersRepoName/contents/$subscribersFilePath")
                .addHeader("Authorization", "token $githubToken")
                .addHeader("Content-Type", "application/json")
                .put(requestBodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(putRequest).execute().use { response ->
                if (response.isSuccessful) {
                    println("✅ Successfully saved ${subscribers.size} subscribers to GitHub")
                } else {
                    println("❌ Failed to save subscribers to GitHub: ${response.code}")
                    val errorBody = response.body?.string()
                    println("❌ Error details: $errorBody")
                }
            }
        } catch (e: Exception) {
            println("❌ Error saving subscribers to GitHub: ${e.message}")
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

    private fun extractFirstParagraph(articleUrl: String, source: Source): String {
        return try {
            val doc = fetchPage(articleUrl)
            if (doc != null) {
                for (selector in source.paragraphSelectors) {
                    val paragraphElements = doc.select(selector)
                    
                    for (element in paragraphElements.take(3)) {
                        val text = element.text().trim()
                        if (text.isNotEmpty() && text.length > 50) {
                            return cleanParagraphText(text)
                        }
                    }
                }
                
                val fallbackParagraphs = doc.select("p")
                for (element in fallbackParagraphs.take(10)) {
                    val text = element.text().trim()
                    if (text.isNotEmpty() && text.length > 80 && !text.contains("cookie", ignoreCase = true)) {
                        return cleanParagraphText(text)
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
            .replace(Regex("^(NICOSIA|LIMASSOL|LARNACA|PAPHOS|FAMAGUSTA|ATHENS|THESSALONIKI|TEL AVIV|JERUSALEM|HAIFA|GAZA)[\\s\\-,]+", RegexOption.IGNORE_CASE), "") 
            .replace(Regex("^(Reuters|AP|Bloomberg|AFP|By\\s+\\w+)[\\s\\-,]+", RegexOption.IGNORE_CASE), "") 
            .replace(Regex("\\(.*?\\)"), "") 
            .replace(Regex("\\[.*?\\]"), "") 
            .replace(Regex("^[\\s\\-•]+"), "")
            .trim()
            .take(400)
            .let { if (it.length == 400) "$it..." else it }
    }

    private fun isCachedTranslationFailure(translation: String, targetLanguage: String): Boolean {
        val failureIndicators = when (targetLanguage) {
            "Hebrew" -> listOf(
                "תרגום נכשל",
                "חריגה ממגבלת קצב", 
                "תרגום לא זמין",
                "כותרת בעברית"
            )
            "Russian" -> listOf(
                "перевод не удался",
                "превышен лимит скорости",
                "заголовок на русском"
            )
            "Greek" -> listOf(
                "η μετάφραση απέτυχε",
                "υπέρβαση ορίου ρυθμού",
                "τίτλος στα ελληνικά"
            )
            else -> listOf("translation failed", "rate limit", "translation unavailable")
        }
        
        return failureIndicators.any { translation.lowercase().contains(it.lowercase()) }
    }

    private fun getSimpleFallback(text: String, targetLanguage: String): String {
        return when (targetLanguage) {
            "Hebrew" -> translateKeywords(text, "Hebrew")
            "Russian" -> translateKeywords(text, "Russian") 
            "Greek" -> translateKeywords(text, "Greek")
            else -> text
        }
    }

    private fun translateKeywords(text: String, targetLanguage: String): String {
        if (targetLanguage == "English") return text
        
        val keywordMaps = when (targetLanguage) {
            "Hebrew" -> mapOf(
                "police" to "משטרה",
                "arrested" to "נעצר", 
                "detained" to "נעצר",
                "fire" to "שריפה",
                "accident" to "תאונה",
                "hospital" to "בית חולים",
                "court" to "בית משפט",
                "bank" to "בנק",
                "government" to "ממשלה",
                "minister" to "שר",
                "president" to "נשיא",
                "parliament" to "פרלמנט",
                "Cyprus" to "קפריסין",
                "Limassol" to "לימסול",
                "Nicosia" to "ניקוסיה", 
                "Larnaca" to "לרנקה",
                "Paphos" to "פאפוס",
                "euro" to "יורו",
                "euros" to "יורו",
                "temperature" to "טמפרטורה",
                "weather" to "מזג אויר",
                "Technology" to "טכנולוגיה",
                "Politics" to "פוליטיקה",
                "Business & Economy" to "עסקים וכלכלה",
                "Crime & Justice" to "פשע וצדק",
                "General News" to "חדשות כלליות",
                "Holidays & Travel" to "חגים ונסיעות"
            )
            "Russian" -> mapOf(
                "police" to "полиция",
                "arrested" to "арестован", 
                "detained" to "задержан",
                "fire" to "пожар",
                "accident" to "авария",
                "hospital" to "больница",
                "court" to "суд",
                "bank" to "банк",
                "government" to "правительство",
                "minister" to "министр",
                "president" to "президент",
                "parliament" to "парламент",
                "Cyprus" to "Кипр",
                "Limassol" to "Лимассол",
                "Nicosia" to "Никосия",
                "Larnaca" to "Ларнака",
                "Paphos" to "Пафос",
                "euro" to "евро",
                "euros" to "евро",
                "temperature" to "температура",
                "weather" to "погода",
                "Technology" to "Технология",
                "Politics" to "Политика", 
                "Business & Economy" to "Бизнес и экономика",
                "Crime & Justice" to "Преступление и правосудие",
                "General News" to "Общие новости",
                "Holidays & Travel" to "Праздники и путешествия"
            )
            "Greek" -> mapOf(
                "police" to "αστυνομία",
                "arrested" to "συνελήφθη",
                "detained" to "κρατήθηκε", 
                "fire" to "φωτιά",
                "accident" to "ατύχημα",
                "hospital" to "νοσοκομείο",
                "court" to "δικαστήριο",
                "bank" to "τράπεζα",
                "government" to "κυβέρνηση",
                "minister" to "υπουργός",
                "president" to "πρόεδρος",
                "parliament" to "κοινοβούλιο",
                "Cyprus" to "Κύπρος",
                "Limassol" to "Λεμεσός",
                "Nicosia" to "Λευκωσία",
                "Larnaca" to "Λάρνακα",
                "Paphos" to "Πάφος", 
                "euro" to "ευρώ",
                "euros" to "ευρώ",
                "temperature" to "θερμοκρασία",
                "weather" to "καιρός",
                "Technology" to "Τεχνολογία",
                "Politics" to "Πολιτική",
                "Business & Economy" to "Επιχειρήσεις & Οικονομία",
                "Crime & Justice" to "Έγκλημα & Δικαιοσύνη", 
                "General News" to "Γενικές Ειδήσεις",
                "Holidays & Travel" to "Διακοπές & Ταξίδια"
            )
            else -> emptyMap()
        }
        
        var translatedText = text
        keywordMaps.forEach { (english, translated) ->
            translatedText = translatedText.replace(english, translated, ignoreCase = true)
        }
        
        return translatedText
    }

    private fun translateText(text: String, targetLanguage: String, sourceLanguage: String = "English"): String {
        if (openAiApiKey.isNullOrEmpty()) {
            return getSimpleFallback(text, targetLanguage)
        }

        val cache = loadTranslationCache()
        val cacheKey = generateCacheKey(text + sourceLanguage, targetLanguage)
        
        if (cache.containsKey(cacheKey)) {
            val cachedTranslation = cache[cacheKey]!!
            if (!isCachedTranslationFailure(cachedTranslation, targetLanguage)) {
                println("✅ Using cached translation for: ${text.take(50)}...")
                return cachedTranslation
            }
        }

        val lastApiCallFile = File("last_api_call.txt")
        if (lastApiCallFile.exists()) {
            try {
                val lastCall = lastApiCallFile.readText().toLongOrNull() ?: 0
                val timeSinceLastCall = System.currentTimeMillis() - lastCall
                val minimumDelay = 2000L
                
                if (timeSinceLastCall < minimumDelay) {
                    val waitTime = minimumDelay - timeSinceLastCall
                    println("⏰ Rate limiting: waiting ${waitTime}ms before API call...")
                    Thread.sleep(waitTime)
                }
            } catch (e: Exception) {
                // Ignore file errors
            }
        }

        var retryCount = 0
        val maxRetries = 2
        
        while (retryCount < maxRetries) {
            val translation = attemptTranslation(text, targetLanguage, sourceLanguage)
            
            if (translation.contains("rate limit") || translation.contains("חריגה ממגבלת קצב") || 
                translation.contains("תרגום נכשל") || translation == text) {
                retryCount++
                if (retryCount < maxRetries) {
                    val backoffDelay = (retryCount * 2000L)
                    println("⚠️ Rate limited (attempt $retryCount/$maxRetries), backing off for ${backoffDelay}ms...")
                    Thread.sleep(backoffDelay)
                    continue
                } else {
                    println("❌ Max retries reached for $targetLanguage translation")
                    break
                }
            }
            
            try {
                lastApiCallFile.writeText(System.currentTimeMillis().toString())
            } catch (e: Exception) {
                // Ignore file errors
            }
            
            cache[cacheKey] = translation
            saveTranslationCache(cache)
            
            return translation
        }
        
        val fallbackResult = getSimpleFallback(text, targetLanguage)
        
        println("❌ Using fallback translation for $targetLanguage")
        return fallbackResult
    }

private fun attemptTranslation(text: String, targetLanguage: String, sourceLanguage: String): String {
        return try {
            // Clean and validate input text
            val cleanText = text.trim()
                .replace("\"", "'") // Replace quotes that might break JSON
                .replace("\n", " ") // Replace newlines
                .replace("\r", " ") // Replace carriage returns
                .replace("\t", " ") // Replace tabs
                .replace(Regex("\\s+"), " ") // Normalize whitespace
                .take(3000) // Limit length to avoid token limits
            
            if (cleanText.isEmpty() || cleanText.length < 3) {
                println("⚠️ Text too short or empty after cleaning, returning original")
                return text
            }

            // Escape text for JSON
            val escapedText = cleanText
                .replace("\\", "\\\\") // Escape backslashes first
                .replace("\"", "\\\"") // Escape quotes
                .replace("\b", "\\b")
                .replace("\u000C", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")

            val systemPrompt = "You are a professional translator. Translate ONLY the provided text from $sourceLanguage to $targetLanguage. Provide ONLY the translation, no explanations or additional text."

            val userPrompt = when (sourceLanguage.lowercase()) {
                "hebrew" -> "Translate this Hebrew text to $targetLanguage: $escapedText"
                "russian" -> "Translate this Russian text to $targetLanguage: $escapedText"
                "greek" -> "Translate this Greek text to $targetLanguage: $escapedText"
                else -> "Translate this $sourceLanguage text to $targetLanguage: $escapedText"
            }

            // Build JSON manually to avoid escaping issues
            val requestBody = """
                {
                  "model": "gpt-4o-mini",
                  "messages": [
                    {"role": "system", "content": "$systemPrompt"},
                    {"role": "user", "content": "$userPrompt"}
                  ],
                  "temperature": 0.1,
                  "max_tokens": 300
                }
            """.trimIndent()

            // Validate JSON structure
            try {
                JSONObject(requestBody) // Test if JSON is valid
            } catch (e: Exception) {
                println("❌ Invalid JSON structure, falling back")
                return text
            }

            val request = Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .addHeader("Authorization", "Bearer $openAiApiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                
                if (response.isSuccessful && responseBody != null) {
                    try {
                        val json = JSONObject(responseBody)
                        val translation = json.getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")
                            .trim()
                        
                        if (translation.isNotEmpty() && translation != cleanText) {
                            println("🔄 Translation API response for $sourceLanguage->$targetLanguage: '${translation.take(50)}...'")
                            return translation
                        } else {
                            println("⚠️ Empty or identical translation received")
                            return text
                        }
                    } catch (e: Exception) {
                        println("❌ Error parsing translation response: ${e.message}")
                        return text
                    }
                } else {
                    println("❌ Translation API failed with code: ${response.code}")
                    if (responseBody != null && responseBody.length < 500) {
                        println("❌ Error response: $responseBody")
                    }
                    
                    // Handle specific error codes
                    when (response.code) {
                        400 -> {
                            println("❌ Bad request - likely malformed content or JSON")
                            return text
                        }
                        429 -> {
                            println("⏰ Rate limited by OpenAI")
                            return "rate limit"
                        }
                        else -> return text
                    }
                }
            }
        } catch (e: Exception) {
            println("❌ Translation error for $targetLanguage from $sourceLanguage: ${e.message}")
            return text
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
                
                linkElements.take(10).forEach { linkElement -> 
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
                            
                            val paragraph = extractFirstParagraph(articleUrl, source)
                            val summary = if (paragraph.isNotEmpty()) paragraph else generateFallbackSummary(title)
                            val category = categorizeArticle(title)

                            val titleTranslations = mutableMapOf<String, String>()
                            titleTranslations["en"] = title
                            titleTranslations["he"] = translateText(title, "Hebrew", source.sourceLanguage)
                            titleTranslations["ru"] = translateText(title, "Russian", source.sourceLanguage)
                            titleTranslations["el"] = translateText(title, "Greek", source.sourceLanguage)

                            val summaryTranslations = mutableMapOf<String, String>()
                            summaryTranslations["en"] = summary
                            summaryTranslations["he"] = translateText(summary, "Hebrew", source.sourceLanguage)
                            summaryTranslations["ru"] = translateText(summary, "Russian", source.sourceLanguage)
                            summaryTranslations["el"] = translateText(summary, "Greek", source.sourceLanguage)

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
                                    "he" to translateText(category, "Hebrew", source.sourceLanguage),
                                    "ru" to translateText(category, "Russian", source.sourceLanguage),
                                    "el" to translateText(category, "Greek", source.sourceLanguage)
                                )
                            ))
                            
                            Thread.sleep(2000) 
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
        return when {
            title.length <= 150 -> title
            else -> {
                val truncated = title.take(150)
                val lastSpace = truncated.lastIndexOf(' ')
                if (lastSpace > 100) {
                    truncated.substring(0, lastSpace) + "..."
                } else {
                    truncated + "..."
                }
            }
        }
    }

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
                
                sourceArticles.forEach { newArticle ->
                    var isDuplicate = false
                    
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
                
                Thread.sleep(500) 
            } catch (e: Exception) {
                println("Error scraping ${source.sourceName}: ${e.message}")
            }
        }

        val newArticles = allArticles.filter { it.url !in seen }
        seen.addAll(newArticles.map { it.url })
        saveSeenArticles(seen)

        val articlesByCountry = newArticles.groupBy { it.country }
        articlesByCountry.forEach { (country, articles) ->
            println("📊 $country: Found ${articles.size} new articles")
        }

        println("📊 Total: ${allArticles.size} articles processed, ${newArticles.size} new articles after deduplication")
        return newArticles
    }

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
                        <span class="lang he" dir="rtl">${translateText(category, "Hebrew", "English")}</span>
                        <span class="lang ru">${translateText(category, "Russian", "English")}</span>
                        <span class="lang el">${translateText(category, "Greek", "English")}</span>
                    </h2>
                """.trimIndent())

                items.forEach { article ->
                    articlesHtml.append("""
                        <div class="article">
                            <div class="lang en active">
                                <h3>${article.titleTranslations["en"] ?: article.title}</h3>
                                <p>${article.summaryTranslations["en"] ?: article.summary}</p>
                                <a href="${article.url}" target="_blank" rel="noopener noreferrer">Read more</a>
                            </div>
                            <div class="lang he" dir="rtl">
                                <h3 dir="rtl">${article.titleTranslations["he"] ?: "כותרת בעברית"}</h3>
                                <p dir="rtl">${article.summaryTranslations["he"] ?: "תקציר בעברית"}</p>
                                <a href="${article.url}" target="_blank" rel="noopener noreferrer" dir="rtl">קרא עוד</a>
                            </div>
                            <div class="lang ru">
                                <h3>${article.titleTranslations["ru"] ?: "Заголовок на русском"}</h3>
                                <p>${article.summaryTranslations["ru"] ?: "Краткое изложение на русском"}</p>
                                <a href="${article.url}" target="_blank" rel="noopener noreferrer">Читать далее</a>
                            </div>
                            <div class="lang el">
                                <h3>${article.titleTranslations["el"] ?: "Τίτλος στα ελληνικά"}</h3>
                                <p>${article.summaryTranslations["el"] ?: "Περίληψη στα ελληνικά"}</p>
                                <a href="${article.url}" target="_blank" rel="noopener noreferrer">Διαβάστε περισσότερα</a>
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
                body { 
                    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Arial, sans-serif; 
                    margin: 0;
                    padding: 20px; 
                    background: #f5f5f5; 
                    line-height: 1.6;
                }
                
                .container { 
                    max-width: 800px; 
                    margin: 0 auto; 
                    background: white; 
                    padding: 20px;
                    border-radius: 12px; 
                    box-shadow: 0 4px 6px rgba(0,0,0,0.1);
                }
                
                .header { 
                    text-align: center; 
                    margin-bottom: 30px; 
                }
                
                .logo { 
                    font-size: 2.5rem; 
                    font-weight: bold; 
                    color: #667eea; 
                    margin-bottom: 10px;
                }
                
                .country-nav { 
                    text-align: center; 
                    margin: 20px 0; 
                    display: flex;
                    flex-wrap: wrap;
                    justify-content: center;
                    gap: 10px;
                }
                
                .country-nav a { 
                    padding: 12px 16px; 
                    background: #667eea; 
                    color: white; 
                    text-decoration: none; 
                    border-radius: 25px; 
                    font-size: 0.9rem;
                    min-width: 100px;
                    text-align: center;
                    transition: all 0.3s ease;
                }
                
                .country-nav a:hover { 
                    background: #764ba2; 
                    transform: translateY(-2px);
                }
                
                .live-news-link {
                    background: linear-gradient(135deg, #ff6b6b 0%, #ee5a24 100%) !important;
                    animation: pulse 2s infinite;
                }
                
                .live-news-link:hover {
                    background: linear-gradient(135deg, #ee5a24 0%, #d63031 100%) !important;
                }
                
                @keyframes pulse {
                    0% { box-shadow: 0 0 0 0 rgba(255, 107, 107, 0.7); }
                    70% { box-shadow: 0 0 0 10px rgba(255, 107, 107, 0); }
                    100% { box-shadow: 0 0 0 0 rgba(255, 107, 107, 0); }
                }
                
                .lang-buttons { 
                    text-align: center; 
                    margin: 30px 0; 
                    display: flex;
                    flex-wrap: wrap;
                    justify-content: center;
                    gap: 8px;
                }
                
                .lang-buttons button { 
                    padding: 10px 16px; 
                    border: none; 
                    border-radius: 20px; 
                    background: #667eea; 
                    color: white; 
                    cursor: pointer; 
                    font-size: 0.9rem;
                    min-width: 80px;
                    transition: all 0.3s ease;
                }
                
                .lang-buttons button.active { 
                    background: #764ba2; 
                    transform: scale(1.05);
                }
                
                .lang-buttons button:hover { 
                    background: #764ba2; 
                }
                
                .lang { display: none; }
                .lang.active { display: block; }
                
                .lang.he { 
                    direction: rtl; 
                    text-align: right; 
                    font-family: 'Arial', 'Tahoma', 'Noto Sans Hebrew', sans-serif; 
                }
                
                .lang.he h2, .lang.he h3 { 
                    text-align: right; 
                    direction: rtl; 
                }
                
                .lang.he p { 
                    text-align: right; 
                    direction: rtl; 
                }
                
                .lang.he a { 
                    direction: rtl;
                    text-align: right;
                    display: inline-block;
                    margin-top: 10px;
                    float: right;
                    clear: both;
                }
                
                .article { 
                    margin: 20px 0; 
                    padding: 24px; 
                    border-left: 4px solid #667eea; 
                    background: #f9f9f9; 
                    border-radius: 8px;
                    transition: all 0.3s ease;
                }
                
                .article:hover {
                    transform: translateY(-2px);
                    box-shadow: 0 4px 12px rgba(0,0,0,0.1);
                }
                
                .article.he { 
                    border-right: 4px solid #667eea; 
                    border-left: none; 
                }
                
                .article h3 { 
                    margin: 0 0 15px 0; 
                    color: #2d3748; 
                    font-size: 1.3rem;
                    font-weight: 600;
                    line-height: 1.4;
                }
                
                .article p { 
                    color: #4a5568; 
                    margin: 15px 0; 
                    font-size: 1rem;
                    line-height: 1.6;
                    display: block;
                    word-wrap: break-word;
                    overflow-wrap: break-word;
                }
                
                .article a { 
                    color: #667eea; 
                    text-decoration: none; 
                    font-weight: 600; 
                    display: inline-block;
                    margin-top: 10px;
                    padding: 8px 16px;
                    background: rgba(102, 126, 234, 0.1);
                    border-radius: 20px;
                    transition: all 0.3s ease;
                }
                
                .article a:hover {
                    background: #667eea;
                    color: white;
                    transform: translateY(-1px);
                }
                
                .footer { 
                    text-align: center; 
                    margin-top: 40px; 
                    color: #718096;
                    padding: 20px 0;
                    border-top: 1px solid #e2e8f0;
                }
                
                .subscription { 
                    background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); 
                    color: white; 
                    padding: 30px; 
                    margin: 40px 0; 
                    border-radius: 12px; 
                    text-align: center; 
                }
                
                .subscription input { 
                    padding: 12px 16px; 
                    margin: 10px; 
                    border: none; 
                    border-radius: 8px; 
                    width: 100%;
                    max-width: 300px;
                    font-size: 1rem;
                }
                
                .subscription button { 
                    background: #FFD700; 
                    color: #333; 
                    border: none; 
                    padding: 12px 24px; 
                    border-radius: 8px; 
                    cursor: pointer; 
                    font-weight: bold; 
                    font-size: 1rem;
                    transition: all 0.3s ease;
                }
                
                .subscription button:hover {
                    background: #FFC700;
                    transform: translateY(-2px);
                }
                
                .subscription .lang.he { 
                    direction: rtl; 
                    text-align: right; 
                }
                
                .subscription .lang.he input { 
                    text-align: right; 
                    direction: rtl; 
                }
                
                .no-articles { 
                    text-align: center; 
                    padding: 60px 20px; 
                    color: #718096; 
                    background: #f7fafc;
                    border-radius: 12px;
                    margin: 20px 0;
                }
                
                .back-to-top {
                    position: fixed;
                    bottom: 30px;
                    right: 30px;
                    background: #667eea;
                    color: white;
                    border: none;
                    border-radius: 50%;
                    width: 50px;
                    height: 50px;
                    font-size: 20px;
                    cursor: pointer;
                    display: none;
                    z-index: 1000;
                    transition: all 0.3s ease;
                    box-shadow: 0 4px 12px rgba(0,0,0,0.15);
                }
                
                .back-to-top:hover {
                    background: #764ba2;
                    transform: translateY(-3px);
                }
                
                .back-to-top.visible {
                    display: block;
                }
                
                h2 {
                    color: #2d3748;
                    font-size: 1.5rem;
                    font-weight: 700;
                    margin: 30px 0 20px 0;
                    padding-bottom: 10px;
                    border-bottom: 2px solid #e2e8f0;
                }
                
                @media (max-width: 768px) {
                    body {
                        padding: 10px;
                    }
                    
                    .container {
                        padding: 15px;
                        border-radius: 8px;
                    }
                    
                    .logo {
                        font-size: 2rem;
                    }
                    
                    .country-nav {
                        flex-direction: column;
                        align-items: center;
                    }
                    
                    .country-nav a {
                        width: 90%;
                        max-width: 300px;
                        margin: 5px 0;
                        padding: 15px;
                        font-size: 1rem;
                    }
                    
                    .lang-buttons {
                        flex-direction: column;
                        align-items: center;
                    }
                    
                    .lang-buttons button {
                        width: 90%;
                        max-width: 200px;
                        margin: 5px 0;
                        padding: 12px;
                        font-size: 1rem;
                    }
                    
                    .article {
                        margin: 15px 0;
                        padding: 20px;
                    }
                    
                    .article h3 {
                        font-size: 1.2rem;
                    }
                    
                    .article p {
                        font-size: 0.95rem;
                    }
                    
                    .subscription {
                        padding: 25px 15px;
                        margin: 30px 0;
                    }
                    
                    .subscription input {
                        width: 90%;
                        margin: 8px 0;
                        font-size: 16px;
                    }
                    
                    .subscription button {
                        width: 90%;
                        padding: 15px;
                        margin: 10px 0;
                        font-size: 1rem;
                    }
                    
                    .back-to-top {
                        bottom: 20px;
                        right: 20px;
                        width: 45px;
                        height: 45px;
                    }
                    
                    h2 {
                        font-size: 1.3rem;
                        margin: 25px 0 15px 0;
                    }
                }
                
                @media (max-width: 480px) {
                    .container {
                        padding: 10px;
                    }
                    
                    .logo {
                        font-size: 1.8rem;
                    }
                    
                    .article {
                        padding: 15px;
                    }
                    
                    .subscription {
                        padding: 20px 10px;
                    }
                }
            </style>
            </head>
            <body>
            <div class="container">
            <div class="header">
            <div class="logo">AI News</div>
            <p>$countryFlag $countryDisplayName Daily Digest • $dayOfWeek, $currentDate</p>
            </div>

            <div class="country-nav">
                <a href="../index.html">🏠 Home</a>
                <a href="../cyprus/index.html">🇨🇾 Cyprus</a>
                <a href="../israel/index.html">🇮🇱 Israel</a>
                <a href="../greece/index.html">🇬🇷 Greece</a>
                <a href="https://ainews.eu.com/live/" class="live-news-link" target="_blank" rel="noopener noreferrer">🔴 Live News</a>
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
            
            <button class="back-to-top" id="backToTop" onclick="scrollToTop()" title="Back to top">↑</button>

            <script>
                let currentLang = 'en';

                function setLang(lang) {
                    document.querySelectorAll('.lang').forEach(el => el.classList.remove('active'));
                    document.querySelectorAll('.lang.' + lang).forEach(el => el.classList.add('active'));
                    document.querySelectorAll('.lang-buttons button').forEach(btn => btn.classList.remove('active'));
                    document.getElementById('btn-' + lang).classList.add('active');
                    currentLang = lang;
                }

                function scrollToTop() {
                    window.scrollTo({
                        top: 0,
                        behavior: 'smooth'
                    });
                }

                function toggleBackToTopButton() {
                    const backToTopButton = document.getElementById('backToTop');
                    if (window.pageYOffset > 300) {
                        backToTopButton.classList.add('visible');
                    } else {
                        backToTopButton.classList.remove('visible');
                    }
                }

                document.addEventListener('DOMContentLoaded', function() {
                    setLang('en');
                    
                    window.addEventListener('scroll', toggleBackToTopButton);
                    
                    document.querySelectorAll('a[target="_blank"]').forEach(link => {
                        link.addEventListener('click', function() {
                            this.style.opacity = '0.7';
                            setTimeout(() => {
                                this.style.opacity = '1';
                            }, 1000);
                        });
                    });
                    
                    document.addEventListener('keydown', function(e) {
                        if (e.key === 't' || e.key === 'T') {
                            if (!e.ctrlKey && !e.altKey && !e.metaKey) {
                                e.preventDefault();
                                scrollToTop();
                            }
                        }
                        
                        if (e.key >= '1' && e.key <= '4' && !e.ctrlKey && !e.altKey && !e.metaKey) {
                            e.preventDefault();
                            const langs = ['en', 'he', 'ru', 'el'];
                            const langIndex = parseInt(e.key) - 1;
                            if (langs[langIndex]) {
                                setLang(langs[langIndex]);
                            }
                        }
                    });
                });
            </script>
            </body>
            </html>""".trimIndent()
    }

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
                
                .live-site-button {
                    display: inline-block;
                    background: linear-gradient(135deg, #ff6b6b 0%, #ee5a24 100%);
                    color: white;
                    padding: 16px 32px;
                    text-decoration: none;
                    border-radius: 25px;
                    font-weight: 600;
                    font-size: 1.2rem;
                    transition: all 0.3s ease;
                    box-shadow: 0 4px 12px rgba(255, 107, 107, 0.4);
                    animation: pulse 2s infinite;
                }
                
                @keyframes pulse {
                    0% { box-shadow: 0 4px 12px rgba(255, 107, 107, 0.4); }
                    50% { box-shadow: 0 6px 20px rgba(255, 107, 107, 0.6); }
                    100% { box-shadow: 0 4px 12px rgba(255, 107, 107, 0.4); }
                }
                
                .live-site-button:hover {
                    transform: translateY(-2px);
                    box-shadow: 0 8px 25px rgba(255, 107, 107, 0.5);
                    background: linear-gradient(135deg, #ee5a24 0%, #d63031 100%);
                }
                
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
            <div class="logo">AI News</div>
            <div class="tagline">Your Multi-Country Daily News Digest</div>
            <p>Automated news aggregation from Cyprus, Israel, and Greece • $dayOfWeek, $currentDate</p>
            <div style="margin-top: 30px;">
                <a href="https://ainews.eu.com/live/" class="live-site-button">🔴 LIVE Breaking News</a>
            </div>
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

    fun uploadToGitHubPages(articles: List<Article>): String {
        val repoName = "ainews-website"
        val countries = listOf("CYPRUS", "ISRAEL", "GREECE")

        return try {
            println("🚀 Starting multi-country upload to GitHub Pages...")

            val mainIndexHtml = generateMainIndexPage(articles)
            uploadFileToGitHub(repoName, "index.html", mainIndexHtml)

            countries.forEach { country ->
                val countryPath = country.lowercase()
                val countryHtml = generateCountryWebsite(articles, country)
                uploadFileToGitHub(repoName, "$countryPath/index.html", countryHtml)
                println("✅ Uploaded $countryPath page")
            }

            val sitemap = generateSitemap()
            uploadFileToGitHub(repoName, "sitemap.xml", sitemap)

            uploadFileToGitHub(repoName, "CNAME", "ainews.eu.com")

            println("🚀 All pages uploaded successfully")
            "https://ainews.eu.com/"
        } catch (e: Exception) {
            println("Error uploading to GitHub Pages: ${e.message}")
            ""
        }
    }

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
                if (sha != null) put("sha", sha!!)
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

            // Filter articles by subscriber's countries
            val subscriberCountryArticles = articles.filter { article ->
                subscriber.countries.contains(article.country)
            }

            if (subscriberCountryArticles.isEmpty()) {
                println("📧 No articles for ${subscriber.email} countries: ${subscriber.countries.joinToString(", ")}")
                return
            }

            // Get primary language (first in list)
            val primaryLang = subscriber.languages.firstOrNull() ?: "en"
            
            // Create language-appropriate subject and content
            val (subject, greeting, bodyText, buttonText) = when (primaryLang) {
                "he" -> Tuple4(
                    "עדכון חדשות יומי - ${subscriberCountryArticles.size} כתבות חדשות",
                    "שלום ${subscriber.name ?: ""}!",
                    "עדכוני חדשות טריים זמינים עם ${subscriberCountryArticles.size} כתבות חדשות מ${subscriber.countries.joinToString(", ")}.",
                    "📖 קרא חדשות"
                )
                "ru" -> Tuple4(
                    "Ежедневный дайджест новостей - ${subscriberCountryArticles.size} новых статей",
                    "Привет ${subscriber.name ?: ""}!",
                    "Доступны свежие новости с ${subscriberCountryArticles.size} новыми статьями из ${subscriber.countries.joinToString(", ")}.",
                    "📖 Читать новости"
                )
                "el" -> Tuple4(
                    "Ημερήσια περίληψη ειδήσεων - ${subscriberCountryArticles.size} νέα άρθρα",
                    "Γεια σας ${subscriber.name ?: ""}!",
                    "Διατίθενται φρέσκες ενημερώσεις ειδήσεων με ${subscriberCountryArticles.size} νέα άρθρα από ${subscriber.countries.joinToString(", ")}.",
                    "📖 Διαβάστε ειδήσεις"
                )
                else -> Tuple4(
                    "Your Daily News Digest - ${subscriberCountryArticles.size} new stories",
                    "Hello ${subscriber.name ?: "there"}!",
                    "Fresh news updates are available with ${subscriberCountryArticles.size} new articles from ${subscriber.countries.joinToString(", ")}.",
                    "📖 Read News"
                )
            }

            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(fromEmail, "AI News"))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(subscriber.email))
                this.subject = subject

                val htmlContent = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <style>
                        body { font-family: Arial, sans-serif; margin: 0; padding: 20px; background: #f5f5f5; }
                        .container { max-width: 600px; margin: 0 auto; background: white; padding: 30px; border-radius: 10px; }
                        .header { text-align: center; margin-bottom: 30px; }
                        .logo { font-size: 2rem; font-weight: bold; color: #667eea; }
                        .button { display: inline-block; background: #667eea; color: white; padding: 15px 30px; text-decoration: none; border-radius: 25px; margin: 20px 0; }
                        .footer { margin-top: 30px; padding-top: 20px; border-top: 1px solid #eee; font-size: 0.9rem; color: #666; }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <div class="header">
                            <div class="logo">🤖 AI News</div>
                        </div>
                        <h1>$greeting</h1>
                        <p>$bodyText</p>
                        <div style="text-align: center;">
                            <a href="$websiteUrl" class="button">$buttonText</a>
                        </div>
                        <div class="footer">
                            <p>You're receiving this because you subscribed to AI News updates.</p>
                            <p>Visit <a href="$websiteUrl">ainews.eu.com</a> to read the latest news.</p>
                        </div>
                    </div>
                </body>
                </html>
                """.trimIndent()

                setContent(htmlContent, "text/html; charset=utf-8")
            }

            Transport.send(message)
        } catch (e: Exception) {
            println("❌ Failed to send email to ${subscriber.email}: ${e.message}")
            throw e // Re-throw to handle in calling function
        }
    }

    // Helper data class for multi-language email content
    data class Tuple4<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    fun sendDailyNotification(articles: List<Article>, websiteUrl: String) {
        val subscribers = loadSubscribers().filter { it.subscribed }

        if (subscribers.isEmpty()) {
            println("📧 No subscribers to notify")
            return
        }

        if (emailPassword.isNullOrEmpty()) {
            println("📧 Email notifications disabled - no EMAIL_PASSWORD configured")
            println("💡 To enable email notifications, set the EMAIL_PASSWORD environment variable")
            return
        }

        println("📧 Sending notifications to ${subscribers.size} subscribers...")
        
        subscribers.forEach { subscriber ->
            try {
                sendEmailNotification(subscriber, articles, websiteUrl)
                println("✅ Email sent to ${subscriber.email}")
                Thread.sleep(1000) // Delay between emails to avoid spam filters
            } catch (e: Exception) {
                println("❌ Failed to send email to ${subscriber.email}: ${e.message}")
            }
        }
        println("✅ Finished sending notifications")
    }
    fun addSubscriber(email: String, name: String?, languages: List<String>, countries: List<String> = listOf("CYPRUS")) {
        val subscribers = loadSubscribers().toMutableList()
        val existingSubscriber = subscribers.find { it.email == email }

        if (existingSubscriber == null) {
            val newSubscriber = Subscriber(
                email = email,
                name = name,
                languages = languages,
                countries = countries,
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

    fun generateDailyWebsite(articles: List<Article>): String {
        println("⚠️ Using legacy single-page generation - consider using generateCountryWebsite() instead")
        return generateCountryWebsite(articles, "CYPRUS")
    }
}

fun main() {
    println("Starting AI News Multi-Country Update...")
    
    val system = AINewsSystem()
    
    // ENABLE THESE LINES:
    system.processFormspreeEmails()
    system.checkAndImportWebSubscriptions()

    system.addSubscriber("lior.global@gmail.com", "Lior", listOf("en", "he"), listOf("CYPRUS", "ISRAEL"))

    val existingSubscribers = system.loadSubscribers()
    println("📧 Current subscribers: ${existingSubscribers.size}")

    try {
        println("🌍 Starting multi-country news aggregation...")
        val articles = system.aggregateNews()
        
        if (articles.isNotEmpty()) {
            system.setupCustomDomain()
            
            val websiteUrl = system.uploadToGitHubPages(articles)
            if (websiteUrl.isNotEmpty()) {
                println("🚀 Multi-country website uploaded: $websiteUrl")
                
                // ENABLE EMAIL NOTIFICATIONS:
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
    
    println("✅ Multi-country cronjob completed successfully")
}