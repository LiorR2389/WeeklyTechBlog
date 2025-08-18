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
            // Simple regex to find message blocks
            val messagePattern = Regex(
                """<div class="tgme_widget_message_text.*?>(.*?)</div>""",
                RegexOption.DOT_MATCHES_ALL
            )
            
            val timePattern = Regex(
                """<time datetime="(.*?)".*?>(.*?)</time>""",
                RegexOption.DOT_MATCHES_ALL
            )
            
            val messageMatches = messagePattern.findAll(html)
            val timeMatches = timePattern.findAll(html).toList()
            
            messageMatches.forEachIndexed { index, messageMatch ->
                try {
                    val messageText = messageMatch.groupValues[1]
                        .replace(Regex("<.*?>"), "") // Remove HTML tags
                        .trim()
                    
                    if (messageText.isNotEmpty() && messageText.length > 10) {
                        // Try to get corresponding timestamp
                        val timeMatch = timeMatches.getOrNull(index)
                        val timestamp = if (timeMatch != null) {
                            parseTimestamp(timeMatch.groupValues[1])
                        } else {
                            System.currentTimeMillis()
                        }
                        
                        // Generate translations for each message with proper fallback
                        val translations = try {
                            mapOf(
                                "en" to translateText(messageText, "English", "Russian"),
                                "he" to translateText(messageText, "Hebrew", "Russian"),
                                "ru" to messageText, // Keep original Russian
                                "el" to translateText(messageText, "Greek", "Russian")
                            )
                        } catch (e: Exception) {
                            println("⚠️ Error generating translations: ${e.message}")
                            mapOf(
                                "en" to "English translation unavailable",
                                "he" to "תרגום לא זמין",
                                "ru" to messageText, // Keep original Russian
                                "el" to "Μετάφραση μη διαθέσιμη"
                            )
                        }
                        
                        val message = TelegramNewsMessage(
                            messageId = (messageText.hashCode().toLong() + timestamp), // Simple ID generation
                            text = messageText,
                            timestamp = timestamp,
                            date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(timestamp)),
                            isBreaking = isBreakingNews(messageText),
                            priority = calculatePriority(messageText),
                            translations = translations
                        )
                        
                        messages.add(message)
                    }
                } catch (e: Exception) {
                    println("⚠️ Error parsing message: ${e.message}")
                }
            }
            
        } catch (e: Exception) {
            println("❌ Error parsing HTML: ${e.message}")
        }
        
        return messages.take(20) // Limit to last 20 messages
    }
    
    private fun parseTimestamp(datetime: String): Long {
        return try {
            // Parse ISO datetime format: 2024-01-01T12:00:00+00:00
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX")
            format.parse(datetime)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
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
    
// FIXED: Enhanced translation function with rate limiting and exponential backoff
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

        // Try translating with rate limiting
        val translation = attemptTranslationWithRetry(text, targetLanguage, sourceLanguage)
        
        // Better detection of translation failures
        val translationFailed = translation == text || 
                               translation.contains("I'm unable to translate") ||
                               translation.contains("I cannot translate") ||
                               translation.contains("translation unavailable") || 
                               translation.contains("תרגום לא זמין") ||
                               translation.contains("Μετάφραση μη διαθέσιμη") ||
                               translation.lowercase().contains("error") ||
                               translation.isBlank() ||
                               translation.length < 10 ||
                               translation.lowercase().contains("unable to") ||
                               translation.lowercase().contains("cannot provide") ||
                               translation.lowercase().contains("i don't have")
        
        // If translation failed and we haven't tried English yet, try English as fallback
        if (translationFailed && sourceLanguage != "English") {
            println("⚠️ Translation from $sourceLanguage failed, trying English fallback...")
            val englishTranslation = attemptTranslationWithRetry(text, targetLanguage, "English")
            
            val englishFailed = englishTranslation == text || 
                               englishTranslation.contains("I'm unable to translate") ||
                               englishTranslation.contains("I cannot translate") ||
                               englishTranslation.contains("translation unavailable") ||
                               englishTranslation.lowercase().contains("error") ||
                               englishTranslation.isBlank() ||
                               englishTranslation.length < 10 ||
                               englishTranslation.lowercase().contains("unable to") ||
                               englishTranslation.lowercase().contains("cannot provide")
            
            if (!englishFailed) {
                println("✅ English fallback successful")
                return englishTranslation
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
        
        return translation
    }

    // NEW: Translation with retry logic and exponential backoff
    private fun attemptTranslationWithRetry(text: String, targetLanguage: String, sourceLanguage: String, maxRetries: Int = 3): String {
        for (attempt in 1..maxRetries) {
            try {
                val result = attemptTranslation(text, targetLanguage, sourceLanguage)
                
                // If we get the original text back, it might be a rate limit issue
                if (result != text) {
                    return result
                }
                
                // If we got rate limited, wait before retrying
                if (attempt < maxRetries) {
                    val waitTime = (attempt * 2000L) // 2s, 4s, 6s
                    println("⏳ Rate limited, waiting ${waitTime}ms before retry $attempt...")
                    Thread.sleep(waitTime)
                }
                
            } catch (e: Exception) {
                println("❌ Translation attempt $attempt failed: ${e.message}")
                if (attempt < maxRetries) {
                    val waitTime = (attempt * 1000L) // 1s, 2s, 3s
                    Thread.sleep(waitTime)
                }
            }
        }
        
        return text // Return original if all attempts failed
    }

    // UPDATED: Better error handling for rate limits
    private fun attemptTranslation(text: String, targetLanguage: String, sourceLanguage: String): String {
        return try {
            val systemPrompt = "You are a professional translator. Translate ONLY the provided text from $sourceLanguage to $targetLanguage. Provide ONLY the translation, no explanations or additional text."

            val userPrompt = if (sourceLanguage == "Russian") {
                "Translate this Russian text to $targetLanguage: $text"
            } else {
                "Translate this text to $targetLanguage: $text"
            }

            val requestBody = """{
  "model": "gpt-4o-mini",
  "messages": [
    {"role": "system", "content": "$systemPrompt"},
    {"role": "user", "content": "$userPrompt"}
  ],
  "temperature": 0.1,
  "max_tokens": 300
}"""

            val request = Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .addHeader("Authorization", "Bearer $openAiApiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val json = JSONObject(response.body?.string())
                        val translation = json.getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")
                            .trim()
                        
                        println("✅ Translation successful: '${translation.take(50)}...'")
                        translation
                    }
                    429 -> {
                        println("⚠️ Rate limit hit (429), will retry...")
                        text // Return original to trigger retry
                    }
                    else -> {
                        println("❌ Translation API failed with code: ${response.code}")
                        text
                    }
                }
            }
        } catch (e: Exception) {
            println("❌ Translation error: ${e.message}")
            text
        }
    }

    // FIXED: Better translation attempt with clearer prompts
    private fun attemptTranslation(text: String, targetLanguage: String, sourceLanguage: String): String {
        return try {
            val systemPrompt = "You are a professional translator. Translate ONLY the provided text from $sourceLanguage to $targetLanguage. Provide ONLY the translation, no explanations or additional text."

            val userPrompt = if (sourceLanguage == "Russian") {
                "Translate this Russian text to $targetLanguage: $text"
            } else {
                "Translate this text to $targetLanguage: $text"
            }

            val requestBody = """{
  "model": "gpt-4o-mini",
  "messages": [
    {"role": "system", "content": "$systemPrompt"},
    {"role": "user", "content": "$userPrompt"}
  ],
  "temperature": 0.1,
  "max_tokens": 300
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
            
            // Keep last 3-4 days of messages
            val recentMessages = processedMessages.sortedByDescending { it.timestamp }.take(500).toMutableList()
            
            // Update missing translations for older messages (limit to prevent API overuse)
            val messagesNeedingTranslation = recentMessages.filter { message ->
                val hasValidTranslations = message.translations?.let { translations ->
                    translations["en"]?.let { en ->
                        en.isNotEmpty() && 
                        en != "English translation unavailable" &&
                        en != "Translation unavailable" &&
                        !en.contains("translation unavailable") &&
                        en.length > 10
                    } == true
                } ?: false
                
                !hasValidTranslations
            }.take(5) // Limit to 5 per run to avoid API rate limits
            
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
                    Thread.sleep(500)
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
                        translation.length > 10) {
                        translation
                    } else {
                        // Runtime fallback for older messages
                        translateText(message.text, "English", "Russian")
                    }
                } ?: translateText(message.text, "English", "Russian")
                
                val hebrewText = message.translations?.get("he")?.let { translation ->
                    if (translation.isNotEmpty() && 
                        translation != "תרגום לא זמין" &&
                        translation.length > 5) {
                        translation
                    } else {
                        translateText(message.text, "Hebrew", "Russian")
                    }
                } ?: translateText(message.text, "Hebrew", "Russian")
                
                val russianText = message.translations?.get("ru") ?: message.text
                
                val greekText = message.translations?.get("el")?.let { translation ->
                    if (translation.isNotEmpty() && 
                        translation != "Μετάφραση μη διαθέσιμη" &&
                        translation.length > 10) {
                        translation
                    } else {
                        translateText(message.text, "Greek", "Russian")
                    }
                } ?: translateText(message.text, "Greek", "Russian")
                
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
        <div class="logo">🤖 AI News</div>
        <h1>🇨🇾 Cyprus Breaking News</h1>
        <p>Real-time updates from @cyprus_control</p>
        <p><strong>$currentDate</strong></p>
        <p style="font-size: 0.9rem; color: #666;">Last updated: $currentTime</p>
    </div>

    <div class="navigation">
        <a href="../index.html">🏠 Home</a>
        <a href="../cyprus/index.html">🇨🇾 Cyprus</a>
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
        <p>🤖 <strong>Automated Live Monitoring</strong></p>
        <p>• Source: <a href="https://t.me/cyprus_control" target="_blank">@cyprus_control</a></p>
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