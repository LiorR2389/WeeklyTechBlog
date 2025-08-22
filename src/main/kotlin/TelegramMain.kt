package com.ainews

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.Base64

/**
 * TELEGRAM LIVE NEWS SCRAPER
 * Monitors @cyprus_control using web scraping
 */

data class TelegramNewsMessage(
    val messageId: Long,
    val text: String,
    val timestamp: Long,
    val date: String,
    val isBreaking: Boolean = false,
    val priority: Int = 1, // 1=urgent, 2=important, 3=normal
    val processed: Boolean = false,
    val translations: Map<String, String> = mapOf(
        "en" to "English translation unavailable",
        "he" to "תרגום לא זמין", 
        "ru" to "", // Will contain original Russian text
        "el" to "Μετάφραση μη διαθέσιμη"
    )
)

class TelegramLiveScraper {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val githubToken = System.getenv("GITHUB_TOKEN")
    private val openAiApiKey = System.getenv("OPENAI_API_KEY")
    
    // Target channel (cyprus_control)
    private val channelUsername = "cyprus_control"
    
    private val processedMessagesFile = File("processed_telegram_messages.json")
    
    fun start() {
        println("🚀 Starting Telegram Monitor for @$channelUsername")
        println("📱 Using HTTP API approach (no authentication needed for public channels)")
        
        startMonitoringLoop()
    }
    
    private fun startMonitoringLoop() {
        println("🔄 Starting single check (cron job mode)...")
        
        try {
            val currentTime = SimpleDateFormat("HH:mm:ss").format(Date())
            println("\n⏰ Check at $currentTime")
            
            // Check for new messages
            val newMessages = checkForNewMessages()
            
            if (newMessages.isNotEmpty()) {
                println("📨 Found ${newMessages.size} new messages!")
                processNewMessages(newMessages)
            } else {
                println("📭 No new messages")
            }
            
            println("✅ Single check completed - exiting")
            
        } catch (e: Exception) {
            println("❌ Error during check: ${e.message}")
            throw e // Let the cron job know there was an error
        }
    }
    
    private fun checkForNewMessages(): List<TelegramNewsMessage> {
        try {
            println("🔍 Checking @$channelUsername for new messages...")
            
            // Use web scraping approach for public channels
            return scrapePublicChannel()
            
        } catch (e: Exception) {
            println("❌ Error checking messages: ${e.message}")
            return emptyList()
        }
    }
    
    private fun scrapePublicChannel(): List<TelegramNewsMessage> {
        try {
            // Scrape the public Telegram channel web page
            val channelUrl = "https://t.me/s/$channelUsername"
            
            val request = Request.Builder()
                .url(channelUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                println("❌ Failed to fetch channel page: ${response.code}")
                return emptyList()
            }
            
            val html = response.body?.string() ?: ""
            
            // Parse messages from HTML (simplified approach)
            val messages = parseChannelMessages(html)
            
            // Filter only new messages
            val processedMessages = loadProcessedMessages()
            val processedIds = processedMessages.map { it.messageId }.toSet()
            
            val newMessages = messages.filter { it.messageId !in processedIds }
            
            println("📊 Found ${messages.size} total messages, ${newMessages.size} new")
            
            return newMessages
            
        } catch (e: Exception) {
            println("❌ Error scraping channel: ${e.message}")
            return emptyList()
        }
    }
    
private fun parseChannelMessages(html: String): List<TelegramNewsMessage> {
    val messages = mutableListOf<TelegramNewsMessage>()
    
    try {
        println("🔍 HTML length: ${html.length} characters")
        
        // Updated regex patterns for current Telegram web format
        val messageBlockPattern = Regex(
            """<div class="tgme_widget_message\s+text_not_supported_wrap.*?data-post="[^"]*?/(\d+)".*?>(.*?)</div>\s*</div>\s*</div>""",
            RegexOption.DOT_MATCHES_ALL
        )
        
        val textPattern = Regex(
            """<div class="tgme_widget_message_text[^"]*"[^>]*>(.*?)</div>""",
            RegexOption.DOT_MATCHES_ALL
        )
        
        val timePattern = Regex(
            """<time[^>]*datetime="([^"]*)"[^>]*>""",
            RegexOption.DOT_MATCHES_ALL
        )
        
        val messageBlocks = messageBlockPattern.findAll(html)
        println("🔍 Found ${messageBlocks.count()} message blocks with proper structure")
        
        messageBlocks.forEachIndexed { index, blockMatch ->
            try {
                // Extract message ID from data-post attribute
                val telegramMessageId = blockMatch.groupValues[1].toLongOrNull() ?: 0L
                val blockContent = blockMatch.groupValues[2]
                
                // Extract text content
                val textMatch = textPattern.find(blockContent)
                val messageText = if (textMatch != null) {
                    textMatch.groupValues[1]
                        .replace(Regex("<br\\s*/?>"), "\n") // Convert <br> to newlines
                        .replace(Regex("<.*?>"), "") // Remove HTML tags
                        .replace("&amp;", "&")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
                        .replace("&quot;", "\"")
                        .replace("&#39;", "'")
                        .trim()
                } else {
                    ""
                }
                
                // Extract timestamp
                val timeMatch = timePattern.find(blockContent)
                val datetimeStr = timeMatch?.groupValues?.get(1) ?: ""
                
                if (messageText.isNotEmpty() && messageText.length > 10) {
                    // Parse timestamp properly
                    val timestamp = parseTimestamp(datetimeStr)
                    val messageDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(timestamp))
                    
                    // Use Telegram's actual message ID + hash for uniqueness
                    val uniqueMessageId = if (telegramMessageId > 0) {
                        telegramMessageId
                    } else {
                        // Fallback: create stable ID from content + date
                        val contentHash = messageText.hashCode().toLong()
                        val dayTimestamp = timestamp / (24 * 60 * 60 * 1000)
                        dayTimestamp * 1000000 + (contentHash and 0xFFFFF)
                    }
                    
                    println("📝 Message ${index + 1}: ID=$uniqueMessageId, Date=$messageDate, Text='${messageText.take(50)}...'")
                    
                    // Check if message is older than 7 days (ignore very old messages)
                    val sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000)
                    if (timestamp < sevenDaysAgo) {
                        println("⏰ Skipping old message from: $messageDate")
                        return@forEachIndexed
                    }
                    
                    // Create message with limited translations (only for recent messages)
                    val translations = if (index < 3 && timestamp > System.currentTimeMillis() - (24 * 60 * 60 * 1000)) {
                        // Only translate last 3 messages from last 24 hours
                        mapOf(
                            "en" to translateText(messageText, "English", "Russian"),
                            "he" to translateText(messageText, "Hebrew", "Russian"),
                            "ru" to messageText,
                            "el" to translateText(messageText, "Greek", "Russian")
                        )
                    } else {
                        // Minimal translations for older/bulk messages
                        mapOf(
                            "en" to "Translation pending...",
                            "he" to "תרגום ממתין...",
                            "ru" to messageText,
                            "el" to "Μετάφραση σε εκκρεμότητα..."
                        )
                    }
                    
                    val message = TelegramNewsMessage(
                        messageId = uniqueMessageId,
                        text = messageText,
                        timestamp = timestamp,
                        date = messageDate,
                        isBreaking = isBreakingNews(messageText),
                        priority = calculatePriority(messageText),
                        translations = translations
                    )
                    
                    messages.add(message)
                }
            } catch (e: Exception) {
                println("⚠️ Error parsing message ${index + 1}: ${e.message}")
            }
        }
        
