# APK Builder Standalone

This folder contains a standalone Android APK builder API. You can run it on a VPS, a local Linux machine, or Windows.

## What is in this folder

- `src/`: Javalin build API
- `template/`: embedded Android WebView APK template
- `gradle/`, `gradlew`, `gradlew.bat`: local standalone Gradle wrapper

## API routes

- `GET /health`
- `GET /api/builds`
- `GET /api/builds/{jobId}`
- `POST /api/builds`
- `GET /api/builds/{jobId}/apk`

Use PNG or JPG/JPEG for uploaded icons.

## One-command run

Linux / VPS:

```bash
bash start-builder.sh
```

Windows:

```powershell
.\start-builder.bat
```

The script reads `.env` first. The default Linux SDK path is `/opt/android-sdk`; if it does not exist, it falls back to `$HOME/android-sdk`.

## Manual run from inside this folder

Linux / VPS:

```bash
export ANDROID_SDK_ROOT="/opt/android-sdk"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export BUILDER_TEMPLATE_DIR="$PWD/template"
export BUILDER_DATA_DIR="$PWD/builder-data"
export PORT=8080
export BUILDER_PORT=8080
export BUILDER_SHARED_SECRET="change-this-secret"

./gradlew installDist
./build/install/buildersrc/bin/buildersrc
```

Windows:

```powershell
$env:ANDROID_SDK_ROOT='D:\Android\sdk'
$env:ANDROID_HOME=$env:ANDROID_SDK_ROOT
$env:BUILDER_TEMPLATE_DIR="$PWD\template"
$env:BUILDER_DATA_DIR="$PWD\builder-data"
$env:PORT='8080'
$env:BUILDER_PORT='8080'
$env:BUILDER_SHARED_SECRET='change-this-secret'

.\gradlew.bat installDist
.\build\install\buildersrc\bin\buildersrc.bat
```

## Optional `.env`

```bash
cp builder.env.example .env
```

## Required environment

- Java 17+
- Android SDK installed
- `ANDROID_SDK_ROOT` or `ANDROID_HOME` must point to a valid SDK location

## Optional environment

```text
BUILDER_JAVA_HOME=/path/to/jdk
BUILDER_MAX_CONCURRENT_BUILDS=1
BUILDER_MAX_PENDING_BUILDS=8
BUILDER_MAX_ICON_BYTES=5242880
BUILDER_JOB_RETENTION_HOURS=168
BUILDER_LOG_PERSIST_EVERY_LINES=20
BUILDER_LOG_PERSIST_INTERVAL_MS=2000
BUILDER_SHARED_SECRET=match-this-in-web
```

## Production hardening notes

- Set `BUILDER_SHARED_SECRET`. This makes the `X-Builder-Token` header required on `/api/*` routes.
- The default uploaded icon size limit is `5MB`.
- Old jobs are cleaned up after `168` hours by default.
- The queue is bounded, so new builds may return `429` during overload.
- Build logs are stored in `builder-data/jobs/<job-id>/job.log`.

## VPS deployment

Supported modes:

- `Hosted Web + VPS`: a hosted web app calls the builder at `https://builder.your-domain.com`.
- `Only VPS`: the web app and builder both run on the same VPS, and the web app calls `http://127.0.0.1:8080`.

### VPS packages

On Ubuntu:

```bash
sudo apt-get update
sudo apt-get install -y curl unzip git nginx python3 python3-venv python3-pip openjdk-17-jdk
```

### Android SDK

```bash
sudo mkdir -p /opt/android-sdk/cmdline-tools
sudo chown -R "$USER:$USER" /opt/android-sdk
cd /tmp
curl -L -o commandlinetools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q commandlinetools.zip -d /opt/android-sdk/cmdline-tools
mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest
export ANDROID_SDK_ROOT=/opt/android-sdk
export ANDROID_HOME=/opt/android-sdk
export PATH=/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:$PATH
yes | sdkmanager --sdk_root=/opt/android-sdk --licenses
sdkmanager --sdk_root=/opt/android-sdk "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

### App setup

Assumption: the repo is stored at `/opt/apk-cloud-launchpad` on the VPS.

```bash
sudo useradd --system --create-home --shell /usr/sbin/nologin apkcloud || true
sudo mkdir -p /opt/apk-cloud-launchpad /etc/apk-cloud-launchpad /var/lib/apk-cloud-launchpad/builder-data
sudo chown -R apkcloud:apkcloud /opt/apk-cloud-launchpad /var/lib/apk-cloud-launchpad
sudo -u apkcloud bash -lc 'cd /opt/apk-cloud-launchpad/buildersrc && chmod +x ./gradlew && ./gradlew installDist'
sudo -u apkcloud bash -lc 'cd /opt/apk-cloud-launchpad/web && python3 -m venv .venv && . .venv/bin/activate && pip install -r requirements.txt'
```

### Env files

```bash
sudo cp /opt/apk-cloud-launchpad/buildersrc/builder.env.example /etc/apk-cloud-launchpad/builder.env
sudo nano /etc/apk-cloud-launchpad/builder.env
sudo nano /etc/apk-cloud-launchpad/web.env
```

Only VPS mode:

```text
REMOTE_BUILDER_BASE_URL=http://127.0.0.1:8080
REMOTE_BUILDER_TOKEN=same-value-as-BUILDER_SHARED_SECRET
```

Hosted Web + VPS mode:

```text
REMOTE_BUILDER_BASE_URL=https://builder.your-domain.com
REMOTE_BUILDER_TOKEN=same-value-as-BUILDER_SHARED_SECRET
```

### systemd services

```bash
sudo cp /opt/apk-cloud-launchpad/buildersrc/apk-builder.service /etc/systemd/system/apk-builder.service
sudo cp /opt/apk-cloud-launchpad/buildersrc/apk-web.service /etc/systemd/system/apk-web.service
sudo systemctl daemon-reload
sudo systemctl enable --now apk-builder apk-web
```

### Nginx

Only VPS mode:

```bash
sudo cp /opt/apk-cloud-launchpad/buildersrc/nginx-vps-only.conf /etc/nginx/sites-available/apk-cloud-launchpad
sudo ln -sf /etc/nginx/sites-available/apk-cloud-launchpad /etc/nginx/sites-enabled/apk-cloud-launchpad
sudo nginx -t
sudo systemctl reload nginx
```

Hosted Web + VPS mode:

```bash
sudo cp /opt/apk-cloud-launchpad/buildersrc/nginx-builder-public.conf /etc/nginx/sites-available/apk-builder
sudo ln -sf /etc/nginx/sites-available/apk-builder /etc/nginx/sites-enabled/apk-builder
sudo nginx -t
sudo systemctl reload nginx
```

### HTTPS

```bash
sudo apt-get install -y certbot python3-certbot-nginx
sudo certbot --nginx
```

## Stored jobs

```text
builder-data/jobs/<job-id>
```
