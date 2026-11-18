# stjukebox TV art (source + generated density references)

Source art (Saskia's retro-MTV neon jukebox) and Lanczos-downscaled density
reference copies. This directory is outside `res/`, so nothing here is packaged
into the APK and it cannot affect resource resolution.

The packaged launcher resources remain the single high-res nodpi bitmaps under
`res/` — `res/mipmap-nodpi/app_icon.png` and `res/mipmap-nodpi-v30/app_icon.png`
(512x512 RGB) and `res/mipmap-nodpi/app_banner.png` (1280x720 RGB). Fire TV
devices are xhdpi; do NOT add smaller density-specific `app_icon`/`app_banner`
files under `res/`, or Android would prefer them and the home row would show a
soft upscaled image instead of the sharp nodpi source.

- `jukebox-firetv-icon-512.png` — icon source (512x512 RGB)
- `app_icon-{mdpi-48,hdpi-72,xhdpi-96,xxhdpi-144,xxxhdpi-192}.png` — generated
- `jukebox-firetv-banner-1280x720.png` — banner source (1280x720 RGB)
- `app_banner-{xhdpi-320x180,xxhdpi-480x270,xxxhdpi-640x360}.png` — generated
