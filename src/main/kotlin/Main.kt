import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.Base64

data class Article(
    val title: String,
    val url: String,
    val summary: String,
    val category: String,
    val date: String,
    val translations: Map<String, String> = emptyMap()
)

class NewsAggregator {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val seenArticlesFile = File("seen_articles.json")
    private val seenArticlesBackupFile = File("seen_articles_backup.json")
    private val openAiApiKey = System.getenv("OPENAI_API_KEY")
    private val githubToken = System.getenv("GITHUB_TOKEN")
    private val githubRepo = System.getenv("GITHUB_REPO") ?: "LiorR2389/WeeklyTechBlog"
    private val githubUsername = System.getenv("GITHUB_USERNAME") ?: "LiorR2389"
    private val emailPassword = System.getenv("EMAIL_PASSWORD")

    private val cyprusMailUrl = "https://cyprus-mail.com"
    private val inCyprusUrl = "https://in-cyprus.philenews.com"

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
            // Backup current file
            if (seenArticlesFile.exists()) {
                seenArticlesFile.copyTo(seenArticlesBackupFile, overwrite = true)
            }
            seenArticlesFile.writeText(gson.toJson(articles))
        } catch (e: Exception) {
            println("Error saving seen articles: ${e.message}")
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
                Jsoup.parse(response.body?.string() ?: "")
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
        return when {
            title.contains("tech", true) ||
                    title.contains("AI", true) ||
                    title.contains("digital", true) ||
                    title.contains("innovation", true) ||
                    title.contains("startup", true) ->
                "Technology sector advancement with implications for Cyprus digital economy."
            else -> "General news story relevant to Cyprus current affairs."
        }
    }

    private fun translateSummary(summary: String): Map<String, String> {
        if (openAiApiKey.isNullOrEmpty()) {
            println("⚠️ OpenAI API key not found, using fallback translations")
            return mapOf(
                "en" to summary,
                "he" to "כתבת חדשות כללית הנוגעת לענייני היום בקפריסין.",
                "ru" to "Общая новость, относящаяся к текущим событиям на Кипре.",
                "el" to "Γενική είδηση σχετική με τις τρέχουσες υποθέσεις της Κύπρου."
            )
        }

        val targetLanguages = mapOf(
            "he" to "Hebrew",
            "ru" to "Russian",
            "el" to "Greek"
        )

        val translations = mutableMapOf<String, String>()
        translations["en"] = summary

        for ((langCode, langName) in targetLanguages) {
            val translated = callOpenAITranslation(summary, langName)
            translations[langCode] = if (translated.isNotEmpty()) translated else summary
            Thread.sleep(500) // Rate limiting
        }

        println("✅ Translated summary to ${translations.size} languages")
        return translations
    }

    private fun callOpenAITranslation(text: String, language: String): String {
        return try {
            val apiUrl = "https://api.openai.com/v1/chat/completions"
            val requestBody = """
                {
                  "model": "gpt-4",
                  "messages": [
                    {"role": "system", "content": "You are a professional news translator. Translate the text accurately while maintaining the journalistic tone."},
                    {"role": "user", "content": "Translate this news summary to $language:\n\n$text"}
                  ],
                  "temperature": 0.3,
                  "max_tokens": 200
                }
            """.trimIndent()

            val request = Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer $openAiApiKey")
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create("application/json".toMediaType(), requestBody))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    println("❌ OpenAI translation failed for $language: ${response.code}")
                    return ""
                }

                val json = JSONObject(response.body?.string())
                val translatedText = json
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()

                println("✅ Translated to $language: ${translatedText.take(50)}...")
                return translatedText
            }
        } catch (e: Exception) {
            println("❌ Translation error for $language: ${e.message}")
            ""
        }
    }

    private fun categorizeArticle(title: String, summary: String): String {
        val content = "$title $summary".lowercase()
        return when {
            content.contains("tech") || content.contains("ai") ||
                    content.contains("digital") || content.contains("innovation") -> "Technology"
            content.contains("real estate") || content.contains("property") ||
                    content.contains("housing") -> "Real Estate"
            content.contains("travel") || content.contains("tourism") ||
                    content.contains("holiday") -> "Holidays & Travel"
            content.contains("business") || content.contains("economy") ||
                    content.contains("financial") -> "Business & Economy"
            else -> "General"
        }
    }

    private fun scrapeCyprusMail(): List<Article> {
        println("🔍 Scraping Cyprus Mail...")
        val doc = fetchPage(cyprusMailUrl) ?: return emptyList()

        return doc.select("article").take(20).mapNotNull { element ->
            try {
                val titleElement = element.select("h2 a, h3 a").first()
                val title = titleElement?.text()?.trim()
                val url = titleElement?.attr("abs:href")

                if (title != null && url != null && title.length > 10 && url.isNotEmpty()) {
                    val summary = generateSummary(title)
                    val translations = translateSummary(summary)

                    Article(
                        title = title,
                        url = url,
                        summary = summary,
                        category = categorizeArticle(title, summary),
                        date = SimpleDateFormat("yyyy-MM-dd").format(Date()),
                        translations = translations
                    )
                } else null
            } catch (e: Exception) {
                println("Error parsing Cyprus Mail article: ${e.message}")
                null
            }
        }
    }

    private fun scrapeInCyprus(): List<Article> {
        println("🔍 Scraping In-Cyprus...")
        val doc = fetchPage(inCyprusUrl) ?: return emptyList()

        return doc.select(".post-item, article").take(20).mapNotNull { element ->
            try {
                val titleElement = element.select(".post-title a, h2 a, h3 a").first()
                val title = titleElement?.text()?.trim()
                val url = titleElement?.attr("abs:href")

                if (title != null && url != null && title.length > 10 && url.isNotEmpty()) {
                    val summary = generateSummary(title)
                    val translations = translateSummary(summary)

                    Article(
                        title = title,
                        url = url,
                        summary = summary,
                        category = categorizeArticle(title, summary),
                        date = SimpleDateFormat("yyyy-MM-dd").format(Date()),
                        translations = translations
                    )
                } else null
            } catch (e: Exception) {
                println("Error parsing In-Cyprus article: ${e.message}")
                null
            }
        }
    }

    fun aggregateNews(): List<Article> {
        println("📰 Starting news aggregation...")
        val seen = loadSeenArticles()
        val allArticles = mutableListOf<Article>()

        // Scrape from both sources
        allArticles.addAll(scrapeCyprusMail())
        allArticles.addAll(scrapeInCyprus())

        // Filter new articles
        val newArticles = allArticles.filter { it.url !in seen }

        // Update seen articles
        seen.addAll(newArticles.map { it.url })
        saveSeenArticles(seen)

        println("📊 Found ${allArticles.size} total articles, ${newArticles.size} new articles")
        return newArticles
    }

    fun generateHtmlBlog(articles: List<Article>): String {
        val currentDate = SimpleDateFormat("yyyy-MM-dd").format(Date())
        val grouped = articles.groupBy { it.category }

        val html = StringBuilder("""
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Weekly Cyprus Blog – $currentDate</title>
                <style>
                    body { 
                        font-family: Arial, sans-serif; 
                        line-height: 1.6; 
                        max-width: 800px; 
                        margin: 0 auto; 
                        padding: 20px; 
                        background-color: #f8f9fa;
                    }
                    .container {
                        background: white;
                        padding: 30px;
                        border-radius: 10px;
                        box-shadow: 0 2px 10px rgba(0,0,0,0.1);
                    }
                    h1 { 
                        color: #2c3e50; 
                        border-bottom: 3px solid #3498db; 
                        padding-bottom: 10px; 
                        text-align: center;
                    }
                    h2 { 
                        color: #34495e; 
                        margin-top: 30px; 
                        font-size: 24px;
                    }
                    .lang-buttons { 
                        text-align: center;
                        margin: 20px 0; 
                        padding: 15px;
                        background: #ecf0f1;
                        border-radius: 8px;
                    }
                    .lang-buttons button { 
                        margin: 5px; 
                        padding: 8px 16px; 
                        border: none;
                        border-radius: 5px;
                        background: #3498db;
                        color: white;
                        cursor: pointer;
                        font-weight: bold;
                        transition: background 0.3s;
                    }
                    .lang-buttons button:hover { 
                        background: #2980b9; 
                    }
                    .lang-buttons button.active { 
                        background: #e74c3c; 
                    }
                    .article { 
                        margin-bottom: 15px; 
                        padding: 15px; 
                        border-left: 4px solid #3498db; 
                        background: #f8f9fa;
                        border-radius: 0 5px 5px 0;
                    }
                    .article-title { 
                        font-weight: bold; 
                        margin-bottom: 8px; 
                        color: #2c3e50;
                        font-size: 16px;
                    }
                    .article-summary { 
                        color: #666; 
                        margin-bottom: 8px;
                        font-style: italic;
                    }
                    .article-link { 
                        color: #3498db; 
                        text-decoration: none; 
                        font-size: 14px;
                        font-weight: bold;
                    }
                    .article-link:hover { 
                        text-decoration: underline; 
                    }
                    .lang { 
                        display: none; 
                    }
                    .lang.active { 
                        display: block; 
                    }
                    .footer {
                        text-align: center;
                        margin-top: 40px;
                        padding-top: 20px;
                        border-top: 1px solid #bdc3c7;
                        color: #7f8c8d;
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <h1>Weekly Cyprus Blog – $currentDate</h1>
                    <div class="lang-buttons">
                        <button onclick="setLang('en')" class="active" id="btn-en">🇬🇧 English</button>
                        <button onclick="setLang('he')" id="btn-he">🇮🇱 עברית</button>
                        <button onclick="setLang('ru')" id="btn-ru">🇷🇺 Русский</button>
                        <button onclick="setLang('el')" id="btn-el">🇬🇷 Ελληνικά</button>
                    </div>
        """.trimIndent())

        grouped.forEach { (category, items) ->
            html.append("\n                    <h2>$category</h2>")
            items.forEach { article ->
                html.append("""
                    <div class="article">
                        <div class="article-title">${article.title}</div>
                        <div class="lang en active">${article.translations["en"] ?: article.summary}</div>
                        <div class="lang he">${article.translations["he"] ?: "תרגום לא זמין"}</div>
                        <div class="lang ru">${article.translations["ru"] ?: "Перевод недоступен"}</div>
                        <div class="lang el">${article.translations["el"] ?: "Μετάφραση μη διαθέσιμη"}</div>
                        <a href="${article.url}" class="article-link" target="_blank">Read more</a>
                    </div>
                """.trimIndent())
            }
        }

        html.append("""
                    <div class="footer">
                        <p>Generated automatically • Updated ${SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date())}</p>
                        <p>Sources: Cyprus Mail, In-Cyprus • Powered by AI Translation</p>
                    </div>
                </div>
                <script>
                    function setLang(lang) {
                        // Remove active class from all buttons
                        document.querySelectorAll('.lang-buttons button').forEach(btn => btn.classList.remove('active'));
                        
                        // Add active class to clicked button
                        document.getElementById('btn-' + lang).classList.add('active');
                        
                        // Hide all language content
                        document.querySelectorAll('.lang').forEach(el => el.classList.remove('active'));
                        
                        // Show selected language content
                        document.querySelectorAll('.lang.' + lang).forEach(el => el.classList.add('active'));
                        
                        // Save preference
                        localStorage.setItem('preferred-language', lang);
                    }
                    
                    // Load saved language preference
                    window.onload = function() {
                        const savedLang = localStorage.getItem('preferred-language') || 'en';
                        setLang(savedLang);
                    };
                </script>
            </body>
            </html>
        """.trimIndent())

        return html.toString()
    }

    fun saveAndPush(htmlContent: String): String {
        val date = SimpleDateFormat("yyyyMMdd").format(Date())
        val filename = "weekly_blog_$date.html"

        try {
            // Save locally
            File(filename).writeText(htmlContent)
            println("💾 Blog saved locally as $filename")

            // Push to GitHub if token available
            if (!githubToken.isNullOrEmpty()) {
                return pushToGitHub(filename, htmlContent)
            } else {
                println("⚠️ GitHub token not found, skipping GitHub upload")
                return ""
            }
        } catch (e: Exception) {
            println("❌ Error saving blog: ${e.message}")
            return ""
        }
    }

    private fun pushToGitHub(filename: String, htmlContent: String): String {
        return try {
            val apiUrl = "https://api.github.com/repos/$githubRepo/contents/$filename"
            val requestBody = JSONObject().apply {
                put("message", "Add weekly Cyprus blog ${SimpleDateFormat("yyyy-MM-dd").format(Date())}")
                put("content", Base64.getEncoder().encodeToString(htmlContent.toByteArray()))
            }

            val request = Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "token $githubToken")
                .addHeader("Accept", "application/vnd.github.v3+json")
                .put(RequestBody.create("application/json".toMediaType(), requestBody.toString()))
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val blogUrl = "https://$githubUsername.github.io/${githubRepo.split("/")[1]}/$filename"
                println("🚀 Blog uploaded to GitHub: $blogUrl")
                return blogUrl
            } else {
                println("❌ GitHub upload failed: ${response.code}")
                return ""
            }
        } catch (e: Exception) {
            println("❌ GitHub upload error: ${e.message}")
            ""
        }
    }

    fun sendEmail(blogUrl: String, articleCount: Int) {
        if (emailPassword.isNullOrEmpty()) {
            println("⚠️ Email password not found, skipping email")
            return
        }

        try {
            val fromEmail = "liorre.work@gmail.com"
            val toEmail = "lior.global@gmail.com"

            val props = Properties().apply {
                put("mail.smtp.host", "smtp.gmail.com")
                put("mail.smtp.port", "587")
                put("mail.smtp.auth", "true")
                put("mail.smtp.starttls.enable", "true")
            }

            val session = Session.getInstance(props, object : jakarta.mail.Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(fromEmail, emailPassword)
                }
            })

            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(fromEmail))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail))
                subject = "🌐 Weekly Cyprus Blog Ready - $articleCount New Articles"

                val emailBody = if (blogUrl.isNotEmpty()) {
                    """
                    🇨🇾 Your Weekly Cyprus Blog is Ready!
                    
                    📊 $articleCount new articles processed
                    🌍 Available in 4 languages: English, Hebrew, Russian, Greek
                    🤖 Powered by AI translation
                    
                    📖 Read your blog: $blogUrl
                    
                    Features:
                    • Real-time language switching
                    • Categorized news sections
                    • AI-powered translations
                    • Mobile-friendly design
                    
                    Generated automatically on ${SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date())}
                    
                    —
                    Cyprus News Aggregator
                    """.trimIndent()
                } else {
                    """
                    🇨🇾 Weekly Cyprus Blog Generated
                    
                    📊 $articleCount new articles processed
                    💾 Blog saved locally (GitHub upload failed)
                    
                    Please check your local files for the latest blog.
                    
                    Generated on ${SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date())}
                    """.trimIndent()
                }

                setText(emailBody)
            }

            Transport.send(message)
            println("📧 Email sent successfully to $toEmail")
        } catch (e: Exception) {
            println("❌ Email sending failed: ${e.message}")
        }
    }
}