        // If no messages found with the new pattern, try fallback patterns
        if (messages.isEmpty()) {
            println("⚠️ No messages found with primary pattern, trying fallback...")
            return parseChannelMessagesFallback(html)
        }
        
        println("📊 Successfully parsed ${messages.size} messages")
        
    } catch (e: Exception) {
        println("❌ Error parsing HTML: ${e.message}")
    }
    
    // Return only last 20 messages, sorted by timestamp descending
    return messages.sortedByDescending { it.timestamp }.take(20)
}

private fun parseChannelMessagesFallback(html: String): List<TelegramNewsMessage> {
    val messages = mutableListOf<TelegramNewsMessage>()
    
    try {
        // Simplified fallback pattern - look for any message-like structure
        val simpleMessagePattern = Regex(
            """data-post="[^"]*?/(\d+)"[^>]*>(.*?)<time[^>]*datetime="([^"]*)"[^>]*>""",
            RegexOption.DOT_MATCHES_ALL
        )
        
        val fallbackMatches = simpleMessagePattern.findAll(html)
        println("🔍 Fallback: Found ${fallbackMatches.count()} potential messages")
        
        fallbackMatches.take(10).forEachIndexed { index, match ->
            try {
                val messageId = match.groupValues[1].toLongOrNull() ?: 0L
                val content = match.groupValues[2]
                val datetime = match.groupValues[3]
                
                // Extract text from content (remove HTML)
                val cleanText = content
                    .replace(Regex("<.*?>"), "")
                    .replace("&amp;", "&")
                    .trim()
                
                if (cleanText.length > 20) {
                    val timestamp = parseTimestamp(datetime)
                    val messageDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(timestamp))
                    
                    println("📝 Fallback message ${index + 1}: '${cleanText.take(50)}...'")
                    
                    val message = TelegramNewsMessage(
                        messageId = messageId,
                        text = cleanText,
                        timestamp = timestamp,
                        date = messageDate,
                        isBreaking = false,
                        priority = 3,
                        translations = mapOf(
                            "en" to "Translation pending...",
                            "he" to "תרגום ממתין...",
                            "ru" to cleanText,
                            "el" to "Μετάφραση σε εκκρεμότητα..."
                        )
                    )
                    
                    messages.add(message)
                }
            } catch (e: Exception) {
                println("⚠️ Error in fallback parsing: ${e.message}")
            }
        }
        
    } catch (e: Exception) {
        println("❌ Error in fallback parsing: ${e.message}")
    }
    
    return messages.sortedByDescending { it.timestamp }
}
 private fun parseTimestamp(datetime: String): Long {
    return try {
        println("🕐 Parsing timestamp: '$datetime'")
        
        // Handle different datetime formats from Telegram
        val formats = listOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX"),     // 2025-08-21T07:50:47+00:00
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'"),     // 2025-08-21T07:50:47Z
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss"),        // 2025-08-21T07:50:47
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss")           // 2025-08-21 07:50:47
        )
        
        for (format in formats) {
            try {
                val parsed = format.parse(datetime)?.time
                if (parsed != null) {
                    val parsedDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(parsed))
                    println("✅ Parsed '$datetime' as: $parsedDate")
                    return parsed
                }
            } catch (e: Exception) {
                // Try next format
            }
        }
        
        println("⚠️ Could not parse timestamp '$datetime', using current time")
        System.currentTimeMillis()
    } catch (e: Exception) {
        println("❌ Error parsing timestamp '$datetime': ${e.message}")
        System.currentTimeMillis()
    }
}

