# HyperBridge v0.6.0-beta4-perf1 validation

Base: official v0.6.0-beta4, commit 69a7bb41.

## Changes
- Use one application-scoped AppPreferences instance across UI, listeners and services to avoid redundant Room collectors and migrations.
- Route Shizuku notification dispatches through a supervised shared coroutine scope and mutex. Schedule XMSF networking restoration even if the notification operation fails.
- Use one-shot Flow reads (first()) in ConfigResolver instead of collecting an infinite flow or creating eager StateFlows.
- Keep beta4 notification generation tracking, widgets and island engines intact.

## Testing
Run Gradle unit tests and debug APK build. On the POCO X6 Pro compare memory PSS and swap across notification bursts, and verify WhatsApp, media, calls, navigation, widgets and the permanent island.

## Limitations
The optional XMSF networking workaround remains dependent on the app process to restore network access. Do not enable it unless the ROM specifically needs it.
A debug APK signed by GitHub Actions may not upgrade an existing release-signed installation. Back up settings before considering reinstalling.