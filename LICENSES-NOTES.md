# License notes (Jukebox fork)

Unofficial notes collected while reviewing what ships inside the APK. The authoritative text remains each project's own license file. `LICENSE` (MIT) is unchanged.

| Component | License | Notes / obligations |
|---|---|---|
| App itself (SmartTube) | MIT, Copyright (c) 2020-present yuliskov (`LICENSE`) | Keep the license and its notice. |
| Submodules `MediaServiceCore` (YouTube API layer) and `SharedModules` (github.com/yuliskov/MediaServiceCore, github.com/yuliskov/SharedModules) | No license file; GitHub reports no license | Default copyright, all rights reserved. Biggest issue for public redistribution; ask the author or keep the fork private / reference the upstream submodule URLs rather than re-hosting. |
| `SharedModules/j2v8` (native V8 bindings, `libj2v8.so` ~15 MB per ABI) | Eclipse Public License 1.0 | Weak copyleft: modified EPL files must have source made available; a binary must offer source for the EPL parts. |
| `doubletapplayerview` (Viktor Krez), `appupdatechecker2` (yuliskov) | MIT | Standard MIT attribution. |
| chatkit, leanback-1.0.0, fragment-1.1.0, filepicker-lib, slidableactivity, commons-io 2.8.0, exoplayer-amzn-2.10.6 (Amazon ExoPlayer port), AndroidX, OkHttp, Retrofit, Gson, RxJava2, Glide (BSD/Apache), conscrypt, cronet, json-path, disklrucache, netcipher, net.gotev:speech | Apache-2.0 | Needs NOTICE/attribution. |
| dnsjava | BSD-2-Clause | BSD attribution. |
| `MediaServiceCore/youtubeapi/src/main/assets/nsigsolver/yt.solver.core.js` (yt-dlp/ejs) | Unlicense | Public domain. |
| ExoPlayer ffmpeg extension module | (in the build) | No `libffmpeg` `.so` ships in the APK (verified), so no FFmpeg LGPL/GPL obligations today; re-check if native ffmpeg is ever enabled. |

No GPL code was found in the shipped APK.

## What matters before going public

- The submodules (`MediaServiceCore`, `SharedModules`) have **no license**, so redistributing them (as source or inside an APK) is not permitted by default. Either get the author's blessing, keep the fork private, or point users at the upstream submodule URLs instead of re-hosting their code.
- `j2v8` is EPL-1.0: if we ever modify its files or ship its binaries, we must offer the source for the EPL-covered parts.
- Apache-2.0 dependencies need NOTICE/attribution when we distribute binaries.
- The ffmpeg situation is clean only as long as no `libffmpeg` `.so` is shipped; verify again before enabling native ffmpeg.
