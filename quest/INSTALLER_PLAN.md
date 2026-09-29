# Quest installer and updater implementation

Status: work in progress, not released. Windows drafts are undergoing correction
and real Windows CI. Automatic headset routing is committed at cbc00870.

## Agreed contract

Per-user .NET 8 Windows manager and Inno installer; unchanged preview3 custom
ALVR runtime under runtime/; manager under manager/. Portable ALVR Dashboard,
driver manifest and session.json share runtime/. State/pairing stays in the
user profile; runtime/session.json survives reinstall and uninstall.

Refuse install/uninstall while SteamVR, ALVR Dashboard, manager or companion
is running, including silent mode. Never force-close VR. Updates may wait up
to 15 seconds for their exact launching manager PID. Explicit Exit stops only
the manager-owned companion, leaving Steam/SteamVR descendants alive.

Manager derives install root from its actual executable location. Verify the
packaged companion, helper and native runtime using build-generated embedded
SHA-256/length metadata. Publish helper before generating metadata; publish
manager last. --verify-install performs no launches and returns nonzero on
missing/corrupt payload. Firewall elevation is a separate fixed-action helper.

Starting the host companion remembers that intent for subsequent manager
launches; explicit Stop disables restoration. Windows login startup stays
opt-in. Quest Connect chooses tracked PCVR on VR-headtracking hardware, with
a Flat screen override; other Android devices connect flat. No SteamVR launch
from configuration, application startup or discovery.

## Remaining release gates

- Finish/review Windows host UX and process ownership; pass Windows installer
  install/reinstall/uninstall/busy refusal/custom-path/hash checks.
- Implement real signed in-app updates for Windows and Quest (RSA-3072 SHA256,
  bounded manifest, exact quest-preview channel/repository URLs, verified
  asset digest/size, anti-downgrade, OS user-confirmed installation).
- Implement paired LAN discovery without auto-trust; retain manual/VPN fallback.
- Build APK with existing signer; native protocol unchanged; publish only after
  Codex and Agy sign off and checks pass. Notify owner on Telegram when ready.
- Actual Quest 3/RTX 4090 streaming requires owner hardware testing.

## Review record

Agy architecture review approved the per-user/fixed-helper/authenticated-device
routing design (2026-09-29). Codex rejected the initial MiniMax Windows draft
for ineffective process gates, destructive uninstall, wrong runtime paths and
fake signature verification. Owner granted Codex takeover (one shared task
grant, recorded in the global ledger). Corrections in progress; Agy implementation
checkpoint review requested. This document is not release sign-off.

Checkpoint review (Agy, 2026-09-29): confirmed state retention and custom-root
layout. Addressed WMI connection reuse and hidden tray startup through an
ApplicationContext. Helper timeout already had an outer exception handler;
added a local exited-process catch. Proposed asynchronous companion race does
not apply: manager Stop waits for exact owned-process exit, and refuses Exit
on denied/timeout. Full updater/install handoff still awaits implementation and
review. No release sign-off granted by this checkpoint.
