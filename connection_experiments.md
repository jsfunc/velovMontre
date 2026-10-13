# Connection experiments — Vélo’v Montre

Experiments conducted on **13 October 2026**. Times below are local to **Europe/Paris (UTC+02:00)**.

## Findings

- All three providers responded within five seconds on every measured computer, Wi-Fi and LTE request.
- Grand Lyon GBFS had the lowest median response time in every configuration tested. GeoJSON had much newer station observation timestamps and is the chosen first source for that reason.
- LTE responses took roughly twice as long as Wi-Fi responses. Connected Bluetooth was substantially slower and less consistent: GeoJSON exceeded the five-second deadline in 3 of 11 connected rounds.
- The first Bluetooth-only attempt had no phone connection and is a disconnected baseline, not a speed comparison. The corrected run also began with one disconnected round; both full-run and connected-only denominators are reported below.
- GBFS station timestamps remained hours old even while some counts changed. They cannot establish the actual age of those counts. A successful download must not automatically make such data green.

## Method and saved runs

The tests issued three simultaneous requests per round, using a new connection per request, with a five-second total network budget covering connection and the complete response body. Twelve rounds were planned at 20-second intervals. Desktop testing used curl (HTTP/1.1); the watch used Android HttpURLConnection. The watch recorded active-network transport before and after each round. Production’s proposed staggered requests were **not** benchmarked by these tests.

The watch identifies itself as **SM-R935F, Android API 36**. The fixed freshness comparison group was **7046, 7012, 7006 and 7013**, even after the app expanded to eight stations. These experiments measured free-space observations; the later electric/normal-bike display change was not part of this experiment.

| Run | Local time | Completed rounds | Recorded transport | Saved evidence |
|---|---|---:|---|---|
| Computer | 22:11–22:14 | 12/12 | Computer network not recorded | [Measurements and analysis](experiments/feeds-20261013T201108Z/) |
| Watch Wi-Fi + Bluetooth | 22:26:10–22:29:59 | 12/12 | Wi-Fi: 12 rounds | [watch-wifi-bluetooth-1791923170080.json](experiments/watch-wifi-bluetooth-1791923170080.json) |
| Watch Wi-Fi only | 22:31:56–22:34:30 | 8/12 | Wi-Fi: 8 rounds | [watch-wifi-only-1791923516143.json](experiments/watch-wifi-only-1791923516143.json) |
| Watch LTE only | 22:42:38–22:46:20 | 12/12 | Mobile: 12 rounds | [watch-lte-only-1791924158895.json](experiments/watch-lte-only-1791924158895.json) |
| Watch Bluetooth + phone (whole run) | 22:47:58–22:51:43 | 12/12 | No active network: 1 round; Bluetooth: 11 rounds | [watch-bluetooth-phone-1791924478992.json](experiments/watch-bluetooth-phone-1791924478992.json) |

The Wi-Fi-only test was stopped after eight completed rounds; every request in those rounds succeeded. Wi-Fi + Bluetooth reported Wi-Fi throughout, so it is not evidence of phone-proxied performance. Phone upstream connection type, signal strength, and radio wake-up state were not recorded.

Version control includes the summaries, normalized desktop measurements (`samples.jsonl`) and named watch JSON runs. Raw desktop response bodies and headers remain local and are excluded by `.gitignore`, as is the replaceable `watch-benchmark.json` export. The archived named files above preserve each watch configuration.

Excluded baselines:

- [Sandbox computer attempt](experiments/feeds-20261013T200921Z/summary.txt): one round, DNS failed for all three providers because networking was unavailable in the development environment.
- [Disconnected Bluetooth attempt](experiments/watch-bluetooth-only-1791923730391.json): 22:35:30–22:39:10, 12 rounds, Android reported no active network and all 36 requests failed DNS resolution. The user confirmed that the watch was not connected to the phone.

## Response times and reliability

Medians and maxima below include only successful responses within five seconds. Failed or timed-out requests are counted separately and are not treated as fast responses. The three-second column measures receipt within three seconds, not merely connection establishment.

