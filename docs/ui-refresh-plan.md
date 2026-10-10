# Easy Share UI and transfer contract

This document records the current UI implementation on local `main`.
The approved application version is `1.0.1` (`versionCode = 6`). Integration does not
authorize a remote push, publication, or key replacement.
Builds use only the Release variant and the existing official signing key.
Missing signing inputs fail packaging; tests and compilation remain available.
Universal and arm64 APKs are generated and signed together by Gradle ABI splits;
CI does not unpack, modify, or re-sign either output.

## Home and settings

- Preserve the approved dynamic-color hero artwork, size, and spacing in regular
  portrait windows. Below 480 dp of available height, limit artwork to one third
  of that height and reduce its explanation spacing to keep controls reachable.
- Keep all settings on Home. The six settings rows share an 84 dp minimum height
  and can grow for larger text. Group enhanced mode with secure transfer, and
  save location with debug logs.
- Edit the brand through its icon and the device name through its name. Pixel
  uses the existing Google artwork; the device card follows the brand color.
- Show `brand · current state` in the device card. Idle readiness depends on
  permissions, Bluetooth, Wi-Fi, and confirmed advertising/GATT readiness, not
  the background-reception toggle. Show a recovery action in the same position
  when unavailable. Do not add a separate transfer-status row to Home.
- Foreground reception remains available when background reception is off.
  Do not add an overlay-permission flow for the foreground half sheet.
- Use the approved localized copy, Material typography roles, 12 dp separation
  between brand radio controls and logos, and one accessible toggle per row.
- Let device names and receiving paths wrap rather than hiding their trailing
  content. Brand selection labels share the remaining row width with the radio
  control and artwork. Keep action ripples inside their rounded touch regions.
- Use the Material title role in the app bar and inset setting dividers to the
  content alignment. Consume Scaffold padding before applying child insets.
- Explain unavailable Shizuku access while keeping its switch disabled. Validate
  trimmed device names before saving: require a nonempty value of at most 64
  UTF-8 bytes, without silently replacing or truncating invalid input.

## Theme and typography

- Keep the existing Easy Share blue accents, device-brand colors, and system
  dynamic-color selection. The fallback schemes define neutral surface and
  outline roles and blue supporting containers for both light and dark mode.
- Use semantic surface containers without an additional tint overlay on sheets.
  Retain the existing shape language and avoid introducing nested cards.
- Use system fonts and Material type sizes with explicit body and label line
  heights. Body and control labels use zero added tracking for Chinese text.

## Launcher foreground

- Retain the exact original three-node silhouette and proportions from
  `drawable-nodpi/easy_share_icon_foreground.png`; its 512 by 512 pixels remain
  unchanged. Keep the white foreground and existing pink-to-orange background
  (`#FFE4478B` to `#FFFC763F`). Android 13 monochrome icons reuse the same
  foreground drawable; the discovery animation loads the same original bitmap
  directly. The launcher remains responsible for the mask and themed-icon tint.
- Replace the negative inset with positive padding derived from the source alpha
  bounds `(73, 65)` to `(439, 429)`. On a 108 dp adaptive-icon canvas, left/right
  insets are 15.307104%, top is 16.526776%, and bottom is 14.087432%. This keeps
  the mark 53.568 dp wide and 53.2753 dp high, with its bounding-box center at
  `(54, 54)`, rather than redrawing or normalizing the original curved segments.
  The maximum opaque-source-pixel radius after scaling is 31.21997 dp, inside
  the central 33 dp safe radius. The search animation uses a 46.164 dp bitmap
  canvas to retain its previous approximately 33 dp visible mark.
- Replace the negative-inset bitmap foreground and remove its unreferenced
  foreground/monochrome density assets and monochrome wrapper. Preserve the
  background, Play Store artwork, and quick-settings tile. Remove legacy launcher
  mipmaps: the minimum API 31 always selects the existing adaptive XML resources.
