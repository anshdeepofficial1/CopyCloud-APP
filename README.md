# CopyCloud App

Account-free native Android connectivity, with a Windows desktop companion. This is an **early development alpha**, not a production-ready release.

## Build and try

- Install Android Studio or JDK 17, Gradle 8.9 and Android SDK 35.
- Run `gradle :app:assembleDebug`. GitHub Actions also builds the debug APK.
- On Windows install Python 3.12, then `pip install -r desktop/requirements.txt` and `python desktop/copycloud.py`.
- Allow the companion on your private network only. Join the same Wi-Fi, transfer the private invite to the phone securely, open Connect, and join.
- Enable desktop clipboard sharing explicitly. In Android Settings enable live clipboard if desired (only while the app is focused). Send text manually in the Clipboard screen otherwise.
- Multiple phones can use the same desktop group. Restart the desktop to rotate its secret and disconnect all devices. Session history stays in memory.

## What this alpha includes

Native Jetpack Compose UI, separate screens, light/dark theme, encrypted LAN invitation handshake, authenticated text frames, sequence checks, real text transfer, desktop clipboard bridge, opt-in foreground Android clipboard monitoring, in-memory history. No paid design tools or services.

## Not yet implemented

QR invites; persistent per-device credentials, revocation and approval; independent device identities; background Android keyboard integration; audio, microphone, voice typing, camera, files, remote control, internet relay, signed installers. No mock controls for these. The initial shared-secret LAN protocol has not been independently audited; trusted LAN testing only. The relay can read text and any member with the invite can join. Avoid passwords or sensitive data, and do not expose the port publicly. The full product needs independent security review and real hardware testing.

## Design

Original editorial typography, a forest/lime palette, motion-based navigation and dedicated workspaces. Free motion inspiration from public design collections only; no paid 60fps MCP, copied proprietary source or assets.

## Verification

See Actions for build and unit-test status. Physical-device compatibility and real clipboard interoperability still require end-to-end testing.
