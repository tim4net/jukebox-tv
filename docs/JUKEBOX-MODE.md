# Jukebox Mode — implementation plan

Plan (no code) for turning the `stjukebox` flavor into a TV party jukebox: phones join a room by
QR / 6-letter code and queue YouTube videos; the TV plays the queue and reports song ends.
Depends on the room protocol served by **jukebox.tfour.net** (join codes, QR target URL, WebSocket
endpoint and message schema are defined server-side; the app is only a client).

Everything below must stay behind the `stjukebox` flavor (own applicationId `net.tfour.jukebox`,
own source set `smarttubetv/src/stjukebox/`, no changes to upstream behavior for other flavors) so
weekly upstream merges keep conflicting as little as possible. Prefer new files over edited files;
where an upstream file must be touched (e.g. one registration line), keep the edit to a single,
easy-to-re-apply hunk.

## Decisions & implementation status

- **DECIDED (issue tim4net/jukebox-tv#2 — autoplay while in a room):** SmartTube's own
  auto-advance (autoplay, next/suggested/related video, end-of-video advance) is
  blocked while a room is *active* — not only while a room song is set — via
  `JukeboxMode.isAutoAdvanceBlocked()` (`sRoomActive || sSongSet`) checked in
  `VideoLoaderController` (`loadNext`, `onPlayEnd`, `waitMetadataSync`,
  `loadNextVideo`, `applyPlaybackMode`). When a song ends the TV only sends
  `next`/`ended` and waits for the server `play`, so the player never substitutes a
  suggested upload for the room's exact `videoId`. Re-entering the join screen
  (`JukeboxJoinActivity`, launcher entry, `singleTask`) re-attaches through
  `JukeboxRoom.start()`, which is idempotent and self-healing: a live socket is
  reused, a dead client/thread is rebuilt, a dead socket is reconnected immediately;
  the only MAIN/LAUNCHER entry in the merged manifest is the join screen
  (SplashActivity/alias filters are removed by the flavor manifest).
- **DECIDED (open question 1 — queue playback):** the TV opens each song itself in room order.
  There is no SmartTube `Playlist` mirroring: every server `{"t":"play",...}` message ends in
  `PlaybackPresenter.openVideo(videoId)`; a `play` for the songId that is already current is
  deduplicated and never re-opens the video. Upstream auto-advance is simply overwritten by the
  next room `play`.
- **DECIDED (open question 2 — song-ended reporting):** song end is reported with the server's
  existing `{"t":"next","token":T}` message — the server's `next` is the advance primitive and has
  no `endedAt` field, so no new `{videoId, endedAt}` message exists. Double-advance guard: `next`
  is sent at most once per songId; if the socket is down when a song ends, the ended songId is
  remembered and `next` is sent after reconnect only if the server's post-ack `play` is still for
  that same songId (otherwise the server already advanced and the pending end is dropped).
- Implemented in `common/src/stjukebox/java/net/tfour/jukebox/room/`: `JukeboxProtocol`
  (pure JSON encode/decode), `JukeboxRoomClient` (room state machine over injected
  `Transport`/`Http`/`Scheduler`/`Store` interfaces; ping every 25s; reconnect backoff 1s→30s,
  reset on open; server `closed` forgets the saved room and starts a new one), `OkHttpTransport`,
  `OkHttpHttp`, `HandlerScheduler`, `PrefsStore` ("jukebox_room" prefs), `JukeboxRoom`
  (process-wide singleton; origin/token from `common/src/stjukebox/res/values/jukebox.xml`) and
  `JukeboxRoomController` (extends `BasePlayerController`, registered through the single guarded
  hook in `PlaybackPresenter`).
- **Join screen:** a separate flavor-only launcher activity (`JukeboxJoinActivity` +
  `jukebox_join.xml` + flavor manifest entry "Jukebox Room", second LAUNCHER/LEANBACK_LAUNCHER
  entry) instead of the overlay inside `BrowseFragment` sketched in (a) below — keeps upstream
  browse UI completely untouched. The room keeps running after the activity is left.
- **Known race (accepted):** upstream autoplay may briefly start a suggestion after a song ends
  before the room's next `play` arrives — the room play wins (it opens over whatever is playing).
- **Bridge token (open item):** if the server sets `BRIDGE_TOKEN`, the TV must send it in
  `bridge-hello`/`next`/`playing`. It is kept in `jukebox_bridge_token` (currently **empty**);
  a real token must never be committed.
- Server protocol summary (authoritative copy): create `POST {origin}/api/rooms` →
  `{"room":"<22-char>","code":"<6-char>"}`; state check `GET {origin}/api/r/{room}/state`;
  WebSocket `{ws|wss}://.../api/r/{room}/ws`; client sends `bridge-hello`/`next`/`playing`/`ping`;
  server sends `bridge-ack`, `play` (after ack if something is playing, and after every advance),
  `state`, `pong`, `error`, `closed`; `videoId` must match `^[A-Za-z0-9_-]{11}$` (others are
  ignored); phone join URL `{origin}/r/{room}`.

## (a) QR + 6-letter join-code overlay

- TV idle/browse screen:
  - `smarttubetv/src/main/java/com/liskovsoft/smartyoutubetv2/tv/ui/browse/BrowseActivity.java`
    inflates `R.layout.fragment_main` in `onCreate`; the hosted
    `.../tv/ui/browse/BrowseFragment.java` gets its root view in `onCreateView` (line ~94) and
    already attaches things to it (precedent: `mProgressBarManager.setRootView((ViewGroup) root)`,
    line ~97). A `JukeboxJoinOverlay` view (code + QR bitmap rendered with the stock
    `android.graphics.Bitmap`/`Canvas` APIs or a tiny bundled QR lib) can be added to that root
    `ViewGroup` and shown when the room is open but playback is idle.
  - Alternative attach point: `.../tv/ui/common/LeanbackActivity.java` `onStart()` if we want the
    overlay on every leanback screen; the browse root is the less invasive choice.
- Player overlay:
  - `smarttubetv/src/main/java/com/liskovsoft/smartyoutubetv2/tv/ui/playback/PlaybackFragment.java`
    inflates `R.layout.fragment_playback`; a small semi-transparent "join this room" corner banner
    can be added to that layout (flavor overlay resource) and toggled from the jukebox controller.
  - UI state hooks without touching upstream UI code: `PlayerUIController`
    (`common/src/main/java/com/liskovsoft/smartyoutubetv2/common/app/models/playback/controllers/PlayerUIController.java`)
    receives `onControlsShown(boolean)` and `onVideoLoaded(Video)`; our own controller can listen
    for the same events to decide when to show/hide the banner.

## (b) Room WebSocket client

- OkHttp is already a dependency (`common/build.gradle` -> `:exoplayer-extension-okhttp`, which
  `api`-exports `com.squareup.okhttp3:okhttp`; also via `SharedModules/sharedutils/build.gradle`;
  version pinned in root `build.gradle` `resolutionStrategy`). `OkHttpClient.newWebSocket(...)` is
  available; there is currently no WebSocket usage in the app, so this is a new, isolated client.
- Create `JukeboxRoomController extends BasePlayerController`
  (`common/src/main/java/com/liskovsoft/smartyoutubetv2/common/app/models/playback/BasePlayerController.java`),
  the same pattern as the existing YouTube-lounge bridge
  `.../app/models/playback/controllers/RemoteController.java` (subscribes to a remote command
  stream; `Command.TYPE_OPEN_VIDEO` at line ~289 ends in
  `getPlaybackPresenter().openVideo(newVideo)` at line ~530).
- Registration: controllers are registered compile-time in the `PlaybackPresenter` constructor's
  `mEventListeners` list (`common/src/main/java/com/liskovsoft/smartyoutubetv2/common/app/presenters/PlaybackPresenter.java`;
  there is no public add/remove API). Plan: register `JukeboxRoomController` only through a single
  guarded line (or a static hook called from the stjukebox source set) so the upstream diff stays
  one line. `getController(Class)` is available for cross-controller lookups.
- Lifecycle: connect in `onEngineInitialized()`, disconnect in `onEngineReleased()` (both fired by
  `PlaybackFragment.initializePlayer()/releasePlayer()`); reuse `onTickle()`
  (`common/.../common/misc/TickleManager.java`) for ping/reconnect while the player runs.

## (c) Queue playback (internal API, no ADB intents)

- Opening a video by id is a one-liner — the same path the app already uses for external VIEW
  intents (`SplashPresenter`'s intent chain ends in
  `PlaybackPresenter.instance(...).openVideo(videoId, finishOnEnded, timeMs, incognito)`):
  - `PlaybackPresenter.openVideo(String videoId)` (line ~93)
  - `PlaybackPresenter.openVideo(String videoId, boolean finishOnEnded, long timeMs, boolean incognito)` (line ~100)
  - `PlaybackPresenter.openVideo(Video video)` (line ~112)
- The shared queue is the `Playlist` singleton
  (`common/src/main/java/com/liskovsoft/smartyoutubetv2/common/app/models/data/Playlist.java`):
  `add(Video)`, `next(Video)`, `addAll(List<Video>)`, `getNext()`, `setCurrent(Video)`, `clear()`.
- Auto-advance is handled upstream: `VideoLoaderController.onPlayEnd()` calls
  `applyPlaybackMode(...)` (PLAYBACK_MODE_ONE/ALL/SHUFFLE/LIST/CLOSE/PAUSE/REVERSE_LIST) which
  loads `Playlist.getNext()` via `SuggestionsController.getNext()/getPrevious()`. ~~Strategy:
  mirror the room queue into `Playlist` (mode ALL) and let upstream do the advancing, or drive
  `openVideo(videoId)` per song-end ourselves for stricter room ordering — decide after seeing the
  protocol's ordering guarantees.~~ DECIDED: the TV opens each song itself in room order (see
  "Decisions & implementation status" above); no `Playlist` mirroring.