- Source checks passed: XML parsing, positive padding, shared monochrome
  references, and no remaining references to removed resources. The original
  foreground remains byte-identical to `HEAD`. Previews render the original alpha and final XML padding
  under circular and rounded-square masks with the existing Sharp runtime.
  These checks establish source consistency and silhouette preservation;
  launcher rendering, themed icons, and animation appearance on a physical
  device remain **unverified**.

The viewport, safe-zone, layer separation, and monochrome behavior follow the
[Android adaptive-icon guidance](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive).

## Transfer half sheet

The approved eight-state design uses the extracted Pixel Quick Share consent
source for measured sizing and typography, retaining Easy Share's existing
Material colors, dynamic palette, device artwork and task owners.

- All transfer sheets measure content and clamp it below the safe top inset.
  There is no transfer-sheet screen-height fraction. The default one-line,
  gesture-navigation sample is 428 dp: header 56 + status 48 + media 252 +
  actions 48 + navigation 24. Actual text wrapping, font scale, insets and
  window constraints determine the runtime height. Settings retain their own
  explicit height cap.
- Use 28 dp top corners, the established 640 dp maximum width, a centered
  headlineSmall title (24 sp / 32 sp) with 24 dp top/horizontal padding, and
  titleSmall status/size labels (14 sp / 20 sp with zero tracking).
- The status row starts 24 dp below the heading and has a 24 dp minimum height.
  A known peer logo scales with the 14 sp text and has a 4 dp gap; unknown
  transfer brands omit that logo and its spacing. Do not infer device models
  from nicknames. Discovery tiles retain their existing device-icon behavior.
- The media region has a 252 dp minimum, with a shared 96 dp visual slot and
  the known total file size 8 dp below it. Generic attachment artwork uses an
  80 dp circle and the existing 32 dp type icon. Success retains that type
  artwork and adds a lower-right check badge. No inner rectangular surface,
  shadow, filenames, or thumbnail collage is used in the approved six transfer
  states. Text-only transfers and unknown sizes do not synthesize file sizes.
- Discovery uses the original search animation in the same visual slot, with
  the status above it and no file size. Device-grid items preserve their
  original 48/40 dp icon geometry, 6 dp name gap and common horizontal center.
  The 300 dp baseline grid body can scroll within a constrained window.
- Footer actions use end-aligned content widths, 12 dp spacing, 24 dp side
  padding, 40 dp visual minimum and the platform's 48 dp touch target. FlowRow
  wraps actions when text or available width requires it. Cancel/Close/Reject
  are outlined; Receive/Open are filled primary actions. All cancel labels
  use the existing localized Cancel resource. The cancel guard still owns
  the enabled state.
- Search, connecting, sending and successful send display the approved short
  status. Incoming file copy is shared between sheets and notifications:
  `{sender} 想要分享 {item}`, `正在接收 {item}` and
  `收到来自 {sender} 的 {item}`. Item type/count follows actual attachment
  metadata and confirmed saved-file counts. The three phrases have no final
  period. Partial/text/failed/saving/unconfirmed outcomes retain their distinct
  state contracts.
- Discovery transitions to the selected outgoing task in the same sheet;
  showing a task from a notification remains a separate existing entry path.
  Neither a layout transition nor a copy change mutates protocol stages.
- Discovery failures show Retry in the existing sheet and preserve the selected
  files. Explanations that duplicate the primary status are omitted.
- Retain the existing lifecycle-managed MDC 1.14 circular wavy indicator for
  active byte progress and finalization. Only accepted transfer progress shows
  a percentage, capped at 99 until the completion receipt/persistence boundary.
  Connecting's approved file preview has no fake byte progress. System
  notifications retain the existing native progress templates.
- Announce state/explanation changes via polite live regions; percentage text
  does not duplicate the progress indicator's numeric semantics. Failure and
  partial-result explanations use the semantic error color plus text.

