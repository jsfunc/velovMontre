# Vélo’v Montre

An autonomous Wear OS app and Android phone companion for checking **electric bikes, normal bikes and free return spaces** in two synchronized groups of up to eight stations each. The first version targets the Galaxy Watch6 (Wear OS 4 / Android 13 or later). The interface is in French.

## Phone companion (v1.2.2)

The `phone` module is a minimal French Android app for Android 13 and later, intended for the Galaxy S24 first and using no Samsung-specific APIs. It shows full station names, station numbers and the same electric / normal / free-space counts and freshness colours as the watch. Both devices download availability independently. No location permission, account or backend is needed.

The phone opens in **Carte** mode. Each station in the selected group shows only the three **electric / normal / free-space** counts, separated by spaces, beside its geographic position. Labels have no background or border. Extra-bold 16 px numbers use saturated green, amber and red for freshness, with a white text outline to stay readable over map details. Compact labels prefer the nearest clear position; a fine connector appears only when a label needs to move farther away. Pinch or use +/− to zoom, drag to pan, and tap a label for the full name and details. **Recentrer** fits the current group. Refreshing counts preserves the map position; switching groups remembers each group's viewport while the activity remains open. **Liste** restores the full-name list, and the app remembers the chosen view. **Modifier** opens the existing station editor and **Terminer** returns to the chosen view.

Coordinates and names come from the same Grand Lyon catalogue request and are cached together. Updating from the older names-only cache is supported; a station without coordinates remains in the list and is explicitly counted as missing from the map. **Actualiser** also retries catalogue loading in map mode. Availability continues to use the existing cache and freshness rules, including expiry while the map is open.

