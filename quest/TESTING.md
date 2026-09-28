# Testing

## Automated tests (build host)

`./gradlew testNonRootDebugUnitTest` runs the unit suite. The 37 tests
covering prefs, routing, mic, and guard logic do not load native code;
`ShadowMoonBridge` skips JNI init only. All 37 passed before the final
manifest delta; a current rerun will refresh the count.

The signed preview APK was statically verified for package, libs, and
entrypoint. Headset and host hardware were not available.

## Hardware checklist (user must execute)

Sideload the APK, run it flat, opt into synthetic 3D, then exercise
SteamVR with head and two controllers tracked, haptics, recenter, audio
out, mic round-trip, rapid exit and relaunch, mode switching, and
sleep / wake. Capture LAN quality metrics from the ALVR dashboard and,
over VPN, record RTT, jitter, and loss for the Travel path. Do not
record measured performance numbers from the build host — there is no
real headset there.