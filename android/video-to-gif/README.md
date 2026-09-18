# Video to GIF Android

A deliberately small Android app that converts local MP4 or WebM videos to looping GIFs.

## What it does

- Uses Android's system file picker, so no storage permission is required.
- Decodes video frames with Android's built-in media stack.
- Encodes GIFs locally with a small Java GIF encoder.
- Lets the user choose output width and frame rate.
- Saves through Android's standard save-file picker.
- Uses no network access and no third-party runtime libraries.

## Build

From this directory:

    gradle :app:assembleDebug

The APK is written to:

    app/build/outputs/apk/debug/app-debug.apk

The repository workflow also builds and uploads the APK as a GitHub Actions artifact.
