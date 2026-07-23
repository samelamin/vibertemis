# Vibertemis Steam Deck quick start

This guide gets the Vibertemis beta installed and added to Steam without
changing SteamOS. The build is ready for Steam Deck testing, but the maintainer
has not yet exercised every step on physical LCD and OLED hardware. Streaming
tuning, Moonlight/Apollo details, and the real-device checklist are in the
[advanced Steam Deck streaming and validation guide](STEAM_DECK.md).

Vibertemis keeps the compatibility application ID
`com.artemisdesktop.ArtemisDesktopDev` and the
`artemis-steam-deck.*` download names so existing installs, shortcuts, and
automation continue to work. The visible application name is still
**Vibertemis**.

## Before you begin

1. Press the **Steam** button.
2. Choose **Power > Switch to Desktop**.
3. Open **Konsole** from the application launcher.

> **Safety:** Never use `sudo` for Vibertemis. Never disable SteamOS read-only protection for Vibertemis. Review commands instead of blindly pasting snippets from issue comments. The commands below install only for your user and do not unlock or modify the SteamOS base system.

## First install: one safe copy/paste block

Copy the whole block into Konsole:

```bash
set -euo pipefail
VIBERTEMIS_INSTALL_DIR="$(mktemp -d "$HOME/Downloads/vibertemis-install.XXXXXX")"
cd "$VIBERTEMIS_INSTALL_DIR"
printf 'Install files will be retained in: %s\n' "$VIBERTEMIS_INSTALL_DIR"
curl --fail --location --retry 3 \
  --output artemis-steam-deck-bundle.tar.gz \
  https://github.com/samelamin/vibertemis/releases/download/steam-deck-latest/artemis-steam-deck-bundle.tar.gz
VIBERTEMIS_ARCHIVE_MEMBERS="$(tar -tzf "artemis-steam-deck-bundle.tar.gz" | LC_ALL=C sort)"
VIBERTEMIS_EXPECTED_MEMBERS="$(
  printf '%s\n' \
    artemis-steam-deck.flatpak \
    artemis-steam-deck.flatpak.sha256 |
    LC_ALL=C sort
)"
VIBERTEMIS_ARCHIVE_TYPES="$(tar -tvzf "artemis-steam-deck-bundle.tar.gz" | cut -c1 | LC_ALL=C sort)"
VIBERTEMIS_EXPECTED_TYPES="$(printf '%s\n' - - | LC_ALL=C sort)"
if [[ "$VIBERTEMIS_ARCHIVE_MEMBERS" != "$VIBERTEMIS_EXPECTED_MEMBERS" ||
      "$VIBERTEMIS_ARCHIVE_TYPES" != "$VIBERTEMIS_EXPECTED_TYPES" ]]; then
  printf '%s\n' 'Archive validation failed: expected exactly two regular files.' >&2
  exit 1
fi
tar -xzf "artemis-steam-deck-bundle.tar.gz" \
  --no-same-owner --no-same-permissions \
  -- artemis-steam-deck.flatpak artemis-steam-deck.flatpak.sha256
sha256sum --check artemis-steam-deck.flatpak.sha256
flatpak remote-add --user --if-not-exists flathub \
  https://flathub.org/repo/flathub.flatpakrepo
flatpak install --user --or-update artemis-steam-deck.flatpak
flatpak info --user com.artemisdesktop.ArtemisDesktopDev
```

What to expect:

- `curl` downloads one atomic archive containing the Flatpak and its checksum.
- The two `tar` listings require exactly the two expected names and require
  both entries to be regular files. Extra, duplicate, linked, or path-changing
  entries stop the block before extraction. The extraction then names only
  those two approved members, ignores archive ownership, and applies your
  normal user permissions.
- `sha256sum` must print `artemis-steam-deck.flatpak: OK`. Because the block
  starts with `set -e`, a mismatch stops before any Flatpak command runs.
- `flatpak remote-add` makes the Flathub runtime source available only for your
  user. Re-running it is harmless.
- `flatpak install` shows the application ID and permissions before asking for
  confirmation.
- `flatpak info` prints the installed application details when installation
  succeeds.

The verified archive and Flatpak stay in the printed
`~/Downloads/vibertemis-install.*` directory. The block deliberately does not
delete them, so they remain available if installation needs diagnosing.

If the download fails, check the network and run the entire block again. It
creates a new directory. If the checksum fails, do not install that file and do
not bypass the check; wait a minute and retry the whole block in a new
directory.

## Launch in Desktop Mode

Start Vibertemis from the application launcher, or copy this into Konsole:

```bash
flatpak run com.artemisdesktop.ArtemisDesktopDev
```

Pair a host and check that the interface responds before adding the shortcut to
Steam. Quit Vibertemis when that check is complete.

## Add Vibertemis to Steam

First confirm that the user installation is present:

```bash
flatpak info --user com.artemisdesktop.ArtemisDesktopDev
```

Then:

1. Open the Steam desktop client from the application launcher.
2. Choose **Games > Add a Non-Steam Game to My Library**.
3. Tick **Vibertemis**, then choose **Add Selected Programs**.
4. If Vibertemis is not listed, restart Steam once after confirming the
   `flatpak info` command above succeeds. Do not browse to an internal Flatpak
   executable.