| State | Sheet main copy | Sheet actions |
| --- | --- | --- |
| Discovering | 正在发现附近设备… | Cancel |
| Found devices | Existing device display names | Cancel |
| Connecting | 正在连接 | Cancel |
| Sending | 正在发送 | Cancel |
| Send complete | 发送完成 | Close |
| Incoming request | {sender} 想要分享 {item} | Reject / Receive |
| Receiving | 正在接收 {item} | Cancel |
| Receive complete | 收到来自 {sender} 的 {item} | Close / Open |

Receive consent expires after 30 seconds using a monotonic deadline. The first
1.5 seconds after accepting guard against an accidental cancel tap. Closing a
sheet is not a rejection, cancellation, or deadline extension.

## Task ownership and live updates

- The approved file notifications reuse the sheet's primary phrase and append
  known total size in parentheses, without a separate body-size row, filename
  list or redundant peer subtitle. Full file success uses the confirmed saved
  count. Partial/text/saving/failed notifications retain their existing details.
- NotificationCompat remains responsible for the system layout. Short chips
  retain brief stages/actual percentages; expanded phrases are not forced into
  the chip. No custom RemoteViews or notification lifecycle change is introduced.
- Foreground receive requests use the half sheet. Background Accept and Decline
  dispatch directly to the receive owner without starting an activity.
- The half sheet and live update represent the same task. Hiding the sheet does
  not stop the task. Dismissing a live update disables promotion for that task,
  rather than enabling it again on every progress tick.
- Keep one notification identity through consent, transfer, saving, and the
  terminal result. Terminal updates are neither ongoing nor promoted; they do
  not automatically reopen the half sheet.
- Terminal outcomes reject late progress and completion writes. A receive
  cancellation freezes progress and waits for storage cleanup to publish the
  actual saved-file outcome. The owner cleans up its active/busy state before
  admitting a new task.
- Accepted outgoing cancellation immediately replaces progress with Canceling
  in the sheet and notification, disables repeated cancellation, and retains
  ownership until cleanup publishes the terminal outcome.
- The optional large notification icon uses dedicated peer-brand artwork.
  Unknown brands and brands without dedicated artwork omit it; do not present
  the built-in Android fallback as a peer logo. Use the same artwork rule in
  the receiving half sheet and all live and terminal transfer notifications.
  The small icon represents the direction.
- File-transfer notification bodies open the task's receiving directory, not
  an internal file-list activity. A completed single-file result opens that
  file; a multiple-file result opens the directory captured for that task.
- Text results keep their clipboard/result entry. Quiet reception-readiness
  channels apply to new installations without resetting existing preferences.

## Device identity, persistence, and partial results

- Keep discovery and reception available according to foreground/background
  policy, while preventing concurrent admissions through the owning service.
- Prefer explicit incoming identity. Enrich it only with fresh discovery data
  matched to the peer address or a unique identity. A truncated cache name must
  not replace a complete incoming name.
- A verified joined remote Wi-Fi Direct group owner may supply a model/brand
  fallback for known model prefixes. An arbitrary nickname is not a model.
  The local device name remains the default unless the user customizes it.
- Cumulative progress stays below 100% until files are persisted. Sending
  distinguishes a completion receipt from an unconfirmed result.
- Cancellation can retain already completed files. Delete incomplete output;
  do not turn invalid archive paths, exceeded transfer limits, or validation
  failures into a successful partial result.
- Store result metadata atomically with a bounded token. Best-effort result
  navigation must not revoke files that were already persisted.
- Capture the receiving directory in task metadata. Changing the preference
  from A to B during a transfer must not redirect Open from A to B. Older JSON
  snapshots without this field still decode; they do not fall back to today's
  preference when the original directory is unknown.
- Preserve the existing transport authentication, TLS, and finite waits.

## Validation and release boundaries

Local integration requires fresh unit tests, lint, release Kotlin/resource
compilation, and R8 processing. Record the current result rather than carrying
forward old APK hashes or installation claims.

