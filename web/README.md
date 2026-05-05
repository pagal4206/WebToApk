# APK Cloud Launchpad Web Portal

Ye package standalone Flask + MongoDB web portal hai. Isko Heroku par bhi run kar sakte ho aur VPS par bhi.

- user registration page
- login page
- protected build dashboard
- MongoDB-based users and sessions
- remote builder proxy
- direct builder connection

[![Deploy](https://www.herokucdn.com/deploy/button.svg)](https://www.heroku.com/deploy?template=https://github.com/pagal4206/project)

## Supported deployment modes

- `Heroku + VPS`: ye web portal Heroku par chalega, builder VPS par chalega.
- `Only VPS`: ye web portal aur builder dono same VPS par chalenge. Web portal internally `http://127.0.0.1:8080` builder ko call karega.

Full VPS guide:

```text
../buildersrc/README.md
```

## Heroku deploy button

Deploy button use karne ke liye is folder ko GitHub repo ke root me rakho. Agar aap manual template URL use karna chahte ho:

```text
https://www.heroku.com/deploy?template=https://github.com/<owner>/<repo>
```

## `app.json` me kya prompt hoga

Required:

```text
REMOTE_BUILDER_BASE_URL=https://your-builder-url
MONGODB_URL=mongodb+srv://username:password@cluster.mongodb.net/apk_cloud_launchpad
```

Heroku + VPS mode me `REMOTE_BUILDER_BASE_URL` public builder domain hoga, for example:

```text
REMOTE_BUILDER_BASE_URL=https://builder.your-domain.com
```

Only VPS mode me:

```text
REMOTE_BUILDER_BASE_URL=http://127.0.0.1:8080
```

## Hidden advanced envs

Ye `app.json` me prompt nahi hote, lekin manually set kiye ja sakte hain:

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

Ya `.env` file me `REMOTE_BUILDER_BASE_URL` aur `MONGODB_URL` dono set kar do.

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

- `/register` par naya user create hota hai
- `/login` par existing user sign in karta hai
- `/` sirf authenticated users ke liye protected dashboard hai
- user sessions MongoDB-backed token system se manage hoti hain

## Builder behavior

- Web portal builder API ko directly call karta hai
- Builder service ko aap manually run aur manage karoge
- `REMOTE_BUILDER_BASE_URL` healthy aur reachable hona chahiye

## Runtime notes

- `Procfile` gunicorn ko `portal_app:app` se run karta hai
- `requirements.txt` me `pymongo` aur `dnspython` added hain taki MongoDB Atlas/SRV URL chale
- `PORT` Heroku khud set karta hai
- Frontend browser me render hota hai, isliye source ko 100% hide nahi kiya ja sakta; real protection auth, backend logic, aur secure headers se aati hai
