# Vélo’v Montre

An autonomous Wear OS app for checking **electric bikes, normal bikes and free return spaces** on two pages of eight stations each, in the requested order. The first version targets the Galaxy Watch6 (Wear OS 4 / Android 13 or later). The interface is in French.

## Use

The app shows two pages of eight bold `name: electric-normal-free` rows. Swipe left to the second page and right to return; each page also scrolls vertically. The selected page survives activity recreation. There are no headings, page labels or test button.

| Page | Stations, in display order |
|---|---|
| First | 7046, 7012, 7006, 7013, 7049, 7005, 7007, 2020 |
| Second | 5030, 5047, 5008, 5016, 5007, 5001, 5053, 5015 |

There is also one Tile per page. Add **Vélo’v · Lyon 5e** beside the original **Vélo’v · disponibilités** Tile to switch lists using the watch’s Tile carousel. Inside the app, horizontal paging works immediately without adding Tiles.
Names are shortened to their first eight characters: `Place de`, `Mérieux`, `Gerland`, `Halle To`, `Cité sco`, `Debourg`, `Farge /`, `Musée de`. For example, `Gerland: 3-7-12` means three electric bikes, seven normal bikes and twelve free spaces. Fonts are slightly smaller (app up to 18 sp, Tile 12 sp at the reference size). The second page shows `Charcot`, `Albéric`, `Sainte-I`, `Point du`, `Place de`, `Place Va`, `Saint-Ju`, `Fulchiro`. Each Tile fits its own eight compact rows. There is no heading, subtitle or separate refresh button.

Tap any row to refresh. A short haptic pulse acknowledges the manual request in the app and Tile (subject to the watch’s touch-feedback settings); it does not mean the download has completed. Automatic refreshes are silent. The upstream feeds contain multiple stations, so tapping one checks all sixteen, using the same full-feed requests. Opening the app also refreshes automatically, then checks once per minute while visible. Add **Vélo’v · disponibilités** using the watch’s Tile carousel.

Each row’s colour indicates the age of its data, not the number of available spaces:

- Green: less than three minutes old.
- Orange: three minutes to less than five minutes old.
- Red: five minutes old or more, or uncertain observation age in a GBFS fallback. The row uses the oldest colour among its visible counts.
- Each missing, invalid, unavailable or expired count becomes `_`; a fully unknown row is red `_-_-_`. Unknown counts never become zero. Renting and returning availability are checked independently; a missing bike count does not hide known free spaces.

Each count keeps its observation, feed publication and download timestamps. GeoJSON counts expire when the oldest of these reaches one hour. GBFS has unreliable station timestamps: old or missing observation dates may be accepted in red if the feed publication and download dates are valid and less than one hour old. These fallback counts expire when the older of publication/download reaches one hour. Refreshing alone cannot turn an old observation green. Missing counts do not hide known ones; a row with green visible counts and a missing field still triggers fallback. Screen-reader descriptions include the observation age. The app checks display age every second while visible; network requests remain subject to the cooldown below. The Tile schedules its colour changes and one-hour expiry through timeline entries, even without another network request.

**Troubleshooting:** long-press a row inside the app to view diagnostics. This shows each row’s status, observation age, feed age, download age and selected source, alongside the last refresh result. It also reports the total refresh duration and the time to receive or fail each feed request, measured on the watch with a monotonic clock. Close it with **OK**. The panel appears only on demand. A failed refresh preserves the last successful snapshot and never resets observation timestamps. Further diagnostics are available through `adb logcat -s Velov`.

Wear OS can route HTTPS through the paired phone automatically. No companion app, account, API key, location permission or hosted backend is required.

## Archived endpoint experiments

The temporary **Test feeds** button was removed in version **1.0.0**. Updating the app preserves previously saved experiment files. The benchmark code remains available for development but is no longer instantiated at application startup.

Earlier versions ran 12 rounds, 20 seconds apart, against all three endpoints concurrently with a five-second network budget. The freshness comparison group remains the original four stations (7046, 7012, 7006, 7013). See [connection_experiments.md](connection_experiments.md) for the complete results and methodology.

The current report and JSON are stored privately as `files/feed-benchmark.txt` and `files/feed-benchmark.json`. Earlier runs remain under `files/feed-experiments/`. Export the latest run from a connected watch into this project directory:

```sh
adb exec-out run-as fr.velovmontre cat files/feed-benchmark.json > experiments/watch-benchmark.json
```

