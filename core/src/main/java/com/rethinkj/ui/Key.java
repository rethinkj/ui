package com.rethinkj.ui;

import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * A key that went down, repeats while held, or was released: {@link App#keyDown}, {@link
 * App#keyUp}, and the window's key events.
 *
 * <p>The name does not depend on the modifiers or on what the key types: the key that types "a" is
 * {@code A}, with or without shift. Named keys use the names of the W3C UI Events {@code key}
 * values, except that the space bar is {@code Space}:
 *
 * <ul>
 *   <li>{@code Enter}, {@code Tab}, {@code Space}, {@code Backspace}, {@code Delete}, {@code
 *       Escape}, {@code Insert}
 *   <li>{@code ArrowLeft}, {@code ArrowRight}, {@code ArrowUp}, {@code ArrowDown}, {@code Home},
 *       {@code End}, {@code PageUp}, {@code PageDown}
 *   <li>{@code F1} to {@code F24}
 *   <li>{@code Numpad0} to {@code Numpad9}, {@code NumpadAdd}, {@code NumpadSubtract}, {@code
 *       NumpadMultiply}, {@code NumpadDivide}, {@code NumpadDecimal}, {@code NumpadEnter} (a
 *       window reports the numpad's Enter as {@code Enter}; a script may still name this one)
 *   <li>{@code Shift}, {@code Control}, {@code Alt}, {@code Meta}, {@code CapsLock}, {@code
 *       NumLock}, {@code ScrollLock}
 *   <li>{@code PrintScreen}, {@code Pause}, {@code ContextMenu}
 *   <li>{@code Unidentified} for a key without a name
 * </ul>
 *
 * <p>Any other key that types a character is named by that character in upper case: {@code A},
 * {@code 1}, {@code -}, {@code Ä}. Keys of non-Latin layouts that have a Latin letter on them
 * report that letter.
 *
 * @param name the key's name, see above
 * @param modifiers the modifiers held, a combination of {@link #SHIFT}, {@link #CONTROL}, {@link
 *     #ALT} and {@link #META}
 * @param repeat whether the key went down again because it is held; always false when it is
 *     released
 */
public record Key(String name, int modifiers, boolean repeat) {

  /** Bits of {@link #modifiers}. */
  public static final int SHIFT = 1;

  public static final int CONTROL = 2;

  public static final int ALT = 4;

  /** The Windows key, or Super on Linux. */
  public static final int META = 8;

  static final Set<String> NAMED = new HashSet<>(Set.of(
      "Enter",
      "Tab",
      "Space",
      "Backspace",
      "Delete",
      "Escape",
      "Insert",
      "ArrowLeft",
      "ArrowRight",
      "ArrowUp",
      "ArrowDown",
      "Home",
      "End",
      "PageUp",
      "PageDown",
      "NumpadAdd",
      "NumpadSubtract",
      "NumpadMultiply",
      "NumpadDivide",
      "NumpadDecimal",
      "NumpadEnter",
      "Shift",
      "Control",
      "Alt",
      "Meta",
      "CapsLock",
      "NumLock",
      "ScrollLock",
      "PrintScreen",
      "Pause",
      "ContextMenu",
      "Unidentified"));

  static {
    for (int i = 1; i <= 24; i++) {
      NAMED.add("F" + i);
    }
    for (int i = 0; i <= 9; i++) {
      NAMED.add("Numpad" + i);
    }
  }

  public Key {
    Objects.requireNonNull(name, "name");
  }

  /** A key without modifiers that is not a repeat. */
  public Key(String name) {
    this(name, 0, false);
  }

  /** Whether a key can have this name: one of the list above, or one character in upper case. */
  static boolean isName(String name) {
    if (NAMED.contains(name)) {
      return true;
    }
    return name.codePointCount(0, name.length()) == 1
        && name.codePointAt(0) > ' '
        && name.toUpperCase(Locale.ROOT).equals(name);
  }
}
