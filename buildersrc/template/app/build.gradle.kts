import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLConnection
import java.util.Properties
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import org.gradle.api.GradleException
import org.gradle.api.Project

plugins {
    alias(libs.plugins.android.application)
}

data class WebAppConfig(
    val appName: String,
    val websiteUrl: String,
    val iconUrl: String,
    val applicationId: String,
    val versionCode: Int,
    val versionName: String,
    val minSdk: Int,
    val allowCleartext: Boolean,
    val iconBackgroundColor: String,
    val userAgentSuffix: String
)

private val MAX_REMOTE_ICON_BYTES = 5 * 1024 * 1024
private val MAX_ICON_DIMENSION = 4096

fun writeWebAppAssets(project: Project, outputDir: File, config: WebAppConfig) {
    outputDir.deleteRecursively()
    outputDir.mkdirs()

    val sourceImage = loadIcon(project, config.iconUrl)
    val backgroundColor = parseColor(config.iconBackgroundColor)

    writeGeneratedValues(outputDir, config)
    writeAdaptiveIconXml(outputDir)
    writeLauncherMipmaps(outputDir, sourceImage, backgroundColor)
}

fun writeGeneratedValues(outputDir: File, config: WebAppConfig) {
    val valuesDir = outputDir.resolve("values").apply { mkdirs() }
    valuesDir.resolve("webapp_generated.xml").writeText(
        """
        |<?xml version="1.0" encoding="utf-8"?>
        |<resources>
        |    <string name="app_name" translatable="false">${escapeXml(config.appName)}</string>
        |    <string name="webapp_url" translatable="false">${escapeXml(config.websiteUrl)}</string>
        |    <string name="webapp_user_agent_suffix" translatable="false">${escapeXml(config.userAgentSuffix)}</string>
        |    <color name="webapp_launcher_background">${config.iconBackgroundColor}</color>
        |</resources>
        """.trimMargin()
    )
}

fun writeAdaptiveIconXml(outputDir: File) {
    val anyDpiDir = outputDir.resolve("mipmap-anydpi-v26").apply { mkdirs() }
    val adaptiveIconXml =
        """
        |<?xml version="1.0" encoding="utf-8"?>
        |<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
        |    <background android:drawable="@color/webapp_launcher_background" />
        |    <foreground android:drawable="@mipmap/webapp_launcher_foreground" />
        |</adaptive-icon>
        """.trimMargin()

    anyDpiDir.resolve("webapp_launcher.xml").writeText(adaptiveIconXml)
    anyDpiDir.resolve("webapp_launcher_round.xml").writeText(adaptiveIconXml)
}

fun writeLauncherMipmaps(outputDir: File, sourceImage: BufferedImage, backgroundColor: Color) {
    val launcherSizes =
        mapOf(
            "mipmap-mdpi" to 48,
            "mipmap-hdpi" to 72,
            "mipmap-xhdpi" to 96,
            "mipmap-xxhdpi" to 144,
            "mipmap-xxxhdpi" to 192
        )
    val adaptiveForegroundSizes =
        mapOf(
            "mipmap-mdpi" to 108,
            "mipmap-hdpi" to 162,
            "mipmap-xhdpi" to 216,
            "mipmap-xxhdpi" to 324,
            "mipmap-xxxhdpi" to 432
        )

    launcherSizes.forEach { (dirName, size) ->
        val mipmapDir = outputDir.resolve(dirName).apply { mkdirs() }
        ImageIO.write(
            createLauncherIcon(sourceImage, size, backgroundColor, maskToCircle = false),
            "png",
            mipmapDir.resolve("webapp_launcher.png")
        )
        ImageIO.write(
            createLauncherIcon(sourceImage, size, backgroundColor, maskToCircle = true),
            "png",
            mipmapDir.resolve("webapp_launcher_round.png")
        )
    }

    adaptiveForegroundSizes.forEach { (dirName, size) ->
        val mipmapDir = outputDir.resolve(dirName).apply { mkdirs() }
        ImageIO.write(
            createAdaptiveForegroundIcon(sourceImage, size),
            "png",
            mipmapDir.resolve("webapp_launcher_foreground.png")
        )
    }
}

