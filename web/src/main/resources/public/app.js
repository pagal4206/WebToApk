const stateStyles = {
  IDLE: "idle",
  QUEUED: "queued",
  PREPARING: "preparing",
  BUILDING: "building",
  COMPLETED: "completed",
  FAILED: "failed",
};

const form = document.getElementById("buildForm");
const submitButton = document.getElementById("submitButton");
const refreshButton = document.getElementById("refreshButton");
const formMessage = document.getElementById("formMessage");
const iconFileInput = document.getElementById("iconFile");
const iconUrlInput = form.elements.iconUrl;
const iconPreviewWrap = document.getElementById("iconPreviewWrap");
const iconPreview = document.getElementById("iconPreview");
const iconPreviewMeta = document.getElementById("iconPreviewMeta");
const stateBadge = document.getElementById("stateBadge");
const progressBar = document.getElementById("progressBar");
const progressValue = document.getElementById("progressValue");
const progressStep = document.getElementById("progressStep");
const summaryApp = document.getElementById("summaryApp");
const summaryPackage = document.getElementById("summaryPackage");
const summaryJob = document.getElementById("summaryJob");
const logOutput = document.getElementById("logOutput");
const downloadPanel = document.getElementById("downloadPanel");
const downloadLink = document.getElementById("downloadLink");
const errorPanel = document.getElementById("errorPanel");
const errorMessage = document.getElementById("errorMessage");

let currentJobId = null;
let pollTimer = null;
let submitHintTimers = [];
let currentPreviewObjectUrl = null;

async function fetchJson(url, options) {
  const response = await fetch(url, options);
  const data = await response.json().catch(() => ({}));

  if (response.status === 401 && response.headers.get("X-Portal-Auth-Required") === "1") {
    window.location.href = "/login";
    throw new Error("Your session expired. Please login again.");
  }

  if (!response.ok) {
    throw new Error(data.message || "Request failed");
  }

  return data;
}

function clearPreviewObjectUrl() {
  if (currentPreviewObjectUrl) {
    URL.revokeObjectURL(currentPreviewObjectUrl);
    currentPreviewObjectUrl = null;
  }
}

function updateIconPreview(source, label) {
  if (!source) {
    iconPreviewWrap.classList.add("hidden");
    iconPreview.removeAttribute("src");
    return;
  }

  iconPreview.src = source;
  iconPreviewMeta.textContent = label;
  iconPreviewWrap.classList.remove("hidden");
}

iconFileInput.addEventListener("change", () => {
  const [file] = iconFileInput.files;
  if (!file) {
    clearPreviewObjectUrl();
    if (iconUrlInput.value.trim()) {
      updateIconPreview(iconUrlInput.value.trim(), "Remote icon URL preview");
    } else {
      updateIconPreview(null, "");
    }
    return;
  }

  clearPreviewObjectUrl();
  currentPreviewObjectUrl = URL.createObjectURL(file);
  updateIconPreview(currentPreviewObjectUrl, `${file.name} selected for launcher + splash assets`);
});

iconUrlInput.addEventListener("input", () => {
  if (iconFileInput.files.length) {
    return;
  }

  clearPreviewObjectUrl();
  const url = iconUrlInput.value.trim();
  if (url) {
    updateIconPreview(url, "Remote icon URL preview");
  } else {
    updateIconPreview(null, "");
  }
});

