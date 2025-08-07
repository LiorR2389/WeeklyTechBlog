package com.ainews

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.UpdatesListener
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.request.GetUpdates
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
 * Monitors @cyprus_control using Telegram HTTP API
 * Much simpler approach - just monitors public channel via HTTP requests
 */

data class TelegramNewsMessage(
    val messageId: Long,
    val text: String,
    val timestamp: Long,
    val date: String,
    val isBreaking: Boolean = false,
    val priority: Int = 1, // 1=urgent, 2=important, 3=normal
    val processed: Boolean = false
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
    private val lastCheckFile = File("last_telegram_check.txt")
    
    fun start() {
        println("🚀 Starting Simple Telegram Monitor for @$channelUsername")
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
            
            // Use Telegram's public API to get channel messages
            // This works for public channels without authentication
            val url = "https://api.telegram.org/bot$getDummyBotToken/getUpdates"
            
            // Alternative: Use web scraping approach for public channels
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
            // This is a basic implementation - could be improved with JSoup
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
                        
                        val message = TelegramNewsMessage(
                            messageId = (messageText.hashCode().toLong() + timestamp), // Simple ID generation
                            text = messageText,
                            timestamp = timestamp,
                            date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(timestamp)),
                            isBreaking = isBreakingNews(messageText),
                            priority = calculatePriority(messageText)
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
    
    private fun processNewMessages(newMessages: List<TelegramNewsMessage>) {
        try {
            println("🔥 Processing ${newMessages.size} new messages...")
            
            // Add to processed messages
            val processedMessages = loadProcessedMessages().toMutableList()
            processedMessages.addAll(newMessages)
            
            // Keep only last 100 messages to avoid file getting too large
            val recentMessages = processedMessages.sortedByDescending { it.timestamp }.take(100)
            saveProcessedMessages(recentMessages)
            
            // Update live website
            updateLiveWebsite(recentMessages.take(20)) // Show last 20 on website
            
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
        
        // Translate recent messages
        val translatedMessages = if (openAiApiKey?.isNotEmpty() == true) {
            recentMessages.map { message ->
                message.copy(
                    translations = mapOf(
                        "en" to message.text,
                        "he" to translateText(message.text, "Hebrew"),
                        "ru" to translateText(message.text, "Russian"),
                        "el" to translateText(message.text, "Greek")
                    )
                )
            }
        } else {
            recentMessages.map { message ->
                message.copy(
                    translations = mapOf(
                        "en" to message.text,
                        "he" to "תרגום לעברית",
                        "ru" to "Перевод на русский",
                        "el" to "Μετάφραση στα ελληνικά"
                    )
                )
            }
        }
        
        val liveHtml = """
        <!DOCTYPE html>
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
                
                /* Language Support */
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
                
                .lang.he h1, .lang.he h2, .lang.he h3 { 
                    text-align: right; 
                    direction: rtl; 
                }
                
                .lang.he p { 
                    text-align: right; 
                    direction: rtl; 
                }
                
                .lang.he .message { 
                    border-right: 4px solid #4CAF50; 
                    border-left: none; 
                    text-align: right;
                }
                
                .lang.he .message.breaking { 
                    border-right-color: #ff4444; 
                }
                
                .lang.he .message.urgent { 
                    border-right-color: #ff9800; 
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
                }
            </style>
        </head>
        <body>
        <div class="container">
            <div class="header">
                <div class="live-indicator">🔴 LIVE</div>
                <div class="logo">🤖 AI News</div>
                
                <div class="lang en active">
                    <h1>🇨🇾 Cyprus Breaking News</h1>
                    <p>Real-time updates from @cyprus_control</p>
                    <p><strong>$currentDate</strong></p>
                    <p style="font-size: 0.9rem; color: #666;">Last updated: $currentTime</p>
                </div>
                
                <div class="lang he">
                    <h1>🇨🇾 חדשות מהירות מקפריסין</h1>
                    <p>עדכונים בזמן אמת מ-@cyprus_control</p>
                    <p><strong>$currentDate</strong></p>
                    <p style="font-size: 0.9rem; color: #666;">עודכן לאחרונה: $currentTime</p>
                </div>
                
                <div class="lang ru">
                    <h1>🇨🇾 Срочные новости Кипра</h1>
                    <p>Обновления в реальном времени от @cyprus_control</p>
                    <p><strong>$currentDate</strong></p>
                    <p style="font-size: 0.9rem; color: #666;">Последнее обновление: $currentTime</p>
                </div>
                
                <div class="lang el">
                    <h1>🇨🇾 Τελευταία Νέα από την Κύπρο</h1>
                    <p>Ενημερώσεις σε πραγματικό χρόνο από @cyprus_control</p>
                    <p><strong>$currentDate</strong></p>
                    <p style="font-size: 0.9rem; color: #666;">Τελευταία ενημέρωση: $currentTime</p>
                </div>
            </div>

            <div class="lang-buttons">
                <button onclick="setLang('en')" class="active" id="btn-en">🇬🇧 English</button>
                <button onclick="setLang('he')" id="btn-he">🇮🇱 עברית</button>
                <button onclick="setLang('ru')" id="btn-ru">🇷🇺 Русский</button>
                <button onclick="setLang('el')" id="btn-el">🇬🇷 Ελληνικά</button>
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
                    <div class="stat-number">${translatedMessages.size}</div>
                    <div class="stat-label lang en active">Recent Messages</div>
                    <div class="stat-label lang he">הודעות אחרונות</div>
                    <div class="stat-label lang ru">Последние сообщения</div>
                    <div class="stat-label lang el">Πρόσφατα μηνύματα</div>
                </div>
                <div class="stat-item">
                    <div class="stat-number">${translatedMessages.count { it.isBreaking }}</div>
                    <div class="stat-label lang en active">Breaking News</div>
                    <div class="stat-label lang he">חדשות מהירות</div>
                    <div class="stat-label lang ru">Срочные новости</div>
                    <div class="stat-label lang el">Έκτακτα νέα</div>
                </div>
                <div class="stat-item">
                    <div class="stat-number">${translatedMessages.count { it.priority == 1 }}</div>
                    <div class="stat-label lang en active">Urgent Updates</div>
                    <div class="stat-label lang he">עדכונים דחופים</div>
                    <div class="stat-label lang ru">Срочные обновления</div>
                    <div class="stat-label lang el">Επείγουσες ενημερώσεις</div>
                </div>
                <div class="stat-item">
                    <div class="stat-number">10 min</div>
                    <div class="stat-label lang en active">Update Frequency</div>
                    <div class="stat-label lang he">תדירות עדכון</div>
                    <div class="stat-label lang ru">Частота обновления</div>
                    <div class="stat-label lang el">Συχνότητα ενημέρωσης</div>
                </div>
            </div>

            ${if (translatedMessages.isEmpty()) {
                """
                <div class="no-messages">
                    <div class="lang en active">
                        <h3>No recent messages</h3>
                        <p>Monitoring @cyprus_control for breaking news...</p>
                        <p>This page updates automatically every 10 minutes</p>
                    </div>
                    <div class="lang he">
                        <h3>אין הודעות אחרונות</h3>
                        <p>מעקב אחר @cyprus_control לחדשות מהירות...</p>
                        <p>העמוד מתעדכן אוטומטית כל 10 דקות</p>
                    </div>
                    <div class="lang ru">
                        <h3>Нет последних сообщений</h3>
                        <p>Мониторинг @cyprus_control для срочных новостей...</p>
                        <p>Страница обновляется автоматически каждые 10 минут</p>
                    </div>
                    <div class="lang el">
                        <h3>Δεν υπάρχουν πρόσφατα μηνύματα</h3>
                        <p>Παρακολούθηση @cyprus_control για έκτακτα νέα...</p>
                        <p>Η σελίδα ενημερώνεται αυτόματα κάθε 10 λεπτά</p>
                    </div>
                </div>
                """.trimIndent()
            } else {
                translatedMessages.sortedByDescending { it.timestamp }.joinToString("\n") { message ->
                    val priorityClass = "priority-${message.priority}"
                    val messageClass = when {
                        message.isBreaking -> "message breaking"
                        message.priority == 1 -> "message urgent"
                        else -> "message"
                    }
                    
                    """
                    <div class="$messageClass">
                        <div class="timestamp">📅 ${message.date}</div>
                        
                        <div class="priority $priorityClass lang en active">
                            ${when(message.priority) {
                                1 -> "🔥 URGENT"
                                2 -> "⚡ IMPORTANT"
                                else -> "📢 NEWS"
                            }}
                        </div>
                        <div class="priority $priorityClass lang he">
                            ${when(message.priority) {
                                1 -> "🔥 דחוף"
                                2 -> "⚡ חשוב"
                                else -> "📢 חדשות"
                            }}
                        </div>
                        <div class="priority $priorityClass lang ru">
                            ${when(message.priority) {
                                1 -> "🔥 СРОЧНО"
                                2 -> "⚡ ВАЖНО"
                                else -> "📢 НОВОСТИ"
                            }}
                        </div>
                        <div class="priority $priorityClass lang el">
                            ${when(message.priority) {
                                1 -> "🔥 ΕΠΕΙΓΟΝ"
                                2 -> "⚡ ΣΗΜΑΝΤΙΚΟ"
                                else -> "📢 ΕΙΔΗΣΕΙΣ"
                            }}
                        </div>
                        
                        <div class="text lang en active">${message.translations["en"] ?: message.text}</div>
                        <div class="text lang he">${message.translations["he"] ?: "תרגום לעברית"}</div>
                        <div class="text lang ru">${message.translations["ru"] ?: "Перевод на русский"}</div>
                        <div class="text lang el">${message.translations["el"] ?: "Μετάφραση στα ελληνικά"}</div>
                    </div>
                    """.trimIndent()
                }
            }}

            <div class="footer">
                <div class="lang en active">
                    <p>🤖 <strong>Automated Live Monitoring</strong></p>
                    <p>Updates every 10 minutes • Source: <a href="https://t.me/cyprus_control" target="_blank">@cyprus_control</a></p>
                    <p><a href="https://ainews.eu.com">ainews.eu.com</a></p>
                    <p style="margin-top: 15px; font-size: 0.8rem;">
                        This page automatically refreshes every 5 minutes<br>
                        For daily comprehensive news, visit our <a href="../index.html">main homepage</a>
                    </p>
                </div>
                <div class="lang he">
                    <p>🤖 <strong>מעקב אוטומטי בזמן אמת</strong></p>
                    <p>מתעדכן כל 10 דקות • מקור: <a href="https://t.me/cyprus_control" target="_blank">@cyprus_control</a></p>
                    <p><a href="https://ainews.eu.com">ainews.eu.com</a></p>
                    <p style="margin-top: 15px; font-size: 0.8rem;">
                        העמוד מתרענן אוטומטית כל 5 דקות<br>
                        לחדשות יומיות מקיפות, בקרו ב<a href="../index.html">עמוד הבית הראשי</a>
                    </p>
                </div>
                <div class="lang ru">
                    <p>🤖 <strong>Автоматический мониторинг в реальном времени</strong></p>
                    <p>Обновления каждые 10 минут • Источник: <a href="https://t.me/cyprus_control" target="_blank">@cyprus_control</a></p>
                    <p><a href="https://ainews.eu.com">ainews.eu.com</a></p>
                    <p style="margin-top: 15px; font-size: 0.8rem;">
                        Страница автоматически обновляется каждые 5 минуты<br>
                        Для ежедневных подробных новостей посетите нашу <a href="../index.html">главную страницу</a>
                    </p>
                </div>
                <div class="lang el">
                    <p>🤖 <strong>Αυτόματη παρακολούθηση σε πραγματικό χρόνο</strong></p>
                    <p>Ενημερώσεις κάθε 10 λεπτά • Πηγή: <a href="https://t.me/cyprus_control" target="_blank">@cyprus_control</a></p>
                    <p><a href="https://ainews.eu.com">ainews.eu.com</a></p>
                    <p style="margin-top: 15px; font-size: 0.8rem;">
                        Η σελίδα ανανεώνεται αυτόματα κάθε 5 λεπτά<br>
                        Για καθημερινά περιεκτικά νέα, επισκεφθείτε την <a href="../index.html">κύρια αρχική σελίδα</a>
                    </p>
                </div>
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
                
                // Update body direction for Hebrew
                if (lang === 'he') {
                    document.body.setAttribute('dir', 'rtl');
                } else {
                    document.body.setAttribute('dir', 'ltr');
                }
            }

            // Initialize
            document.addEventListener('DOMContentLoaded', function() {
                setLang('en');
            });
        </script>
        </body>
        </html>
        """.trimIndent()
        
        File("live_news.html").writeText(liveHtml)
        println("📄 Live website updated with ${translatedMessages.size} recent messages (with translations)")
    }; 
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
                    <div class="stat-number">${recentMessages.count { it.isBreaking }}</div>
                    <div class="stat-label">Breaking News</div>
                </div>
                <div class="stat-item">
                    <div class="stat-number">${recentMessages.count { it.priority == 1 }}</div>
                    <div class="stat-label">Urgent Updates</div>
                </div>
                <div class="stat-item">
                    <div class="stat-number">10 min</div>
                    <div class="stat-label">Update Frequency</div>
                </div>
            </div>

            ${if (recentMessages.isEmpty()) {
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
                    
                    """
                    <div class="$messageClass">
                        <div class="timestamp">📅 ${message.date}</div>
                        <div class="priority $priorityClass">
                            ${when(message.priority) {
                                1 -> "🔥 URGENT"
                                2 -> "⚡ IMPORTANT"
                                else -> "📢 NEWS"
                            }}
                        </div>
                        <div class="text">${message.text}</div>
                    </div>
                    """.trimIndent()
                }
            }}

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
        </html>
        """.trimIndent()
        
        File("live_news.html").writeText(liveHtml)
        println("📄 Live website updated with ${recentMessages.size} recent messages")
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

            // Upload file
            val base64Content = Base64.getEncoder().encodeToString(content.toByteArray())
            val requestBody = JSONObject().apply {
                put("message", "Update live news - ${SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date())}")
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
                if (response.isSuccessful) {
                    println("✅ Successfully uploaded $filePath")
                } else {
                    println("❌ Failed to upload $filePath: ${response.code}")
                }
            }
        } catch (e: Exception) {
            println("❌ Error uploading $filePath: ${e.message}")
        }
    }
    
    // NEW: Translation function for live messages
    private fun translateText(text: String, targetLanguage: String): String {
        if (openAiApiKey.isNullOrEmpty()) {
            return when (targetLanguage) {
                "Hebrew" -> "תרגום לעברית"
                "Russian" -> "Перевод на русский"
                "Greek" -> "Μετάφραση στα ελληνικά"
                else -> text
            }
        }

        return try {
            val requestBody = """
                {
                  "model": "gpt-4o-mini",
                  "messages": [
                    {"role": "system", "content": "Translate breaking news messages accurately and concisely. Keep the urgency and tone."},
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
                    
                    println("🌐 Translated to $targetLanguage: ${translation.take(50)}...")
                    translation
                } else {
                    println("❌ Translation failed for $targetLanguage")
                    text
                }
            }
        } catch (e: Exception) {
            println("❌ Translation error for $targetLanguage: ${e.message}")
            text
        }
    }
}

fun main() {
    println("🔴 Starting TELEGRAM Live News Scraper for @cyprus_control...")
    println("📱 Using web scraping approach (no authentication needed)")
    println("⚡ Running in CRON MODE - single check and exit")
    
    // Environment variables check
    val githubToken = System.getenv("GITHUB_TOKEN")
    
    println("\n🔧 Configuration Check:")
    println("   GITHUB_TOKEN: ${if (githubToken.isNullOrEmpty()) "❌ MISSING" else "✅ SET"}")
    println("   Target Channel: @cyprus_control")
    println("   Method: Web scraping (public channel)")
    
    try {
        val scraper = TelegramLiveScraper()
        scraper.start()
        
        println("✅ Telegram scraper completed successfully")
        
    } catch (e: Exception) {
        println("❌ Fatal error: ${e.message}")
        e.printStackTrace()
        kotlin.system.exitProcess(1) // Exit with error code for cron job
    }
    
    println("🏁 Exiting...")
}