If multiple Android devices are connected, add `-s WATCH_SERIAL` after `adb`. The assistant can read the exported file in this shared project; it cannot directly read files that remain only on the watch.

Export the full history from the earlier network comparisons with:
```sh
adb exec-out run-as fr.velovmontre tar -cf - files/feed-experiments > experiments/watch-experiments.tar
```

This preserves all runs for comparison, including any partial attempts. Keep the app installed and its data intact until the export is complete.

Desktop comparison (Python 3 and curl required):

```sh
python3 scripts/benchmark_feeds.py --rounds 12 --interval 20
```

The script saves raw responses, timings, per-station ages and a summary under `experiments/`. Its simultaneous cold HTTP/1.1 requests make endpoint comparisons close in time, but desktop curl and the watch's Android networking stack can differ. These experiments do not change the normal request order.

The full comparison of desktop, watch Wi-Fi, LTE and Bluetooth runs is in [connection_experiments.md](connection_experiments.md), with links to preserved raw data. Grand Lyon GBFS is fastest; GeoJSON has newer station timestamps. Bluetooth was the slowest tested route and had several deadline failures. The normal refresh protocol below follows the agreed GeoJSON-first policy.

## Build and install

Use Android Studio with JDK 17 or newer, Android SDK Platform 36 and Build Tools 36.0.0. Open this directory as a Gradle project. The wrapper uses Gradle 8.13 and Android Gradle Plugin 8.12.1.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

For installation, enable developer options, ADB debugging and wireless debugging on the watch. Pair with Android Studio or use the pairing and connection addresses shown by the watch (their ports can differ):

```sh
adb pair WATCH_IP:PAIRING_PORT
adb connect WATCH_IP:DEBUG_PORT
adb -s WATCH_IP:DEBUG_PORT install -r app/build/outputs/apk/debug/app-debug.apk
```

