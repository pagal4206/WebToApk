package com.projecttemp.portal

import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

data class PortalConfig(
    val projectRoot: Path,
    val templateRoot: Path,
    val dataRoot: Path,
    val jobsRoot: Path,
    val port: Int,
    val javaHome: String?,
    val maxConcurrentBuilds: Int,
    val maxPendingBuilds: Int,
    val maxIconBytes: Long,
    val jobRetentionHours: Long,
    val logPersistEveryLines: Int,
    val logPersistIntervalMillis: Long,
    val sharedSecret: String?,
    val isWindows: Boolean
) {
    companion object {
        fun fromEnvironment(projectRoot: Path): PortalConfig {
            val root = projectRoot.absolute().normalize()
            val templateRoot =
                resolvePath(System.getenv("BUILDER_TEMPLATE_DIR"), root)
                    ?.absolute()
                    ?.normalize()
                    ?: root.resolve("template")
            val dataRoot =
                resolvePath(System.getenv("BUILDER_DATA_DIR"), root)
                    ?.absolute()
                    ?.normalize()
                    ?: root.resolve("builder-data")
            val jobsRoot = dataRoot.resolve("jobs")
            val port = (System.getenv("BUILDER_PORT") ?: System.getenv("PORT"))?.toIntOrNull() ?: 8080
            val javaHome = firstNonBlank(System.getenv("BUILDER_JAVA_HOME"), System.getenv("JAVA_HOME"))
            val maxConcurrentBuilds = parsePositiveInt("BUILDER_MAX_CONCURRENT_BUILDS", 1)
            val maxPendingBuilds = parsePositiveInt("BUILDER_MAX_PENDING_BUILDS", maxOf(maxConcurrentBuilds * 4, 8))
            val maxIconBytes = parsePositiveLong("BUILDER_MAX_ICON_BYTES", 5L * 1024 * 1024)
            val jobRetentionHours = parsePositiveLong("BUILDER_JOB_RETENTION_HOURS", 168)
            val logPersistEveryLines = parsePositiveInt("BUILDER_LOG_PERSIST_EVERY_LINES", 20)
            val logPersistIntervalMillis = parsePositiveLong("BUILDER_LOG_PERSIST_INTERVAL_MS", 2_000)
            val sharedSecret = firstNonBlank(System.getenv("BUILDER_SHARED_SECRET"))
            val isWindows = System.getProperty("os.name").contains("win", ignoreCase = true)

            jobsRoot.createDirectories()

            return PortalConfig(
                projectRoot = root,
                templateRoot = templateRoot,
                dataRoot = dataRoot,
                jobsRoot = jobsRoot,
                port = port,
                javaHome = javaHome,
                maxConcurrentBuilds = maxConcurrentBuilds,
                maxPendingBuilds = maxPendingBuilds,
                maxIconBytes = maxIconBytes,
                jobRetentionHours = jobRetentionHours,
                logPersistEveryLines = logPersistEveryLines,
                logPersistIntervalMillis = logPersistIntervalMillis,
                sharedSecret = sharedSecret,
                isWindows = isWindows
            )
        }

        private fun resolvePath(rawValue: String?, projectRoot: Path): Path? {
            val trimmed = rawValue?.trim().orEmpty()
            if (trimmed.isEmpty()) {
                return null
            }

            val candidate = Path.of(trimmed)
            return if (candidate.isAbsolute) candidate else projectRoot.resolve(candidate)
        }

        private fun firstNonBlank(vararg values: String?): String? =
            values.firstOrNull { !it.isNullOrBlank() }?.trim()

        private fun parsePositiveInt(name: String, defaultValue: Int): Int =
            System.getenv(name)?.trim()?.toIntOrNull()?.coerceAtLeast(1) ?: defaultValue

        private fun parsePositiveLong(name: String, defaultValue: Long): Long =
            System.getenv(name)?.trim()?.toLongOrNull()?.coerceAtLeast(1) ?: defaultValue
    }
}

fun PortalConfig.describeEnvironment(): Map<String, Any> =
    mapOf(
        "port" to port,
        "serviceRoot" to projectRoot.absolutePathString(),
        "templateRoot" to templateRoot.absolutePathString(),
        "dataRoot" to dataRoot.absolutePathString(),
        "jobsRoot" to jobsRoot.absolutePathString(),
        "javaHomeConfigured" to !javaHome.isNullOrBlank(),
        "maxConcurrentBuilds" to maxConcurrentBuilds,
        "maxPendingBuilds" to maxPendingBuilds,
        "maxIconBytes" to maxIconBytes,
        "jobRetentionHours" to jobRetentionHours,
        "logPersistEveryLines" to logPersistEveryLines,
        "logPersistIntervalMillis" to logPersistIntervalMillis,
        "sharedSecretConfigured" to !sharedSecret.isNullOrBlank(),
        "templateReady" to templateRoot.resolve("app").exists(),
        "localPropertiesPresent" to (
            templateRoot.resolve("local.properties").exists() ||
                !firstNonBlank(System.getenv("ANDROID_SDK_ROOT"), System.getenv("ANDROID_HOME")).isNullOrBlank()
            )
    )

private fun firstNonBlank(vararg values: String?): String? =
    values.firstOrNull { !it.isNullOrBlank() }?.trim()
