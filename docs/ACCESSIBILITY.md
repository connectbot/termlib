# Accessibility User Guide

Welcome to the accessibility guide for ConnectBot's terminal. We strive to engineer an accessibility experience designed for efficiency and semantic understanding.

## Key Features

*   **Review Mode**: Browse history without sending keys to the shell.
*   **Semantic Navigation**: Jump between prompts and commands instantly.
*   **Live Announcements**: Hear output as it happens (debounced for clarity).
*   **Shadow List**: Fully accessible line-by-line navigation compatible with TalkBack.

---

## How to Use

### 1. Basic Navigation (Input Mode)
By default, you are in **Input Mode**.
*   **Typing**: Use the on-screen keyboard or a physical keyboard to type commands.
*   **Reading**: TalkBack will announce new output automatically.
*   **Swiping**: You can swipe left/right to explore the screen, but for detailed reading, we recommend Review Mode.

### 2. Using Review Mode
Review Mode disconnects your keyboard from the shell, allowing you to scroll through history and inspect text granularly.

**To Enter Review Mode:**
1.  Focus on the terminal output.
2.  Open the TalkBack **Local Context Menu** (swipe up then right, or use 3-finger tap).
3.  Select **Actions** -> **Toggle Review Mode**.
    *   *Alternatively:* Double-tap the "Toggle Review Mode" custom action if exposed.

**In Review Mode:**
*   **Arrow Keys (Physical Keyboard)**:
    *   `Up`/`Down`: Move to previous/next line.
    *   `Left`/`Right`: Move by character (or word, depending on TalkBack granularity).
*   **Touch**: Swipe right/left to read line by line.
*   **Visual Cue**: A green highlight border appears around the focused line.

**To Exit Review Mode:**
*   Simply type any character key (letters, numbers, Enter). The terminal will automatically switch back to Input Mode and send the key to the shell.
*   Or use the **Toggle Review Mode** action again.

### 3. Semantic Navigation
We use "Shell Integration" to understand the structure of your terminal (Prompts vs Output).

**Actions Available:**
*   **Jump to Previous Prompt**: Skips all output and lands on the last prompt. Useful after running a command with long output (like `cat large_file`).
*   **Jump to Next Prompt**: Moves forward to the next command.
*   **Copy Line**: Copies the currently focused line to the clipboard.

**How to trigger:**
1.  Focus on a line in Review Mode.
2.  Open TalkBack **Actions** menu.
3.  Select the desired jump action.

---

## Shell Configuration (Required for Semantic Nav)

To enable Semantic Navigation (jumping to prompts), your shell must emit standard **OSC 133** escape sequences.

### For Bash
Add this to your `~/.bashrc`:

```bash
# ConnectBot integration (OSC 133)
PS1='[\e]133;A\e\]'$PS1'[\e]133;B\e\]'

__bp_preexec_interactive() {
    printf '\e]133;C\e\'
}
preexec_functions+=(__bp_preexec_interactive)

__bp_precmd_interactive() {
    printf '\e]133;D;%s\e\\' "$?"
}
precmd_functions+=(__bp_precmd_interactive)
```

*Note: You may need a helper like `bash-preexec` for the hooks to work perfectly, but the PS1 modification alone enables Prompt Jumping.*

### For Zsh
Add this to your `~/.zshrc`:

```zsh
# ConnectBot integration (OSC 133)
setopt PROMPT_SP
PS1=$'%{\e]133;A\e\%}'$PS1$'%{\e]133;B\e\%}'

preexec() {
    print -Pn '\e]133;C\e\'
}

precmd() {
    print -Pn '\e]133;D;%?\e\'
}
```

---

## Keyboard Shortcuts

| Key | Action (Review Mode) | Action (Input Mode) |
| :--- | :--- | :--- |
| `Up` / `Down` | Navigate History Lines | Send Up/Down to Shell |
| `Left` / `Right` | Navigate Characters | Send Left/Right to Shell |
| `PageUp` / `PageDown` | Scroll Page | Send PageUp/PageDown to Shell |
| `Enter` | **Exit Review Mode** & Send Enter | Send Enter |
| `Any Letter` | **Exit Review Mode** & Type | Type Character |

---

## Accessibility Conformance

This implementation targets **WCAG 2.1 Level AA** compliance.
*   **Contrast**: Default themes meet 4.5:1 contrast ratio.
*   **Resize**: Text scales with system font size settings.
*   **Focus**: Visible focus indicators in Review Mode.
*   **No Timing**: Users have unlimited time to read output.