Open **Vélo’v Montre** once, then add its Tile using the watch’s Tile carousel. See [Google’s wireless debugging instructions](https://developer.android.com/training/wearables/get-started/debug-wifi).

The APK produced during initial development uses a temporary debug signing key. A later Android Studio build may have a different key: if Android reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, uninstall this first debug build before installing the new one. Uninstalling clears its cached data.

## Data and refresh behaviour

The primary source is Grand Lyon’s [official WFS GeoJSON resource](https://data.grandlyon.com/geoserver/metropole-de-lyon/ows?SERVICE=WFS&SRSNAME=EPSG:4326&VERSION=2.0.0&outputFormat=application/json&request=GetFeature&typename=metropole-de-lyon:jcd_jcdecaux.jcdvelov). Useful counts are published and cached immediately. If any of the 48 counts across sixteen stations is missing or not green, try [Grand Lyon GBFS](https://download.data.grandlyon.com/files/rdata/jcd_jcdecaux.jcdvelov/station_status.json), then [Cyclocity GBFS](https://api.cyclocity.fr/contracts/lyon/gbfs/v3/station_status.json). An error or incomplete/non-green response starts the next provider immediately; a pending request gets a three-second head start. Slow earlier requests remain eligible to improve the display until their five-second deadline. Once all counts are green, remaining requests are cancelled. These sources share an upstream operator; fallback does not guarantee independent availability.

Responses merge independently for electric bikes, normal bikes and free spaces. Missing fields do not erase valid cached fields. Better age categories win, then newer observation times; a known closure wins an equal-time tie. GeoJSON, Grand Lyon GBFS and Cyclocity provide the tie-breaking preference for otherwise equal readings; repeated downloads from the same source can update counts even when its station timestamp is unchanged. Every selected count retains its own provenance through caching. Expired values become `_`. A late cancelled response cannot publish an update.

The parser accepts GBFS 2.x epoch timestamps and 3.x ISO timestamps, reads explicit `num_docks_available`, and checks `is_installed`, `is_returning` and `is_renting`. GeoJSON uses `available_bike_stands`, `status`, `last_update` and `last_update_fme`. Counts are never inferred by subtracting bikes from capacity.

Bike counts come from GBFS `vehicle_types_available` entries identified as `electrical` and `mechanical`, or GeoJSON `total_stands.availabilities.electricalBikes` and `mechanicalBikes`. Battery subtypes are not added to their electric total. GBFS `is_renting` controls bike availability; GeoJSON uses `status`. Missing type counts stay unknown rather than being inferred from totals. Each selected count retains its own timestamps and source through merging and caching; old caches have unknown bike counts until refreshed. The endpoint experiment still measures its original four-station free-space comparison group.

Opening the app or entering the Tile requests a refresh, subject to a 60-second cooldown. Manual refresh uses a 10-second cooldown. Concurrent callers share a request. Each production request has a five-second total deadline, with connect/read timeouts no longer than five seconds and a two-megabyte decoded response limit. If all requests hang, they start around 0, 3 and 6 seconds, and the coordinator finishes around 11 seconds; usable partial results are displayed earlier. Socket cleanup runs separately so it cannot block the coordinator. Up to three HTTPS redirects are followed; HTTP downgrades are rejected. HTTP response caching is disabled. Successful snapshots are persisted atomically in private app storage.

Tile requests wait at most 1.5 seconds for a result before rendering cached data. Every usable partial result requests a Tile update; the foreground app sees it on its next one-second display tick. Timeline entries turn rows orange at three minutes, red at five minutes, and replace counts with `_` at one hour, even without another network response. Wear OS controls actual refresh scheduling; the requested one-minute freshness interval is not a guaranteed background polling schedule. There is no permanent background service.

Five minutes marks old information. The one-hour cutoff uses declared data age for GeoJSON and the older feed/download age for GBFS fallback. Counts describe the last observation and cannot reserve a space.

Data attribution: **JCDecaux / Métropole de Lyon, Licence Ouverte**. [Official dataset and licence reference](https://transport.data.gouv.fr/datasets/stations-velov-de-la-metropole-de-lyon-disponibilites-temps-reel), [GBFS field definitions](https://gbfs.org/documentation/reference/#station_statusjson).

## Validation

All 58 JVM tests and four desktop benchmark tests pass. They cover feed formats, station order, full/closed/missing states, expiry, timestamp preservation, malformed values, fallback, presentation and the Tile’s automatic expiry timelines, plus GeoJSON parsing, retained failure reasons, HTTPS redirects, response decoding, size limits, per-station merging, exact age-colour boundaries and one-hour discard. App sources and resources were also compiled using the local JDK and Android SDK.

The v1.0.0 APK was assembled with the SDK’s manifest merger, AAPT2, Java compiler, D8, zipalign and apksigner against cached dependencies. Its signature was verified. The manual Java compilation now uses the Android SDK system modules, so desktop-only Java library calls are rejected. The ordinary Gradle build and Android lint could not run in the development sandbox because Gradle could not obtain a usable local networking interface. A standard Gradle build should be run outside that restriction before release.

Version 0.1.5 fixes the benchmark report loader introduced in 0.1.4: it called `Files.readString` during application startup, which is unavailable on the supported watch versions. The replacement uses a bounded input stream and handles a missing report without attempting to read it. The user subsequently completed the watch benchmark on v0.1.5. JVM tests alone do not establish Android API compatibility; the new v1.0.0 pager and second Tile still need visual verification on the watch.

The new protocol’s on-watch latency, visual rendering and battery usage remain unverified. Before relying on the app:

1. Compare all sixteen station counts with the official Vélo’v app.
2. Check refresh using the phone connection, then watch Wi-Fi/LTE with the phone disconnected.
3. Disable connectivity: cached data should remain, failures should be available on long press, and rows must turn orange at three minutes, red at five minutes, and replace numbers with `_` at one hour.
4. Reopen the app after process termination to check persisted data.
5. Swipe left/right between app pages and up/down within each page. Check that swiping does not trigger tap-to-refresh, and that long-press diagnostics still work. Add both Tiles and check their station groups and text on the round display.

## Source layout

- `data/Availability.java`: favourites, status rules and expiry.
- `data/GbfsParser.java`, `data/GrandLyonParser.java`, `data/FeedClient.java`: parsing and source fallback, independently testable on the JVM.
- `data/HttpTransport.java`: HTTPS, redirects and bounded downloads.
- `data/AvailabilityRepository.java`: HTTPS, request sharing and disk cache.
- `VelovTileService.java`, `WestTileService.java`: the two Tile providers and refresh actions.
- `TileLayout.java`: eight-station layout per page and expiry timelines.
- `MainActivity.java`: horizontal station paging, vertical scrolling, tap-to-refresh and on-demand diagnostics.

The Gradle wrapper binary is distributed by the Gradle project under Apache License 2.0.