private fun filterRecentMessages(messages: List<TelegramNewsMessage>): List<TelegramNewsMessage> {
    val threeDaysAgo = System.currentTimeMillis() - (3 * 24 * 60 * 60 * 1000)
    val recentMessages = messages.filter { it.timestamp > threeDaysAgo }
    
    println("📅 Filtered to ${recentMessages.size} messages from last 3 days (was ${messages.size})")
    
    return recentMessages.sortedByDescending { it.timestamp }
}
    
    private fun isBreakingNews(text: String): Boolean {
        val breakingKeywords = listOf(
            "breaking", "urgent", "alert", "emergency", "update",
            "confirmed", "developing", "just in", "live", "latest"
        )
        val lowerText = text.lowercase()
        return breakingKeywords.any { lowerText.contains(it) }
    }
    
    private fun calculatePriority(text: String): Int {
        val urgentKeywords = listOf("emergency", "alert", "breaking", "urgent")
        val importantKeywords = listOf("update", "confirmed", "developing", "report", "latest")
        
        val lowerText = text.lowercase()
        return when {
            urgentKeywords.any { lowerText.contains(it) } -> 1 // Urgent
            importantKeywords.any { lowerText.contains(it) } -> 2 // Important
            else -> 3 // Normal
        }
    }



    private fun translateText(text: String, targetLanguage: String, sourceLanguage: String = "Russian"): String {
    if (openAiApiKey.isNullOrEmpty()) {
        println("⚠️ No OpenAI API key, using fallback translations")
        return when (targetLanguage) {
            "English" -> "English translation unavailable (no API key)"
            "Hebrew" -> "תרגום לא זמין"
            "Greek" -> "Μετάφραση μη διαθέσιμη"
            else -> text
        }
    }

    // Skip translation if same language
    if ((sourceLanguage == "Russian" && targetLanguage == "Russian") ||
        (sourceLanguage == "English" && targetLanguage == "English")) {
        return text
    }

    // Add rate limiting delay
    Thread.sleep(1500) // 1.5 second delay between requests
    
    val translation = attemptTranslation(text, targetLanguage, sourceLanguage)
    
    // Better detection of translation failures
    val translationFailed = isTranslationFailure(translation, text, targetLanguage)
    
    if (translationFailed) {
        println("❌ Translation failed for $sourceLanguage->$targetLanguage")
        return when (targetLanguage) {
            "English" -> "Translation unavailable"
            "Hebrew" -> "תרגום לא זמין"
            "Greek" -> "Μετάφραση μη διαθέσιμη"
            else -> text
        }
    }
    
    println("✅ Translation successful: '${translation.take(50)}...'")
    return translation
}
    


// NEW: Fast translation attempt with reduced timeouts
private fun attemptTranslationFast(text: String, targetLanguage: String): String {
    return try {
        // Clean and validate input text
        val cleanText = text.trim()
        if (cleanText.isEmpty() || cleanText.length > 4000) {
            return text
        }

        // Simpler, faster prompts
        val systemPrompt = "Translate Russian to $targetLanguage. Keep emojis. Respond only with translation."
        val userPrompt = cleanText

        // Streamlined JSON request
        val requestBody = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user") 
                    put("content", userPrompt)
                })
            })
            put("temperature", 0.1)
            put("max_tokens", 300) // Reduced for faster response
        }

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $openAiApiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        // Use faster client with shorter timeouts for catch-up
        val fastClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        fastClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string()
            
            if (response.isSuccessful && responseBody != null) {
                try {
                    val json = JSONObject(responseBody)
                    val translation = json.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        .trim()
                    
                    return translation
                } catch (e: Exception) {
                    println("❌ Error parsing translation response: ${e.message}")
                    return text
                }
            } else {
                println("❌ Translation API failed with code: ${response.code}")
                
                // Handle rate limiting with shorter backoff
                if (response.code == 429) {
                    println("⏰ Rate limited, adding short delay...")
                    Thread.sleep(1000) // Reduced from 5000ms to 1000ms
                }
                
                return text
            }
        }
    } catch (e: Exception) {
        println("❌ Fast translation error for Russian->$targetLanguage: ${e.message}")
        return text
    }
}

// NEW: Better fallback translations
private fun getFallbackTranslation(text: String, targetLanguage: String): String {
    return when (targetLanguage) {
        "English" -> translateKeywords(text, "English")
        "Hebrew" -> "$text [רוסית]"
        "Greek" -> "$text [Ρωσικά]"
        else -> text
    }
}



private fun needsTranslation(message: TelegramNewsMessage): Boolean {
    val translations = message.translations ?: return true
    
    // Check each language to see if it needs translation
    val languagesToCheck = listOf("en", "he", "el")
    
    return languagesToCheck.any { lang ->
        val translation = translations[lang]
        
        // Needs translation if:
        // 1. Translation is null or empty
        // 2. Translation is a placeholder/fallback message
        // 3. Translation is identical to Russian original (failed translation)
        when {
            translation.isNullOrEmpty() -> true
            isPlaceholderTranslation(translation, lang) -> true
            translation == message.text && lang != "ru" -> true // Failed translation
            translation.length < 10 && message.text.length > 50 -> true // Too short
            else -> false
        }
    }
}





