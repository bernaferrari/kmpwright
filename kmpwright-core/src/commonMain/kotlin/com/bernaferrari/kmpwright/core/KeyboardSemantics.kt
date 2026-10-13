package com.bernaferrari.kmpwright.core

internal data class SyntheticKeyDescriptor(
    val key: String,
    val code: String,
    val keyCode: Int,
    val text: String?
)

internal fun describeSyntheticKeyPress(requestedKey: String, pressedModifiers: Set<String>): SyntheticKeyDescriptor {
    val physicalKey = canonicalModifierKey(requestedKey)
    val key = displayedKeyForModifiers(physicalKey, pressedModifiers)
    return SyntheticKeyDescriptor(
        key = key,
        code = keyCodeNameForKey(physicalKey),
        keyCode = domKeyCodeForKey(physicalKey),
        text = insertedTextForKey(physicalKey, pressedModifiers)
    )
}

internal fun keyValueForPhysicalKey(physicalKey: String, pressedModifiers: Set<String>): String =
    describeSyntheticKeyPress(physicalKey, pressedModifiers).key

internal fun textForKeyPress(physicalKey: String, pressedModifiers: Set<String>): String? =
    describeSyntheticKeyPress(physicalKey, pressedModifiers).text

internal fun domKeyCodeForPhysicalKey(physicalKey: String): Int =
    describeSyntheticKeyPress(physicalKey, emptySet()).keyCode

internal fun domCodeForPhysicalKey(physicalKey: String): String =
    describeSyntheticKeyPress(physicalKey, emptySet()).code

internal fun isSelectAllShortcut(key: String, pressedModifiers: Set<String>): Boolean {
    val canonicalKey = canonicalModifierKey(key)
    if (!canonicalKey.equals("a", ignoreCase = true)) return false
    if (KeyboardModifiers.ALT in pressedModifiers) return false
    return KeyboardModifiers.CONTROL in pressedModifiers || KeyboardModifiers.META in pressedModifiers
}

/**
 * Canonical key name: modifier aliases (`Ctrl`, `Cmd`, `Option`, `ControlOrMeta`) become `Control`/`Meta`/
 * `Alt`, and Playwright's US-layout code names become their key (`Space` -> `" "`, `KeyA` -> `"a"`,
 * `Digit1` -> `"1"`, `Minus` -> `"-"`, ...). Other names (`Enter`, `ArrowLeft`, `F5`, single characters)
 * are returned unchanged.
 */
internal fun canonicalModifierKey(key: String): String = when (key.lowercase()) {
    "shift" -> KeyboardModifiers.SHIFT
    "control", "ctrl" -> KeyboardModifiers.CONTROL
    "alt", "option" -> KeyboardModifiers.ALT
    "meta", "cmd", "command" -> KeyboardModifiers.META
    "controlormeta" -> controlOrMetaModifier()
    else -> keyForCodeName(key) ?: key
}

/** The key produced by a Playwright US-layout code name, or null when [name] is not one. */
private fun keyForCodeName(name: String): String? {
    if (name.length == 4 && name.startsWith("Key") && name[3] in 'A'..'Z') return name[3].lowercase()
    if (name.length == 6 && name.startsWith("Digit") && name[5] in '0'..'9') return name[5].toString()
    return when (name) {
        "Space" -> " "
        "Backquote" -> "`"
        "Minus" -> "-"
        "Equal" -> "="
        "BracketLeft" -> "["
        "BracketRight" -> "]"
        "Backslash" -> "\\"
        "Semicolon" -> ";"
        "Quote" -> "'"
        "Comma" -> ","
        "Period" -> "."
        "Slash" -> "/"
        "Esc" -> "Escape"
        "Return" -> "Enter"
        else -> null
    }
}

/** Every named (non-character) key the synthetic keyboards understand, as in Playwright's US layout. */
internal val NAMED_KEYS: Set<String> = setOf(
    "Enter", "Tab", "Escape", "Backspace", "Delete", "Insert", "ArrowLeft", "ArrowUp", "ArrowRight", "ArrowDown",
    "Home", "End", "PageUp", "PageDown", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12",
    KeyboardModifiers.SHIFT, KeyboardModifiers.CONTROL, KeyboardModifiers.ALT, KeyboardModifiers.META, KeyboardModifiers.CAPS_LOCK
)

internal fun String.isModifierKey(): Boolean = when (this) {
    KeyboardModifiers.SHIFT,
    KeyboardModifiers.CONTROL,
    KeyboardModifiers.ALT,
    KeyboardModifiers.META -> true
    else -> false
}

internal fun String.shouldApplyDomEditing(): Boolean = when (this) {
    "Backspace", "Delete", "Enter", "Tab", "Escape", "ArrowLeft", "ArrowRight", "Home", "End" -> true
    else -> false
}

internal fun String.toUnicodeTextUnits(): List<String> {
    val units = mutableListOf<String>()
    var index = 0
    while (index < length) {
        val current = this[index]
        val next = this.getOrNull(index + 1)
        if (current in '\uD800'..'\uDBFF' && next != null && next in '\uDC00'..'\uDFFF') {
            units += substring(index, index + 2)
            index += 2
        } else {
            units += current.toString()
            index += 1
        }
    }
    return units
}

private fun displayedKeyForModifiers(physicalKey: String, pressedModifiers: Set<String>): String {
    if (physicalKey.length != 1 || physicalKey.isModifierKey()) {
        return physicalKey
    }
    return if (KeyboardModifiers.SHIFT in pressedModifiers) {
        shiftModifiedKey(physicalKey)
    } else {
        physicalKey
    }
}