The application ID remains stable across updates, so this shortcut normally
needs to be added only once.

## Launch in Gaming Mode

Before leaving Desktop Mode, this copy/paste check confirms the shortcut's app
is still installed:

```bash
flatpak info --user com.artemisdesktop.ArtemisDesktopDev
```

Double-click **Return to Gaming Mode** on the desktop. In Gaming Mode, open
**Library > Non-Steam**, select **Vibertemis**, and choose **Play**. Start with
Steam's standard gamepad template. Confirm D-pad, sticks, `A`, `B`, and the
Steam on-screen keyboard before changing controller mappings.

## Update from inside Vibertemis

A verified rolling build checks the maintained release for a strictly newer
source commit. It does not interrupt a stream or open a dialog by itself.

1. Open Vibertemis and activate the update button, or use
   **Settings > Vibertemis Features > Check for updates**.
2. Review the current build, available build, and download size.
3. Choose **Download update**. Vibertemis downloads the exact release asset to
   Downloads and verifies its recorded byte size and SHA-256.
4. In Gaming Mode, the verified file is retained and Vibertemis asks you to
   switch to Desktop Mode. It does not try to install from Gaming Mode.
5. Reopen Vibertemis in Desktop Mode. The app refetches the release information
   and reverifies the same file before enabling **Open installer**.
6. Choose **Open installer**, review the request in Discover, and confirm the
   update there. Vibertemis reports only that the installer was requested; it
   never claims Discover completed the installation.
7. Close Vibertemis, finish the update in Discover, and relaunch it.

Print the build identity before or after an update with:

```bash
flatpak run com.artemisdesktop.ArtemisDesktopDev --build-info
```

This beta uses a single-file Flatpak bundle rather than a Flatpak repository,
so `flatpak update` alone cannot discover a new Vibertemis bundle. Use the
verified in-app flow or repeat the first-install block.

## If Discover does not open: manual fallback

Switch to Desktop Mode and reopen the update dialog. Choose **Copy manual
command** only after the dialog says the download is verified. Paste that exact
command into Konsole. It has this quoted form:

```bash
set -euo pipefail
VIBERTEMIS_UPDATE="$HOME/Downloads/artemis-steam-deck-PASTE_12_CHARACTER_COMMIT.flatpak"
if [[ ! -f "$VIBERTEMIS_UPDATE" ]]; then
  printf 'Verified update file not found: %s\n' "$VIBERTEMIS_UPDATE" >&2
  exit 1
fi
flatpak install --user --or-update "$VIBERTEMIS_UPDATE"
```

Replace `PASTE_12_CHARACTER_COMMIT` with the 12-character filename shown by
Vibertemis, or use the exact command copied by the app. The explicit file guard
prints an error and exits before Flatpak if the path is wrong. Do not remove the
quotes, choose a different download, or run the install command before
Vibertemis reports verification.

If the update dialog reports that the saved file or release changed, use
**Check for updates** again and download a fresh copy. A failed Discover handoff
does not delete the verified file.

## Safe diagnostics

These commands report the installed package, embedded build identity, and only
the session values needed to distinguish Desktop and Gaming Mode:

```bash
flatpak info --user com.artemisdesktop.ArtemisDesktopDev
flatpak run com.artemisdesktop.ArtemisDesktopDev --build-info
printf 'XDG_CURRENT_DESKTOP=%s\nKDE_FULL_SESSION=%s\nXDG_SESSION_TYPE=%s\nGAMESCOPE_WAYLAND_DISPLAY=%s\n' \
  "${XDG_CURRENT_DESKTOP:-}" \
  "${KDE_FULL_SESSION:-}" \
  "${XDG_SESSION_TYPE:-}" \
  "${GAMESCOPE_WAYLAND_DISPLAY:-}"
```

For a launch log:

```bash
mkdir -p "$HOME/vibertemis-logs"
set -o pipefail
flatpak run com.artemisdesktop.ArtemisDesktopDev 2>&1 \
  | tee "$HOME/vibertemis-logs/vibertemis-$(date +%Y%m%d-%H%M%S).log"
```

Quit Vibertemis after reproducing the issue. Review the log before sharing it;
it can contain hostnames, IP addresses, application names, and other local
details. Share only the four session values printed above, not a full
environment dump.

## Uninstall

This removes the user installation:

```bash
flatpak uninstall --user com.artemisdesktop.ArtemisDesktopDev
```

Flatpak may ask for confirmation. This command does not request deletion of the
application's saved settings and pairing data. Remove the non-Steam shortcut
from Steam separately if you no longer want it in the library.

## Next steps

For resolution and refresh matching, Moonlight/Apollo/Vibepollo setup, codecs,
HDR, latency, controls, logs, and the LCD/OLED acceptance matrix, continue with
the [advanced Steam Deck streaming and validation guide](STEAM_DECK.md).
Report reproducible results in the
[Vibertemis issue tracker](https://github.com/samelamin/vibertemis/issues).