The map bundles [Leaflet 1.9.4](https://leafletjs.com/reference.html) locally and uses OpenStreetMap HTTPS tiles without an API key. Attribution stays visible, the WebView identifies this app, and its standard HTTP cache honors tile caching headers. Only tiles for the viewed area are requested; there is no bulk/offline-map download. See the [OpenStreetMap tile policy](https://operations.osmfoundation.org/policies/tiles/). The map background needs connectivity for uncached areas and shows an error if tiles fail; cached station data and the list remain available. The tile service receives normal network requests for the area viewed; the app does not request the phone's location.

The map executes only bundled scripts from a local HTTPS asset origin. File/content access and geolocation are disabled; there is no JavaScript-to-Java interface. Remote subresources are limited to the tile server, station names are inserted as text, and attribution links open in the browser. Leaflet's license is included at `phone/src/main/assets/map/vendor/LICENSE`.

Tap **Modifier** to manage exactly two groups of up to eight stations. Search by station number, check the full name, then tap a result to add it. Already-selected stations cannot be added twice. Hold a row to drag it before another row; drop on a group tab to move it to that group, or on the end-of-list target to append it. Tapping a row while editing also offers accessible move-up, move-down, move-to-other-group and removal actions. Removal asks for confirmation. A full group rejects additions and incoming moves. Empty groups are allowed. **Terminer** hides the editing tools.

The initial selection is the original sixteen stations listed below. Names are refreshed from the official station catalogue; the complete catalogue is cached for number search offline after its first successful download. If catalogue retrieval fails, the phone explains that it is searching the saved catalogue and offers a retry.

Selections are saved on the phone before transmission. Wear OS Data Layer stores the latest configuration and synchronizes it when the paired devices can communicate again. Revision numbers prevent older deliveries from undoing newer changes; publishing is serialized so fast edits cannot overwrite each other out of order. The watch persists the selection and sends a receipt. The phone’s editing footer distinguishes a saved/pending selection from one acknowledged by the watch. Opening the phone app retries publishing after a Play services failure. Availability is never transported through Data Layer.

The watch remains autonomous while disconnected and retains its latest synchronized selection. A selection arriving while it is visible rebuilds both pages; both Tiles use the same groups. Empty groups show an instruction to choose stations on the phone. An older in-flight availability request cannot publish over changed station membership, and the next request bypasses the old cooldown. Reordering or renaming the same stations refreshes layouts without forcing another feed download.

Both APKs intentionally use **`fr.velovmontre`** and must be signed with the **same certificate** for Data Layer communication. The phone APK goes on the phone; the watch APK goes on the watch. A standard build of this project uses the same local debug key for both. The devices must already be paired using the watch’s companion setup. See [Google’s Data Layer requirements](https://developer.android.com/training/wearables/data/overview).

Build and test both applications:

```sh
./gradlew :shared:testDebugUnitTest :app:testDebugUnitTest :phone:testDebugUnitTest \
  :app:lintDebug :phone:lintDebug :app:assembleDebug :phone:assembleDebug
```

Install the matching pair without uninstalling either existing app:

```sh
adb -s PHONE_SERIAL install -r phone/build/outputs/apk/debug/phone-debug.apk
adb -s WATCH_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

Open both apps once. Edit a group on the phone and check for **Stations synchronisées avec la montre** in editing mode. If an earlier watch build has a different signing key, rebuild both APKs with that key to preserve its data; Android rejects an update signed with a different certificate.

The shared module contains the feed parsers, request coordinator, freshness rules, availability cache, selection model, persistence and Data Layer protocol. Validation passed 93 automated tests across the shared/watch/phone modules and four Python feed tests. Android UI tests run with Robolectric at API 33, with networking disabled; a native-rendered list preview is written to `phone/build/reports/phone-preview.png`. Browser checks exercise the real bundled Leaflet code: both groups, non-overlapping labels, full-name details, count updates without recentering, remembered viewports, empty/missing-coordinate groups, text injection protection, and tile failure/recovery. Run `scripts/test_phone_map.py` using its documented Chromium command and Python `websocket-client`. All tile requests are intercepted and replaced locally; `phone/build/reports/map-preview-fixture.png` uses synthetic counts and blank tiles, not a live map screenshot. The coordinate fixture is a small extract of the official catalogue retrieved on 2026-10-14; the live request returned 458 valid station coordinates.

Both APK signatures were verified and share the same certificate. Phone lint reports no issues; watch lint reports only two tool/dependency update notices. A physical-device smoke test is still required: load real map tiles on the S24, pinch/pan, open a station, switch groups, rotate, test font scaling, then disable connectivity and check the list fallback. For synchronization, change and reorder both groups, disconnect the watch, make further edits, reconnect, verify both Tiles and pages, then restart both apps. Test drag gestures and the Galaxy Watch bezel on the physical devices.

## Use

The watch shows two pages of up to eight condensed-bold `name: electric-normal-free` rows. The narrower typeface leaves more horizontal room for counts without reducing the text height. Tiles use slightly tighter letter spacing, compatible with older Wear OS renderers. Swipe left to the second page and right to return; each page also scrolls vertically. The selected page survives activity recreation. The table below is the initial selection; the phone can change its contents and order. There are no headings, page labels or test button.

| Page | Stations, in display order |
|---|---|
| First | 7046, 7012, 7006, 7013, 7049, 7005, 7007, 2020 |
| Second | 5030, 5047, 5008, 5016, 5007, 5001, 5053, 5015 |

There is also one Tile per page. Add **Vélo’v · groupe 2** beside the original **Vélo’v · groupe 1** Tile to switch lists using the watch’s Tile carousel. Inside the app, horizontal paging works immediately without adding Tiles.
Names are shortened to their first eight characters: `Place de`, `Mérieux`, `Gerland`, `Halle To`, `Cité sco`, `Debourg`, `Farge /`, `Musée de`. For example, `Gerland: 3-7-12` means three electric bikes, seven normal bikes and twelve free spaces. Fonts are slightly smaller (app up to 18 sp, Tile 12 sp at the reference size). Initially, the second page shows `Charcot`, `Albéric`, `Sainte-I`, `Point du`, `Place de`, `Place Va`, `Saint-Ju`, `Fulchiro`. Each Tile fits up to eight compact rows from its synchronized group. There is no heading, subtitle or separate refresh button.

Tap any row to refresh. A short haptic pulse acknowledges the manual request in the app and Tile (subject to the watch’s touch-feedback settings); it does not mean the download has completed. Automatic refreshes are silent. Tapping one row checks all selected stations: GeoJSON requests only these stations and the required fields; GBFS fallback still downloads its full feed. Tile margins also accept a tap to refresh. The app supports bezel/rotary scrolling on the selected page. Opening the app also refreshes automatically, then checks once per minute while visible. Add **Vélo’v · groupe 1** using the watch’s Tile carousel.

Each row’s colour indicates the age of its data, not the number of available spaces:

- Green: less than three minutes old.
- Orange: three minutes to less than five minutes old.
- Red: five minutes old or more, or uncertain observation age in a GBFS fallback. The row uses the oldest colour among its visible counts.
- Each missing, invalid, unavailable or expired count becomes `_`; a fully unknown row is red `_-_-_`. Unknown counts never become zero. Renting and returning availability are checked independently; a missing bike count does not hide known free spaces.

Each count keeps its observation, feed publication and download timestamps. GeoJSON counts expire when the oldest of these reaches one hour. GBFS has unreliable station timestamps: old or missing observation dates may be accepted in red if the feed publication and download dates are valid and less than one hour old. These fallback counts expire when the older of publication/download reaches one hour. Refreshing alone cannot turn an old observation green. Missing counts do not hide known ones; a row with green visible counts and a missing field still triggers fallback. App screen-reader descriptions include the observation age. Tile descriptions use an absolute date/time with UTC offset, so a cached timeline never announces a frozen relative age. Unknown and expired Tile rows retain full station names. The app checks display age every second while visible, updates only changed view properties, and skips the hidden page except during paging; network requests remain subject to the cooldown below. The Tile schedules its colour changes and one-hour expiry through timeline entries, even without another network request.

**Troubleshooting:** long-press a row inside the app to view diagnostics. This shows each row’s status, observation age, feed age, download age and selected source, alongside the last refresh result. It also reports the total refresh duration and the time to receive or fail each feed request, measured on the watch with a monotonic clock. Close it with **OK**. The panel appears only on demand. A failed refresh preserves the last successful snapshot and never resets observation timestamps. Further diagnostics are available through `adb logcat -s Velov`.

Wear OS can route HTTPS through the paired phone automatically. A companion app is needed only to edit the station selection. No account, API key, location permission or hosted backend is required.

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

The primary source is Grand Lyon’s [official WFS GeoJSON resource](https://data.grandlyon.com/geoserver/metropole-de-lyon/ows?SERVICE=WFS&SRSNAME=EPSG:4326&VERSION=2.0.0&outputFormat=application/json&request=GetFeature&typename=metropole-de-lyon:jcd_jcdecaux.jcdvelov). Useful counts are published and cached immediately. If any count across the selected stations is missing or not green, try [Grand Lyon GBFS](https://download.data.grandlyon.com/files/rdata/jcd_jcdecaux.jcdvelov/station_status.json), then [Cyclocity GBFS](https://api.cyclocity.fr/contracts/lyon/gbfs/v3/station_status.json). An error or incomplete/non-green response starts the next provider immediately; a pending request gets a three-second head start. Slow earlier requests remain eligible to improve the display until their five-second deadline. Once all selected counts are green, remaining requests are cancelled. These sources share an upstream operator; fallback does not guarantee independent availability.

Responses merge independently for electric bikes, normal bikes and free spaces. Missing fields do not erase valid cached fields. Better age categories win, then newer observation times; a known closure wins an equal-time tie. GeoJSON, Grand Lyon GBFS and Cyclocity provide the tie-breaking preference for otherwise equal readings; repeated downloads from the same source can update counts even when its station timestamp is unchanged. Every selected count retains its own provenance through caching. Expired values become `_`. A late cancelled response cannot publish an update.

The parser accepts GBFS 2.x epoch timestamps and 3.x ISO timestamps, reads explicit `num_docks_available`, and checks `is_installed`, `is_returning` and `is_renting`. GeoJSON uses `available_bike_stands`, `status`, `last_update` and `last_update_fme`. Each station retains its own publication timestamp. A missing publication field uses that station’s observation timestamp for older payloads; an explicitly invalid publication date stays unknown. Malformed GeoJSON features are skipped and duplicate station IDs are excluded, with warnings available in refresh diagnostics; unrelated valid stations remain usable. Counts are never inferred by subtracting bikes from capacity.

Bike counts come from GBFS `vehicle_types_available` entries identified as `electrical` and `mechanical`, or GeoJSON `total_stands.availabilities.electricalBikes` and `mechanicalBikes`. Battery subtypes are not added to their electric total. GBFS `is_renting` controls bike availability; GeoJSON uses `status`. Missing type counts stay unknown rather than being inferred from totals. Each selected count retains its own timestamps and source through merging and caching; old caches have unknown bike counts until refreshed. The endpoint experiment still measures its original four-station free-space comparison group.

Opening the app or entering the Tile requests a refresh, subject to a 60-second cooldown. Manual refresh uses a 10-second cooldown. Concurrent callers share a request. Each production request has a five-second total deadline, with connect/read timeouts no longer than five seconds and a two-megabyte decoded response limit. If all requests hang, they start around 0, 3 and 6 seconds, and the coordinator finishes around 11 seconds; usable partial results are displayed earlier. Socket cleanup runs separately so it cannot block the coordinator. Up to three HTTPS redirects are followed; HTTP downgrades are rejected. HTTP response caching is disabled. Successful snapshots are persisted atomically in private app storage. Version 1.0.1 uses `availability-v2.json`: the old availability cache is not loaded because its per-station GeoJSON publication dates cannot be recovered. After upgrading, counts remain unknown until the first successful refresh. Saved benchmark history is retained.

Tile requests wait at most 1.5 seconds for a result before rendering cached data. Every usable partial result requests a Tile update; the foreground app sees it on its next one-second display tick. Timeline entries turn rows orange at three minutes, red at five minutes, and replace counts with `_` at one hour, even without another network response. Wear OS controls actual refresh scheduling; the requested one-minute freshness interval is not a guaranteed background polling schedule. There is no permanent background service.

Five minutes marks old information. The one-hour cutoff uses declared data age for GeoJSON and the older feed/download age for GBFS fallback. Counts describe the last observation and cannot reserve a space.

Data attribution: **JCDecaux / Métropole de Lyon, Licence Ouverte**. [Official dataset and licence reference](https://transport.data.gouv.fr/datasets/stations-velov-de-la-metropole-de-lyon-disponibilites-temps-reel), [GBFS field definitions](https://gbfs.org/documentation/reference/#station_statusjson).

## Validation

The v1.0.1 baseline passed 67 JVM tests and four desktop benchmark tests. Version 1.1.0 adds selection, persistence, custom-station fetching, configurable Tile and Android UI regression tests. They cover feed formats, station order, full/closed/missing states, expiry, timestamp preservation, malformed values, fallback, presentation and the Tile’s automatic expiry timelines, plus GeoJSON parsing, retained failure reasons, HTTPS redirects, response decoding, size limits, per-station merging, exact age-colour boundaries and one-hour discard. App sources and resources were also compiled using the local JDK and Android SDK.

Version 1.0.1 passed the normal Gradle command `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` using Android Studio’s bundled JDK and SDK 36. Lint reports zero errors and four dependency/tooling update notices. The regression tests cover independent publication dates through merge/cache serialization, invalid/future dates, malformed and duplicate records, retained parser warnings, the filtered production query, stable Tile accessibility descriptions and the shared Tile refresh target. The source APK remains a debug build.

On 2026-10-14, a live comparison of the full and filtered WFS requests returned 458 and 16 features respectively. Every selected property matched for all sixteen favourites. Response bodies were 507,957 and 6,598 bytes (98.7% smaller); the selected properties are `number,status,available_bike_stands,last_update,last_update_fme,total_stands`, with a `CQL_FILTER` containing all sixteen station numbers. The archived benchmark continues using the full-feed URL. These desktop measurements establish payload reduction, not watch latency or battery savings.

Version 0.1.5 fixes the benchmark report loader introduced in 0.1.4: it called `Files.readString` during application startup, which is unavailable on the supported watch versions. The replacement uses a bounded input stream and handles a missing report without attempting to read it. The user subsequently completed the watch benchmark on v0.1.5. JVM tests alone do not establish Android API compatibility; the new v1.0.0 pager and second Tile still need visual verification on the watch.

Physical-device synchronization, on-watch latency, visual rendering and battery usage remain unverified. Before relying on the app:

1. Compare all sixteen station counts with the official Vélo’v app.
2. Check refresh using the phone connection, then watch Wi-Fi/LTE with the phone disconnected.
3. Disable connectivity: cached data should remain, failures should be available on long press, and rows must turn orange at three minutes, red at five minutes, and replace numbers with `_` at one hour.
4. Reopen the app after process termination to check persisted data.
5. Swipe left/right between app pages and up/down within each page; check bezel scrolling acts on the selected page. Try large system fonts and TalkBack, including the offline Tile’s absolute timestamps and the refresh target in its margins. Check that swiping does not trigger tap-to-refresh, and that long-press diagnostics still work. Add both Tiles and check their station groups and text on the round display.

## Source layout

- `shared/src/main/java/fr/velovmontre/data/Availability.java`: favourites, status rules and expiry.
- `data/GbfsParser.java`, `data/GrandLyonParser.java`, `data/FeedClient.java`: parsing and source fallback, independently testable on the JVM.
- `data/HttpTransport.java`: HTTPS, redirects and bounded downloads.
- `data/AvailabilityRepository.java`: HTTPS, request sharing and disk cache.
- `VelovTileService.java`, `WestTileService.java`: the two Tile providers and refresh actions.
- `TileLayout.java`: eight-station layout per page and expiry timelines.
- `app/.../MainActivity.java`: configurable watch paging, scrolling, tap-to-refresh and diagnostics.
- `phone/.../PhoneActivity.java`: full-name phone lists and station selection controls.
- `shared/.../selection/`: validated configuration, cached catalogue parsing, persistence and Wear Data Layer synchronization.

The Gradle wrapper binary is distributed by the Gradle project under Apache License 2.0.
