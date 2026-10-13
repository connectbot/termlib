# Terminal accessibility

The terminal exposes its text through Android virtual accessibility nodes. TalkBack can
explore visible rows by touch, move between rows with its normal previous/next gestures,
and scroll into retained history. Character and word reading use TalkBack reading controls.

## Reading and reviewing output

Touch-exploring a terminal character, focusing a text row, scrolling with accessibility, or choosing **Review output**
captures the current screen and retained history. The displayed text is frozen and new-output
speech pauses while the terminal continues processing data. The focused row stays available
even if the live terminal clears its screen, evicts history, or switches to a full-screen app.

The controls below the terminal also appear as accessibility actions:

- **Refresh review** replaces the snapshot with current output and starts at the current cursor.
- **Resume live output** releases the snapshot and resumes feedback for subsequent output. Focus stays on the same toggle button (now **Review output**); invoking the accessibility action instead places focus on the live terminal.
- **Return to input** resumes the terminal, focuses its input connection, and opens the current software keyboard, including after the host recorded a keyboard dismissal. `keyboardEnabled=false` still prevents input.
- **Search output** searches the frozen document, including across soft-wrapped rows.
  Previous/next match controls reveal the matching row and report its position in the results.
- **Start/End of line** move to the bounds of the current grid row, including trailing spaces.
  Soft-wrapped rows have separate boundaries.
- **Previous line**, **Next line**, and **Start/End of output** navigate the entire frozen
  document and reveal the destination automatically. Character and word reading also cross
  row and viewport boundaries. Words join across soft wraps; paragraph movement follows
  hard line breaks.
- **Double-tap and hold** on terminal output starts selection at the last explored character
  or review cursor. Navigating by characters, words, or lines extends it. Repeat the gesture
  to stop extending and keep the selection ready to copy. The accessible long-click action
  is labeled **Start selection** or **Stop selection** to match its current behavior.
- **Start selection** anchors a selection at the review cursor. Subsequent navigation,
  including row focus, extends it through retained history. **Stop selection** stops extending
  the range; **Switch selection endpoint** changes which end moves. **Clear selection** stays
  in review. **Select all output** selects the frozen screen and retained history.
- **Copy selection** joins soft wraps and preserves hard breaks and blank lines, omitting
  trailing grid spaces before copied hard breaks. **Copy line** still copies one row.
- **Describe formatting** reports terminal foreground/background colors (hexadecimal), bold,
  italic, underline style, strikethrough, blink, and reverse video. It uses the review cursor
  or selection, reporting mixed attributes where applicable. Formatting is spoken only on
  request; **Describe line** reports row position and semantic labels.
- **Previous link** and **Next link** visit individual occurrences through the entire frozen
  history, reveal the destination, and report its label, URL, and position among the links.
  They stop at either end. **Open link** activates only the link under the review cursor or
  last touch point, through the existing `onHyperlinkClick` callback. Repeated URLs remain
  separate occurrences. OSC 8 metadata takes precedence; plain URLs are included when the
  emulator's `autoDetectUrls` option is enabled. Wrapped detected URLs form one occurrence.
  Android 26 and later also expose individual URL spans for screen-reader link controls;
  Android 24 and 25 use the same previous/next/open actions.

Touch exploration keeps normal row speech while tracking the character under the finger,
including movement within the already focused row. It uses the renderer's bidi mapping and
normalizes wide cells, surrogate pairs, and combining sequences to a grapheme start. The
point remains available after lifting the finger. An empty selection follows the finger;
mark mode extends a selection. An existing selection outside mark mode is preserved, so
**Describe formatting** still describes that selection while **Open link** uses the touched
link. Scrolling or replacing the review snapshot clears the transient touch target.

These actions are also available in the **Review actions** menu. Android 37 and later expose
native selections spanning virtual rows on the terminal's parent node; older Android versions use the same app-managed
selection through review actions and keyboard commands.

With a physical keyboard focused on the review surface, Up/Down move between rows,
Left/Right read characters, Ctrl+Left/Right read words, and Shift extends selection across
rows. Page Up/Down move the review cursor by a viewport; Home/End move to the current grid
row boundaries, and Ctrl+Home/End move to the document boundaries. Up/Down retain the preferred
terminal column through shorter rows.
Ctrl+Shift+M toggles mark mode, which extends selections without holding Shift.
Ctrl+Shift+X switches the active endpoint, Ctrl+A selects all output, and Ctrl+C copies.
Alt+Up/Down visit previous/next links, with Shift or mark mode extending selection.
Ctrl+Enter opens the current link.
Escape clears review and returns to input. Other character keys in review are not sent to the remote application.
Tab moves to the controls. TalkBack's own keyboard shortcuts remain service-controlled.

