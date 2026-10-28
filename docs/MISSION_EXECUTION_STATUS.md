Goal (as read): Finish the Android media app's known emulator-validation gaps and deliver a reusable REA workflow; deploy to the phone only after the user's explicit approval of the emulator result.

Effort: High · Executor: AGENT, then USER for acceptance.
Impact: preview APK, Android instrumentation, local Ollama, serial-targeted ADB, local evidence and computer backups. Existing Waydroid installation and its data are preserved.

## Acceptance and evidence rules

- Conversation establishes requirements, including all corrections and the phone gate. It does not override contradictory measured evidence.
- C1: Every device mutation names one verified non-phone serial and the preview package. Runtime choice does not artificially exclude Waydroid.
- C2: The actual Android 14+ permission dialog proves denied, selected-only, full, reselection, recreation and revocation behavior against externally owned fixtures.
- C3: Real image/video OCR reaches a real installed local model and persists meaningful structured output without fallback. No paid external inference is introduced.
- C4: Computer transfer copies actual media and metadata, verifies source/destination hashes, and leaves originals untouched.
- C5: One command builds, checks and records results against one unchanged APK hash, including installed-artifact equality, regression, lint and both themes.
- C6: Review files identify limitations honestly. No phone install, publication or false claim of user approval.

| # | Action | Success verifier | Status | Evidence |
|---|---|---|---|---|
| 1 | Audit conversation claims, source, APK and connected target | All known gaps mapped to C1–C6; source intelligence current | PASS | read/measured: prior report.json; current APK SHA256 9a1929565ff695b415a2ae1f4ba179e79426d90a5a956aedc0f6ef30036739a2; REA ev_97719371cf0c6753d65596174e6fdf588958aa55af2f7545bb80c81ed2024170; project-intel prechange10:42:37Z |
| 2 | Implement capability-based runtime and fixture guards | Explicit AVD/Waydroid targets accepted only with matching host/device evidence; physical and API<34 targets rejected for full acceptance | PASS | measured AVD runtime proof: uat/mission-runtime-proof/report.json; proxy: runtime boundary tests accept matching Waydroid and reject physical/mismatched targets |
| 3 | Close permission, live inference and computer-transfer gaps | Real selected image/video reads, denied/full/revoked access; actual local-model output; matching media backup hashes | PASS | measured: uat/mission-acceptance-2026-10-27/report.json; five permission phases, two real inference cases, nine media files plus metadata |
| 4 | Deliver one deterministic REA entrypoint | Explicit serial/runtime, matching installed/candidate APK, retained evidence and failure status | PASS | scripts/emulator_mission.py; 17 workflow/backup unit tests; complete entrypoint executed |
| 5 | Run required regression and visual verification | 55 unit tests; 21 device checks; lint0errors; both rendered themes inspected; identical APK after cold boot | PASS | primary report plus theme-supplement.log and theme-light-final.png/theme-dark-final.png; 74 existing lint warnings remain |
| 6 | Prepare user review and approval boundary | App available on same AVD with local-model demo prepared; phone installation unperformed | PASS | report review_profile, retained analysis in database backup, same-AVD recovery receipt; user acceptance remains PENDING |

## Findings that constrain execution

- F1: Existing successful 13-device-test receipt belongs to APK3b91854e…, not current9a192956…. A later theme change invalidated the device acceptance claim, not the unit-test result.
- F2: Current connected target is emulator-5554, Android16/API36, ranchu. The phone is absent from current REA inventory. Every ADB call must still specify a serial.
- F3: Existing Waydroid is stopped; last measured image Android13/API33. Community Android16 images have positive user reports, but this host has no verified newer installation. Do not upgrade or wipe its protected data to complete app testing. API36 provides real Android14+ behavior now; claims about Android14/15-specific compatibility still require those versions.
- F4: Old tests hard-code ranchu/goldfish. Replace that artificial restriction with explicit runtime intent and corroborated properties, not a permissive phone bypass.
- F5: Local qwen3.5:0.8b exists. Production-style requests returned an empty answer in one host probe; think:false returned JSON. Emulator verification must resolve this before C3 passes.
- F6: Metadata export exists; full computer transfer was not proven. A real serial-targeted backup must include media bytes, metadata and matching hashes; no simulated sync success.
- F7: Existing test suite proves protocol mocks, OCR, keyword fallback and app operations. It does not prove cloud inference, all OEM behavior or user acceptance. Local-model success will not establish cloud-provider quality.
- F8: No claim of globally minimal tokens/tool calls is supported by a benchmark. Reuse fixed evidence, batch commands, and record timings rather than manufacture an optimum.

## Plan validation

Checked against the available conversation, current source/Blueprint, prior test receipt, current APK digest and REA inventory. Requirements map to C1–C6; dependencies run in order; no phone action is required for emulator acceptance. Prior user praise of another Waydroid app does not prove this app's newer permission behavior.

Completion: emulator stage complete. User review and phone approval remain external gates; no phone deployment performed.

Execution update: production build/unit/lint passed against APK8f5f5d629e174dc9be74da5ba10f265cd9166d1ee04cd35bd40adc57f2ec6c1b (55 unit tests; 0 lint errors,74 existing warnings). REA static package inspection confirms preview package,target36 and selected-media declaration: uat/mission-static-apk.json. Static declarations are not runtime proof.

Verified corrections during execution: native Ollama now requests generic structured JSON, think:false and a2048-token generation limit, with source-grounding instructions. Real initial device tests failed with fallback; the revised production APK passed both real model tests. A later image review caught screenshots taken before SystemUI rendered the new icon contrast; capture now verifies contrasting clock pixels. A repeated regression exposed a virtualized tag row outside the viewport; explicit list-to-row scrolling passed its focused4.496s device test. Failed receipts remain under uat/mission-final-2026-10-27 and uat/mission-final-review-2026-10-27.

Final APK SHA256: `8f5f5d629e174dc9be74da5ba10f265cd9166d1ee04cd35bd40adc57f2ec6c1b`. Build, installed artifact and runtime checks agree. Test-only theme capture was strengthened afterward; its separate APK digest and focused pass are retained without rewriting the original batch identity.

Limits: API36 was exercised, not every Android release/OEM; existing Waydroid13 remains unchanged and newer Waydroid integration is untested; live cloud inference is untested; backup is an actual PC-side ADB workflow while the app icon exports metadata. Emulator disconnected once, with no identified kernel cause; same-AVD cold boot and same-APK check recovered it. No global minimum token/tool-call claim is made.

Manual action: review the emulator. Verifier: the user confirms satisfaction and explicitly approves any later phone installation. No manual installation/configuration is required now.

STOP
