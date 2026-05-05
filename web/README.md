# Build Portal

This package is a standalone Flask + MongoDB web portal. You can run it on a hosted platform or on a VPS.

- user registration page
- login page
- protected build dashboard
- MongoDB-based users and sessions
- remote builder proxy
- direct builder connection

## Supported deployment modes

- `Hosted Web + VPS`: the web portal runs on a hosted platform and the builder runs on a VPS.
- `Only VPS`: the web portal and builder both run on the same VPS. The web portal calls the builder through `http://127.0.0.1:8080`.

Full VPS guide:

```text
../buildersrc/README.md
```

## Repo-root deploy metadata

If you want deploy metadata to work from the repository root, keep the root-level wrapper files in sync with this folder.

## `app.json` prompts

Required:

```text
REMOTE_BUILDER_BASE_URL=https://your-builder-url
MONGODB_URL=mongodb+srv://username:password@cluster.mongodb.net/apk_cloud_launchpad
```

In Hosted Web + VPS mode, `REMOTE_BUILDER_BASE_URL` should be the public builder domain, for example:

```text
REMOTE_BUILDER_BASE_URL=https://builder.your-domain.com
```

In Only VPS mode:

```text
REMOTE_BUILDER_BASE_URL=http://127.0.0.1:8080
```

## Advanced environment variables

These are not prompted by `app.json`, but you can set them manually:

```text
REMOTE_BUILDER_TOKEN=match-builder-shared-secret
REMOTE_BUILDER_HEALTH_PATH=/health
BUILDER_REQUEST_TIMEOUT_SECONDS=900
SESSION_TTL_DAYS=30
API_RATE_LIMIT_MAX_REQUESTS=60
API_RATE_LIMIT_WINDOW_SECONDS=60
```

## Local run

Linux:

```bash
bash start-web.sh https://your-builder-url mongodb+srv://username:password@cluster.mongodb.net/apk_cloud_launchpad
```

Windows:

```powershell
.\start-web.bat https://your-builder-url mongodb+srv://username:password@cluster.mongodb.net/apk_cloud_launchpad
```

Or set both `REMOTE_BUILDER_BASE_URL` and `MONGODB_URL` in the `.env` file.

Manual run on Linux:

```bash
export REMOTE_BUILDER_BASE_URL="https://your-builder-url"
export MONGODB_URL="mongodb+srv://username:password@cluster.mongodb.net/apk_cloud_launchpad"
python3 -m pip install -r requirements.txt
export PORT=8090
python3 -m portal_app
```

Manual run on Windows:

```powershell
$env:REMOTE_BUILDER_BASE_URL='https://your-builder-url'
$env:MONGODB_URL='mongodb+srv://username:password@cluster.mongodb.net/apk_cloud_launchpad'
python -m pip install -r requirements.txt
$env:PORT='8090'
python -m portal_app
```

## UI and auth flow

- `/register` creates a new user
- `/login` signs in an existing user
- `/` is a protected dashboard for authenticated users only
- user sessions are managed through a MongoDB-backed token system

## Builder behavior

- The web portal calls the builder API directly
- You run and manage the builder service manually
- `REMOTE_BUILDER_BASE_URL` must be healthy and reachable

## Runtime notes

- `Procfile` runs Gunicorn with `portal_app:app`
- `requirements.txt` includes `pymongo` and `dnspython` so MongoDB Atlas/SRV URLs work
- The hosting platform may set `PORT` automatically
- The frontend renders in the browser, so source code cannot be hidden completely; real protection comes from authentication, backend logic, and secure headers
