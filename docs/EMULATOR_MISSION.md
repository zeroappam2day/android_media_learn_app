# REA emulator acceptance

From this project, run:

```bash
python3 scripts/emulator_mission.py verify --serial emulator-5554 --runtime emulator
```

The command requires the existing Android SDK, Java, REA, Python3.11+, ffmpeg and the local Ollama server. It uses no paid service or model download. It selects the already installed Apache2 model `aiconjured/Qwen3.8-27B-Uncensored-HauhauCS-Aggressive-Q5_NVFP4:latest`; override `--model` only with another installed local model that passes the same substantive tests. Existing Claude/OpenAI subscriptions may control development; their API credits are not assumed.

Each run uses a new `uat/mission-<time>` directory. Failure returns nonzero and preserves its failed receipt. Successful automation leaves visual inspection and user acceptance explicit; it never marks them passed automatically.

## Verifiers

1. REA device identity and actual properties must agree with the explicit serial/runtime. AVD requires an emulator serial plus QEMU and ranchu/goldfish properties. Waydroid requires the live host container IP plus matching Waydroid properties. A caller label alone cannot authorize a phone.
2. Full acceptance requires API34+. Older Waydroid remains usable for ordinary REA investigation and compatible app regression, but cannot prove Android14 selected-media permission behavior. The test guard accepts Waydroid; no AVD-only product restriction remains.
3. Build, unit tests, lint and app/test APK hashes are recorded. The installed base APK SHA256 must equal the candidate, before and after testing. Source cannot change during acceptance.
4. Five separate real OS consent runs cover denied, selected-image/reselection, selected-video/reselection, full and revoked access, with externally owned disposable images and MP4s. Activity recreation and background/resume must preserve the correct scope. Permission resets touch only the preview package, never global device state.
5. Existing app regression runs against synthetic fixtures. A unique generated video is provisioned through REA, never copied from private media. No permission assertion is replaced by mocks.
6. Real image and video OCR must reach the real local model and persist grounded structured analysis. The image has one known command; the video has none. Invented commands/URLs or fallback fail acceptance. These fixtures bound the claim; they cannot prove all future model answers correct. A successful run intentionally retains one synthetic analyzed video and its tested local-model settings for user review. AVD uses its host-loopback address; Waydroid retains the serial-specific reverse mapping needed for that local endpoint.
7. Both app themes must use matching system-bar icon contrast, retain operable header controls and produce screenshots for inspection. Capture waits for actual clock pixels to have the expected contrast, rather than trusting an in-process flag before SystemUI has rendered it.
8. REA stops the preview app to snapshot its database/WAL consistently. Real metadata and available media are copied to the computer; each file is read independently twice and its destination digest must match. Originals remain intact. This is a real ADB backup, not a network-sync service or a change to the app's metadata-export button.
9. REA relaunches the app and captures the final screen for user review. No phone-install command exists in this entrypoint.

Independent backup:

```bash
python3 scripts/emulator_mission.py backup --serial emulator-5554 --runtime emulator
```

The backup currently supports this preview/debug package's indexed MediaStore files. It does not promise backup of arbitrary apps, private app data, release builds or unavailable/revoked media. An unreadable or changing file fails the backup instead of reporting simulated success.

## Waydroid preservation

Do not replace this host's existing Android13 image or user data just to run this app's acceptance. Its dedicated ext4 data image and both seccomp safeguards must remain effective. A future Android14+ Waydroid target can run the same workflow after its host lifecycle, graphics, OCR availability and API are verified. Current Play-services OCR requires GMS; a VANILLA image would need an independently tested bundled-OCR change first. A newer image alone is not evidence those integrations work.

The existing Android16/API36 AVD supplies the missing permission behavior now. This establishes API36 behavior, not every intermediate Android version or Samsung-specific behavior. User acceptance and later phone verification remain separate.

## Evidence and stop rule

Keep the failed receipt when repairing a cause. Reuse unchanged artifact evidence; rerun affected checks after source, dependency, runtime or input changes. Batch routine checks and inspect only failures plus final required visuals. Timings and command counts are observable in `report.json`; a globally minimum token/tool-call claim is unsupported.

After the checks and visual review pass, give the user the exact APK hash and evidence links. Deploy to the phone only after the user is happy and explicitly approves that reviewed candidate.
