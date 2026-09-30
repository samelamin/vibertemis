# Signed Windows update contract

## Preview6 repair

MiniMax M3 drafted the update worker; Codex reviewed and repaired it under the
owner's update-repair authorization. Agy Gemini 3.1 Pro (High) is the second
reviewer. Keep per-user Inno installation and its existing busy-session refusal;
no MSIX migration, Restart Manager termination of VR, or new trust-key override.

- Filter/sort newer Quest tags before reading manifests. Broken old release assets
  cannot mask a valid new release; an invalid newest candidate fails visibly.
- Keep exact signed metadata with the downloaded file; verify again at handoff.
- A renamed copy of the installed manager validates a bounded job, signed release,
  fixed cache/layout paths, original executable and retained parent handle.
- Parent owns Ready/Commit events before launching worker. No commit means abort;
  no parent exit means no installer. Never close/kill unrelated SteamVR sessions.
- Retain installer handle/exit code. Check the installed manager's FileVersion,
  not the installer's metadata, plus `--verify-install` success before success.
- Save outcome before reopening. A valid previous installation can reopen after
  failure, with an error. Never report an unsuccessful attempt as updated.
- Unit tests cover invalid jobs/signatures, spoofed parent/helper, failed/slow
  installer, verification failure, cancellation, stale release and cached download.
- `smoke-update.ps1` builds a private temporary previous-version fixture trusting
  an ephemeral test key, then applies the unmodified release installer through
  the real worker. Test binaries/keys are never uploaded. Check custom Unicode
  path, installed version, integrity, reopened GUI and unchanged user state.
- Existing users whose updater is already broken may need one manual installation.
  No hardware stream-quality guarantee follows from installer tests.