| Configuration | Provider | Success ≤5 s | Success ≤3 s | Median (s) | Maximum successful (s) |
|---|---|---:|---:|---:|---:|
| Computer | Grand Lyon GeoJSON | 12/12 | 12/12 | 0.252 | 0.366 |
| Computer | Grand Lyon GBFS | 12/12 | 12/12 | 0.227 | 0.296 |
| Computer | Cyclocity GBFS | 12/12 | 12/12 | 0.231 | 0.245 |
| Watch Wi-Fi + Bluetooth | Grand Lyon GeoJSON | 12/12 | 12/12 | 0.486 | 0.550 |
| Watch Wi-Fi + Bluetooth | Grand Lyon GBFS | 12/12 | 12/12 | 0.261 | 0.293 |
| Watch Wi-Fi + Bluetooth | Cyclocity GBFS | 12/12 | 12/12 | 0.358 | 0.465 |
| Watch Wi-Fi only | Grand Lyon GeoJSON | 8/8 | 8/8 | 0.483 | 0.562 |
| Watch Wi-Fi only | Grand Lyon GBFS | 8/8 | 8/8 | 0.271 | 0.294 |
| Watch Wi-Fi only | Cyclocity GBFS | 8/8 | 8/8 | 0.374 | 0.401 |
| Watch LTE only | Grand Lyon GeoJSON | 12/12 | 12/12 | 1.135 | 1.224 |
| Watch LTE only | Grand Lyon GBFS | 12/12 | 12/12 | 0.535 | 0.730 |
| Watch LTE only | Cyclocity GBFS | 12/12 | 12/12 | 0.867 | 0.984 |
| Watch Bluetooth + phone (whole run) | Grand Lyon GeoJSON | 8/12 | 0/12 | 3.804 | 4.394 |
| Watch Bluetooth + phone (whole run) | Grand Lyon GBFS | 11/12 | 9/12 | 1.465 | 4.960 |
| Watch Bluetooth + phone (whole run) | Cyclocity GBFS | 10/12 | 7/12 | 2.145 | 3.622 |

### Connected Bluetooth detail

Round 1 reported no active network and all three hostname lookups failed. Rounds 2–12 reported Bluetooth before and after each round. Restricting the comparison to those **11 connected rounds**:

| Provider | Success ≤5 s | Success ≤3 s | Deadline failures | Median successful response |
|---|---:|---:|---:|---:|
| Grand Lyon GeoJSON | 8/11 | 0/11 | 3 | 3.804 s |
| Grand Lyon GBFS | 11/11 | 9/11 | 0 | 1.465 s |
| Cyclocity GBFS | 10/11 | 7/11 | 1 | 2.145 s |

GeoJSON timed out in rounds 2, 7 and 11; Cyclocity timed out in round 2. Round 7 GeoJSON did not complete within the additional cancellation/parsing grace, so its elapsed network duration is absent; it remains a failed five-second attempt. The other deadline cancellations returned just after five seconds. These small overruns are cancellation overhead, not successful responses.

Every successful GeoJSON response on Bluetooth took more than three seconds. A three-second fallback trigger would therefore activate frequently on Bluetooth, while none of the measured Wi-Fi or LTE requests reached that threshold. This supports keeping a slow GeoJSON request running while starting GBFS, but the effect of that staggered overlap still needs measurement. Simultaneous full-feed downloads may themselves compete for Bluetooth bandwidth.

## Freshness and timestamp consistency

The original experiment defines “fresh” as an effective declared age below **five minutes**. Effective age uses the oldest observation, publication and download timestamp. This differs from the app’s **green** threshold of **three minutes**. Neither metric independently proves when a physical station’s count last changed.

Both GBFS feeds had zero rounds with all four station timestamps younger than five minutes in every connected experiment. Their station timestamps were approximately 4–20 hours old across the experiments, although feed publication timestamps were recent.

GeoJSON results:

| Configuration | Successful GeoJSON samples | All four <3 min | All four <5 min | Median age 7046 | Median age 7012 | Median age 7006 | Median age 7013 |
|---|---:|---:|---:|---:|---:|---:|---:|
| Computer | 12 | 3/12 | 9/12 | 2.54 min | 1.15 min | 3.85 min | 3.50 min |
| Watch Wi-Fi + Bluetooth | 12 | 0/12 | 1/12 | 2.91 min | 2.56 min | 8.61 min | 8.54 min |
| Watch Wi-Fi only | 8 | 0/8 | 2/8 | 7.21 min | 6.19 min | 3.81 min | 3.76 min |
| Watch LTE only | 12 | 0/12 | 2/12 | 1.89 min | 2.45 min | 1.85 min | 5.07 min |
| Watch Bluetooth + phone (whole run) | 8 | 1/8 | 4/8 | 2.06 min | 2.64 min | 1.13 min | 5.73 min |

The four-station fractions above use successful GeoJSON samples as their denominator; failed requests are already counted in the reliability table. Station 7046 is Place de l’École; 7012 Mérieux / Pavillons; 7006 Gerland; 7013 Halle Tony Garnier.

Freshness varied with the time of each run. For example, Gerland and Halle Tony had median GeoJSON ages around 8.6 minutes during the first watch Wi-Fi run, whereas the LTE medians were around 1.9 and 5.1 minutes. These sequential runs do not establish that one network access produces fresher source data.

