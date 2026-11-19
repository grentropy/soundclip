# Soundclip

A small Android app for adding a section of a song from your phone to a photo or video.

- Pick a photo or video, then a song stored on the phone.
- Drag over the song's waveform to choose which part plays; the section is locked to the video's length (or to a photo length you set, 3–60 s).
- Set music and original-sound volume, with optional 1.5 s fade in/out.
- Preview, then save as MP4 to `Movies/Soundclip`.

Requires no permissions and no Google Play Services. Uses AndroidX Media3 (Apache 2.0) for playback and export.

## Builds

Every push to `main` builds a signed APK with GitHub Actions and publishes it under **Releases**.
The signing key lives only in the repo's Actions secrets; installs of later builds update in place.

Local build: `gradle assembleRelease` with `SIGNING_KEYSTORE_PATH`, `SIGNING_STORE_PASSWORD`,
`SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD` set (or `gradle assembleDebug` without them).

License: GPL-3.0
