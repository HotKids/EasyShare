# Easy Share UI and transfer contract

This document records the approved implementation prepared for local `main`.
The application version remains `0.4` (`versionCode = 4`). Integration does not
authorize a remote push, release, signing change, or device installation.

## Home and settings

- Preserve the approved dynamic-color hero artwork, size, and spacing.
- Keep all settings on Home. The six settings rows share an 84 dp minimum height
  and can grow for larger text. Group enhanced mode with secure transfer, and
  save location with debug logs.
- Edit the brand through its icon and the device name through its name. Pixel
  uses the existing Google artwork; the device card follows the brand color.
- Show `brand · current state` in the device card. Idle readiness depends on
  Bluetooth and Wi-Fi, not the background-reception toggle. Do not add a
  separate transfer-status row to Home.
- Foreground reception remains available when background reception is off.
  Do not add an overlay-permission flow for the foreground half sheet.
- Use the approved localized copy, Material typography roles, 12 dp separation
  between brand radio controls and logos, and one accessible toggle per row.

## Transfer half sheet

Samsung Quick Share supplies the interaction reference; Easy Share retains its
Material colors, typography, and device-brand artwork.

- Center the app title and the peer/status description with the peer logo.
  Keep contextual information beside the state it explains, not below the page.
- Discovery contains the animated search or discovered devices and Cancel.
  It has no attachment summary or nested shaded device container.
- Active transfers use a central attachment panel and an independent text-button
  footer. Long filenames use middle ellipsis and retain their full-name tooltip.
- Fit active sheets to the available content height; discovery uses a 0.48
  height fraction. Content remains scrollable on short windows. The attachment
  panel has a 192 dp minimum height and the active progress visual is 96 dp.
- Results show up to three available image previews. Received files use Close
  and Open; text results without a file use Close. Sending results use Close.
  Results are not automatically dismissed.
- Use the official Material Components 1.14.0 wavy indicator inside the app.
  Stop its animation and release its wave speed on lifecycle changes. System
  notifications use native progress rather than this custom view.

| State | Progress | Actions |
| --- | --- | --- |
| Discovery | Search animation | Cancel |
| Connecting | Indeterminate | Cancel transfer |
| Waiting for acceptance | No byte progress | Send: Cancel transfer; receive: Decline / Accept |
| Transferring | 0–99% until the completion boundary | Cancel transfer |
| Saving or awaiting the completion receipt | Indeterminate | Cancel where supported by the task |
| Receive complete or partial with saved files | Terminal result | Close / Open |
| Send complete or partial | Terminal result | Close |
| Failed, declined, timed out, canceled, or unconfirmed | Terminal explanation | Close |

Receive consent expires after 30 seconds using a monotonic deadline. The first
1.5 seconds after accepting guard against an accidental cancel tap. Closing a
sheet is not a rejection, cancellation, or deadline extension.

## Task ownership and live updates

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
- The large notification icon is the peer's brand, with the built-in Android
  icon as the unknown-brand fallback. The small icon represents the direction.
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

On 2026-10-07, all 205 unit tests passed without skips. Debug lint completed with
no errors and 100 warnings; release Kotlin/resource compilation and R8
processing also passed. These checks did not sign, install, or publish an APK.

Physical end-to-end peer recognition, native notification/PendingIntent
dispatch, storage-provider rollback, process death, TalkBack, large-font
rendering, and tablet acceptance remain **unverified** for this integration.
The adaptive two-pane implementation is present at windows of at least
840 × 480 dp; its physical tablet acceptance remains a release gate.

The separate 0.5 draft is not release approval. Version changes, signing,
publication, and remote pushes require their own authorization.