There is direct evidence that the GBFS station timestamps do not track every count update:

- Desktop: both GBFS sources changed station 7046 from 19 to 20 free spaces, and 7012 from 12 to 13, without advancing those stations’ `last_reported` timestamps.
- Watch Wi-Fi + Bluetooth: Grand Lyon GBFS changed 7012 under a fixed timestamp.
- Watch LTE: Cyclocity changed 7006 under a fixed timestamp.

This demonstrates a mismatch between counts and their advertised observation times; it does not establish the exact true age or correctness of every count. GBFS remains useful as a fallback, with conservative age indication.

## Agreed refresh protocol

1. Start GeoJSON and display usable counts immediately as they arrive. Preserve valid cached counts while requests are running.
2. Start Grand Lyon GBFS immediately if GeoJSON fails or returns missing/non-green counts; otherwise start it after three seconds without a usable complete green result. Do not cancel a slow GeoJSON request merely because fallback starts.
3. Apply the same rule to Cyclocity after Grand Lyon GBFS: start immediately on failure/incomplete or non-green data, or after its three-second head start.
4. Merge per station and per count. A fallback fills gaps or improves readings; it must not replace a green reading with an older or uncertain one. Repaint progressively without waiting for all providers. Requests still fetch whole feeds, not individual stations.
5. Green means <3 minutes, orange 3–5 minutes, red ≥5 minutes. GeoJSON counts expire at one hour of declared data age. GBFS counts with old or absent station timestamps may fill gaps in red, but require valid recent feed/download timestamps and expire without a successful update within one hour. Failed requests never refresh cached timestamps.
6. Keep the five-second per-request deadline as a bounded first implementation, with three seconds serving as the next-provider trigger rather than an immediate cancellation. Stop remaining work once all requested counts are green, or all attempts have completed/expired.

This protocol is implemented in v0.1.7. Local tests cover request order, staggering (with shorter injected timing budgets in tests), progressive results, deadlines, cancellation, per-count merging, cache round trips and Tile expiry. Its end-to-end latency and rendering still need validation on the watch; the measurements above concern the earlier concurrent benchmark, not this new strategy.

## Limits and next checks

### Why Bluetooth might be slow

Wear OS generally proxies watch internet traffic through the paired phone when Bluetooth is active. This introduces both the watch–phone link and phone forwarding into the request path. Android also documents the platform's trade-off between network bandwidth and battery life. [Official Wear OS networking documentation](https://developer.android.com/training/wearables/data/network-communication)

The desktop captures provide a concrete payload-size clue: median downloaded response-body sizes were **508,785 bytes for GeoJSON**, **155,922 bytes for Cyclocity**, and **10,929 bytes for Grand Lyon GBFS**. Grand Lyon GBFS returned `Content-Encoding: gzip`; GeoJSON and Cyclocity did not in the saved first-round headers, despite the client's gzip request. These are desktop transfer measurements; the watch experiment did not record byte counts or response headers.

The large size difference is consistent with the observed Bluetooth ranking and makes bandwidth/forwarding a plausible contributor. It does not isolate the Bluetooth radio as the cause: phone internet access, DNS/TLS setup, proxy scheduling, link quality and contention between the three concurrent requests were not measured independently.

To distinguish them, put the phone on the same Wi-Fi used for the direct watch test, compare phone-direct and watch-through-Bluetooth fetches of the same feeds, then compare one-at-a-time versus simultaneous requests on the watch. Instrument connection/header time, body-download time, byte counts and encoding. A further useful experiment is a server-side GeoJSON filter for only the eight favourite stations and required fields; support and resulting correctness should be verified before deployment. The implemented protocol continues using the measured full-feed endpoints.

### Scope of the conclusions

These are short runs from one computer and one watch, taken sequentially at one location. There was no controlled variation of signal strength, battery state, phone upstream connection, mobility or congestion. Success in 8–12 requests is not a long-term reliability estimate. Successful-only latency medians, particularly for Bluetooth GeoJSON, exclude slow failures and must be read together with the failure counts.

The experiments demonstrate a real slow path through connected Bluetooth, consistent with the reported slow-refresh experience; they do not prove that it caused every earlier delay. Normal app refresh also includes source ordering, parsing, cache selection, cooldowns and display scheduling, which these per-endpoint times do not measure.

After installing the protocol change, check that initial GeoJSON counts appear promptly on Wi-Fi/LTE, that Bluetooth starts fallback after three seconds while GeoJSON continues, and that partial results preserve each count’s source and age. Test offline expiry and verify that no background response can reset an expired count’s age without a successful new download.
