package com.rethinkj.ui.window;

import java.util.Locale;

/**
 * The names of {@link com.rethinkj.ui.Key}s, which lists them, from SDL key codes. A key code
 * follows the keyboard layout but not the modifiers: the key that types "a" is {@code A}, with or
 * without shift, and keys of non-Latin layouts that have a Latin letter on them report that
 * letter, as SDL does by default.
 */
final class Keys {

  private Keys() {}

  static final String UNIDENTIFIED = "Unidentified";

  /** SDL marks key codes that have no character with this bit and the scancode below it. */
  private static final int SCANCODE_MASK = 1 << 30;

  static String name(int keycode) {
    if ((keycode & SCANCODE_MASK) != 0) {
      return scancodeKey(keycode & ~SCANCODE_MASK);
    }
    return switch (keycode) {
      case 0 -> UNIDENTIFIED;
      case 0x08 -> "Backspace";
      case 0x09 -> "Tab";
      case 0x0D -> "Enter";
      case 0x1B -> "Escape";
      case 0x20 -> "Space";
      case 0x7F -> "Delete";
      default -> {
        if (keycode < 0x20 || !Character.isValidCodePoint(keycode)) {
          yield UNIDENTIFIED;
        }
        yield Character.toString(keycode).toUpperCase(Locale.ROOT);
      }
    };
  }

  /** Keys without a character, by their SDL scancode. */
  private static String scancodeKey(int scancode) {
    if (scancode >= 58 && scancode <= 69) {
      return "F" + (scancode - 57);
    }
    if (scancode >= 104 && scancode <= 115) {
      return "F" + (scancode - 104 + 13);
    }
    if (scancode >= 89 && scancode <= 97) {
      return "Numpad" + (scancode - 88);
    }
    return switch (scancode) {
      case 57 -> "CapsLock";
      case 70 -> "PrintScreen";
      case 71 -> "ScrollLock";
      case 72 -> "Pause";
      case 73 -> "Insert";
      case 74 -> "Home";
      case 75 -> "PageUp";
      case 77 -> "End";
      case 78 -> "PageDown";
      case 79 -> "ArrowRight";
      case 80 -> "ArrowLeft";
      case 81 -> "ArrowDown";
      case 82 -> "ArrowUp";
      case 83 -> "NumLock";
      case 84 -> "NumpadDivide";
      case 85 -> "NumpadMultiply";
      case 86 -> "NumpadSubtract";
      case 87 -> "NumpadAdd";
      case 88 -> "NumpadEnter";
      case 98 -> "Numpad0";
      case 99 -> "NumpadDecimal";
      case 101 -> "ContextMenu";
      case 224, 228 -> "Control";
      case 225, 229 -> "Shift";
      case 226, 230 -> "Alt";
      case 227, 231 -> "Meta";
      default -> UNIDENTIFIED;
    };
  }
}
