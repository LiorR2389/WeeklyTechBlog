import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

data class Article(
    val title: String,
    val url: String,
    val summary: String,
    val category: String,
    val date: String
)

class NewsAggregator {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val seenArticlesFile = File("seen_articles.json")
    private val lastCheckFile = File("last_check.txt")

    private fun loadSeenArticles(): MutableSet<String> {
        return if (seenArticlesFile.exists()) {
            val json = seenArticlesFile.readText()
            val type = object : TypeToken<Set<String>>() {}.type
            gson.fromJson<Set<String>>(json, type).toMutableSet()
        } else {
            mutableSetOf()
        }
    }

    private fun saveSeenArticles(articles: Set<String>) {
        seenArticlesFile.writeText(gson.toJson(articles))
    }

    private fun fetchPage(url: String): Document? {
        return try {
            println("Fetching: $url")
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val doc = Jsoup.parse(response.body?.string() ?: "")
                println("✅ Successfully fetched $url (${doc.title()})")
                doc
            } else {
                println("❌ Failed to fetch $url: ${response.code}")
                null
            }
        } catch (e: Exception) {
            println("❌ Error fetching $url: ${e.message}")
            null
        }
    }

    private fun scrapeCyprusMail(): List<Article> {
        val articles = mutableListOf<Article>()
        println("\n🔍 Scraping Cyprus Mail...")
        val doc = fetchPage("https://cyprus-mail.com") ?: return articles

        val articleElements = doc.select("article")
        println("Found ${articleElements.size} article elements")

        // Try different selectors for Cyprus Mail
        val selectors = listOf(
            "article h2 a",
            "article h3 a",
            "article .entry-title a",
            "article a[href*='/2025/']", // Links containing 2025
            ".post-title a",
            "h2 a",
            "h3 a"
        )

        for (selector in selectors) {
            val elements = doc.select(selector)
            println("Trying selector '$selector': ${elements.size} elements")

            if (elements.size > 0) {
                println("First element: ${elements.first()?.text()?.take(100)}")

                elements.take(20).forEach { element ->
                    try {
                        val title = element.text()?.trim()
                        val url = element.absUrl("href")

                        if (!title.isNullOrEmpty() && url.isNotEmpty() && title.length > 10) {
                            val summary = generateSummary(title)
                            val category = categorizeArticle(title, summary)
                            val date = SimpleDateFormat("yyyy-MM-dd").format(Date())

                            val article = Article(title, url, summary, category, date)
                            if (articles.none { it.url == url }) { // Avoid duplicates
                                articles.add(article)
                                println("✅ Found: $title")
                            }
                        }
                    } catch (e: Exception) {
                        println("Error processing element: ${e.message}")
                    }
                }
                break // Use first working selector
            }
        }

        println("Cyprus Mail: ${articles.size} articles")
        return articles
    }

    private fun scrapeInCyprus(): List<Article> {
        val articles = mutableListOf<Article>()
        println("\n🔍 Scraping In-Cyprus...")
        val doc = fetchPage("https://in-cyprus.philenews.com") ?: return articles

        // Since we found 53 h3 a elements, let's use those
        val elements = doc.select("h3 a")
        println("Found ${elements.size} h3 a elements")

        elements.take(20).forEach { element ->
            try {
                val title = element.text()?.trim()
                val url = element.absUrl("href")

                if (!title.isNullOrEmpty() && url.isNotEmpty() && title.length > 10) {
                    val summary = generateSummary(title)
                    val category = categorizeArticle(title, summary)
                    val date = SimpleDateFormat("yyyy-MM-dd").format(Date())

                    articles.add(Article(title, url, summary, category, date))
                    println("✅ Found: $title")
                }
            } catch (e: Exception) {
                println("Error processing In-Cyprus article: ${e.message}")
            }
        }

        println("In-Cyprus: ${articles.size} articles")
        return articles
    }

    private fun scrapeSigmaLive(): List<Article> {
        val articles = mutableListOf<Article>()
        println("\n🔍 Scraping SigmaLive...")
        val doc = fetchPage("https://www.sigmalive.com") ?: return articles

        // Try broader selectors for SigmaLive
        val selectors = listOf(
            "a[href*='/news/']",
            "a[href*='/sports/']",
            "a[href*='/article']",
            ".title a",
            ".headline a",
            "h1 a",
            "h2 a",
            "h3 a",
            "a[title]"
        )

        for (selector in selectors) {
            val elements = doc.select(selector)
            println("Trying selector '$selector': ${elements.size} elements")

            if (elements.size > 0) {
                println("First element: ${elements.first()?.text()?.take(100)}")

                elements.take(20).forEach { element ->
                    try {
                        val title = element.text()?.trim()
                        val url = element.absUrl("href")

                        if (!title.isNullOrEmpty() && url.isNotEmpty() &&
                            url.contains("sigmalive.com") && title.length > 10) {
                            val summary = generateSummary(title)
                            val category = categorizeArticle(title, summary)
                            val date = SimpleDateFormat("yyyy-MM-dd").format(Date())

                            val article = Article(title, url, summary, category, date)
                            if (articles.none { it.url == url }) { // Avoid duplicates
                                articles.add(article)
                                println("✅ Found: $title")
                            }
                        }
                    } catch (e: Exception) {
                        println("Error processing element: ${e.message}")
                    }
                }
                break // Use first working selector
            }
        }

        println("SigmaLive: ${articles.size} articles")
        return articles
    }

    private fun generateSummary(title: String): String {
        return when {
            title.contains("property", true) || title.contains("real estate", true) ->
                "Property market development in Cyprus with potential impact on local economy."
            title.contains("tourism", true) || title.contains("hotel", true) ->
                "Tourism sector news affecting Cyprus hospitality industry."
            title.contains("technology", true) || title.contains("AI", true) || title.contains("tech", true) ->
                "Technology sector advancement with implications for Cyprus digital economy."
            title.contains("economy", true) || title.contains("economic", true) ->
                "Economic development news impacting Cyprus financial landscape."
            else -> "General news story relevant to Cyprus current affairs."
        }
    }

    private fun categorizeArticle(title: String, summary: String): String {
        val text = "$title $summary".lowercase()

        return when {
            text.contains("property") || text.contains("real estate") || text.contains("housing") || text.contains("apartment") || text.contains("construction") -> "Real Estate"
            text.contains("tourism") || text.contains("hotel") || text.contains("travel") || text.contains("tourist") || text.contains("visitor") -> "Tourism"
            text.contains("technology") || text.contains("tech") || text.contains("ai") || text.contains("digital") || text.contains("software") || text.contains("internet") -> "Technology"
            text.contains("economy") || text.contains("economic") || text.contains("finance") || text.contains("business") || text.contains("bank") || text.contains("investment") || text.contains("market") -> "Economy"
            text.contains("holiday") || text.contains("vacation") || text.contains("festival") || text.contains("celebration") -> "Holidays & Travel"
            text.contains("art") || text.contains("music") || text.contains("culture") || text.contains("painting") || text.contains("auction") || text.contains("exhibition") || text.contains("concert") -> "Arts & Culture"
            text.contains("police") || text.contains("arrest") || text.contains("crime") || text.contains("court") || text.contains("prison") || text.contains("attack") || text.contains("theft") -> "Crime & Justice"
            text.contains("government") || text.contains("president") || text.contains("minister") || text.contains("parliament") || text.contains("political") || text.contains("election") -> "Politics"
            text.contains("temperature") || text.contains("weather") || text.contains("celsius") || text.contains("rain") || text.contains("wind") -> "Weather"
            text.contains("health") || text.contains("hospital") || text.contains("medical") || text.contains("doctor") || text.contains("patient") -> "Health"
            text.contains("israel") || text.contains("gaza") || text.contains("evacuation") || text.contains("military") || text.contains("repatriation") -> "International Affairs"
            else -> "Other"
        }
    }

    fun aggregateNews(): List<Article> {
        val seenArticles = loadSeenArticles()
        val allArticles = mutableListOf<Article>()

        println("Starting news aggregation...")

        allArticles.addAll(scrapeCyprusMail())
        allArticles.addAll(scrapeInCyprus())
        allArticles.addAll(scrapeSigmaLive())

        val newArticles = allArticles.filter { it.url !in seenArticles }

        seenArticles.addAll(newArticles.map { it.url })
        saveSeenArticles(seenArticles)

        println("\n📊 SUMMARY:")
        println("Found ${allArticles.size} total articles")
        println("New articles: ${newArticles.size}")

        if (newArticles.isNotEmpty()) {
            println("\nSample articles:")
            newArticles.take(3).forEach { article ->
                println("- [${article.category}] ${article.title}")
            }
        }

        return newArticles
    }

    fun generateHtmlBlog(articles: List<Article>): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd")
        val currentDate = dateFormat.format(Date())

        val groupedArticles = articles.groupBy { it.category }

        val html = StringBuilder()
        html.append("""
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Weekly Cyprus Blog – $currentDate</title>
                <style>
                    body { font-family: Arial, sans-serif; line-height: 1.6; max-width: 800px; margin: 0 auto; padding: 20px; }
                    h1 { color: #2c3e50; border-bottom: 3px solid #3498db; padding-bottom: 10px; }
                    h2 { color: #34495e; margin-top: 30px; }
                    .article { margin-bottom: 15px; padding: 10px; border-left: 3px solid #3498db; }
                    .article-title { font-weight: bold; margin-bottom: 5px; }
                    .article-summary { color: #666; }
                    .article-link { color: #3498db; text-decoration: none; font-size: 0.9em; }
                    .article-link:hover { text-decoration: underline; }
                </style>
            </head>
            <body>
                <h1>Weekly Cyprus Blog – $currentDate</h1>
        """.trimIndent())

        groupedArticles.forEach { (category, categoryArticles) ->
            html.append("<h2>$category</h2>\n")
            categoryArticles.forEach { article ->
                html.append("""
                    <div class="article">
                        <div class="article-title">${article.title}</div>
                        <div class="article-summary">${article.summary}</div>
                        <a href="${article.url}" class="article-link" target="_blank">Read more</a>
                    </div>
                """.trimIndent())
            }
        }

        html.append("""
            </body>
            </html>
        """.trimIndent())

        return html.toString()
    }

    fun saveBlog(htmlContent: String): String {
        val dateFormat = SimpleDateFormat("yyyyMMdd")
        val currentDate = dateFormat.format(Date())
        val filename = "weekly_blog_$currentDate.html"

        File(filename).writeText(htmlContent)
        println("Blog saved as $filename")
        return filename
    }

    fun sendEmail(blogFilename: String) {
        val emailPassword = System.getenv("EMAIL_PASSWORD") ?: run {
            println("EMAIL_PASSWORD environment variable not set")
            return
        }

        val fromEmail = "liorre@work.gmail.com"
        val toEmail = "lior.global@gmail.com"

        val properties = Properties().apply {
            put("mail.smtp.host", "smtp.gmail.com")
            put("mail.smtp.port", "587")
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
        }

        val session = Session.getInstance(properties, object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(fromEmail, emailPassword)
            }
        })

        try {
            val blogUrl = "https://your-domain.com/$blogFilename"
            val encodedMessage = URLEncoder.encode("give me this week's blog $blogUrl", "UTF-8")
            val gptLink = "https://chatgpt.com/g/g-684aba40cbf48191895de6ea9585a001-weeklytechblog?t=$encodedMessage"

            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(fromEmail))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail))
                subject = "Weekly Cyprus Blog - ${SimpleDateFormat("yyyy-MM-dd").format(Date())}"
                setText("""
                    Your weekly Cyprus blog is ready!
                    
                    Click here to get the AI-generated summary:
                    $gptLink
                    
                    Or view the full blog at:
                    $blogUrl
                """.trimIndent())
            }

            Transport.send(message)
            println("Email sent successfully!")

        } catch (e: Exception) {
            println("Error sending email: ${e.message}")
        }
    }
}

fun main() {
    println("Starting Weekly Tech Blog Generator...")

    val aggregator = NewsAggregator()

    try {
        val articles = aggregator.aggregateNews()

        if (articles.isEmpty()) {
            println("No new articles found this week.")
            return
        }

        val htmlContent = aggregator.generateHtmlBlog(articles)
        val blogFilename = aggregator.saveBlog(htmlContent)
        aggregator.sendEmail(blogFilename)

        println("Weekly blog generation completed successfully!")

    } catch (e: Exception) {
        println("Error during blog generation: ${e.message}")
        e.printStackTrace()
    }
}