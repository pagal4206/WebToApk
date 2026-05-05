package com.projecttemp.portal

import java.net.URI
import java.util.Locale
import java.util.UUID

enum class JobState {
    QUEUED,
    PREPARING,
    BUILDING,
    COMPLETED,
    FAILED
}

data class JobLogEntry(
    val timestamp: String,
    val message: String
)

data class BuildJobSnapshot(
    val id: String,
    val state: JobState,
    val progress: Int,
    val step: String,
    val appName: String,
    val websiteUrl: String,
    val applicationId: String,
    val artifactFileName: String? = null,
    val errorMessage: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val logs: List<JobLogEntry> = emptyList()
)

data class BuildRequest(
    val appName: String,
    val websiteUrl: String,
    val iconUrl: String?,
    val applicationId: String?,
    val versionCode: Int,
    val versionName: String,
    val minSdk: Int,
    val allowCleartext: Boolean,
    val iconBackgroundColor: String,
    val userAgentSuffix: String
)

data class UploadedIconPayload(
    val fileName: String,
    val bytes: ByteArray
)

class UserInputException(message: String) : RuntimeException(message)

class TooManyRequestsException(
    message: String,
    val retryAfterSeconds: Int? = null
) : RuntimeException(message)

fun buildRequestFromForm(
    appName: String?,
    websiteUrl: String?,
    iconUrl: String?,
    applicationId: String?,
    versionCode: String?,
    versionName: String?,
    minSdk: String?,
    allowCleartext: Boolean,
    iconBackgroundColor: String?,
    userAgentSuffix: String?
): BuildRequest {
    val cleanAppName = appName?.trim().orEmpty()
    val cleanWebsiteUrl = websiteUrl?.trim().orEmpty()
    val cleanIconUrl = iconUrl?.trim().orEmpty().ifBlank { null }
    val cleanApplicationId = applicationId?.trim().orEmpty().ifBlank { null }
    val cleanVersionName = versionName?.trim().orEmpty().ifBlank { "1.0.0" }
    val cleanUserAgentSuffix = userAgentSuffix?.trim().orEmpty()
    val cleanIconBackgroundColor = normalizeColor(iconBackgroundColor?.trim().orEmpty().ifBlank { "#FFFFFF" })
    val cleanVersionCode = versionCode?.trim()?.toIntOrNull() ?: 1
    val cleanMinSdk = minSdk?.trim()?.toIntOrNull() ?: 21

    if (cleanAppName.isBlank()) {
        throw UserInputException("App name is required.")
    }
    if (cleanWebsiteUrl.isBlank()) {
        throw UserInputException("Website URL is required.")
    }
    parseWebsiteUri(cleanWebsiteUrl)
    cleanIconUrl?.let(::parseIconUri)
    if (cleanVersionCode < 1) {
        throw UserInputException("Version code must be 1 or greater.")
    }
    if (cleanMinSdk !in 21..36) {
        throw UserInputException("Min SDK must be between 21 and 36.")
    }
    if (cleanApplicationId != null) {
        validateApplicationId(cleanApplicationId)
    }

    return BuildRequest(
        appName = cleanAppName,
        websiteUrl = cleanWebsiteUrl,
        iconUrl = cleanIconUrl,
        applicationId = cleanApplicationId,
        versionCode = cleanVersionCode,
        versionName = cleanVersionName,
        minSdk = cleanMinSdk,
        allowCleartext = allowCleartext,
        iconBackgroundColor = cleanIconBackgroundColor,
        userAgentSuffix = cleanUserAgentSuffix
    )
}

fun resolveApplicationId(request: BuildRequest): String =
    request.applicationId ?: generateApplicationId(request.websiteUrl, request.appName)

fun generateJobId(): String = "job-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"

fun parseWebsiteUri(value: String): URI = parseHttpUri(value, "Website URL")

fun parseIconUri(value: String): URI = parseHttpUri(value, "Icon URL")

private fun parseHttpUri(value: String, fieldLabel: String): URI {
    val uri =
        try {
            URI(value)
        } catch (error: Exception) {
            throw UserInputException("$fieldLabel must be a valid http:// or https:// URL.")
        }

    val scheme = uri.scheme?.lowercase(Locale.US)
    if (scheme != "http" && scheme != "https") {
        throw UserInputException("$fieldLabel must start with http:// or https://.")
    }
    if (uri.host.isNullOrBlank()) {
        throw UserInputException("$fieldLabel must include a valid domain or host.")
    }
    return uri
}

fun generateApplicationId(websiteUrl: String, appName: String): String {
    val uri = parseWebsiteUri(websiteUrl)
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

    return (baseSegments + sanitizePackageSegment(appName, "app")).joinToString(".")
}

fun validateApplicationId(value: String) {
    val pattern = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    if (!pattern.matches(value)) {
        throw UserInputException("Application ID must look like a valid Android package name, for example com.example.app.")
    }
}

fun sanitizePackageSegment(value: String, fallback: String): String {
    val normalized =
        value
            .trim()
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_]+"), "_")
            .trim('_')
            .ifBlank { fallback }

    return if (normalized.first().isLetter()) normalized else "${fallback}_$normalized"
}

fun normalizeColor(value: String): String {
    if (!value.startsWith("#")) {
        throw UserInputException("Provide the icon background color in hex format, for example #FFFFFF.")
    }

    val hex = value.removePrefix("#")
    val normalizedHex =
        when (hex.length) {
            3 -> hex.map { "$it$it" }.joinToString("")
            6, 8 -> hex
            else -> throw UserInputException("Icon background color must use #RGB, #RRGGBB, or #AARRGGBB format.")
        }

    if (!normalizedHex.matches(Regex("^[0-9a-fA-F]+$"))) {
        throw UserInputException("Icon background color must contain only hexadecimal digits.")
    }

    return "#${normalizedHex.uppercase(Locale.US)}"
}

fun safeArtifactName(value: String): String =
    value
        .trim()
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "webapp" }