fun loadIcon(project: Project, source: String): BufferedImage {
    val trimmedSource = source.trim()
    val iconBytes =
        if (trimmedSource.startsWith("https://", ignoreCase = true) ||
            trimmedSource.startsWith("http://", ignoreCase = true)
        ) {
            downloadRemoteIcon(trimmedSource)
        } else {
            val localFile = File(trimmedSource).takeIf { it.isAbsolute } ?: project.rootProject.file(trimmedSource)
            if (!localFile.isFile) {
                throw GradleException("Icon source not found: $trimmedSource")
            }
            localFile.readBytes()
        }

    val decoded =
        ImageIO.read(iconBytes.inputStream())
            ?: throw GradleException(
                "Icon could not be decoded. Use a PNG or JPG image URL/path in webapp.properties."
            )

    if (decoded.width <= 0 || decoded.height <= 0) {
        throw GradleException("Icon image dimensions invalid hain.")
    }
    if (decoded.width > MAX_ICON_DIMENSION || decoded.height > MAX_ICON_DIMENSION) {
        throw GradleException("Icon image bahut badi hai. ${MAX_ICON_DIMENSION}x${MAX_ICON_DIMENSION} ke andar rakho.")
    }

    return decoded
}

fun downloadRemoteIcon(source: String): ByteArray {
    val connection =
        URI(source).toURL().openConnection().let {
            it as? HttpURLConnection
                ?: throw GradleException("Only HTTP(S) icon URLs supported hain.")
        }

    connection.instanceFollowRedirects = true
    connection.connectTimeout = 15_000
    connection.readTimeout = 15_000
    connection.requestMethod = "GET"
    connection.setRequestProperty("Accept", "image/png,image/jpeg,image/*;q=0.8,*/*;q=0.5")

    try {
        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            throw GradleException("Icon URL download fail ho gaya. HTTP $responseCode mila.")
        }

        val contentLength = connection.contentLengthLong
        if (contentLength > MAX_REMOTE_ICON_BYTES) {
            throw GradleException("Icon URL file ${(MAX_REMOTE_ICON_BYTES / (1024 * 1024)).coerceAtLeast(1)}MB se badi nahi honi chahiye.")
        }

        val guessedType = connection.contentType?.substringBefore(";")?.trim().orEmpty()
        if (guessedType.isNotBlank() && !guessedType.startsWith("image/")) {
            throw GradleException("Icon URL image content return nahi kar rahi.")
        }

        return connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var totalRead = 0
            while (true) {
                val read = input.read(buffer)
                if (read == -1) {
                    break
                }
                totalRead += read
                if (totalRead > MAX_REMOTE_ICON_BYTES) {
                    throw GradleException("Icon URL file ${(MAX_REMOTE_ICON_BYTES / (1024 * 1024)).coerceAtLeast(1)}MB se badi nahi honi chahiye.")
                }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    } catch (error: IOException) {
        throw GradleException("Icon URL download nahi ho saki: ${error.message}", error)
    } finally {
        connection.disconnect()
    }
}

fun createLauncherIcon(
    sourceImage: BufferedImage,
    size: Int,
    backgroundColor: Color,
    maskToCircle: Boolean
): BufferedImage {
    val output = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val graphics = output.createGraphics()
    configureGraphics(graphics)

    if (maskToCircle) {
        graphics.clip = Ellipse2D.Float(0f, 0f, size.toFloat(), size.toFloat())
    }

    graphics.color = backgroundColor
    graphics.fillRect(0, 0, size, size)
    drawCenteredImage(sourceImage, graphics, size, (size * 0.72).roundToInt())
    graphics.dispose()
    return output
}

