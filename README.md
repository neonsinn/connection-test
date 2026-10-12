# Drive Video Library
An Android-first, static web app for viewing MP4 videos stored in a selected Google Drive folder. Videos remain in Google Drive; the app does not upload or duplicate them.

## Current features
- Google OAuth sign-in with **read-only** Drive permission
- Browse My Drive folders and choose one
- List and search MP4 files directly inside the selected folder
- Google Drive embedded video preview, plus Open in Drive fallback
- Remember chosen folder ID/name locally on this device (not in GitHub)
- Responsive Android layout; installable PWA manifest

## Setup required before sign-in works
1. Create a Google Cloud project in [Google Cloud Console](https://console.cloud.google.com/).
2. Enable **Google Drive API** for that project.
3. Configure **Google Auth Platform / OAuth consent screen**. For a personal app, choose the appropriate audience and add your Google account as a test user if in testing mode. Google may require consent verification depending on audience/scopes.
4. Create an **OAuth Client ID** of type **Web application**.
5. Add your exact deployed HTTPS origin to **Authorized JavaScript origins** (e.g. `https://neonsinn.github.io` for GitHub Pages; origin means scheme and host, without a path).
6. Replace `PASTE_GOOGLE_OAUTH_CLIENT_ID_HERE` in `config.js` with the Web Client ID. A client ID is public; **never commit a client secret or OAuth access token**.
7. Deploy via HTTPS (e.g. GitHub Pages or Vercel), then open it on Android Chrome and select **Add to Home screen** if desired.

**Privacy:** OAuth access tokens are held only in memory and not persisted. The chosen folder ID/name is saved to localStorage on the device. Filenames are fetched directly from Google Drive after sign-in, not sent to this repository or an app server. Google Drive preview uses Google's embedded player and your Google account's browser session.

## Current limitations
- Searches filenames in the chosen folder only, not nested folders.
- Only loaded pages of results are searched locally; use **Load more** for additional files.
- Playback is through Drive's preview iframe, which may ask for Google sign-in or be blocked by account/browser restrictions; **Open in Google Drive** is a fallback.
- Folder browser shows up to 1,000 folders per level.
- No tags, favorites, thumbnail generation, offline playback, or direct MP4 range streaming yet.
- This is a personal prototype, not a production security audit.

## Roadmap
1. Confirm Google sign-in and folder browsing on Android.
2. Add editable tags and favorites, storing metadata separately in Google Drive with narrowly scoped write access.
3. Improve streaming and video thumbnails after confirming browser/Drive behavior.