The 1.0 lint cleanup removes verified orphan resources and pre-API-31 launcher
bitmaps, moves density-independent artwork without changing its bytes, uses
plural resources for partial results, and removes API 31 checks that are always
true at the current minimum SDK. AndroidViewModel supplies its Application
through the existing getter. Backup rules explicitly retain the backup opt-out.
The build pins AGP 9.5.0-alpha09 to include the upstream removal of
`Configuration.setVisible` calls ([issue 565740572](https://developer.android.com/studio/releases/fixed-bugs/studio/2026.2.2)).
This is a preview toolchain dependency: stable AGP 9.4.1 does not include the
complete fix. Prefer a stable AGP release once it includes that fix, and validate
toolchain changes with `--warning-mode=fail` so deprecations fail verification.
The wrapper uses Gradle 9.8.1, its official distribution SHA-256, and the matching
generated wrapper. Kotlin plugins share patch version 2.4.21. No deprecation
logging is suppressed. Publication still requires an explicit release request.
Duplicate Netty license notices are merged instead of discarded. Only the
unused optional LZF/LZ4 decoder classes are excluded from forced Netty keeps;
the existing HTTP, TLS and WebSocket pipelines retain their keep rules.
The native ABI split list now includes x86_64, using the existing dependency
native libraries and the same official Release signing path. No lint baseline
or new warning suppression is introduced.

## 1.0 trial preparation

The four approved Chinese home descriptions use no punctuation. All existing
home titles and the introduction remain unchanged. README is rewritten for
users and explicitly distinguishes the unpublished 1.0 trial from Releases.

The current Release checks pass: 212 unit tests, zero lint issues, Kotlin and
resource compilation, and R8. A synthetic signing override outside
`store.file` is rejected by the complete `android.injected.signing.*` guard.

Native stripping uses the default NDK 28.2.13676358. libpag duplicates ARMv7
libraries under obsolete `armeabi`; excluding that directory during Gradle's
native-library merge resolves the strip warnings while retaining supported
ABIs. The same NDK and the SDK action fix are included in CI.

Ktor 3.6 brings multiple native QUIC classifiers into its Netty dependency
graph. The transfer server uses HTTPS and WSS over TCP and does not enable
HTTP/3, so `netty-codec-native-quic` is excluded at the Ktor Netty dependency
edge. HTTP/3 and QUIC Java classes remain available for Ktor's type references.
This removes the unused native files that triggered AGP's multiple-file
dependency-report warning without suppressing the report. Revisit this
exclusion before enabling HTTP/3. `NettyTransferTransportTest` exercises a real
Netty TLS listener, a certificate-pinned download, and a WSS message exchange.

Source synchronization targets `codex/1.0-pixel-test`, whose push does not
match the existing main-only publication workflow. No Release, tag, or manual
workflow dispatch is authorized for this trial. Device acceptance and actual
transfers remain separate from build and installation verification.

## Earlier 0.4 integration evidence

The notification-artwork follow-up passed all 214 Release unit tests with no
failures, errors, or skips. The unknown-brand and missing-artwork regressions
failed against the previous Android fallback and passed after the shared
artwork rule was applied. Release compilation, resource processing, lint, and
R8 completed successfully; lint reported zero errors and 95 warnings.
The canonical `:app:assembleRelease` invocation produced universal and arm64
APKs, both verified against the existing official certificate in `AGENTS.md`.
The arm64 APK was installed with `adb install -r` on the verified Pixel 11 Pro
(`67150DLKX003JM`). Readback confirmed version `0.4`, code `4`, and the installed
`base.apk` matched the local APK SHA-256:
`792710c5e820247c1ad28d111e1abbeea369d1e4b8affacdd74f82dc85c62b00`.
The app launched successfully. All 19 device-brand assets remain byte-identical
to `HEAD`.

The Pixel's Live Updates setting was read as checked and enabled without
changing it. Receiver diagnostics now record stage changes and notification
eligibility/permission booleans on API 37, plus explicit Live Update dismissal
events. They exclude peer names, file names, task keys, and payloads. These
diagnostics do not change notification presentation or alert policy. Actual
promotion after accepting an OPPO request and fresh unknown-brand notification
rendering remain **unverified** pending a new transfer. A disappearing heads-up
window alone does not establish that the system removed promotion.

The preceding optional receive-brand build passed all 213 Release unit tests with
no failures or skips, Release compilation/resource processing, lint, and R8.
Lint reported zero errors and 95 warnings. The canonical `:app:assembleRelease`
invocation produced universal and arm64 APKs; both passed signature verification
against the official certificate documented in `AGENTS.md`. Its arm64
APK was installed with `adb install -r` on the verified Pixel 11 Pro
(`67150DLKX003JM`), preserving app data. Device readback confirmed version `0.4`
and code `4`; its installed `base.apk` SHA-256 matched that build's local APK:
`f940ffacb16a508778fdce95a93fc566379b484e7be76fec6d05e317dd811499`.
Unknown-brand rendering, long sender names, large text, dark mode, and TalkBack
remain **unverified** on device; the earlier Pixel installation below applies
to the preceding build.

On 2026-10-07, all 213 unit tests passed without failures or skips. The new
font-scaling and fallback-color regressions failed before their fixes and passed
afterward. Release unit tests, lint, Kotlin/resource compilation, and R8 all
completed successfully with the final Release-only configuration. Lint reported
zero errors and 95 warnings after refining the existing half-sheet structure and
centering the discovery empty state. All 19 device-brand assets (18 PNGs and one
vector) remain byte-identical to `HEAD`. The established local signing source is
the existing macOS Keychain services and release keystore documented in
`AGENTS.md`. A real `:app:assembleRelease` invocation completed successfully and
produced both native ABI-split outputs. Both APKs passed signature verification
with official certificate SHA-256
`cd178ef09e01063f9028e0392d1fb6148661d851c79e3ba11a825351a7c26fcf`.
No post-packaging signing, replacement key, debug signing, or unsigned output
was used. Nothing was published.

The arm64 APK was installed with `adb install -r` on the identified Pixel 11 Pro
(`67150DLKX003JM`). Its installed `base.apk` SHA-256 matched the current local
arm64 output. A physical-device screenshot showed the OPPO Find N6 logo and
name centered on the same axis in the discovery grid. The source already
requested this common center before the explicit cell-width change; the
photographed offset's cause remains unknown. The Pura X Max/default-brand item,
two-device grid, and search empty-state rendering were not captured and remain
**unverified**. No transfer was initiated during the layout check.

Unit tests validate layout decisions and fallback-color contrast;
they do not establish rendered text bounds, dynamic-palette contrast, keyboard
reachability, screen-reader announcements, or visual acceptance.

The refined original-layout preview uses current source parameters, project
assets, and synthetic sample data. The wavy ring uses a fixed-phase model of
the pinned MDC 1.14.0 tokens and control-point calculations. Navigation and
mandatory-gesture bottom insets are simulated as 24 dp without double counting.
It is a design render, not an Android screenshot. Beyond the physical discovery
grid check above, dynamic colors, font scaling, animation, and transfer
interaction remain **unverified**.

The implementation follows the official Android guidance for
[semantics and live regions](https://developer.android.com/develop/ui/compose/accessibility/semantics),
[touch targets](https://developer.android.com/develop/ui/compose/accessibility/api-defaults),
[Material insets](https://developer.android.com/develop/ui/compose/system/material-insets),
and the pinned [Material Components progress indicators](https://github.com/material-components/material-components-android/blob/1.14.0/docs/components/ProgressIndicator.md).

Physical end-to-end peer recognition, native notification/PendingIntent
dispatch, storage-provider rollback, process death, TalkBack, large-font
rendering, and tablet acceptance remain **unverified** for this integration.
The adaptive two-pane implementation is present at windows of at least
840 × 480 dp; its physical tablet acceptance remains a release gate.

The separate 0.5 draft is not release approval. Version changes, replacement of
the signing identity, publication, and remote pushes require their own authorization.