fun createAdaptiveForegroundIcon(sourceImage: BufferedImage, size: Int): BufferedImage {
    val output = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val graphics = output.createGraphics()
    configureGraphics(graphics)
    drawCenteredImage(sourceImage, graphics, size, (size * 0.66).roundToInt())
    graphics.dispose()
    return output
}

fun drawCenteredImage(
    sourceImage: BufferedImage,
    graphics: Graphics2D,
    canvasSize: Int,
    targetBoxSize: Int
) {
    val scale =
        minOf(
            targetBoxSize.toDouble() / sourceImage.width.toDouble(),
            targetBoxSize.toDouble() / sourceImage.height.toDouble()
        )
    val drawWidth = (sourceImage.width * scale).roundToInt().coerceAtLeast(1)
    val drawHeight = (sourceImage.height * scale).roundToInt().coerceAtLeast(1)
    val left = (canvasSize - drawWidth) / 2
    val top = (canvasSize - drawHeight) / 2
    graphics.drawImage(sourceImage, left, top, drawWidth, drawHeight, null)
}

fun configureGraphics(graphics: Graphics2D) {
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    graphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY)
}

fun parseColor(colorValue: String): Color {
    val hex = colorValue.removePrefix("#")
    return when (hex.length) {
        6 -> Color(hex.toInt(16))
        8 -> Color(hex.toLong(16).toInt(), true)
        else -> throw GradleException("Unsupported color format: $colorValue")
    }
}

fun escapeXml(value: String): String =
    value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "\\'")

fun loadWebAppConfig(rootDir: File): WebAppConfig {
    val configFile = rootDir.resolve("webapp.properties")
    if (!configFile.isFile) {
        throw GradleException("Missing webapp.properties in ${rootDir.absolutePath}")
    }

    val properties =
        Properties().apply {
            configFile.inputStream().use(::load)
        }

    fun requiredText(key: String): String =
        properties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw GradleException("Missing `$key` in ${configFile.name}")

    fun optionalText(key: String): String = properties.getProperty(key)?.trim().orEmpty()

    val appName = requiredText("appName")
    val websiteUrl = requiredText("websiteUrl")
    val iconUrl = requiredText("iconUrl")
    val configuredApplicationId = properties.getProperty("applicationId")?.trim().orEmpty()
    val applicationId =
        configuredApplicationId.takeIf { it.isNotEmpty() }
            ?: generateApplicationId(websiteUrl, appName)
    val versionCode = properties.getProperty("versionCode")?.trim()?.toIntOrNull() ?: 1
    val versionName =
        properties.getProperty("versionName")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "1.0.0"
    val minSdk = properties.getProperty("minSdk")?.trim()?.toIntOrNull() ?: 21
    val allowCleartext =
        when (val value = properties.getProperty("allowCleartext")?.trim()?.lowercase()) {
            null -> websiteUrl.startsWith("http://", ignoreCase = true)
            "true" -> true
            "false" -> false
            else -> throw GradleException("`allowCleartext` must be either true or false")
        }
    val iconBackgroundColor = normalizeColor(properties.getProperty("iconBackgroundColor")?.trim() ?: "#FFFFFF")
    val userAgentSuffix = optionalText("userAgentSuffix")

    validateWebUrl("websiteUrl", websiteUrl)
    validateApplicationId(applicationId)

    if (versionCode < 1) {
        throw GradleException("`versionCode` must be 1 or greater")
    }
    if (minSdk < 21 || minSdk > 36) {
        throw GradleException("`minSdk` must be between 21 and 36")
    }

    return WebAppConfig(
        appName = appName,
        websiteUrl = websiteUrl,
        iconUrl = iconUrl,
        applicationId = applicationId,
        versionCode = versionCode,
        versionName = versionName,
        minSdk = minSdk,
        allowCleartext = allowCleartext,
        iconBackgroundColor = iconBackgroundColor,
        userAgentSuffix = userAgentSuffix
    )
}

fun validateWebUrl(key: String, value: String) {
    parseWebsiteUri(key, value)
}