fun main() {
    println("🚀 Starting Weekly Cyprus Blog Generator...")
    println("📅 ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}")

    val aggregator = NewsAggregator()

    // Check required environment variables
    val requiredEnvVars = listOf("OPENAI_API_KEY", "GITHUB_TOKEN", "EMAIL_PASSWORD")
    requiredEnvVars.forEach { envVar ->
        val value = System.getenv(envVar)
        if (value.isNullOrEmpty()) {
            println("⚠️ $envVar not set")
        } else {
            println("✅ $envVar configured")
        }
    }

    try {
        // Aggregate news
        val articles = aggregator.aggregateNews()

        if (articles.isEmpty()) {
            println("ℹ️ No new articles found.")
            return
        }

        println("📝 Generating multilingual HTML blog...")
        val html = aggregator.generateHtmlBlog(articles)

        println("💾 Saving and uploading blog...")
        val blogUrl = aggregator.saveAndPush(html)

        println("📧 Sending notification email...")
        aggregator.sendEmail(blogUrl, articles.size)

        println("✅ Weekly Cyprus Blog generation complete!")
        if (blogUrl.isNotEmpty()) {
            println("🌐 Blog URL: $blogUrl")
        }

    } catch (e: Exception) {
        println("❌ Error: ${e.message}")
        e.printStackTrace()
    }
}