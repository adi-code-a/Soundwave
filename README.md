# Soundwave Android starter

A simple Android local music player starter built with Kotlin, Jetpack Compose, and Media3 ExoPlayer. It lets you select local audio files, search the selected library, and play/pause tracks.

## Build on GitHub Actions
1. Upload the **contents** of this folder to the root of a GitHub repository, preserving the `.github/workflows/build-apk.yml` path.
2. Commit the files to the `main` branch.
3. Open the repository's Actions tab and open the `Build Soundwave APK` run.
4. When successful, download the `Soundwave-debug-apk` artifact. Extract it to get `app-debug.apk`, then install it on your Android phone.

This is a starter app, not a finished Spotify replacement. Cloud music, streaming accounts, persistent playlists/likes, and background playback controls are not implemented yet. The APK must be built and tested before it can be considered installable.
