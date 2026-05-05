# APK Builder Standalone

Ye folder standalone Android APK builder API hai. Isko VPS, local Linux machine, ya Windows machine par run kiya ja sakta hai.

## Is folder ke andar kya hai

- `src/`: Javalin build API
- `template/`: embedded Android WebView APK template
- `gradle/`, `gradlew`, `gradlew.bat`: local standalone Gradle wrapper

## API routes

- `GET /health`
- `GET /api/builds`
- `GET /api/builds/{jobId}`
- `POST /api/builds`
- `GET /api/builds/{jobId}/apk`

Uploaded icons ke liye PNG ya JPG/JPEG use karo.

## One command run

Linux / VPS:

```bash
bash start-builder.sh
```

Windows:

```powershell
.\start-builder.bat
```

Script pehle `.env` read karti hai. Default Linux SDK path `/opt/android-sdk` hai; agar woh nahi milta to `$HOME/android-sdk` try hota hai.

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
cp .env.example .env
```

## Required environment

- Java 17+
- Android SDK installed
- `ANDROID_SDK_ROOT` ya `ANDROID_HOME` valid hona chahiye

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

- `BUILDER_SHARED_SECRET` set karo. Isse `/api/*` routes par `X-Builder-Token` header required hoga.
- Uploaded icon size default `5MB` tak limited hai.
- Old jobs default `168` hours baad cleanup ho jate hain.
- Queue bounded hai, isliye overload me new builds `429` return kar sakte hain.
- Build logs text file me store hote hain: `builder-data/jobs/<job-id>/job.log`.

## VPS deployment

Supported modes:

- `Heroku + VPS`: Heroku web app `https://builder.your-domain.com` builder ko call karegi.
- `Only VPS`: web app `http://127.0.0.1:8080` builder ko internally call karegi.

### VPS packages

Ubuntu VPS par:

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

Assumption: repo VPS par `/opt/apk-cloud-launchpad` me rakha jayega.

```bash
sudo useradd --system --create-home --shell /usr/sbin/nologin apkcloud || true
sudo mkdir -p /opt/apk-cloud-launchpad /etc/apk-cloud-launchpad /var/lib/apk-cloud-launchpad/builder-data
sudo chown -R apkcloud:apkcloud /opt/apk-cloud-launchpad /var/lib/apk-cloud-launchpad
sudo -u apkcloud bash -lc 'cd /opt/apk-cloud-launchpad/buildersrc && chmod +x ./gradlew && ./gradlew installDist'
sudo -u apkcloud bash -lc 'cd /opt/apk-cloud-launchpad/heroku-web && python3 -m venv .venv && . .venv/bin/activate && pip install -r requirements.txt'
```

### Env files

```bash
sudo cp /opt/apk-cloud-launchpad/buildersrc/builder.env.example /etc/apk-cloud-launchpad/builder.env
sudo cp /opt/apk-cloud-launchpad/buildersrc/web.env.example /etc/apk-cloud-launchpad/web.env
sudo nano /etc/apk-cloud-launchpad/builder.env
sudo nano /etc/apk-cloud-launchpad/web.env
```

Only VPS mode me:

```text
REMOTE_BUILDER_BASE_URL=http://127.0.0.1:8080
REMOTE_BUILDER_TOKEN=same-value-as-BUILDER_SHARED_SECRET
```

Heroku + VPS mode me:

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

Heroku + VPS mode:

```bash
sudo cp /opt/apk-cloud-launchpad/buildersrc/nginx-builder-public.conf /etc/nginx/sites-available/apk-builder
sudo ln -sf /etc/nginx/sites-available/apk-builder /etc/nginx/sites-enabled/apk-builder
sudo nginx -t
sudo systemctl reload nginx
```

HTTPS ke liye:

```bash
sudo apt-get install -y certbot python3-certbot-nginx
sudo certbot --nginx
```

## Stored jobs

```text
builder-data/jobs/<job-id>
```
