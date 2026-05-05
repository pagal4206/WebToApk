package com.projecttemp.portal

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.javalin.Javalin
import io.javalin.http.Context
import io.javalin.http.ContentType
import io.javalin.http.UploadedFile
import io.javalin.json.JavalinJackson
import java.nio.file.Path
import kotlin.io.path.absolutePathString

private const val BUILDER_TOKEN_HEADER = "X-Builder-Token"

class BuilderAuthException(message: String) : RuntimeException(message)

fun main() {
    val serviceRoot = Path.of("").toAbsolutePath().normalize()
    val config = PortalConfig.fromEnvironment(serviceRoot)
    val objectMapper =
        ObjectMapper()
            .registerModule(KotlinModule.Builder().build())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    val jobManager = BuildJobManager(config, objectMapper)

    val app =
        Javalin.create { javalinConfig ->
            javalinConfig.showJavalinBanner = false
            javalinConfig.jsonMapper(JavalinJackson(objectMapper))
        }

    app.before { ctx ->
        applyBuilderSecurityHeaders(ctx)
        if (ctx.path().startsWith("/api/")) {
            enforceBuilderToken(ctx, config)
        }
    }

    app.exception(UserInputException::class.java) { error, ctx ->
        ctx.status(400).json(mapOf("message" to (error.message ?: "Invalid request")))
    }

    app.exception(BuilderAuthException::class.java) { error, ctx ->
        ctx.status(401).json(mapOf("message" to (error.message ?: "Unauthorized")))
    }

    app.exception(TooManyRequestsException::class.java) { error, ctx ->
        error.retryAfterSeconds?.let { ctx.header("Retry-After", it.toString()) }
        ctx.status(429).json(mapOf("message" to (error.message ?: "Too many requests")))
    }

    app.exception(Exception::class.java) { error, ctx ->
        error.printStackTrace()
        ctx.status(500).json(mapOf("message" to (error.message ?: "Unexpected server error")))
    }

    app.get("/") { ctx ->
        ctx.json(
            mapOf(
                "service" to "apk-builder",
                "message" to "APK builder API is running.",
                "routes" to listOf("/health", "/api/builds", "/api/builds/{jobId}", "/api/builds/{jobId}/apk")
            )
        )
    }

    app.get("/health") { ctx ->
        ctx.json(
            mapOf(
                "status" to "ok",
                "environment" to config.describeEnvironment()
            )
        )
    }

    app.get("/api/builds") { ctx ->
        ctx.json(jobManager.listJobs())
    }

    app.get("/api/builds/{jobId}") { ctx ->
        val snapshot = jobManager.getJob(ctx.pathParam("jobId"))
        if (snapshot == null) {
            ctx.status(404).json(mapOf("message" to "Build job not found"))
        } else {
            ctx.json(snapshot)
        }
    }

    app.post("/api/builds") { ctx ->
        val iconUpload = ctx.uploadedFile("iconFile")?.toPayload()
        val request =
            buildRequestFromForm(
                appName = ctx.formParam("appName"),
                websiteUrl = ctx.formParam("websiteUrl"),
                iconUrl = ctx.formParam("iconUrl"),
                applicationId = ctx.formParam("applicationId"),
                versionCode = ctx.formParam("versionCode"),
                versionName = ctx.formParam("versionName"),
                minSdk = ctx.formParam("minSdk"),
                allowCleartext = ctx.formParam("allowCleartext") == "true",
                iconBackgroundColor = ctx.formParam("iconBackgroundColor"),
                userAgentSuffix = ctx.formParam("userAgentSuffix")
            )

        val snapshot = jobManager.createJob(request, iconUpload)
        ctx.status(202).json(snapshot)
    }

    app.get("/api/builds/{jobId}/apk") { ctx ->
        val artifact = jobManager.resolveArtifact(ctx.pathParam("jobId"))
        if (artifact == null) {
            ctx.status(404).json(mapOf("message" to "APK not ready yet"))
            return@get
        }

        ctx.contentType(ContentType.APPLICATION_OCTET_STREAM)
        ctx.header("Content-Disposition", "attachment; filename=\"${artifact.fileName}\"")
        ctx.result(artifact.toFile().inputStream())
    }

    println("APK builder ready on http://localhost:${config.port}")
    println("Template root: ${config.templateRoot.absolutePathString()}")
    println("Jobs dir: ${config.jobsRoot.absolutePathString()}")
    app.start(config.port)
}

private fun UploadedFile.toPayload(): UploadedIconPayload =
    UploadedIconPayload(
        fileName = filename(),
        bytes = content().use { it.readBytes() }
    )

private fun enforceBuilderToken(ctx: Context, config: PortalConfig) {
    val expectedSecret = config.sharedSecret?.trim().orEmpty()
    if (expectedSecret.isBlank()) {
        return
    }

    val providedSecret = ctx.header(BUILDER_TOKEN_HEADER)?.trim().orEmpty()
    if (providedSecret != expectedSecret) {
        throw BuilderAuthException("Builder token missing ya invalid hai.")
    }
}

private fun applyBuilderSecurityHeaders(ctx: Context) {
    ctx.header("X-Content-Type-Options", "nosniff")
    ctx.header("X-Frame-Options", "DENY")
    ctx.header("Referrer-Policy", "no-referrer")
    ctx.header("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
    ctx.header("Cross-Origin-Resource-Policy", "same-origin")
    ctx.header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
    if (ctx.path().startsWith("/api/")) {
        ctx.header("Cache-Control", "no-store")
    } else {
        ctx.header("Cache-Control", "no-cache")
    }
}
