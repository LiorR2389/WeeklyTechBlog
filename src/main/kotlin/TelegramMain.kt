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
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
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
        println("🔄 Starting single check with 5-minute timeout...")
        
        val startTime = System.currentTimeMillis()
        val timeoutMs = 5 * 60 * 1000 // 5 minutes
        
        try {
            val currentTime = SimpleDateFormat("HH:mm:ss").format(Date())
            println("\n⏰ Check at $currentTime")
            
            // Check for new messages with timeout protection
            val newMessages = checkForNewMessages()
            
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed > timeoutMs) {
                println("⏰ Timeout after ${elapsed/1000}s - forcing exit")
                return
            }
            
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
        
        val newMessages = scrapePublicChannel()
        
        // If scraping failed, use fallback
        if (newMessages.isEmpty()) {
            val processedMessages = loadProcessedMessages()
            if (processedMessages.isNotEmpty()) {
                println("🔄 No new messages found, using cached data for live page")
                val recentMessages = processedMessages.sortedByDescending { it.timestamp }.take(30)
                updateLiveWebsite(recentMessages)
                uploadToGitHub()
            }
        }
        
        return newMessages
        
    } catch (e: Exception) {
        println("❌ Error checking messages: ${e.message}")
        return handleRateLimiting()
    }
}
    
    private fun scrapePublicChannel(): List<TelegramNewsMessage> {
    try {
        println("🔍 Fetching channel page...")
        
        // Add random delay to avoid rate limiting
        val randomDelay = (2000..5000).random()
        println("⏰ Waiting ${randomDelay}ms to avoid rate limiting...")
        Thread.sleep(randomDelay.toLong())
        
        val channelUrl = "https://t.me/s/$channelUsername"
        
        val request = Request.Builder()
            .url(channelUrl)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36")
            .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
            .addHeader("Accept-Language", "en-US,en;q=0.9")
            .addHeader("Accept-Encoding", "gzip, deflate, br")
            .addHeader("Cache-Control", "no-cache")
            .addHeader("Pragma", "no-cache")
            .addHeader("Sec-Ch-Ua", "\"Google Chrome\";v=\"119\", \"Chromium\";v=\"119\", \"Not?A_Brand\";v=\"24\"")
            .addHeader("Sec-Ch-Ua-Mobile", "?0")
            .addHeader("Sec-Ch-Ua-Platform", "\"Windows\"")
            .addHeader("Sec-Fetch-Dest", "document")
            .addHeader("Sec-Fetch-Mode", "navigate")
            .addHeader("Sec-Fetch-Site", "none")
            .addHeader("Sec-Fetch-User", "?1")
            .addHeader("Upgrade-Insecure-Requests", "1")
            .addHeader("Referer", "https://t.me/")
            .build()
        
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                println("❌ HTTP error: ${response.code} - ${response.message}")
                return emptyList()
            }
            
            val html = response.body?.string() ?: ""
            println("✅ HTML fetched: ${html.length} chars")
            
            // Check for rate limiting or blocking
            if (html.length < 10000) {
                println("⚠️ Suspiciously small HTML response - might be rate limited")
                println("🔍 Response headers: ${response.headers}")
            }
            
            // Check for Telegram's rate limiting page
            if (html.contains("Too Many Requests") || html.contains("429") || html.contains("rate limit")) {
                println("⚠️ Rate limited by Telegram - backing off")
                return emptyList()
            }
            
            // Save HTML for debugging
            try {
                val debugContent = if (html.length > 50000) html.take(50000) else html
                File("debug_telegram.html").writeText(debugContent)
                println("🔍 Saved HTML sample to debug_telegram.html (${debugContent.length} chars)")
            } catch (e: Exception) {
                println("⚠️ Could not save debug HTML: ${e.message}")
            }
            
            // Check for corrupted data
            if (html.contains("�") || html.all { it.code < 32 || it.code > 126 }) {
                println("❌ Received corrupted or binary data")
                return emptyList()
            }
            
            // Parse messages from HTML
            val messages = parseChannelMessages(html)
            
            // Filter only new messages
            val processedMessages = loadProcessedMessages()
            val processedIds = processedMessages.map { it.messageId }.toSet()
            
            val newMessages = messages.filter { it.messageId !in processedIds }
            
            println("📊 Found ${messages.size} total messages, ${newMessages.size} new")
            
            return newMessages
        }
        
    } catch (e: Exception) {
        println("❌ Error scraping channel: ${e.message}")
        e.printStackTrace()
        return emptyList()
    }
}
    
    private fun parseChannelMessages(html: String): List<TelegramNewsMessage> {
        println("🔍 Starting HTML parsing...")
        val messages = mutableListOf<TelegramNewsMessage>()
        
        try {
            println("🔍 HTML length: ${html.length} characters")
            
            // Check if we got the right page
            if (!html.contains("tgme_widget_message")) {
                println("❌ No Telegram messages found in HTML - might be blocked or wrong format")
                println("🔍 HTML preview: ${html.take(500)}...")
                return emptyList()
            }
            
            // Simplified regex patterns to avoid backtracking issues
            val messagePattern = Regex(
                """<div class="tgme_widget_message.*?>(.*?)</div>\s*<div class="tgme_widget_message_footer">""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)
            )
            
            val textPattern = Regex(
                """<div class="tgme_widget_message_text.*?>(.*?)</div>""",
                RegexOption.DOT_MATCHES_ALL
            )
            
            val timePattern = Regex(
                """<time datetime="([^"]*)"[^>]*>([^<]*)</time>""",
                RegexOption.DOT_MATCHES_ALL
            )
            
            println("🔍 Searching for message patterns...")
            
            // Find all time elements first
            val timeMatches = timePattern.findAll(html).toList()
            println("🔍 Found ${timeMatches.size} time elements")
            
            // Find all message text elements  
            val textMatches = textPattern.findAll(html).toList()
            println("🔍 Found ${textMatches.size} text elements")
            
            // Process up to 20 most recent messages
            val messagesToProcess = minOf(textMatches.size, timeMatches.size, 20)
            println("🔍 Processing $messagesToProcess messages...")
            
            for (i in 0 until messagesToProcess) {
                try {
                    val textMatch = textMatches.getOrNull(i)
                    val timeMatch = timeMatches.getOrNull(i)
                    
                    if (textMatch == null || timeMatch == null) {
                        println("⚠️ Skipping message $i - missing text or time")
                        continue
                    }
                    
                    val messageText = textMatch.groupValues[1]
                        .replace(Regex("<[^>]*>"), "") // Remove HTML tags
                        .replace("&amp;", "&")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
                        .replace("&quot;", "\"")
                        .replace("&#39;", "'")
                        .replace(Regex("\\s+"), " ") // Normalize whitespace
                        .trim()
                    
                    if (messageText.isEmpty() || messageText.length < 20) {
                        println("⚠️ Skipping message $i - too short: '${messageText.take(30)}...'")
                        continue
                    }
                    
                    // Parse timestamp
                    val timestamp = parseTimestamp(timeMatch.groupValues[1])
                    val messageDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(timestamp))
                    
                    // Skip messages older than 7 days
                    val sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000)
                    if (timestamp < sevenDaysAgo) {
                        println("⏰ Skipping old message from: $messageDate")
                        continue
                    }
                    
                    // Create unique message ID
                    val messageId = generateMessageId(messageText, timestamp, i)
                    
                    println("📝 Message ${i + 1}: ID=$messageId, Date=$messageDate, Text='${messageText.take(50)}...'")
                    
                    // Limited translations for recent messages only
                    val translations = if (i < 3 && timestamp > System.currentTimeMillis() - (24 * 60 * 60 * 1000)) {
                        try {
                            mapOf(
                                "en" to translateText(messageText, "English", "Russian"),
                                "he" to translateText(messageText, "Hebrew", "Russian"),
                                "ru" to messageText,
                                "el" to translateText(messageText, "Greek", "Russian")
                            )
                        } catch (e: Exception) {
                            println("⚠️ Translation failed for message $i: ${e.message}")
                            mapOf(
                                "en" to "Translation failed",
                                "he" to "תרגום נכשל",
                                "ru" to messageText,
                                "el" to "Η μετάφραση απέτυχε"
                            )
                        }
                    } else {
                        mapOf(
                            "en" to "Translation pending...",
                            "he" to "תרגום ממתין...",
                            "ru" to messageText,
                            "el" to "Μετάφραση σε εκκρεμότητα..."
                        )
                    }
                    
                    val message = TelegramNewsMessage(
                        messageId = messageId,
                        text = messageText,
                        timestamp = timestamp,
                        date = messageDate,
                        isBreaking = isBreakingNews(messageText),
                        priority = calculatePriority(messageText),
                        translations = translations
                    )
                    
                    messages.add(message)
                    
                    // Add small delay to avoid overwhelming the translation API
                    if (i < 3) {
                        Thread.sleep(1000)
                    }
                    
                } catch (e: Exception) {
                    println("⚠️ Error parsing message $i: ${e.message}")
                    // Continue with next message
                }
            }
            
            println("📊 Successfully parsed ${messages.size} messages")
            
        } catch (e: Exception) {
            println("❌ Error in parseChannelMessages: ${e.message}")
            e.printStackTrace()
        }
        
        // Return messages sorted by timestamp (newest first)
        return messages.sortedByDescending { it.timestamp }
    }
    
    private fun generateMessageId(text: String, timestamp: Long, index: Int): Long {
        // Create a more stable message ID
        val textHash = text.hashCode().toLong() and 0x7FFFFFFF // Positive hash
        val dayTimestamp = timestamp / (24 * 60 * 60 * 1000) // Day-level timestamp
        
        // Combine day + content hash + index for uniqueness
        return (dayTimestamp * 1000000) + (textHash % 100000) + index
    }
    
    private fun parseTimestamp(datetime: String): Long {
        return try {
            println("🕐 Parsing timestamp: '$datetime'")
            
            // Handle different datetime formats from Telegram
            val formats = listOf(
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX"),     // 2025-08-21T07:50:47+00:00
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'"),     // 2025-08-21T07:50:47Z
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss"),        // 2025-08-21T07:50:47
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss"),          // 2025-08-21 07:50:47
                SimpleDateFormat("MMM dd, yyyy 'at' HH:mm"),      // Aug 21, 2025 at 07:50
                SimpleDateFormat("dd.MM.yyyy HH:mm")              // 21.08.2025 07:50
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

        // Try translating from the specified source language first
        val translation = attemptTranslation(text, targetLanguage, sourceLanguage)
        
        // FIXED: Better detection of translation failures
        val translationFailed = isTranslationFailure(translation, text, targetLanguage)
        
        // If translation failed and we haven't tried English yet, try English as fallback
        if (translationFailed && sourceLanguage != "English") {
            println("⚠️ Translation from $sourceLanguage failed, trying English fallback...")
            val englishTranslation = attemptTranslation(text, targetLanguage, "English")
            
            val englishFailed = isTranslationFailure(englishTranslation, text, targetLanguage)
            
            if (!englishFailed) {
                println("✅ English fallback successful: '${englishTranslation.take(50)}...'")
                return englishTranslation
            } else {
                println("❌ English fallback also failed: '${englishTranslation.take(50)}...'")
            }
        }
        
        // If all else fails, return a proper fallback message
        if (translationFailed) {
            println("❌ All translation attempts failed for target: $targetLanguage")
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

    // NEW: More accurate translation failure detection
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
            "Error translating"
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
        val tooShort = translation.length < 10 && originalText.length > 20
        
        return hasFailureIndicator || isKnownFallback || tooShort || translation.isBlank()
    }

    // UPDATED: Better translation attempt with improved prompts
    private fun attemptTranslation(text: String, targetLanguage: String, sourceLanguage: String): String {
        return try {
            // More specific system prompt based on source language
            val systemPrompt = when (sourceLanguage) {
                "Russian" -> "You are a professional Russian-to-$targetLanguage translator. Translate the following Russian text to $targetLanguage. Provide ONLY the translation, no explanations."
                "English" -> "You are a professional English-to-$targetLanguage translator. Translate the following English text to $targetLanguage. Provide ONLY the translation, no explanations."
                else -> "You are a professional translator. Translate the following $sourceLanguage text to $targetLanguage. Provide ONLY the translation, no explanations."
            }

            val userPrompt = "Translate this text: $text"

            val requestBody = """{
      "model": "gpt-4o-mini",
      "messages": [
        {"role": "system", "content": "$systemPrompt"},
        {"role": "user", "content": "$userPrompt"}
      ],
      "temperature": 0.1,
      "max_tokens": 400
    }"""

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
                    
                    println("🔄 Translation API response for $sourceLanguage->$targetLanguage: '${translation.take(50)}...'")
                    translation
                } else {
                    println("❌ Translation API failed with code: ${response.code}")
                    text
                }
            }
        } catch (e: Exception) {
            println("❌ Translation error for $sourceLanguage->$targetLanguage: ${e.message}")
            text
        }
    }
    
    private fun processNewMessages(newMessages: List<TelegramNewsMessage>) {
        try {
            println("🔥 Processing ${newMessages.size} new messages...")
            
            // Add to processed messages
            val processedMessages = loadProcessedMessages().toMutableList()
            processedMessages.addAll(newMessages)
            
            // Keep last 500 messages (about 1 month of data)
            val recentMessages = processedMessages.sortedByDescending { it.timestamp }.take(500).toMutableList()
            
            // Find messages needing translation updates (limit to 2 to control costs)
            val messagesNeedingTranslation = recentMessages.filter { message ->
                val hasValidTranslations = message.translations?.let { translations ->
                    translations["en"]?.let { en ->
                        en.isNotEmpty() && 
                        en != "English translation unavailable" &&
                        en != "Translation unavailable" &&
                        en != "Translation failed" &&
                        en != "Translation pending..." &&
                        !en.contains("translation unavailable") &&
                        !en.contains("Translation pending") &&
                        en.length > 10
                    } == true
                } ?: false
                
                !hasValidTranslations
            }.take(2) // LIMIT: Only 2 messages per run to control costs
            
            println("📝 Found ${messagesNeedingTranslation.size} messages needing translation updates")
            
            // Update translations for messages that need them
            messagesNeedingTranslation.forEach { oldMessage ->
                try {
                    println("🔄 Updating translations for message: '${oldMessage.text.take(50)}...'")
                    
                    val updatedTranslations = mapOf(
                        "en" to translateText(oldMessage.text, "English", "Russian"),
                        "he" to translateText(oldMessage.text, "Hebrew", "Russian"),
                        "ru" to oldMessage.text, // Keep original Russian
                        "el" to translateText(oldMessage.text, "Greek", "Russian")
                    )
                    
                    // Update the message in the list
                    val messageIndex = recentMessages.indexOfFirst { it.messageId == oldMessage.messageId }
                    if (messageIndex != -1) {
                        val updatedMessage = oldMessage.copy(translations = updatedTranslations)
                        recentMessages[messageIndex] = updatedMessage
                        println("✅ Updated translations for message ID: ${oldMessage.messageId}")
                    }
                    
                    // Small delay to avoid API rate limits
                    Thread.sleep(1000)
                } catch (e: Exception) {
                    println("⚠️ Failed to update translation for message: ${e.message}")
                }
            }
            
            // Save updated messages
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
            e.printStackTrace()
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
    <div class="text">$russianText</div>
</div>
                """.trimIndent()
            }
        }
        
        val liveHtml = generateLiveHtmlPage(currentDate, currentTime, recentMessages, messagesHtml)
        
        File("live_news.html").writeText(liveHtml)
        println("📄 Live website updated with ${recentMessages.size} recent messages")
    }
    
    private fun generateLiveHtmlPage(currentDate: String, currentTime: String, recentMessages: List<TelegramNewsMessage>, messagesHtml: String): String {
        val breakingCount = recentMessages.count { it.isBreaking }
        val urgentCount = recentMessages.count { it.priority == 1 }
        
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
            .stats { grid-template-columns: repeat(2, 1fr); }
        }
    </style>
</head>
<body>
<div class="container">
    <div class="header">
        <div class="live-indicator">🔴 LIVE</div>
        <div class="logo">🤖 AI News</div>
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

    <div class="stats">
        <div class="stat-item">
            <div class="stat-number">${recentMessages.size}</div>
            <div class="stat-label">Recent Messages</div>
        </div>
        <div class="stat-item">
            <div class="stat-number">$breakingCount</div>
            <div class="stat-label">Breaking News</div>
        </div>
        <div class="stat-item">
            <div class="stat-number">$urgentCount</div>
            <div class="stat-label">Urgent Updates</div>
        </div>
        <div class="stat-item">
            <div class="stat-number">10 min</div>
            <div class="stat-label">Update Frequency</div>
        </div>
    </div>

    $messagesHtml

    <div class="footer">
        <p>🤖 <strong>Automated Live Monitoring</strong></p>
        <p>Updates every 10 minutes • Source: <a href="https://t.me/cyprus_control" target="_blank">@cyprus_control</a></p>
        <p><a href="https://ainews.eu.com">ainews.eu.com</a></p>
        <p style="margin-top: 15px; font-size: 0.8rem;">
            This page automatically refreshes every 5 minutes<br>
            For daily comprehensive news, visit our <a href="../index.html">main homepage</a>
        </p>
    </div>
</div>
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