## (d) Song-ended reporting

- The single callback that fires on natural video end:
  `ExoPlayerController.onPlayerStateChanged(boolean playWhenReady, int playbackState)`
  (`common/src/main/java/com/liskovsoft/smartyoutubetv2/common/exoplayer/controller/ExoPlayerController.java`,
  line ~337): `Player.STATE_ENDED == playbackState && playWhenReady` (line ~344) calls
  `mEventListener.onPlayEnd()` (line ~357), which is the `PlaybackPresenter`; it fans
  `onPlayEnd()` out to every registered controller. (`onPositionDiscontinuity` with
  `DISCONTINUITY_REASON_PERIOD_TRANSITION` also reports `onPlayEnd()`.)
- So `JukeboxRoomController.onPlayEnd()` is the hook: read the just-finished video via
  `getVideo()` / `getPresenter().getVideo()` and send `{videoId, endedAt}` (ISO-8601 UTC or epoch
  ms, per the jukebox.tfour.net schema) on the room WebSocket. The auto-play of the next queue
  item must not be blocked by the report; fire-and-forget with a small retry buffer if offline.
  ~~send `{videoId, endedAt}`~~ DECIDED: song end is reported with the server's existing
  `{"t":"next"}` message (see "Decisions & implementation status" above).

## Build/flavor isolation checklist

- All jukebox classes live in a `com.liskovsoft.smartyoutubetv2.tv.jukebox` (or similar) package
  under `smarttubetv/src/stjukebox/java/`, resources under `smarttubetv/src/stjukebox/res/`.
- Flavor-specific strings/icons already exist under `smarttubetv/src/stjukebox/res/`.
- The only upstream-file edits: the single controller-registration line in `PlaybackPresenter` and
  (optionally) overlay views; both must be re-appliable mechanically during
  `scripts/upstream-merge.sh` runs.
- Room client must be inert unless the `stjukebox` flavor is active (guard by a `BuildConfig`
  field or simply by only shipping the code in the flavor source set).
