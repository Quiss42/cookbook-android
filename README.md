# Cookbook for Android

A native Android client (Kotlin, Jetpack Compose) for the
[Nextcloud Cookbook](https://github.com/nextcloud/cookbook) app.

It is a rewrite of the Flutter client
[Teifun2/nextcloud-cookbook-flutter](https://github.com/Teifun2/nextcloud-cookbook-flutter)
(CC0), keeping its user experience but using Android's own tooling.

## Features

- Login through the browser (Nextcloud Login Flow v2) or with an app password
- Categories with cover photos, recipe lists, server-side search
- Recipe view with ticking off ingredients and steps, keep-screen-on while cooking
- Light/dark theme, wallpaper colours, per-app language (Android 13+)

Planned: editing and creating recipes, importing from a URL (share menu),
cooking timers, offline cache.

## Building

Every push builds a release APK with GitHub Actions (Actions tab → run → artifact).
A new version (`APP_VERSION_NAME` in `gradle.properties`) on the default branch
also creates a GitHub release with the APK.

Locally: Android SDK + Gradle 8.14, then `gradle :app:assembleDebug`.

Release signing uses the repository secrets `ANDROID_KEYSTORE_BASE64`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`
(or `key.properties` locally).
