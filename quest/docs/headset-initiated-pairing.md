# Headset-initiated pairing — preview 9

Preview 0.1.0.9, sequence 9.

## User flow

1. Install the matching Windows manager. Successful **Setup VR** enables persistent pairing reception. **Pair headset** can re-enable it later.
2. On Quest, choose **Setup VR**, then select the discovered PC. Manual IPv4/hostname and optional port are available when discovery cannot reach it.
3. Compare the code on both screens and approve it in the Windows panel. Quest saves the pairing; the Windows request closes automatically while receiving controls remain available.
4. Later, choose **Connect**. The existing explicit SteamVR restart warning remains. Closing the Windows window leaves the manager in its tray; saved startup and pairing preferences survive restart.

## Boundaries

- Vibeshine is unchanged. VR pairing does not require Moonlight pairing or file copying.
- The manager renews a short receiving lease through authenticated loopback administration. The headset uses public enrollment endpoints and cannot approve itself.
- Discovery names, addresses, ports and TXT fields are untrusted hints. Initial trust combines the observed TLS certificate, canonical transcript and human code approval. Later connections use the saved pin.
- Discovery is bounded to 8 seconds and 16 admitted services. Network failure leaves a manual-address fallback.
- Pending requests expire; denial or cancellation does not revoke existing paired devices. Credentials are persisted before approval is reported.
- Manual ports target an already reachable endpoint. The Windows Advanced panel does not configure a custom listener port.
- No new codec, native-streaming or WAN guarantee is made by this patch.

## Review and validation

Codex, Claude and Agy reviewed the Windows implementation with no remaining blocking findings. The portable Windows suite passed 320 tests and the app cross-build passed. Go race tests passed. Android review, all 279 unit tests, and APK assembly passed. Installed Windows CI is a mandatory publication gate. Physical Quest 3 / RTX 4090 testing remains for the owner.