private fun isPlaceholderTranslation(translation: String, language: String): Boolean {
    val placeholders = when (language) {
        "en" -> listOf(
            "translation unavailable", 
            "english translation unavailable",
            "translation pending",
            "translation not available"
        )
        "he" -> listOf(
            "תרגום לא זמין",
            "תרגום ממתין",
            "כותרת בעברית"
        )
        "el" -> listOf(
            "μετάφραση μη διαθέσιμη",
            "μετάφραση σε εκκρεμότητα",
            "τίτλος στα ελληνικά"
        )
        else -> emptyList()
    }
    
    return placeholders.any { translation.lowercase().contains(it.lowercase()) }
}





private fun translateMessageSafely(message: TelegramNewsMessage): Map<String, String> {
    val originalText = message.text
    val existingTranslations = message.translations ?: emptyMap()
    
    val updatedTranslations = mutableMapOf<String, String>()
    
    // Always keep the original Russian
    updatedTranslations["ru"] = originalText
    
    // Try to translate each language
    val languagesToTranslate = mapOf(
        "en" to "English",
        "he" to "Hebrew", 
        "el" to "Greek"
    )
    
    languagesToTranslate.forEach { (langCode, langName) ->
        val existingTranslation = existingTranslations[langCode]
        
        // Only translate if current translation is bad/missing
        if (existingTranslation == null || isPlaceholderTranslation(existingTranslation, langCode) || 
            existingTranslation == originalText || existingTranslation.length < 10) {
            
            println("🔄 Translating to $langName...")
            val newTranslation = translateTextWithFallback(originalText, langName)
            updatedTranslations[langCode] = newTranslation
            
            // Small delay between languages
            Thread.sleep(500)
        } else {
            // Keep existing translation if it's good
            updatedTranslations[langCode] = existingTranslation
            println("✅ Keeping existing $langName translation")
        }
    }
    
    return updatedTranslations
}

// NEW: Fast translation mode for catch-up
private fun translateMessageFast(message: TelegramNewsMessage): Map<String, String> {
    val originalText = message.text
    val existingTranslations = message.translations ?: emptyMap()
    
    val updatedTranslations = mutableMapOf<String, String>()
    
    // Always keep the original Russian
    updatedTranslations["ru"] = originalText
    
    // Try to translate each language with minimal delays
    val languagesToTranslate = mapOf(
        "en" to "English",
        "he" to "Hebrew", 
        "el" to "Greek"
    )
    
    languagesToTranslate.forEach { (langCode, langName) ->
        val existingTranslation = existingTranslations[langCode]
        
        // Only translate if current translation is bad/missing
        if (existingTranslation == null || isPlaceholderTranslation(existingTranslation, langCode) || 
            existingTranslation == originalText || existingTranslation.length < 10) {
            
            println("🔄 Fast translating to $langName...")
            val newTranslation = translateTextFast(originalText, langName)
            updatedTranslations[langCode] = newTranslation
            
            // Very minimal delay
            Thread.sleep(100)
        } else {
            // Keep existing translation if it's good
            updatedTranslations[langCode] = existingTranslation
            println("✅ Keeping existing $langName translation")
        }
    }
    
    return updatedTranslations
}

// NEW: Translation with multiple fallback strategies
private fun translateTextWithFallback(text: String, targetLanguage: String): String {
    // Strategy 1: Try OpenAI translation
    if (!openAiApiKey.isNullOrEmpty()) {
        val aiTranslation = translateText(text, targetLanguage, "Russian")
        
        // Check if AI translation succeeded
        if (!isTranslationFailure(aiTranslation, text, targetLanguage)) {
            return aiTranslation
        }
        
        println("⚠️ AI translation failed, trying fallbacks...")
    }
    
    // Strategy 2: Try simple keyword-based translation for common phrases
    val keywordTranslation = translateKeywords(text, targetLanguage)
    if (keywordTranslation != text) {
        println("✅ Using keyword-based translation")
        return keywordTranslation
    }
    
    // Strategy 3: Return original with language indicator
    println("⚠️ All translation strategies failed, keeping original")
    return when (targetLanguage) {
        "English" -> "$text [RU]"
        "Hebrew" -> "$text [רוסית]"
        "Greek" -> "$text [Ρωσικά]"
        else -> text
    }
}

// NEW: Fast translation with reduced delays
private fun translateTextFast(text: String, targetLanguage: String): String {
    if (openAiApiKey.isNullOrEmpty()) {
        return getFallbackTranslation(text, targetLanguage)
    }

    // Skip translation if same language
    if (targetLanguage == "Russian") {
        return text
    }

    // Minimal rate limiting delay
    Thread.sleep(300) // Reduced from 1500ms to 300ms
    
    val translation = attemptTranslationFast(text, targetLanguage)
    
    // Better detection of translation failures
    val translationFailed = isTranslationFailure(translation, text, targetLanguage)
    
    if (translationFailed) {
        println("❌ Translation failed for Russian->$targetLanguage, using fallback")
        return getFallbackTranslation(text, targetLanguage)
    }
    
    println("✅ Translation successful: '${translation.take(30)}...'")
    return translation
}

