package com.projecttemp.portal

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import java.util.Comparator
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.outputStream
import kotlin.io.path.writeText

private const val MAX_LOG_ENTRIES = 250
private const val MAX_ICON_DIMENSION = 4096

class BuildJobManager(
    private val config: PortalConfig,
    private val objectMapper: ObjectMapper
) {
    private val executor = Executors.newFixedThreadPool(config.maxConcurrentBuilds)
    private val jobs = ConcurrentHashMap<String, ManagedJob>()
    private val templateEntries =
        listOf(
            "app",
            "gradle",
            "build.gradle.kts",
            "settings.gradle.kts",
            "gradle.properties",
            "gradlew",
            "gradlew.bat",
            ".gitignore"
        )

    init {
        cleanupExpiredJobs()
        loadPersistedJobs()
    }

    fun createJob(request: BuildRequest, uploadedIcon: UploadedIconPayload?): BuildJobSnapshot {
        if (uploadedIcon == null && request.iconUrl.isNullOrBlank()) {
            throw UserInputException("Upload an icon file or provide an icon URL.")
        }
        uploadedIcon?.let(::validateUploadedIcon)

        synchronized(this) {
            enforceQueueCapacity()

            val jobId = generateJobId()
            val jobRoot = config.jobsRoot.resolve(jobId)
            val workspaceRoot = jobRoot.resolve("workspace")
            val artifactRoot = jobRoot.resolve("artifact")
            val applicationId = resolveApplicationId(request)
            val now = nowIso()
            val queuedLog = JobLogEntry(now, "Build queued")
            val initialSnapshot =
                BuildJobSnapshot(
                    id = jobId,
                    state = JobState.QUEUED,
                    progress = 5,
                    step = "Queued for build",
                    appName = request.appName,
                    websiteUrl = request.websiteUrl,
                    applicationId = applicationId,
                    createdAt = now,
                    updatedAt = now,
                    logs = listOf(queuedLog)
                )

            val managedJob =
                ManagedJob(
                    request = request,
                    uploadedIcon = uploadedIcon,
                    jobRoot = jobRoot,
                    workspaceRoot = workspaceRoot,
                    artifactRoot = artifactRoot,
                    logFile = jobRoot.resolve("job.log"),
                    snapshot = initialSnapshot
                )

            jobRoot.createDirectories()
            artifactRoot.createDirectories()
            appendLogLine(managedJob, queuedLog)
            persist(managedJob)
            jobs[jobId] = managedJob

            executor.submit {
                runJob(managedJob)
            }

            return initialSnapshot
        }
    }

    fun getJob(jobId: String): BuildJobSnapshot? = jobs[jobId]?.snapshot

    fun listJobs(limit: Int = 12): List<BuildJobSnapshot> =
        jobs.values
            .map { it.snapshot }
            .sortedByDescending { it.createdAt }
            .take(limit)

    fun resolveArtifact(jobId: String): Path? {
        val job = jobs[jobId] ?: return null
        val artifactName = job.snapshot.artifactFileName ?: return null
        val artifactPath = job.artifactRoot.resolve(artifactName)
        return artifactPath.takeIf { it.exists() }
    }

    private fun loadPersistedJobs() {
        if (!config.jobsRoot.exists()) {
            return
        }

        Files.list(config.jobsRoot).use { rootEntries ->
            rootEntries
                .filter { it.isDirectory() }
                .forEach { jobRoot ->
                    val jobFile = jobRoot.resolve("job.json")
                    if (!jobFile.exists()) {
                        return@forEach
                    }

                    runCatching {
                        val snapshot = objectMapper.readValue(jobFile.toFile(), BuildJobSnapshot::class.java)
                        val persistedLogs = loadRecentLogEntries(jobRoot.resolve("job.log"))
                        val baseLogs = if (persistedLogs.isNotEmpty()) persistedLogs else snapshot.logs
                        val interruptionEntry =
                            if (snapshot.state.isActive()) {
                                JobLogEntry(nowIso(), "Server restarted before job could finish")
                            } else {
                                null
                            }
                        val normalizedSnapshot =
                            if (interruptionEntry != null) {
                                snapshot.copy(
                                    state = JobState.FAILED,
                                    progress = snapshot.progress.coerceAtLeast(1),
                                    step = "Build interrupted",
                                    errorMessage = "The build was interrupted because the server restarted or stopped.",
                                    updatedAt = interruptionEntry.timestamp,
                                    logs = (baseLogs + interruptionEntry).takeLast(MAX_LOG_ENTRIES)
                                )
                            } else {
                                snapshot.copy(logs = baseLogs.takeLast(MAX_LOG_ENTRIES))
                            }

                        val managedJob =
                            ManagedJob(
                                request = null,
                                uploadedIcon = null,
                                jobRoot = jobRoot,
                                workspaceRoot = jobRoot.resolve("workspace"),
                                artifactRoot = jobRoot.resolve("artifact"),
                                logFile = jobRoot.resolve("job.log"),
                                snapshot = normalizedSnapshot
                            )

                        if (interruptionEntry != null) {
                            appendLogLine(managedJob, interruptionEntry)
                        }

                        jobs[normalizedSnapshot.id] = managedJob
                        if (normalizedSnapshot != snapshot) {
                            persist(managedJob)
                        } else {
                            managedJob.lastPersistedAt = Instant.now()
                        }
                    }
                }
        }
    }

    private fun cleanupExpiredJobs() {
        if (!config.jobsRoot.exists()) {
            return
        }

        val cutoff = Instant.now().minus(Duration.ofHours(config.jobRetentionHours))
        Files.list(config.jobsRoot).use { rootEntries ->
            rootEntries
                .filter { it.isDirectory() }
                .forEach { jobRoot ->
                    runCatching {
                        if (resolveJobUpdatedAt(jobRoot).isBefore(cutoff)) {
                            deleteRecursively(jobRoot)
                        }
                    }
                }
        }
    }

    private fun runJob(job: ManagedJob) {
        try {
            updateJob(job, JobState.PREPARING, 12, "Preparing isolated workspace", "Creating a clean Android template workspace")
            prepareWorkspace(job)

            updateJob(job, JobState.PREPARING, 24, "Writing app configuration", "Applying submitted app settings")
            writeWebAppProperties(job)

            updateJob(job, JobState.BUILDING, 34, "Gradle build starting", "Launching Android release build")
            runGradleBuild(job)

            updateJob(job, JobState.BUILDING, 96, "Collecting APK", "Copying finished APK into builder storage")
            val artifactFile = collectArtifact(job)

            appendLog(job, "APK ready: ${artifactFile.fileName}")
            updateJob(
                job,
                JobState.COMPLETED,
                100,
                "Build complete",
                "APK is ready to download",
                artifactFileName = artifactFile.fileName.toString(),
                errorMessage = null
            )
        } catch (error: Exception) {
            val failureMessage = error.message ?: "Unknown build failure"
            appendLog(job, failureMessage)
            updateJob(
                job,
                JobState.FAILED,
                job.snapshot.progress.coerceAtLeast(1),
                "Build failed",
                "Build failed",
                errorMessage = failureMessage
            )
        }
    }

    private fun prepareWorkspace(job: ManagedJob) {
        if (!config.templateRoot.resolve("app").exists()) {
            throw IllegalStateException("Template app folder missing at ${config.templateRoot.absolutePathString()}")
        }

        if (job.workspaceRoot.exists()) {
            deleteRecursively(job.workspaceRoot)
        }
        job.workspaceRoot.createDirectories()

        templateEntries.forEach { entryName ->
            val source = config.templateRoot.resolve(entryName)
            if (!source.exists()) {
                return@forEach
            }
            copyRecursively(source, job.workspaceRoot.resolve(entryName))
        }

        val localPropertiesSource = config.templateRoot.resolve("local.properties")
        when {
            localPropertiesSource.exists() -> copyRecursively(localPropertiesSource, job.workspaceRoot.resolve("local.properties"))
            else -> createLocalPropertiesIfPossible(job.workspaceRoot)
        }

        if (!config.isWindows) {
            job.workspaceRoot.resolve("gradlew").toFile().setExecutable(true)
        }
    }

    private fun createLocalPropertiesIfPossible(workspaceRoot: Path) {
        val sdkRoot = System.getenv("ANDROID_SDK_ROOT")?.trim().orEmpty().ifBlank { System.getenv("ANDROID_HOME")?.trim().orEmpty() }
        if (sdkRoot.isBlank()) {
            return
        }

        val escapedSdkPath =
            if (config.isWindows) sdkRoot.replace("\\", "\\\\") else sdkRoot

        workspaceRoot.resolve("local.properties").writeText("sdk.dir=$escapedSdkPath")
    }

    private fun writeWebAppProperties(job: ManagedJob) {
        val request = job.request ?: throw IllegalStateException("Job request missing")
        val properties = Properties()
        val iconReference =
            if (job.uploadedIcon != null) {
                saveUploadedIcon(job)
            } else {
                request.iconUrl ?: throw IllegalStateException("Icon URL missing")
            }

        properties["appName"] = request.appName
        properties["websiteUrl"] = request.websiteUrl
        properties["iconUrl"] = iconReference
        properties["applicationId"] = request.applicationId.orEmpty()
        properties["versionCode"] = request.versionCode.toString()
        properties["versionName"] = request.versionName
        properties["minSdk"] = request.minSdk.toString()
        properties["allowCleartext"] = request.allowCleartext.toString()
        properties["iconBackgroundColor"] = request.iconBackgroundColor
        properties["userAgentSuffix"] = request.userAgentSuffix

        job.workspaceRoot.resolve("webapp.properties").outputStream().use { output ->
            properties.store(output, "Generated by buildersrc build job")
        }

        appendLog(job, "webapp.properties generated")
    }

    private fun saveUploadedIcon(job: ManagedJob): String {
        val payload = job.uploadedIcon ?: throw IllegalStateException("Uploaded icon missing")
        val extension = payload.fileName.substringAfterLast('.', "png").lowercase().takeIf { it in setOf("png", "jpg", "jpeg") } ?: "png"
        val targetDir = job.workspaceRoot.resolve("uploaded-assets").createDirectories()
        val targetFile = targetDir.resolve("icon.$extension")
        targetFile.outputStream(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { output ->
            output.write(payload.bytes)
        }
        appendLog(job, "Uploaded icon stored as ${targetFile.fileName}")
        return "uploaded-assets/icon.$extension"
    }

    private fun validateUploadedIcon(payload: UploadedIconPayload) {
        if (payload.bytes.size.toLong() > config.maxIconBytes) {
            throw UserInputException("Uploaded icon must not be larger than ${(config.maxIconBytes / (1024 * 1024)).coerceAtLeast(1)}MB.")
        }

        val extension = payload.fileName.substringAfterLast('.', "").lowercase()
        if (extension.isNotBlank() && extension !in setOf("png", "jpg", "jpeg")) {
            throw UserInputException("Uploaded icons currently support only PNG or JPG/JPEG files.")
        }

        val decoded = ImageIO.read(payload.bytes.inputStream())
        if (decoded == null) {
            throw UserInputException("Uploaded icon could not be decoded. Use a PNG or JPG/JPEG file.")
        }
        if (decoded.width <= 0 || decoded.height <= 0) {
            throw UserInputException("Uploaded icon must have valid dimensions.")
        }
        if (decoded.width > MAX_ICON_DIMENSION || decoded.height > MAX_ICON_DIMENSION) {
            throw UserInputException("Uploaded icon is too large. Keep it within ${MAX_ICON_DIMENSION}x${MAX_ICON_DIMENSION}.")
        }
    }

    private fun runGradleBuild(job: ManagedJob) {
        val command =
            if (config.isWindows) {
                listOf(
                    "cmd",
                    "/c",
                    "gradlew.bat",
                    "--console=plain",
                    "--no-daemon",
                    "packageRelease",
                    "-x",
                    "generateReleaseLintVitalReportModel",
                    "-x",
                    "lintVitalAnalyzeRelease",
                    "-x",
                    "lintVitalReportRelease",
                    "-x",
                    "lintVitalRelease"
                )
            } else {
                listOf(
                    job.workspaceRoot.resolve("gradlew").absolutePathString(),
                    "--console=plain",
                    "--no-daemon",
                    "packageRelease",
                    "-x",
                    "generateReleaseLintVitalReportModel",
                    "-x",
                    "lintVitalAnalyzeRelease",
                    "-x",
                    "lintVitalReportRelease",
                    "-x",
                    "lintVitalRelease"
                )
            }

        val processBuilder =
            ProcessBuilder(command)
                .directory(job.workspaceRoot.toFile())
                .redirectErrorStream(true)

        val environment = processBuilder.environment()
        if (!config.javaHome.isNullOrBlank()) {
            environment["JAVA_HOME"] = config.javaHome
            if (config.isWindows) {
                environment["Path"] = "${config.javaHome}\\bin;${environment["Path"].orEmpty()}"
            } else {
                environment["PATH"] = "${config.javaHome}/bin:${environment["PATH"].orEmpty()}"
            }
        }

        val process = processBuilder.start()
        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            reader.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) {
                    appendLog(job, trimmed)
                    progressHint(trimmed)?.let { hint ->
                        if (hint.first > job.snapshot.progress) {
                            updateJob(
                                job = job,
                                state = JobState.BUILDING,
                                progress = hint.first,
                                step = hint.second,
                                logMessage = trimmed,
                                appendLogEntry = false
                            )
                        }
                    }
                }
            }
        }

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw IllegalStateException("Gradle build failed with exit code $exitCode.")
        }
    }

    private fun collectArtifact(job: ManagedJob): Path {
        val artifactSource = job.workspaceRoot.resolve("app/build/outputs/apk/release/app-release.apk")
        if (!artifactSource.exists()) {
            throw IllegalStateException("The release APK was not created. Build output is missing.")
        }

        val artifactName = "${safeArtifactName(job.snapshot.appName)}.apk"
        val artifactTarget = job.artifactRoot.resolve(artifactName)
        Files.copy(artifactSource, artifactTarget, StandardCopyOption.REPLACE_EXISTING)
        return artifactTarget
    }

    private fun updateJob(
        job: ManagedJob,
        state: JobState,
        progress: Int,
        step: String,
        logMessage: String,
        artifactFileName: String? = job.snapshot.artifactFileName,
        errorMessage: String? = job.snapshot.errorMessage,
        appendLogEntry: Boolean = true
    ) {
        synchronized(job) {
            val now = Instant.now()
            val nowIso = now.toString()
            val logEntry = JobLogEntry(nowIso, logMessage)
            val logs =
                if (appendLogEntry) {
                    (job.snapshot.logs + logEntry).takeLast(MAX_LOG_ENTRIES)
                } else {
                    job.snapshot.logs
                }

            job.snapshot =
                job.snapshot.copy(
                    state = state,
                    progress = progress.coerceIn(job.snapshot.progress.coerceAtLeast(0), 100),
                    step = step,
                    artifactFileName = artifactFileName,
                    errorMessage = errorMessage,
                    updatedAt = nowIso,
                    logs = logs
                )

            if (appendLogEntry) {
                appendLogLine(job, logEntry)
            }

            persist(job, now)
        }
    }

    private fun appendLog(job: ManagedJob, message: String) {
        synchronized(job) {
            val now = Instant.now()
            val entry = JobLogEntry(now.toString(), message)
            job.snapshot =
                job.snapshot.copy(
                    updatedAt = entry.timestamp,
                    logs = (job.snapshot.logs + entry).takeLast(MAX_LOG_ENTRIES)
                )
            appendLogLine(job, entry)
            job.pendingLogEntriesSincePersist += 1
            if (shouldPersistSnapshot(job, now)) {
                persist(job, now)
            }
        }
    }

    private fun shouldPersistSnapshot(job: ManagedJob, now: Instant): Boolean =
        job.pendingLogEntriesSincePersist >= config.logPersistEveryLines ||
            Duration.between(job.lastPersistedAt, now).toMillis() >= config.logPersistIntervalMillis

    private fun persist(job: ManagedJob, persistedAt: Instant = Instant.now()) {
        job.jobRoot.createDirectories()
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(job.jobRoot.resolve("job.json").toFile(), job.snapshot)
        job.lastPersistedAt = persistedAt
        job.pendingLogEntriesSincePersist = 0
    }

    private fun appendLogLine(job: ManagedJob, entry: JobLogEntry) {
        job.jobRoot.createDirectories()
        val sanitizedMessage = entry.message.replace("\r", " ").replace("\n", " ")
        Files.writeString(
            job.logFile,
            "${entry.timestamp}\t$sanitizedMessage${System.lineSeparator()}",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND
        )
    }

    private fun loadRecentLogEntries(logFile: Path): List<JobLogEntry> {
        if (!logFile.exists()) {
            return emptyList()
        }

        val recentEntries = ArrayDeque<JobLogEntry>()
        Files.newBufferedReader(logFile, StandardCharsets.UTF_8).use { reader ->
            reader.lineSequence().forEach { line ->
                parseLogLine(line)?.let { entry ->
                    recentEntries.addLast(entry)
                    if (recentEntries.size > MAX_LOG_ENTRIES) {
                        recentEntries.removeFirst()
                    }
                }
            }
        }
        return recentEntries.toList()
    }

    private fun parseLogLine(line: String): JobLogEntry? {
        val separatorIndex = line.indexOf('\t')
        if (separatorIndex <= 0) {
            return null
        }

        val timestamp = line.substring(0, separatorIndex)
        val message = line.substring(separatorIndex + 1)
        return JobLogEntry(timestamp, message)
    }

    private fun resolveJobUpdatedAt(jobRoot: Path): Instant {
        val jobFile = jobRoot.resolve("job.json")
        if (jobFile.exists()) {
            val updatedAt =
                runCatching {
                    objectMapper.readTree(jobFile.toFile()).path("updatedAt").takeIf { !it.isMissingNode }?.asText()
                }.getOrNull()
            val parsedInstant = updatedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
            if (parsedInstant != null) {
                return parsedInstant
            }
        }

        return Files.getLastModifiedTime(jobRoot).toInstant()
    }

    private fun enforceQueueCapacity() {
        val activeJobs =
            jobs.values.count {
                it.snapshot.state.isActive()
            }

        if (activeJobs >= config.maxPendingBuilds) {
            throw TooManyRequestsException(
                message = "The build queue is currently full. Please try again shortly.",
                retryAfterSeconds = 60
            )
        }
    }

    private fun progressHint(line: String): Pair<Int, String>? =
        when {
            "Task :app:generateWebAppAssets" in line -> 40 to "Generating app assets"
            "Task :app:mergeReleaseResources" in line || "Task :app:processReleaseResources" in line -> 58 to "Processing Android resources"
            "Task :app:compileReleaseKotlin" in line || "Task :app:compileReleaseJavaWithJavac" in line -> 72 to "Compiling Android app"
            "Task :app:mergeDexRelease" in line || "Task :app:dexBuilderRelease" in line -> 84 to "Optimizing application binaries"
            "Task :app:packageRelease" in line -> 92 to "Packaging release APK"
            "BUILD SUCCESSFUL" in line -> 98 to "Finalizing build"
            else -> null
        }

    private fun copyRecursively(source: Path, target: Path) {
        if (Files.isDirectory(source)) {
            Files.walk(source).use { stream ->
                stream.forEach { current ->
                    val relative = source.relativize(current)
                    val destination = target.resolve(relative.toString())
                    if (Files.isDirectory(current)) {
                        destination.createDirectories()
                    } else {
                        destination.parent?.createDirectories()
                        Files.copy(current, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                    }
                }
            }
        } else {
            target.parent?.createDirectories()
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        }
    }

    private fun deleteRecursively(root: Path) {
        if (!root.exists()) {
            return
        }

        Files.walk(root).use { stream ->
            stream
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }

    private fun nowIso(): String = Instant.now().toString()

    private fun JobState.isActive(): Boolean =
        this == JobState.QUEUED || this == JobState.PREPARING || this == JobState.BUILDING

    private data class ManagedJob(
        val request: BuildRequest?,
        val uploadedIcon: UploadedIconPayload?,
        val jobRoot: Path,
        val workspaceRoot: Path,
        val artifactRoot: Path,
        val logFile: Path,
        var snapshot: BuildJobSnapshot,
        var lastPersistedAt: Instant = Instant.EPOCH,
        var pendingLogEntriesSincePersist: Int = 0
    )
}
