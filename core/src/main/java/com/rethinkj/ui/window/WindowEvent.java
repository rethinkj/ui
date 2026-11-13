package com.rethinkj.ui.window;

/**
 * Something that happened to a {@link Window}, delivered to its {@link WindowListener}. Positions
 * and sizes are in logical pixels, the units {@link com.rethinkj.ui.App} lays out in; the
 * window's {@link Window#scale} converts them to physical pixels.
 */
public sealed interface WindowEvent {

  /** Bits of {@code modifiers} in key events. */
  int SHIFT = 1;

  int CONTROL = 2;

  int ALT = 4;

  /** The Windows key, or Super on Linux. */
  int META = 8;

  /** The pointer moved over the window. */
  record PointerMove(float x, float y) implements WindowEvent {}

  /** The pointer left the window, or the part of it that reports pointer moves. */
  record PointerLeave() implements WindowEvent {}

  /**
   * A button went down.
   *
   * @param clicks 1 for a single click, 2 for a double click, and so on
   */
  record PointerDown(float x, float y, PointerButton button, int clicks) implements WindowEvent {}

  record PointerUp(float x, float y, PointerButton button) implements WindowEvent {}

  /**
   * The wheel or the touchpad scrolled, by {@code dx} and {@code dy} in steps of the wheel, with
   * the pointer at {@code x, y}. Positive {@code dy} scrolls up, away from the user; positive {@code
   * dx} scrolls right. Systems that flip the direction ("natural scrolling") are already accounted
   * for.
   */
  record Wheel(float dx, float dy, float x, float y) implements WindowEvent {}

  /**
   * A key went down, or repeats while held.
   *
   * @param key the key's name, which does not depend on the modifiers: {@code A}, {@code Enter},
   *     {@code ArrowLeft}, {@code F5}, {@code Space}, {@code Escape}. Letters are upper case; other
   *     keys that type a character are named by it, keys of the number pad start with {@code
   *     Numpad}, and keys without a name are {@code Unidentified}.
   * @param modifiers the modifiers held, a combination of {@link #SHIFT}, {@link #CONTROL}, {@link
   *     #ALT} and {@link #META}
   */
  record KeyDown(String key, int modifiers, boolean repeat) implements WindowEvent {}

  /** A key was released; see {@link KeyDown} for the names. */
  record KeyUp(String key, int modifiers) implements WindowEvent {}

  /** Text typed with the keyboard or composed with an input method, to insert where the caret is. */
  record TextInput(String text) implements WindowEvent {}

  /** The window's size changed, in logical pixels. */
  record Resized(float width, float height) implements WindowEvent {}

  /**
   * The window's scale changed, for example because it moved to another display. A {@link
   * Resized} follows if the size in logical pixels changed as well.
   */
  record ScaleChanged(float scale) implements WindowEvent {}

  /** The window was minimized, maximized or restored to its normal size. */
  record StateChanged(boolean minimized, boolean maximized) implements WindowEvent {}

  /** The window gained or lost the keyboard focus. */
  record FocusChanged(boolean focused) implements WindowEvent {}

  /**
   * The user asked to close the window: with the close button, Alt+F4 or the taskbar. The window
   * stays open until {@link Window#close} is called.
   */
  record CloseRequested() implements WindowEvent {}

  /**
   * Draw the window now, before the listener returns.
   *
   * @param live true while the user moves or resizes the window on Windows: the system then runs a
   *     loop of its own, and the app's loop does not get to run until the user lets go. This comes
   *     many times a second, and is the time to draw a frame if the size changed or something
   *     moves. False when the system lost what the window showed and it has to be drawn again.
   */
  record Redraw(boolean live) implements WindowEvent {}
}