// NEW: Fast translation attempt with reduced timeouts
private fun attemptTranslationFast(text: String, targetLanguage: String): String {
    return try {
        // Clean and validate input text
        val cleanText = text.trim()
        if (cleanText.isEmpty() || cleanText.length > 4000) {
            return text
        }

        // Simpler, faster prompts
        val systemPrompt = "Translate Russian to $targetLanguage. Keep emojis. Respond only with translation."
        val userPrompt = cleanText

        // Streamlined JSON request
        val requestBody = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user") 
                    put("content", userPrompt)
                })
            })
            put("temperature", 0.1)
            put("max_tokens", 300) // Reduced for faster response
        }

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $openAiApiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        // Use faster client with shorter timeouts for catch-up
        val fastClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        fastClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string()
            
            if (response.isSuccessful && responseBody != null) {
                try {
                    val json = JSONObject(responseBody)
                    val translation = json.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        .trim()
                    
                    return translation
                } catch (e: Exception) {
                    println("❌ Error parsing translation response: ${e.message}")
                    return text
                }
            } else {
                println("❌ Translation API failed with code: ${response.code}")
                
                // Handle rate limiting with shorter backoff
                if (response.code == 429) {
                    println("⏰ Rate limited, adding short delay...")
                    Thread.sleep(1000) // Reduced from 5000ms to 1000ms
                }
                
                return text
            }
        }
    } catch (e: Exception) {
        println("❌ Fast translation error for Russian->$targetLanguage: ${e.message}")
        return text
    }
}

// NEW: Better fallback translations
private fun getFallbackTranslation(text: String, targetLanguage: String): String {
    return when (targetLanguage) {
        "English" -> translateKeywords(text, "English")
        "Hebrew" -> "$text [רוסית]"
        "Greek" -> "$text [Ρωσικά]"
        else -> text
    }
}

// NEW: Simple keyword-based translation for common terms
private fun translateKeywords(text: String, targetLanguage: String): String {
    if (targetLanguage != "English") return text // Only implement English for now
    
    val keywordMap = mapOf(
        // Common news terms
        "полиция" to "police",
        "арестован" to "arrested", 
        "задержан" to "detained",
        "пожар" to "fire",
        "авария" to "accident",
        "больница" to "hospital",
        "суд" to "court",
        "банк" to "bank",
        "правительство" to "government",
        "министр" to "minister",
        "президент" to "president",
        "парламент" to "parliament",
        "Кипр" to "Cyprus",
        "Лимассол" to "Limassol",
        "Никосия" to "Nicosia", 
        "Ларнака" to "Larnaca",
        "Пафос" to "Paphos",
        "евро" to "euros",
        "температура" to "temperature",
        "погода" to "weather"
    )
    
    var translatedText = text
    keywordMap.forEach { (russian, english) ->
        translatedText = translatedText.replace(russian, english, ignoreCase = true)
    }
    
    // If we made any substitutions, it's a partial translation
    return if (translatedText != text) {
        "$translatedText [Partial Translation]"
    } else {
        text
    }
}
private fun isTranslationFailure(translation: String, originalText: String, targetLanguage: String): Boolean {
    // Don't consider it a failure if translation equals original for same-language translation
    if (targetLanguage == "Russian" && translation == originalText) {
        return false
    }
    
    // Check for obvious failure indicators
    val failureIndicators = listOf(
        "I'm unable to translate",
        "I cannot translate", 
        "I don't have",
        "I cannot provide",
        "Unable to translate",
        "Cannot translate",
        "Translation error",
        "Error translating",
        "API request failed"
    )
    
    val translationLower = translation.lowercase()
    val hasFailureIndicator = failureIndicators.any { translationLower.contains(it) }
    
    // Check for known fallback messages
    val knownFallbacks = when (targetLanguage) {
        "Hebrew" -> listOf("תרגום לא זמין", "כותרת בעברית")
        "Greek" -> listOf("μετάφραση μη διαθέσιμη", "τίτλος στα ελληνικά")
        "English" -> listOf("translation unavailable", "english translation unavailable")
        else -> emptyList()
    }
    
    val isKnownFallback = knownFallbacks.any { translation.lowercase().contains(it.lowercase()) }
    
    // Consider it failed if:
    // 1. Has failure indicators
    // 2. Is a known fallback message
    // 3. Is too short (less than 10 characters) and not intentionally short
    // 4. Is empty or blank
    // 5. Is identical to original when it shouldn't be
    val tooShort = translation.length < 10 && originalText.length > 20
    val identicalWhenShouldntBe = translation == originalText && targetLanguage != "Russian"
    
    return hasFailureIndicator || isKnownFallback || tooShort || translation.isBlank() || identicalWhenShouldntBe
}

private fun attemptTranslation(text: String, targetLanguage: String, sourceLanguage: String): String {
    return try {
        // Clean and validate input text
        val cleanText = text.trim()
        if (cleanText.isEmpty() || cleanText.length > 4000) {
            println("⚠️ Text too long or empty, skipping translation")
            return text
        }

        // More specific system prompt
        val systemPrompt = when (targetLanguage) {
            "English" -> "You are a professional Russian-to-English translator. Translate the Russian text to natural, fluent English. Preserve emojis and formatting. Respond only with the translation."
            "Hebrew" -> "You are a professional Russian-to-Hebrew translator. Translate the Russian text to natural, fluent Hebrew. Preserve emojis and formatting. Respond only with the Hebrew translation."
            "Greek" -> "You are a professional Russian-to-Greek translator. Translate the Russian text to natural, fluent Greek. Preserve emojis and formatting. Respond only with the Greek translation."
            else -> "You are a professional translator. Translate from $sourceLanguage to $targetLanguage. Preserve emojis and formatting. Respond only with the translation."
        }

        val userPrompt = "Translate this text: $cleanText"

        // Fixed JSON structure
        val requestBody = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user") 
                    put("content", userPrompt)
                })
            })
            put("temperature", 0.1)
            put("max_tokens", 500)
        }

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $openAiApiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
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
                    
                    println("🔄 Translation API response for $sourceLanguage->$targetLanguage: '${translation.take(50)}...'")
                    return translation
                } catch (e: Exception) {
                    println("❌ Error parsing translation response: ${e.message}")
                    return text
                }
            } else {
                println("❌ Translation API failed with code: ${response.code}")
                if (responseBody != null) {
                    println("❌ Error response: ${responseBody.take(200)}")
                }
                
                // Handle rate limiting with exponential backoff
                if (response.code == 429) {
                    println("⏰ Rate limited, adding longer delay...")
                    Thread.sleep(5000) // 5 second delay for rate limiting
                }
                
                return text
            }
        }
    } catch (e: Exception) {
        println("❌ Translation error for $sourceLanguage->$targetLanguage: ${e.message}")
        return text
    }
}