private fun insertedTextForKey(physicalKey: String, pressedModifiers: Set<String>): String? {
    if (physicalKey.length != 1 || physicalKey.isModifierKey()) return null
    if (KeyboardModifiers.CONTROL in pressedModifiers) return null
    if (KeyboardModifiers.ALT in pressedModifiers) return null
    if (KeyboardModifiers.META in pressedModifiers) return null
    return displayedKeyForModifiers(physicalKey, pressedModifiers)
}

private fun shiftModifiedKey(key: String): String {
    if (key.length != 1) return key
    val char = key.single()
    return when {
        char in 'a'..'z' -> char.uppercase()
        char in 'A'..'Z' -> key
        else -> SHIFTED_PUNCTUATION[char]?.toString() ?: key
    }
}

private fun keyCodeNameForKey(key: String): String = when (key) {
    "Enter" -> "Enter"
    "Tab" -> "Tab"
    "Escape" -> "Escape"
    "ArrowLeft" -> "ArrowLeft"
    "ArrowUp" -> "ArrowUp"
    "ArrowRight" -> "ArrowRight"
    "ArrowDown" -> "ArrowDown"
    "Backspace" -> "Backspace"
    "Delete" -> "Delete"
    "Home" -> "Home"
    "End" -> "End"
    "PageUp" -> "PageUp"
    "PageDown" -> "PageDown"
    "Insert" -> "Insert"
    KeyboardModifiers.CAPS_LOCK -> "CapsLock"
    "F1" -> "F1"
    "F2" -> "F2"
    "F3" -> "F3"
    "F4" -> "F4"
    "F5" -> "F5"
    "F6" -> "F6"
    "F7" -> "F7"
    "F8" -> "F8"
    "F9" -> "F9"
    "F10" -> "F10"
    "F11" -> "F11"
    "F12" -> "F12"
    KeyboardModifiers.SHIFT -> "ShiftLeft"
    KeyboardModifiers.CONTROL -> "ControlLeft"
    KeyboardModifiers.ALT -> "AltLeft"
    KeyboardModifiers.META -> "MetaLeft"
    " " -> "Space"
    else -> when {
        key.length == 1 && key.single() in 'a'..'z' -> "Key${key.uppercase()}"
        key.length == 1 && key.single() in 'A'..'Z' -> "Key$key"
        key.length == 1 && key.single() in '0'..'9' -> "Digit$key"
        key.length == 1 -> punctuationCodeFor(key.single())
        else -> key
    }
}

private fun domKeyCodeForKey(key: String): Int = when (key) {
    "Enter" -> 13
    "Tab" -> 9
    "Escape" -> 27
    "ArrowLeft" -> 37
    "ArrowUp" -> 38
    "ArrowRight" -> 39
    "ArrowDown" -> 40
    "Backspace" -> 8
    "Delete" -> 46
    "Home" -> 36
    "End" -> 35
    "PageUp" -> 33
    "PageDown" -> 34
    "Insert" -> 45
    KeyboardModifiers.CAPS_LOCK -> 20
    "F1" -> 112
    "F2" -> 113
    "F3" -> 114
    "F4" -> 115
    "F5" -> 116
    "F6" -> 117
    "F7" -> 118
    "F8" -> 119
    "F9" -> 120
    "F10" -> 121
    "F11" -> 122
    "F12" -> 123
    KeyboardModifiers.SHIFT -> 16
    KeyboardModifiers.CONTROL -> 17
    KeyboardModifiers.ALT -> 18
    KeyboardModifiers.META -> 91
    " " -> 32
    else -> when {
        key.length == 1 && key.single() in 'a'..'z' -> key.single().uppercaseChar().code
        key.length == 1 && key.single() in 'A'..'Z' -> key.single().code
        key.length == 1 && key.single() in '0'..'9' -> key.single().code
        key.length == 1 -> punctuationKeyCodeFor(key.single())
        else -> 0
    }
}

private fun punctuationCodeFor(char: Char): String = when (char) {
    ';', ':' -> "Semicolon"
    '=', '+' -> "Equal"
    ',', '<' -> "Comma"
    '-', '_' -> "Minus"
    '.', '>' -> "Period"
    '/', '?' -> "Slash"
    '`', '~' -> "Backquote"
    '[', '{' -> "BracketLeft"
    '\\', '|' -> "Backslash"
    ']', '}' -> "BracketRight"
    '\'', '"' -> "Quote"
    else -> char.toString()
}

private fun punctuationKeyCodeFor(char: Char): Int = when (char) {
    ';', ':' -> 186
    '=', '+' -> 187
    ',', '<' -> 188
    '-', '_' -> 189
    '.', '>' -> 190
    '/', '?' -> 191
    '`', '~' -> 192
    '[', '{' -> 219
    '\\', '|' -> 220
    ']', '}' -> 221
    '\'', '"' -> 222
    else -> 0
}

private val SHIFTED_PUNCTUATION = mapOf(
    '1' to '!',
    '2' to '@',
    '3' to '#',
    '4' to '$',
    '5' to '%',
    '6' to '^',
    '7' to '&',
    '8' to '*',
    '9' to '(',
    '0' to ')',
    '-' to '_',
    '=' to '+',
    '[' to '{',
    ']' to '}',
    '\\' to '|',
    ';' to ':',
    '\'' to '"',
    ',' to '<',
    '.' to '>',
    '/' to '?',
    '`' to '~'
)
