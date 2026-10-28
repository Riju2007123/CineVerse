# CineVerse

A movies & series **catalogue app** for Android with playback powered by libmpv
(the same engine as the mpv-android fork).

## Run it

Android Studio → Open `C:\Users\riju\AndroidStudioProjects\CineVerse` → Run.
Launcher: `is.xyz.mpv.cine.CineActivity`, app id `com.cineverse.app`.

Terminal build:

```
gradlew.bat :app:assembleDefaultDebug
```

APKs: `app\build\outputs\apk\default\debug\`.
Ready-to-install universal build: `Desktop\CineVerse-1.0.apk`.

## Where the library comes from

**Cinemeta** (`v3-cinemeta.strem.io`) — the Stremio metadata addon.

- Free, no API key, no signup
- Whole IMDb catalogue: films + TV
- Top charts, genre charts, title search
- Full details: poster, backdrop, logo, rating, runtime, cast, director
- Every season and episode with stills and synopses

Endpoints used:

| Purpose | Endpoint |
|---|---|
| Top movies | `/catalog/movie/top.json` |
| Top series | `/catalog/series/top.json` |
| Genre chart | `/catalog/{movie,series}/top/genre=Action.json` |
| Search | `/catalog/{movie,series}/top/search=batman.json` |
| Details | `/meta/{movie,series}/tt0111161.json` |

Images come from `images.metahub.space` (posters/backdrops/logos) and
`episodes.metahub.space` (episode stills), loaded with Coil.

## Screens

| Screen | Content |
|---|---|
| **Home** | Hero banner, Continue watching, 9 curated rows (trending movies, trending series, Action, Comedy, Sci-Fi, Horror, Animation, Drama series, Documentary) |
| **Browse** | Movies/Series switch + 16 genres |
| **Search** | Debounced IMDb search, All/Movies/Series filter |
| **Details** | Backdrop, poster, rating, runtime, genres, overview, cast, director; series get a season picker and full episode list with stills |
| **My list** | Bookmarks + resume progress |

## Playback

Tapping Watch or an episode plays it in **mpv** — no embed page is ever shown.

1. `CineResolverActivity` loads the provider page in a WebView that sits **completely
   behind an opaque cover** (it is never visible).
2. The provider's own player JavaScript resolves/decrypts its stream there.
3. The first HLS/MP4 request it makes is captured, probed over HTTP and handed to
   `MPVActivity` with the right `Referer` / `User-Agent` / cookies.
4. mpv then does everything: decoding, subtitles, audio tracks, gestures, PiP,
   background playback and resume positions (written under `anime_progress`,
   keyed by the `anime_track` extra, which drives Continue watching).

Resolution takes about 4–5 seconds the first time. The URL is cached for 30
minutes (`CineStreamCache`), so replaying the same movie/episode opens mpv
immediately.

Sources, in order: **VidFast** (primary, resolves 480p–2160p HLS), **VidLink**,
**2Embed**. Long-press a Watch button or an episode to play a direct link in mpv
instead.

## Structure

```
app/src/main/java/is/xyz/mpv/cine/
├── CineActivity / CineApp.kt   app shell, Home, Browse, Search, My list, Settings
├── CineDetails.kt              details screen, episode list, custom-link dialog
├── CineUi.kt                   poster cards, hero banner, rows, chips
├── CineModels.kt               CineTitle / CineEpisode / PlayRequest
├── CinemetaApi.kt              OkHttp + JSON catalogue client with caching
├── CineSources.kt              providers + resolved-stream cache
├── CineResolverActivity.kt     hidden resolver → direct URL → mpv
├── CineLibrary.kt              watchlist, history, saved links, progress
├── CinePlayer.kt               direct-URL playback in MPVActivity
└── CineTheme.kt                dark cinematic Material3 theme
```

The mpv engine files (`MPVActivity`, `MPVLib`, `MPVView`, `jniLibs/`) are
unchanged from the working mpv-android fork.