// UPDATED: Reduce translation load to prevent rate limiting
private fun processNewMessages(newMessages: List<TelegramNewsMessage>) {
    try {
        println("🔥 Processing ${newMessages.size} new messages...")
        
        // Add to processed messages
        val processedMessages = loadProcessedMessages().toMutableList()
        processedMessages.addAll(newMessages)
        
        // Keep last 3-4 days of messages
        val recentMessages = processedMessages.sortedByDescending { it.timestamp }.take(500).toMutableList()
        
        // CATCH-UP MODE: Process ALL messages needing translation (up to 30)
        val messagesNeedingTranslation = recentMessages.filter { message ->
            needsTranslation(message)
        }.sortedByDescending { it.timestamp }.take(30) // Process ALL 30 messages
        
        println("📝 CATCH-UP MODE: Found ${messagesNeedingTranslation.size} messages needing translation updates")
        
        // Update translations for ALL messages that need them
        messagesNeedingTranslation.forEachIndexed { index, oldMessage ->
            try {
                println("🔄 Translating message ${index + 1}/${messagesNeedingTranslation.size}: '${oldMessage.text.take(50)}...'")
                
                // Fast translation - minimal delays for catch-up
                val updatedTranslations = translateMessageFast(oldMessage)
                
                // Update the message in the list
                val messageIndex = recentMessages.indexOfFirst { it.messageId == oldMessage.messageId }
                if (messageIndex != -1) {
                    val updatedMessage = oldMessage.copy(translations = updatedTranslations)
                    recentMessages[messageIndex] = updatedMessage
                    println("✅ Updated translations for message ID: ${oldMessage.messageId}")
                }
                
                // Very short delay for catch-up mode
                Thread.sleep(200) // Only 200ms between messages
            } catch (e: Exception) {
                println("⚠️ Failed to update translation for message: ${e.message}")
            }
        }
        
        // Save updated messages with new translations
        saveProcessedMessages(recentMessages)
        if (messagesNeedingTranslation.isNotEmpty()) {
            println("💾 Saved ${messagesNeedingTranslation.size} updated message translations")
        }
        
        // Show last 30 messages on website
        updateLiveWebsite(recentMessages.take(30))
        
        // Upload to GitHub Pages
        uploadToGitHub()
        
        println("✅ Messages processed and website updated")
        
    } catch (e: Exception) {
        println("❌ Error processing messages: ${e.message}")
    }
}
    private fun loadProcessedMessages(): List<TelegramNewsMessage> {
        return if (processedMessagesFile.exists()) {
            try {
                val json = processedMessagesFile.readText()
                val type = object : TypeToken<List<TelegramNewsMessage>>() {}.type
                gson.fromJson<List<TelegramNewsMessage>>(json, type) ?: emptyList()
            } catch (e: Exception) {
                println("Error loading processed messages: ${e.message}")
                emptyList()
            }
        } else emptyList()
    }
    
    private fun saveProcessedMessages(messages: List<TelegramNewsMessage>) {
        try {
            processedMessagesFile.writeText(gson.toJson(messages))
        } catch (e: Exception) {
            println("Error saving processed messages: ${e.message}")
        }
    }
    
    private fun updateLiveWebsite(recentMessages: List<TelegramNewsMessage>) {
        val currentTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())
        val currentDate = SimpleDateFormat("EEEE, MMMM dd, yyyy").format(Date())
        
        val messagesHtml = if (recentMessages.isEmpty()) {
            """
                <div class="no-messages">
                    <h3>No recent messages</h3>
                    <p>Monitoring @cyprus_control for breaking news...</p>
                    <p>This page updates automatically every 10 minutes</p>
                </div>
            """.trimIndent()
        } else {
            recentMessages.sortedByDescending { it.timestamp }.joinToString("\n") { message ->
                val priorityClass = "priority-${message.priority}"
                val messageClass = when {
                    message.isBreaking -> "message breaking"
                    message.priority == 1 -> "message urgent"
                    else -> "message"
                }
                
                val priorityLabel = when(message.priority) {
                    1 -> "🔥 URGENT"
                    2 -> "⚡ IMPORTANT"
                    else -> "📢 NEWS"
                }
                
                val englishText = message.translations?.get("en")?.let { translation ->
                    if (translation.isNotEmpty() && 
                        translation != "English translation unavailable" && 
                        translation != "Translation unavailable" &&
                        !translation.contains("translation unavailable") &&
                        !translation.contains("Translation pending") &&
                        translation.length > 10) {
                        translation
                    } else {
                        // Runtime fallback for older messages (with reduced frequency)
                        message.text // Show original Russian for now
                    }
                } ?: message.text
                
                val hebrewText = message.translations?.get("he")?.let { translation ->
                    if (translation.isNotEmpty() && 
                        translation != "תרגום לא זמין" &&
                        !translation.contains("תרגום ממתין") &&
                        translation.length > 5) {
                        translation
                    } else {
                        message.text // Show original Russian for now
                    }
                } ?: message.text
                
                val russianText = message.translations?.get("ru") ?: message.text
                
                val greekText = message.translations?.get("el")?.let { translation ->
                    if (translation.isNotEmpty() && 
                        translation != "Μετάφραση μη διαθέσιμη" &&
                        !translation.contains("Μετάφραση σε εκκρεμότητα") &&
                        translation.length > 10) {
                        translation
                    } else {
                        message.text // Show original Russian for now
                    }
                } ?: message.text
                
                """
<div class="$messageClass">
    <div class="timestamp">📅 ${message.date}</div>
    <div class="priority $priorityClass">
        $priorityLabel
    </div>
    <div class="lang en active">
        <div class="text">$englishText</div>
    </div>
    <div class="lang he">
        <div class="text" dir="rtl">$hebrewText</div>
    </div>
    <div class="lang ru">
        <div class="text">$russianText</div>
    </div>
    <div class="lang el">
        <div class="text">$greekText</div>
    </div>
</div>
                """.trimIndent()
            }
        }
        
        val liveHtml = generateLiveHtmlPage(currentDate, currentTime, recentMessages, messagesHtml)
        
        File("live_news.html").writeText(liveHtml)
        println("📄 Live website updated with ${recentMessages.size} recent messages")
    }
    
    private fun generateLiveHtmlPage(currentDate: String, currentTime: String, recentMessages: List<TelegramNewsMessage>, messagesHtml: String): String {
        return """<!DOCTYPE html>
<html lang="en">
<head>
    <title>🔴 LIVE: Cyprus Breaking News | AI News</title>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta http-equiv="refresh" content="300"> <!-- Auto refresh every 5 minutes -->
    <meta name="description" content="Live breaking news from Cyprus - Real-time updates from @cyprus_control">
    <style>
        body { 
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Arial, sans-serif;
            margin: 0; 
            padding: 20px; 
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            min-height: 100vh;
            line-height: 1.6;
        }
        .container { 
            max-width: 800px; 
            margin: 0 auto; 
            background: white; 
            padding: 30px; 
            border-radius: 15px; 
            box-shadow: 0 10px 30px rgba(0,0,0,0.2);
        }
        .header { 
            text-align: center; 
            margin-bottom: 30px; 
            padding-bottom: 20px;
            border-bottom: 2px solid #f0f0f0;
        }
        .live-indicator { 
            background: #ff4444; 
            color: white; 
            padding: 8px 16px; 
            border-radius: 25px; 
            display: inline-block; 
            margin-bottom: 15px; 
            font-weight: bold;
            animation: pulse 2s infinite; 
        }
        @keyframes pulse { 
            0%, 100% { opacity: 1; transform: scale(1); } 
            50% { opacity: 0.8; transform: scale(1.05); } 
        }
        .logo {
            font-size: 2.5rem;
            font-weight: bold;
            color: #667eea;
            margin-bottom: 10px;
        }
        .navigation {
            text-align: center;
            margin: 20px 0;
            padding: 15px;
            background: #f8f9fa;
            border-radius: 10px;
        }
        .navigation a {
            display: inline-block;
            margin: 0 10px;
            padding: 8px 16px;
            background: #667eea;
            color: white;
            text-decoration: none;
            border-radius: 20px;
            transition: all 0.3s;
        }
        .navigation a:hover {
            background: #764ba2;
            transform: translateY(-2px);
        }
        
        /* FIXED: Added missing lang-buttons CSS */
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
        
        /* FIXED: Proper language visibility control */
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
        
        .lang.he .text { 
            direction: rtl;
            text-align: right;
        }
        
        .message { 
            margin: 20px 0; 
            padding: 25px; 
            border-left: 4px solid #4CAF50; 
            background: #f9f9f9; 
            border-radius: 10px;
            transition: all 0.3s ease;
        }
        .message:hover {
            transform: translateY(-2px);
            box-shadow: 0 5px 15px rgba(0,0,0,0.1);
        }
        .message.breaking { 
            border-left-color: #ff4444; 
            background: linear-gradient(135deg, #fff5f5 0%, #ffebee 100%);
        }
        .message.urgent { 
            border-left-color: #ff9800; 
            background: linear-gradient(135deg, #fff8f0 0%, #fff3e0 100%);
        }
        .timestamp { 
            color: #666; 
            font-size: 0.9rem; 
            margin-bottom: 10px; 
            font-weight: 500;
        }
        .text { 
            font-size: 1.1rem; 
            line-height: 1.7; 
            color: #333;
            margin-bottom: 10px;
        }
        .priority { 
            display: inline-block; 
            padding: 4px 12px; 
            border-radius: 15px; 
            font-size: 0.8rem; 
            font-weight: bold;
            margin-bottom: 10px; 
        }
        .priority-1 { 
            background: #ffcdd2; 
            color: #c62828; 
        }
        .priority-2 { 
            background: #ffe0b2; 
            color: #f57c00; 
        }
        .priority-3 { 
            background: #e1bee7; 
            color: #7b1fa2; 
        }
        .stats {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
            gap: 15px;
            margin: 20px 0;
            padding: 20px;
            background: #f8f9fa;
            border-radius: 10px;
        }
        .stat-item {
            text-align: center;
            padding: 15px;
            background: white;
            border-radius: 8px;
            box-shadow: 0 2px 8px rgba(0,0,0,0.1);
        }
        .stat-number {
            font-size: 1.8rem;
            font-weight: bold;
            color: #667eea;
            margin-bottom: 5px;
        }
        .stat-label {
            font-size: 0.9rem;
            color: #666;
        }
        .footer {
            text-align: center;
            margin-top: 40px;
            padding: 20px 0;
            border-top: 2px solid #f0f0f0;
            color: #666;
        }
        .footer a {
            color: #667eea;
            text-decoration: none;
            font-weight: 500;
        }
        .footer a:hover {
            text-decoration: underline;
        }
        .no-messages {
            text-align: center;
            padding: 60px 20px;
            color: #666;
            background: #f8f9fa;
            border-radius: 10px;
            margin: 20px 0;
        }
        @media (max-width: 768px) {
            body { padding: 10px; }
            .container { padding: 20px; }
            .logo { font-size: 2rem; }
            .navigation a { 
                display: block; 
                margin: 5px 0; 
                padding: 12px 20px; 
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
            .stats { grid-template-columns: repeat(1, 1fr); }
        }
    </style>
</head>
<body>
<div class="container">
    <div class="header">
        <div class="live-indicator">🔴 LIVE</div>
        <div class="logo">AI News</div>
        <h1>🇨🇾 Cyprus Breaking News</h1>
        <p>Real-time updates from @cyprus_control</p>
        <p><strong>$currentDate</strong></p>
        <p style="font-size: 0.9rem; color: #666;">Last updated: $currentTime</p>
    </div>

    <div class="navigation">
        <a href="../index.html">🏠 Home</a>
        <a href="../cyprus/index.html">📰 Daily Cyprus</a>
        <a href="../israel/index.html">🇮🇱 Israel</a>
        <a href="../greece/index.html">🇬🇷 Greece</a>
        <a href="https://t.me/cyprus_control" target="_blank">📱 @cyprus_control</a>
    </div>

    <div class="lang-buttons">
        <button onclick="setLang('en')" class="active" id="btn-en">🇬🇧 English</button>
        <button onclick="setLang('he')" id="btn-he">🇮🇱 עברית</button>
        <button onclick="setLang('ru')" id="btn-ru">🇷🇺 Русский</button>
        <button onclick="setLang('el')" id="btn-el">🇬🇷 Ελληνικά</button>
    </div>

    <div class="stats">
        <div class="stat-item">
            <div class="stat-number">${recentMessages.size}</div>
            <div class="stat-label">Recent Messages</div>
        </div>
    </div>

    $messagesHtml

    <div class="footer">
        <p><strong>Automated Live Monitoring</strong></p>
        <p>Updates every 10 minutes • Source: <a href="https://t.me/cyprus_control" target="_blank">@cyprus_control</a></p>
        <p><a href="https://ainews.eu.com">ainews.eu.com</a></p>
        <p style="margin-top: 15px; font-size: 0.8rem;">
            This page automatically refreshes every 5 minutes<br>
            For daily comprehensive news, visit our <a href="../index.html">main homepage</a>
        </p>
    </div>
</div>

<script>
    let currentLang = 'en';

    function setLang(lang) {
        // Hide all language elements
        document.querySelectorAll('.lang').forEach(el => el.classList.remove('active'));
        // Show selected language elements
        document.querySelectorAll('.lang.' + lang).forEach(el => el.classList.add('active'));
        // Update button states
        document.querySelectorAll('.lang-buttons button').forEach(btn => btn.classList.remove('active'));
        document.getElementById('btn-' + lang).classList.add('active');
        currentLang = lang;
        
        try {
            localStorage.setItem('liveNewsLang', lang);
        } catch (e) {
            // Silently fail if localStorage not available
        }
    }

    document.addEventListener('DOMContentLoaded', function() {
        let savedLang = 'en';
        try {
            savedLang = localStorage.getItem('liveNewsLang') || 'en';
        } catch (e) {
            // Silently fail if localStorage not available
        }
        setLang(savedLang);
        
        document.addEventListener('keydown', function(e) {
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
</html>"""
    }
    
    private fun uploadToGitHub() {
        try {
            if (githubToken.isNullOrEmpty()) {
                println("⚠️ No GitHub token, skipping upload")
                return
            }
            
            val liveContent = File("live_news.html").readText()
            uploadFileToGitHub("ainews-website", "live/index.html", liveContent)
            
            println("🚀 Live page uploaded to GitHub Pages: https://ainews.eu.com/live/")
            
        } catch (e: Exception) {
            println("❌ Error uploading to GitHub: ${e.message}")
        }
    }
    
    private fun uploadFileToGitHub(repoName: String, filePath: String, content: String) {
        try {
            // Get existing file SHA (if exists)
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

            // Upload file with proper JSON structure
            val base64Content = Base64.getEncoder().encodeToString(content.toByteArray())
            val requestBodyJson = JSONObject()
            requestBodyJson.put("message", "Update live news - ${SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date())}")
            requestBodyJson.put("content", base64Content)
            if (sha != null) {
                requestBodyJson.put("sha", sha!!)
            }

            val putRequest = Request.Builder()
                .url("https://api.github.com/repos/LiorR2389/$repoName/contents/$filePath")
                .addHeader("Authorization", "token $githubToken")
                .addHeader("Content-Type", "application/json")
                .put(requestBodyJson.toString().toRequestBody("application/json".toMediaType()))
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
}

fun main() {
    println("🚀 Starting Telegram Live News Scraper...")
    
    try {
        val scraper = TelegramLiveScraper()
        scraper.start()
    } catch (e: Exception) {
        println("❌ Fatal error: ${e.message}")
        e.printStackTrace()
        System.exit(1)
    }
}