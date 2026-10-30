# Wayfinder

Dark navigation app on Google Maps Platform.

- `windows/` Electron desktop app (builds a Windows installer .exe)
- `android/` Android app with Android Auto support (builds an .apk)

## Build with GitHub Actions
1. Settings > Secrets and variables > Actions > New repository secret: name `MAPS_API_KEY`, value your Google API key.
2. Open the Actions tab. Both workflows run on every push, or use "Run workflow".
3. Open a finished run and download **Wayfinder-APK** or **Wayfinder-Setup** under Artifacts.

Google APIs to enable: Maps SDK for Android, Maps JavaScript API, Places API (New), Routes API, Geolocation API. Billing must be on.
The Windows app asks for the key on first launch.
