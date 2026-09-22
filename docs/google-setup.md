# Google Cloud setup for ClearTravel

ClearTravel needs a Google Cloud project only for **client-side** keys: the Maps SDK
key (trip map view) and an OAuth client for the optional Google account features
(Calendar sync, Drive uploads, backup to Drive). There is no server component.
Everything degrades gracefully when these values are absent — the app builds and
runs fully offline with empty defaults.

## 1. Create the Google Cloud project

1. Open <https://console.cloud.google.com/> and create a project (e.g.
   `cleartravel`).
2. Note the project — every step below happens inside it.

## 2. Enable the APIs

In **APIs & Services → Library**, enable:

- **Maps SDK for Android** (trip map view)
- **Google Calendar API** (Calendar sync)
- **Google Drive API** (attachment uploads + backups)

## 3. Create the Maps API key

1. **APIs & Services → Credentials → Create credentials → API key.**
2. Restrict it (strongly recommended):
   - *Application restrictions* → **Android apps** → add package
     `com.itsluminous.cleartravel` + your signing certificate's **SHA-1**.
     - Debug SHA-1: `keytool -list -v -alias androiddebugkey -keystore ~/.android/debug.keystore -storepass android`
     - Release SHA-1: `keytool -list -v -alias <key alias> -keystore <your keystore>`
     - Add BOTH if you build both variants.
   - *API restrictions* → **Maps SDK for Android** only.
3. Copy the key — it goes into `local.properties` (step 6).

## 4. Configure the OAuth consent screen

1. **APIs & Services → OAuth consent screen** (a.k.a. Google Auth Platform).
2. User type **External**, app name "ClearTravel", your support email.
3. Under **Data Access / Scopes**, add the two scopes the app requests
   incrementally:
   - `https://www.googleapis.com/auth/calendar.app.created`
   - `https://www.googleapis.com/auth/drive.file`

   A missing scope here makes the in-app toggle's authorization step fail even
   though linking works.
4. While the app is in *Testing* publishing status, add your Google account under
   **Test users** — other accounts cannot complete consent.

## 5. Create the OAuth clients

Two clients are involved; the id you configure in the app is the **Web** one:

1. **Credentials → Create credentials → OAuth client ID → Android**.
   - Package name `com.itsluminous.cleartravel`, plus the same SHA-1(s) as step 3.
   - This client is required for Google Play services to trust the app's
     signature. Its id is **NOT** used in the app config.
2. **Credentials → Create credentials → OAuth client ID → Web application**.
   - Name it e.g. `cleartravel-web`. No redirect URIs are needed.
   - **Copy THIS client id** — it is the `GOOGLE_WEB_CLIENT_ID` value.

> ⚠️ **Error `[28444]` ("Developer console is not set up correctly")** during
> account linking almost always means one of:
> - an **Android** client id was supplied as `GOOGLE_WEB_CLIENT_ID` — it must be
>   the **Web application** client id;
> - the Android OAuth client for the build's signing certificate (SHA-1) is
>   missing — create one per certificate (debug AND release).

## 6. Put the values in the ROOT `local.properties`

`local.properties` sits at the **repository root** (next to `settings.gradle.kts`)
and is **git-ignored** — never commit it. Empty/absent values are safe defaults
(blank map tiles; Google section shows "not set up").

```properties
MAPS_API_KEY=AIza...your-maps-key...
GOOGLE_WEB_CLIENT_ID=1234567890-abc123.apps.googleusercontent.com
```

> ⚠️ **Trap (hit twice during validation): `app/local.properties` is IGNORED.**
> `app/build.gradle.kts` reads `rootProject.file("local.properties")` only. A file
> at `app/local.properties` — which Android Studio never creates but is an easy
> guess — silently leaves both values empty: the map stays blank and Settings →
> Google account shows "not set up" even though the ids are correct. Put the file
> at the repo root, then rebuild.

Rebuild after editing (`./gradlew assembleDebug`) — the values are baked in at
build time (manifest placeholder + `BuildConfig`). Quick check that the build saw
them: `grep -c 'GOOGLE_WEB_CLIENT_ID = ""' app/build/generated/source/buildConfig/debug/com/itsluminous/cleartravel/BuildConfig.java`
prints `0` when the id was picked up.

## 7. Test checklist

1. Build + install with both values set; open a trip's map view → tiles render.
2. Menu → Settings → **Google account** shows "Connect Google account" (not the
   "not set up" notice).
3. Tap Connect → account picker appears → pick a test-user account → snackbar
   "Connected as …" and the email is shown.
4. Toggle **Calendar sync** on → the consent sheet asks for the calendar scope →
   approve → within a minute (on network) a **"ClearTravel"** calendar appears in
   Google Calendar with one event per itinerary item / train / flight. The primary
   calendar is never touched.
5. Edit an itinerary item → the event updates on the next sync pass; delete a trip
   → its events disappear.
6. Toggle **Drive uploads** on → approve the Drive scope → attachments and
   boarding passes appear in a **"ClearTravel"** Drive folder.
7. Toggle **Backup to Drive** on, then Menu → Backup & Restore → Export backup →
   the backup (a `.zip`-named file that is in fact a password-sealed envelope,
   ADR-031) appears in the Drive folder; export six times → only the 5 newest remain. Drive only ever receives
   sealed files — nothing there is readable without your password.
8. Fresh-install restore — two paths now exist:
   - **First-run wizard** (ADR-032): reinstall → create a password (step 1) →
     *Connect Google* (step 2, grants the Drive-backup scope) → step 3 lists the
     newest Drive backups → tap one → step 4 asks for the **backup's own password**
     (it is sealed with the password of the install that made it — usually the old
     one, not the one you just created) → the data merges and the app opens.
   - **Settings** (later): link the same account, open Backup & Restore → the
     "Restore your data from Drive?" prompt shows the newest backup's date + size;
     Import restores your data (merge, never a wipe), asking for the source password
     when the backup came from another install.
9. Disconnect from Settings → optional "Also delete calendar" removes the
   ClearTravel calendar; local data is untouched.