function renderJob(snapshot) {
  const style = stateStyles[snapshot?.state] || "idle";
  const progress = snapshot?.progress || 0;

  stateBadge.textContent = snapshot ? snapshot.state.replaceAll("_", " ") : "Idle";
  stateBadge.className = `status-badge ${style}`;

  progressBar.style.width = `${progress}%`;
  progressValue.textContent = `${progress}%`;
  progressStep.textContent = snapshot?.step || "No build running right now.";
  summaryApp.textContent = snapshot?.appName || "No active build";
  summaryPackage.textContent = snapshot?.applicationId || "-";
  summaryJob.textContent = snapshot?.id || "-";

  const logs = snapshot?.logs?.map((entry) => `[${entry.timestamp}] ${entry.message}`).join("\n");
  logOutput.textContent = logs || "No logs yet.";
  logOutput.scrollTop = logOutput.scrollHeight;

  if (snapshot?.state === "COMPLETED" && snapshot?.artifactFileName) {
    downloadLink.href = `/api/builds/${snapshot.id}/apk`;
    downloadLink.download = snapshot.artifactFileName;
    downloadPanel.classList.remove("hidden");
  } else {
    downloadPanel.classList.add("hidden");
  }

  if (snapshot?.state === "FAILED") {
    errorMessage.textContent = snapshot.errorMessage || "Build failed.";
    errorPanel.classList.remove("hidden");
  } else {
    errorPanel.classList.add("hidden");
  }
}

function clearPollTimer() {
  if (pollTimer) {
    clearTimeout(pollTimer);
    pollTimer = null;
  }
}

function scheduleNextPoll(delayMs) {
  clearPollTimer();
  if (!currentJobId) {
    return;
  }
  pollTimer = window.setTimeout(pollCurrentJob, delayMs);
}

async function pollCurrentJob() {
  if (!currentJobId) {
    return;
  }

  try {
    const snapshot = await fetchJson(`/api/builds/${currentJobId}`);
    renderJob(snapshot);

    if (snapshot.state === "COMPLETED" || snapshot.state === "FAILED") {
      clearPollTimer();
      submitButton.disabled = false;
      submitButton.textContent = "Build APK";
      formMessage.textContent = snapshot.state === "COMPLETED"
        ? "Build complete. APK ready for download."
        : (snapshot.errorMessage || "Build failed. Review the logs and try again.");
      return;
    }

    scheduleNextPoll(snapshot.state === "QUEUED" ? 2600 : 2000);
  } catch (error) {
    formMessage.textContent = error.message;
    scheduleNextPoll(5000);
  }
}

function startPolling(jobId) {
  currentJobId = jobId;
  clearPollTimer();
  pollCurrentJob();
}

function clearSubmitHints() {
  submitHintTimers.forEach((timer) => clearTimeout(timer));
  submitHintTimers = [];
}

function scheduleSubmitHints() {
  clearSubmitHints();
  submitHintTimers.push(window.setTimeout(() => {
    if (submitButton.disabled) {
      formMessage.textContent = "The build service is getting ready. Your request will enter the queue as soon as it is available.";
    }
  }, 3500));
  submitHintTimers.push(window.setTimeout(() => {
    if (submitButton.disabled) {
      formMessage.textContent = "The remote build environment is still initializing. Please wait a little longer.";
    }
  }, 15000));
}

form.addEventListener("submit", async (event) => {
  event.preventDefault();

  const formData = new FormData(form);
  const hasFile = iconFileInput.files.length > 0;
  const hasUrl = Boolean(iconUrlInput.value.trim());

  if (!hasFile && !hasUrl) {
    formMessage.textContent = "You must provide either an icon upload or an icon URL.";
    return;
  }

  submitButton.disabled = true;
  submitButton.textContent = "Submitting...";
  formMessage.textContent = "Request received. Starting the protected build workflow...";
  errorPanel.classList.add("hidden");
  downloadPanel.classList.add("hidden");
  scheduleSubmitHints();

  try {
    const snapshot = await fetchJson("/api/builds", {
      method: "POST",
      body: formData,
    });

    renderJob(snapshot);
    startPolling(snapshot.id);
    formMessage.textContent = "Build queued. Progress will update live here.";
    submitButton.textContent = "Building...";
    clearSubmitHints();
  } catch (error) {
    clearSubmitHints();
    submitButton.disabled = false;
    submitButton.textContent = "Build APK";
    formMessage.textContent = error.message;
  }
});

refreshButton.addEventListener("click", () => {
  if (currentJobId) {
    pollCurrentJob();
  }
});

window.addEventListener("beforeunload", clearPreviewObjectUrl);
