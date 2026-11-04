# Aurorama G1 Kokoro Exact - Independent QA

**INDEPENDENT QA: BLOCKED**

- Branch/HEAD: `codex/v2-integration` / `cb68738e6dd81cfcce7e224aabb361876286e636`; tracked=0, outside evidence root=0.
- Verdict: `BLOCKED_P1_HARNESS_API_COMPAT`; P0–P3 = `0/1/0/2`.
- Device locks released: ACTIVE=`0`; final lock SHA-256 `84b1905c12b8b18971b2f670cf019ba249e035c4dde4ce30ec7e5d0ce6a901f5`.

## K60

| Voice | API/ABI | Load ms | First ms | Inference ms | Audio s | RTF | Peak PSS KB | Samples/max gap |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| `zf_001` | 35 / arm64-v8a | 1531 | 2844 | 40569 | 58.075 | 0.698562 | 928419 | 30 / 1.511 s |
| `zm_010` | 35 / arm64-v8a | 1584 | 2811 | 40968 | 58.725 | 0.697625 | 926473 | 30 / 1.509 s |

- Long text: 401 chars / 356 Han via existing `probe-zf` override; 116.300 s, RTF `0.765262`, peak PSS `926,968 KB`, no truncation/crash.
- Zero network: UID `10304`; airplane=1, active default network=`none`; identity blocks and `mAppUidStatsMap` both show rx/tx delta 0.
- Background: 181.5 s screen-asleep supplement did not complete; source/manifest have no service owner => `BLOCKED: NO_BACKGROUND_OWNER`.
- Restart: interrupted/completed force-stop both return to `preparing`; no persistence/resume contract => `BLOCKED: NO_RESUME_STATE`.
- Cancel: no internal cancel; forced cancel leaves PID null and no partial WAV/temp output.
- Audio owner: dumpsys shows no harness audio/media session => `BLOCKED: HARNESS_NOT_AUDIO_OWNER`.
- speaker58 / `zm_009`: static whitelist rejects; runtime forbidden hits 0.

## Pad 5

- Device: API 33 / arm64-v8a; APK install exit 0 and base SHA MATCH.
- P1: `java.lang.NoClassDefFoundError: java/util/HexFormat` before model load, although APK declares minSdk 26.
- `zf_001` produced no WAV/first-audio/RTF; `zm_010` was not accepted as complete after the stop.
- All later Pad branches stopped under the new-P0–P2 rule.

## Cleanup

- Both harness installs uninstalled successfully; PID and package external directories absent.
- Production packages before/after MATCH on both devices; no user data, account, server, or security settings changed.
- Network/screen restored: K60 Wi-Fi active network 174, Pad Wi-Fi active network 186; both Awake/unlocked; ACTIVE=0.

## Maximum Claim

On K60 API 35 / arm64-v8a only, the current isolated APK proves zf_001 sid3 and zm_010 sid59 each generate a 45-60 second 24 kHz mono WAV, a 356-Han long-text override completes, and the executed offline/cancel paths converge. It does not prove a two-device runtime, background ownership, resume, audio ownership, production integration, voice quality, or G1 readiness.

Evidence: `report.json`, `device-snapshots.json`, `commands.log`, `logcat-*`, `netstats-*`, `pss-*.csv` under this QA root.
