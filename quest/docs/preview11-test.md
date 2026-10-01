# Quest Preview 11 Owner Test Checklist — 0.1.0.11

Home LAN only. Run every step with Quest controllers and again with bare hands.
This is an experimental owner-hardware test; a clean pass here is not proof
that the build is flawless. Native VR does not implement public IP or port
forwarding, so do not attempt WAN streaming with native VR. Flat-screen
streaming runs through Vibeshine; WAN use there is not exercised by this
checklist.

## Flat-screen pointer

- Click, drag, and aim precisely. Pointer motion must not stall after a click.
- Settings rows, including codec and bitrate, respond to taps with the larger
  touch targets.

## Input handoff

- Switch between controllers and hands during use.
- Trigger a tracking loss (cover the controllers or hands briefly). The pointer
  must be released, not left stuck pressed, and the UI must stay usable
  afterwards.

## Immersive mode

- Open the cog menu and the settings panel from the immersive environment.
- Toggle each codec available on this machine: AV1, HEVC, Pyro.

## Home 200 and travel

- Home 200 and travel are separate bitrate profiles, not content presets.
- Set the Home 200 profile to 200 Mbps and save it.
- Switch to the travel profile and confirm its own earlier bitrate is retained:
  default 30 Mbps, maximum 45 Mbps. Saving Home 200 must not change travel.
- Switch back to Home 200 and confirm 200 Mbps is still stored.

## PCVR

- Connect, then disconnect and reconnect. The session must restore the current
  configuration without a needless manual SteamVR restart. If you changed
  settings, an approved SteamVR restart may still be required; that is expected.

## Startup and updates

- Sign in to Windows, then confirm Vibertemis VR Host Manager starts automatically.
- Confirm each platform checks for updates automatically on launch.
- Walk one Update action through the app-side steps on each platform. The
  Android/Windows OS consent dialog may appear during the update; that is
  expected and cannot be pre-suppressed.
- Interrupt an update partway and restart the app. It should recover and offer
  the update again rather than being stuck.

## Reporting

Record what you observed, including failures, exactly as they occur. Results of
this checklist are owner acceptance evidence; no automated pass counts and no
release-published status are claimed in these docs.
