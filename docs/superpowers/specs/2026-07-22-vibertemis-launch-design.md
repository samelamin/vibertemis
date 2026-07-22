# Vibertemis launch design

## Purpose

Launch this maintained fork publicly as **Vibertemis**, an AI-enhanced Artemis
client focused on Steam Deck reliability and compatibility with Vibepollo,
Apollo, and Sunshine. The launch should make the fork's verified improvements
easy to understand, invite useful beta reports, and preserve compatibility with
existing Artemis installations.

The name follows Vibepollo's convention: Artemis becomes Vibertemis because AI
is an explicit part of the maintenance and development workflow. Sam Elamin
directs and maintains the fork; Codex assists with implementation, tests,
packaging, and documentation; Claude is used as an independent design and code
reviewer. The project must not imply that upstream Artemis or all inherited code
was AI-generated.

## Public identity

The user-facing product and repository name is `Vibertemis`:

- GitHub repository: `samelamin/vibertemis`;
- README title, badges, downloads, clone instructions, support links, and release
  language;
- Linux and Flatpak desktop display names and AppStream summary;
- macOS application bundle display name and package artifact names;
- Windows product description, installer display name, and package artifact
  names where the existing build system exposes them safely; and
- visible application labels that currently present Artemis as the product.

The README tagline is: "An AI-enhanced Artemis/Moonlight client built for Steam
Deck and Vibepollo-compatible streaming."

## Compatibility boundary

Public renaming must not strand existing users or create a second application
identity unnecessarily. These technical compatibility identifiers remain:

- Flatpak application ID `com.artemisdesktop.ArtemisDesktopDev`;
- QSettings organization/domain and application key used by existing settings
  and pairings;
- operating-system upgrade/install identifiers that determine whether a new
  package replaces an old one;
- legacy `artemis` executable and `artemis.pro` project names where changing
  them would break launch commands, scripts, or downstream packaging;
- existing URL scheme and `art://` artwork behavior; and
- source filenames, C++ class names, copyright notices, and Git history.

Where the framework supports it, the runtime keeps the legacy internal
application name for settings while setting the visible display name to
`Vibertemis`. Documentation explains the retained Flatpak ID and executable as
compatibility details, not incomplete branding.

The local Git configuration will use:

- `origin`: `https://github.com/samelamin/vibertemis.git` for fetch and push;
- `upstream`: `https://github.com/wjbeckett/artemis.git` for fetch only; and
- a disabled upstream push URL so an accidental upstream push fails locally.

## README launch story

The README lead introduces Vibertemis, identifies it as Sam Elamin's maintained
fork of William Beckett's Artemis, and explains the AI-enhanced name without
diminishing upstream authorship.

A prominent `Why Vibertemis?` section compares the maintained fork with the
upstream reports that motivated it. Claims are limited to behavior covered by
the branch's tests, packaging checks, or locally exercised package path:

| Area | Upstream problem reports | Vibertemis status |
| --- | --- | --- |
| Steam Deck installation | #27, #53, #58 | Pinned rolling Flatpak, install instructions, dependency and startup checks |
| Build reliability | #48 | Fork-owned workflows, pinned inputs, CI contracts, and release artifacts |
| Handheld streaming | Steam Deck-focused maintenance | Gamescope/Vulkan compatibility, exact fractional refresh metadata, bounded audio queueing, and controller-first dialogs/settings |
| Apple Silicon | #63 | Native arm64 package and DMG verification with the signing limitation disclosed |
| Maintenance | #49 | Active fork issue tracker, rolling beta release, and documented AI-assisted workflow |

The comparison must not claim upstream code is broken in every environment. It
describes reported problems and what this fork now verifies.

The README also includes:

- `AI-enhanced development`, naming Codex and Claude's roles and stating that
  human direction, review, and responsibility remain with the maintainer;
- `Beta testers wanted`, with a short list of high-value Steam Deck, Vibepollo,
  Apollo, Sunshine, HDR, AV1, fractional-refresh, controller, audio, and macOS
  test scenarios;
- `Known validation gaps`, explicitly stating that physical Deck HDR, Deck
  hardware AV1 decoding, signed/notarized macOS distribution, and real-host
  streaming are not proven merely by CI; and
- issue-reporting guidance that asks for the exact build, host/version, stream
  settings, client logs, and reproduction details.

The existing detailed Steam Deck and development documentation remains the
source of truth. The README gives a concise path into it rather than duplicating
the full acceptance matrix.

## Issue-backed product roadmap

The beta launches on verified reliability work rather than adding a broad,
untested feature solely for marketing. The first post-beta headline feature is
per-host settings profiles, informed by upstream issues #54 and #67. Clipboard
file synchronization/drag-and-drop (#51 and #52), Windows frame pacing (#50),
and Steam Link builds (#59) remain candidate roadmap items pending scope and
platform validation.

HDR (#21) and hardware AV1 decoding (#23) are beta acceptance targets. The
packaging includes the required codec capabilities and validation probes, but
the project will not mark those reports solved until a physical Steam Deck live
stream demonstrates them.

## Repository rename and redirects

Rename the GitHub repository only after local content and workflow gates are
ready. GitHub's repository redirect will preserve old links, but all maintained
user journeys and workflow repository checks must be changed to
`samelamin/vibertemis`. Upstream attribution links remain pointed at
`wjbeckett/artemis`.

The rolling release tag stays `steam-deck-latest`. Compatibility asset names
may remain `artemis-steam-deck.*` for the first renamed beta so existing direct
download scripts continue working. New Vibertemis aliases can be added only if
the release publisher verifies both names from the same build; the README uses
the compatibility filename until that is implemented.

## Verification

- Add a repository branding contract that rejects obsolete fork-owned
  `samelamin/artemis` URLs and checks the required Vibertemis README sections.
- Preserve the existing narrow allowlist for upstream Artemis attribution.
- Parse modified workflow YAML and run `actionlint` when available.
- Run existing repository URL, workflow, Flatpak manifest/metadata, updater,
  and packaging tests affected by the identity change.
- Build or package any platform whose build metadata changes when the local
  toolchain is available; otherwise rely on the fork's platform CI and report
  that limitation explicitly.
- Inspect the final diff for accidental changes to compatibility identifiers,
  source copyrights, and upstream attribution.

## Reddit beta announcement

Draft, but do not publish, a Reddit post after the pushed commit and resulting
build state are known. The post should be personal and transparent: Sam is
reviving Artemis as Vibertemis with AI-assisted development, wants real hardware
feedback, and will use reports to prioritize the roadmap. It should lead with
the Steam Deck Flatpak and Vibepollo/Apollo compatibility, link to the release
and issue tracker, list the most useful tests, disclose beta limitations, and
avoid claims such as "flawless," "Steam Deck Verified," or "HDR fixed" until
physical acceptance evidence exists.

## Out of scope for this launch change

- Replacing compatibility IDs, settings locations, or URL schemes.
- Rebasing the Moonlight codebase.
- Implementing per-host profiles or file transfer before the beta post.
- Publishing the Reddit post or submitting the Flatpak to Flathub.
- Claiming production readiness, Steam Deck Verified status, or complete
  physical hardware coverage.
