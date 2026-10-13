# Terminal accessibility

The terminal exposes its text through Android virtual accessibility nodes. TalkBack can
explore visible rows by touch, move between rows with its normal previous/next gestures,
and scroll into retained history. Character and word reading use TalkBack reading controls.

## Reading and reviewing output

Focusing a terminal text row, scrolling with accessibility, or choosing **Review output**
captures the current screen and retained history. The displayed text is frozen and new-output
speech pauses while the terminal continues processing data. The focused row stays available
even if the live terminal clears its screen, evicts history, or switches to a full-screen app.

The controls below the terminal also appear as accessibility actions:

- **Refresh review** replaces the snapshot with current output and starts at the current cursor.
- **Resume live output** releases the snapshot and resumes feedback for subsequent output.
- **Return to input** resumes the terminal and restores direct keyboard input.
- **Search output** searches the frozen document, including across soft-wrapped rows.
  Previous/next match controls reveal the matching row and report its position in the results.
- **Copy line**, text selection/copy, and **Describe line** act on the row being inspected.

With a physical keyboard focused on the review surface, Up/Down move between rows,
Left/Right read characters, Ctrl+Left/Right read words, Shift extends selection within a row,
Page Up/Down scroll, and Home/End move to the document boundaries. Ctrl+C copies a selection.
Escape returns to input. Other character keys in review are not sent to the remote application.
Tab moves to the controls. TalkBack's own keyboard shortcuts remain service-controlled.

The snapshot retains the original terminal grid. Changing the view size changes how many
rows/columns fit; horizontal scrolling and text navigation can reveal clipped columns.

## Direct input and composing commands

Direct input is the default. Choose **Return to input** to use the terminal keyboard.

**Compose command** opens a standard Android-accessible text editor. Text, selection, and
IME composition stay local until **Send text** or **Send and Enter** is selected. Cancel,
Back, or dismissing the editor discards the draft. Sending text uses normal terminal input
handling; Send and Enter additionally sends an Enter key. The composer is optional and
requires `keyboardEnabled = true`.

## Live feedback

Processed screen changes and lines entering history are collected before UI frame updates.
Feedback uses fixed 300 ms windows, with repeated edits to a row coalesced. Pending output
text is bounded to 16 KiB of UTF-16 storage; overflow is reported and retained text remains
available through review. Review suppresses new feedback rather than accumulating a speech
backlog. Returning to live output does not replay everything received during review.

Cursor-only changes report character or row context from rendered terminal text. This does
not synthesize character echo from keystrokes. TalkBack controls speech delivery and may
combine updates; applications cannot determine its complete speech queue or internal
word/character reading cursor. Already queued speech may finish after review begins.

## Shell integration

OSC 133 metadata enables **Previous prompt**, **Next prompt**, **Read last command output**,
**Copy last command output**, and command-completion feedback. Prompt and command labels
are separate from node text, preserving text offsets for selection and copying.

Configure the shell to emit these sequences (terminated by BEL or ESC followed by backslash):

| Sequence | Meaning |
| --- | --- |
| OSC 133;A | Prompt starts |
| OSC 133;B | Command input starts |
| OSC 133;C | Command output starts |
| OSC 133;D;exit-code | Command finishes |

Use the shell's supported pre-command/pre-prompt hooks to emit these markers. Basic review,
search, and copying work without shell integration. OSC 8 links expose an Open link action.

## Library integration

`Terminal` accepts optional `accessibilityOptions` and `onAccessibilityControllerAvailable`:

```kotlin
Terminal(
    terminalEmulator = emulator,
    keyboardEnabled = true,
    accessibilityOptions = TerminalAccessibilityOptions(
        announceOutput = true,
        announceCursor = true,
    ),
    onAccessibilityControllerAvailable = { controller ->
        // Keep this controller for host UI actions. Invoke it on the main thread.
    },
)
```

`TerminalAccessibilityController` exposes `isReviewing`, `startReview()`, `refreshReview()`,
`resumeLiveOutput()`, and `returnToInput()`. `ComposeController` also opens the standard editor
when accessibility is enabled. Accessibility services and touch exploration are tracked
separately; enabling a service alone does not replace ordinary touch gestures.

## Validation

Host tests cover virtual nodes/actions, scrolling, focus retention, frozen snapshots, search,
text offsets, output collection, command grouping, and input isolation. These tests do not
establish TalkBack speech quality or accessibility conformance.

Before release, record Android and TalkBack versions and test on the minimum supported API
and a recent device, with blind users. Exercise swipe traversal across viewport boundaries,
touch exploration, two-finger scrolling, reading controls, Bluetooth keyboards, composition,
continuous output, history eviction, alternate-screen apps, resize, and returning to input.