fun validateApplicationId(value: String) {
    val pattern = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    if (!pattern.matches(value)) {
        throw GradleException(
            "`applicationId` must look like a valid Android package name, for example com.example.webapp"
        )
    }
}

fun generateApplicationId(websiteUrl: String, appName: String): String {
    val uri = parseWebsiteUri("websiteUrl", websiteUrl)
    val hostSegments =
        uri.host
            ?.split(".")
            ?.filter { it.isNotBlank() }
            ?.map { sanitizePackageSegment(it, "site") }
            ?.filter { it != "www" }
            .orEmpty()
    val baseSegments =
        if (hostSegments.size >= 2) {
            hostSegments.asReversed()
        } else {
            listOf("com", "site")
        }
    val appSegment = sanitizePackageSegment(appName, "app")
    return (baseSegments + appSegment).joinToString(".")
}

fun sanitizePackageSegment(value: String, fallback: String): String {
    val normalized =
        value
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9_]+"), "_")
            .trim('_')
            .ifBlank { fallback }

    return if (normalized.first().isLetter()) {
        normalized
    } else {
        "${fallback}_$normalized"
    }
}

fun parseWebsiteUri(key: String, value: String): URI {
    val uri =
        try {
            URI(value.trim())
        } catch (error: Exception) {
            throw GradleException("`$key` must be a valid http:// or https:// URL", error)
        }

    if ((uri.scheme?.equals("https", ignoreCase = true) != true) &&
        (uri.scheme?.equals("http", ignoreCase = true) != true)
    ) {
        throw GradleException("`$key` must start with http:// or https://")
    }

    if (uri.host.isNullOrBlank()) {
        throw GradleException("`$key` must include a valid domain or host")
    }

    return uri
}

fun normalizeColor(value: String): String {
    val trimmedValue = value.trim()
    if (!trimmedValue.startsWith("#")) {
        throw GradleException("`iconBackgroundColor` must be a hex color like #FFFFFF or #FF1A1A1A")
    }

    val hex = trimmedValue.removePrefix("#")
    val normalizedHex =
        when (hex.length) {
            3 -> hex.map { "$it$it" }.joinToString("")
            6, 8 -> hex
            else -> throw GradleException("`iconBackgroundColor` only supports #RGB, #RRGGBB, or #AARRGGBB")
        }

    if (!normalizedHex.matches(Regex("^[0-9a-fA-F]+$"))) {
        throw GradleException("`iconBackgroundColor` must contain only hexadecimal digits")
    }

    return "#${normalizedHex.uppercase()}"
}

val webAppConfig = loadWebAppConfig(rootDir)
val generatedWebAppResPath = layout.buildDirectory.dir("generated/webapp/res").get().asFile

android {
    namespace = "com.projecttemp.project"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = webAppConfig.applicationId
        minSdk = webAppConfig.minSdk
        targetSdk = 36
        versionCode = webAppConfig.versionCode
        versionName = webAppConfig.versionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["usesCleartextTraffic"] = webAppConfig.allowCleartext.toString()
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }

        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    sourceSets {
        getByName("main") {
            res.srcDir(generatedWebAppResPath)
        }
    }
}

val generateWebAppAssets =
    tasks.register("generateWebAppAssets") {
        group = "webapp"
        description = "Generates launcher icons and app resources from webapp.properties"
        outputs.upToDateWhen { false }
        inputs.property("appName", webAppConfig.appName)
        inputs.property("websiteUrl", webAppConfig.websiteUrl)
        inputs.property("iconUrl", webAppConfig.iconUrl)
        inputs.property("iconBackgroundColor", webAppConfig.iconBackgroundColor)
        inputs.property("userAgentSuffix", webAppConfig.userAgentSuffix)
        outputs.dir(generatedWebAppResPath)
        doLast {
            writeWebAppAssets(project, generatedWebAppResPath, webAppConfig)
        }
    }

tasks.named("preBuild").configure {
    dependsOn(generateWebAppAssets)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.swiperefreshlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
