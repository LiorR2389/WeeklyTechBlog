import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.Base64

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

        // For Cyprus Mail, get the title from the article's heading, not the link
        articleElements.take(20).forEach { article ->
            try {
                // Try to find title in different ways
                val titleElement = article.select("h2, h3, .entry-title, .post-title").first()
                val linkElement = article.select("a[href*='/2025/']").first()

                val title = titleElement?.text()?.trim()
                val url = linkElement?.absUrl("href")

                if (!title.isNullOrEmpty() && !url.isNullOrEmpty() && title.length > 10) {
                    val summary = generateSummary(title)
                    val category = categorizeArticle(title, summary)
                    val date = SimpleDateFormat("yyyy-MM-dd").format(Date())

                    val newArticle = Article(title, url, summary, category, date)
                    if (articles.none { it.url == url }) {
                        articles.add(newArticle)
                        println("✅ Found: $title")
                    }
                }
            } catch (e: Exception) {
                println("Error processing Cyprus Mail article: ${e.message}")
            }
        }

        println("Cyprus Mail: ${articles.size} articles")
        return articles
    }

    private fun scrapeInCyprus(): List<Article> {
        val articles = mutableListOf<Article>()
        println("\n🔍 Scraping In-Cyprus...")
        val doc = fetchPage("https://in-cyprus.philenews.com") ?: return articles

        val elements = doc.select("h3 a")
        println("Found ${elements.size} h3 a elements")

        elements.take(20).forEach { element ->
            try {
                val title = element.text()?.trim()
                val url = element.absUrl("href")

                if (!title.isNullOrEmpty() && url.isNotEmpty() && title.length > 10) {
                    // Check for duplicates
                    if (articles.none { it.url == url || it.title == title }) {
                        val summary = generateSummary(title)
                        val category = categorizeArticle(title, summary)
                        val date = SimpleDateFormat("yyyy-MM-dd").format(Date())

                        articles.add(Article(title, url, summary, category, date))
                        println("✅ Found: $title")
                    }
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

        // For SigmaLive, try to find complete article containers
        val selectors = listOf(
            ".article-item",
            ".news-item",
            ".story",
            "article",
            ".post"
        )

        for (selector in selectors) {
            val elements = doc.select(selector)
            println("Trying container selector '$selector': ${elements.size} elements")

            if (elements.size > 0) {
                elements.take(20).forEach { container ->
                    try {
                        // Look for title and link within the container
                        val titleElement = container.select("h1, h2, h3, .title, .headline").first()
                        val linkElement = container.select("a[href*='/news/'], a[href*='/article']").first()

                        val title = titleElement?.text()?.trim()
                        val url = linkElement?.absUrl("href") ?: titleElement?.parent()?.absUrl("href")

                        if (!title.isNullOrEmpty() && !url.isNullOrEmpty() &&
                            url.contains("sigmalive.com") && title.length > 5) {
                            val summary = generateSummary(title)
                            val category = categorizeArticle(title, summary)
                            val date = SimpleDateFormat("yyyy-MM-dd").format(Date())

                            val newArticle = Article(title, url, summary, category, date)
                            if (articles.none { it.url == url }) {
                                articles.add(newArticle)
                                println("✅ Found: $title")
                            }
                        }
                    } catch (e: Exception) {
                        println("Error processing SigmaLive article: ${e.message}")
                    }
                }
                break
            }
        }

        // If no containers found, try direct link approach
        if (articles.isEmpty()) {
            println("Trying direct links approach...")
            val links = doc.select("a[href*='/news/'], a[href*='/sports/']")
            println("Found ${links.size} direct links")

            links.take(20).forEach { link ->
                try {
                    val title = link.text()?.trim() ?: link.attr("title")?.trim()
                    val url = link.absUrl("href")

                    if (!title.isNullOrEmpty() && url.contains("sigmalive.com") && title.length > 5) {
                        val summary = generateSummary(title)
                        val category = categorizeArticle(title, summary)
                        val date = SimpleDateFormat("yyyy-MM-dd").format(Date())

                        val newArticle = Article(title, url, summary, category, date)
                        if (articles.none { it.url == url }) {
                            articles.add(newArticle)
                            println("✅ Found: $title")
                        }
                    }
                } catch (e: Exception) {
                    println("Error processing SigmaLive link: ${e.message}")
                }
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

        // Push to GitHub
        pushToGitHub(filename, htmlContent)

        return filename
    }

    private fun pushToGitHub(filename: String, htmlContent: String) {
        try {
            val githubToken = System.getenv("GITHUB_TOKEN") ?: run {
                println("❌ GITHUB_TOKEN environment variable not set")
                return
            }

            val githubRepo = System.getenv("GITHUB_REPO") ?: "LiorR2389/WeeklyTechBlog"
            val githubUsername = System.getenv("GITHUB_USERNAME") ?: "LiorR2389"

            println("🔍 GitHub Repository: $githubRepo")

            // GitHub API URL for creating files (simplified)
            val apiUrl = "https://api.github.com/repos/$githubRepo/contents/$filename"
            println("🔍 API URL: $apiUrl")

            // Create the request body (simplified - no SHA check for now)
            val requestBody = JSONObject().apply {
                put("message", "Add weekly blog - ${SimpleDateFormat("yyyy-MM-dd").format(Date())}")
                put("content", Base64.getEncoder().encodeToString(htmlContent.toByteArray()))
            }

            println("🔍 Request body created")

            val request = Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "token $githubToken")
                .addHeader("Accept", "application/vnd.github.v3+json")
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "WeeklyTechBlog")
                .put(RequestBody.create(
                    "application/json".toMediaType(),
                    requestBody.toString()
                ))
                .build()

            println("🔍 Making API request...")
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            println("🔍 Response code: ${response.code}")

            if (response.isSuccessful) {
                println("✅ Successfully pushed $filename to GitHub!")
                val repoName = githubRepo.split("/")[1]
                println("🌐 Blog will be available at: https://$githubUsername.github.io/$repoName/$filename")
            } else {
                println("❌ Failed to push to GitHub: ${response.code}")

                // Try alternative approach - create via different method
                tryAlternativeGitHubPush(filename, htmlContent, githubToken, githubRepo, githubUsername)
            }

        } catch (e: Exception) {
            println("❌ Error pushing to GitHub: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun tryAlternativeGitHubPush(filename: String, htmlContent: String, githubToken: String, githubRepo: String, githubUsername: String) {
        try {
            println("🔄 Trying alternative GitHub push method...")

            val currentDate = SimpleDateFormat("yyyyMMdd").format(Date())

            // Create the landing page HTML
            val landingPageName = "index.html"
            val landingPageContent = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Cyprus Blog Analyzer</title>
    <style>
        body {
            font-family: Arial, sans-serif;
            max-width: 600px;
            margin: 100px auto;
            padding: 20px;
            text-align: center;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            color: white;
            min-height: 100vh;
        }
        .container {
            background: rgba(255, 255, 255, 0.1);
            padding: 40px;
            border-radius: 20px;
            backdrop-filter: blur(10px);
        }
        .btn {
            background: #4CAF50;
            color: white;
            padding: 15px 30px;
            border: none;
            border-radius: 10px;
            font-size: 18px;
            cursor: pointer;
            margin: 10px;
            transition: all 0.3s;
            text-decoration: none;
            display: inline-block;
        }
        .btn:hover {
            background: #45a049;
            transform: translateY(-2px);
        }
    </style>
</head>
<body>
    <div class="container">
        <h1>🇨🇾 Weekly Cyprus Blog</h1>
        <p>Your automated news summary is ready!</p>
        
        <a href="#" class="btn" onclick="openBlog()">📖 Read Full Blog</a>
        <a href="#" class="btn" onclick="analyzeWithGPT()">🤖 Get AI Analysis</a>
        
        <p><small>Blog generated automatically every Monday</small></p>
    </div>

    <script>
        const blogUrl = new URLSearchParams(window.location.search).get('blog') || window.location.href.replace('index.html', 'weekly_blog_$currentDate.html');

        function openBlog() {
            window.open(blogUrl, '_blank');
        }

        function analyzeWithGPT() {
            const message = 'Please analyze this week Cyprus blog: ' + blogUrl;
            navigator.clipboard.writeText(message).then(() => {
                window.open('https://chatgpt.com/g/g-684aba40cbf48191895de6ea9585a001-weeklytechblog', '_blank');
                alert('Message copied! Paste it in ChatGPT.');
            }).catch(() => {
                window.open('https://chatgpt.com/g/g-684aba40cbf48191895de6ea9585a001-weeklytechblog', '_blank');
                prompt('Copy this message:', message);
            });
        }
    </script>
</body>
</html>
            """.trimIndent()

            // Push both blog and landing page as Gists
            val gistUrl = "https://api.github.com/gists"

            val gistBody = JSONObject().apply {
                put("description", "Weekly Cyprus Blog - ${SimpleDateFormat("yyyy-MM-dd").format(Date())}")
                put("public", true)
                put("files", JSONObject().apply {
                    put(filename, JSONObject().apply {
                        put("content", htmlContent)
                    })
                    put(landingPageName, JSONObject().apply {
                        put("content", landingPageContent)
                    })
                })
            }

            val gistRequest = Request.Builder()
                .url(gistUrl)
                .addHeader("Authorization", "token $githubToken")
                .addHeader("Accept", "application/vnd.github.v3+json")
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(
                    "application/json".toMediaType(),
                    gistBody.toString()
                ))
                .build()

            val gistResponse = client.newCall(gistRequest).execute()

            if (gistResponse.isSuccessful) {
                val gistResponseBody = gistResponse.body?.string()
                val gistJson = JSONObject(gistResponseBody ?: "{}")
                val gistHtmlUrl = gistJson.optString("html_url")
                val gistId = gistJson.optString("id")

                // Store both URLs for email
                System.setProperty("BLOG_URL", "$gistHtmlUrl#file-${filename.replace(".", "-")}")
                System.setProperty("LANDING_URL", "https://gist.githack.com/$githubUsername/$gistId/raw/index.html")

                println("✅ Created GitHub Gist: $gistHtmlUrl")
                println("📝 Blog available as Gist until repository issue is resolved")
            } else {
                println("❌ Gist creation also failed: ${gistResponse.code}")
            }

        } catch (e: Exception) {
            println("❌ Alternative method failed: ${e.message}")
        }
    }

    fun sendEmail(blogFilename: String) {
        val emailPassword = System.getenv("EMAIL_PASSWORD") ?: run {
            println("EMAIL_PASSWORD environment variable not set")
            return
        }

        val githubUsername = System.getenv("GITHUB_USERNAME") ?: "LiorR2389"
        val githubRepo = System.getenv("GITHUB_REPO") ?: "LiorR2389/WeeklyTechBlog"
        val repoName = githubRepo.split("/")[1]

        val fromEmail = "liorre.work@gmail.com"
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
            val blogUrl = System.getProperty("BLOG_URL") ?: "Blog URL not available"
            val landingUrl = System.getProperty("LANDING_URL") ?: "https://gist.github.com/$githubUsername"

            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(fromEmail))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail))
                subject = "Weekly Cyprus Blog - ${SimpleDateFormat("yyyy-MM-dd").format(Date())}"
                setText("""
                    🗞️ Your Weekly Cyprus Blog is Ready!
                    
                    🚀 ONE-CLICK ACCESS: $landingUrl
                    
                    From the landing page you can:
                    • 📖 Read the full blog
                    • 🤖 Get instant AI analysis (copies message to clipboard)
                    
                    This week's highlights:
                    • Fresh Cyprus news from multiple sources
                    • Categorized by topic for easy reading
                    • Ready for AI analysis and insights
                    
                    Generated automatically every Monday.
                """.trimIndent())
            }

            Transport.send(message)
            println("📧 Email sent successfully with GitHub blog link!")

        } catch (e: Exception) {
            println("❌ Error sending email: ${e.message}")
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

        println("✅ Weekly blog generation completed successfully!")

    } catch (e: Exception) {
        println("❌ Error during blog generation: ${e.message}")
        e.printStackTrace()
    }
}