The snapshot retains the original terminal grid. Changing the view size changes how many
rows/columns fit; horizontal scrolling and text navigation can reveal clipped columns.

## Session entry and retained review

Hosts can retain one `TerminalAccessibilityState` alongside each open emulator and pass
it as `Terminal(accessibilityState = state)`. Frozen text, reading position, selection,
and viewport offsets then survive leaving the screen or switching sessions, even when
live output has since scrolled away. State is memory-only and contains no views or
callbacks; a different emulator starts fresh.

`TerminalAccessibilityOptions(requestInitialFocus = true)` opts into one initial
TalkBack focus request per terminal view. Set it only when the host screen is resumed
and no dialog or authentication prompt needs focus. Live sessions target the terminal
container without freezing output; retained reviews target their previous row without
changing the selection. Subsequent output does not repeat the request. Hosts can call
`TerminalAccessibilityController.requestAccessibilityFocus()` after a popup closes to
restore the live host or the current review row without changing selection or input mode.
Only call it once the terminal window is ready and no other prompt needs focus.

Hosts can temporarily disable `announceOutput` and `announceCursor` while a popup owns
attention. Feedback is discarded during that interval and cleared at the transition,
so closing the popup does not replay muted output.

ConnectBot retains this state per open connection, announces the session name, and keeps
its toolbar available during touch exploration. Extra keys stay visible while the
software keyboard is open, with space reserved below the terminal. When the keyboard
closes, the existing extra-key visibility preference and auto-hide behavior apply.

## Direct input and composing commands

Consecutive blank rows form one swipe stop, announced as, for example, **7 blank lines**.
Line navigation skips the whole run in either direction, including runs longer than the
viewport. Touch exploration and selection retain physical row positions, and copying
preserves the original line breaks.

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
search, and copying work without shell integration. Links use the OSC 8 ranges retained by
the parser; this does not expand parser support for OSC 8 ranges spanning multiple rows.

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

The opt-in device tests `TerminalAccessibilityDeviceTest#talkBackSwipesNavigateIntoScrollbackAndBack`,
`TerminalAccessibilityDeviceTest#talkBackSwipesSkipWholeBlankRuns`,
and `TerminalAccessibilityDeviceTest#talkBackTouchTargetsCharactersAndIndividualLinks` use the emulator's virtual touchscreen with TalkBack enabled. First run
`tools/talkback-touch-bridge.py --sdk <sdk-path> --emulator-ini <running-emulator-ini>`
in a Python environment containing `grpcio` and `grpcio-tools`, then run the device test
with instrumentation argument `talkback=true`. The emulator startup log identifies its INI.
The bridge uses local authenticated gRPC and never prints the emulator token. It exits after
five minutes by default (`--seconds` changes the limit).

UiAutomation reads the resulting accessibility focus without suppressing TalkBack. Shell
`input swipe` and UiObject2 gesture injection do not exercise the accessibility input filter;
they must not be used as evidence of TalkBack swipe navigation. Direct accessibility-node
action tests separately cover scrolling, granular movement, selection, line boundaries, and
remote URL-span activation (including exactly-once callbacks and stale-span rejection).
The touch test holds a finger down before slowly dragging across characters and multiple
links on the same row, then checks the selected offsets and link callbacks.
The API 37 extended-selection test runs only on a compatible device.
`TerminalAccessibilityDeviceTest#longPressStartsAndStopsSelection` checks the standard
long-click action by default. With `talkback=true` and the touchscreen bridge running,
it checks real double-tap-and-hold gestures, selection extension, and copying instead.

To run the framework action tests on a particular device without the opt-in swipe test:

```sh
ANDROID_SERIAL=localhost:5556 ./gradlew :lib:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.connectbot.terminal.TerminalAccessibilityDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.notClass=org.connectbot.terminal.TerminalAccessibilityDeviceTest#talkBackSwipesNavigateIntoScrollbackAndBack,org.connectbot.terminal.TerminalAccessibilityDeviceTest#talkBackTouchTargetsCharactersAndIndividualLinks,org.connectbot.terminal.TerminalAccessibilityDeviceTest#talkBackSwipesSkipWholeBlankRuns,org.connectbot.terminal.TerminalAccessibilityDeviceTest#resumeLiveOutputKeepsFocusOnTheReviewToggle
```

For a touchscreen TalkBack test, select only its method and target the emulator connected to the bridge:

```sh
ANDROID_SERIAL=emulator-5554 ./gradlew :lib:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.connectbot.terminal.TerminalAccessibilityDeviceTest#talkBackSwipesNavigateIntoScrollbackAndBack \
  -Pandroid.testInstrumentationRunnerArguments.talkback=true
```
