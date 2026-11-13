package com.rethinkj.ui.window;

import com.rethinkj.ui.Key;
import java.nio.file.Path;
import java.util.List;

/**
 * Something that happened to a {@link Window}, delivered to its {@link WindowListener}. Positions
 * and sizes are in logical pixels, the units {@link com.rethinkj.ui.App} lays out in; the
 * window's {@link Window#scale} converts them to physical pixels.
 */
public sealed interface WindowEvent {

  /** The pointer moved over the window. */
  record PointerMove(float x, float y) implements WindowEvent {}

  /** The pointer left the window, or the part of it that reports pointer moves. */
  record PointerLeave() implements WindowEvent {}

  /**
   * A button went down.
   *
   * @param clicks 1 for a single click, 2 for a double click, and so on
   * @param modifiers the modifiers held, as in {@link Key#modifiers}
   */
  record PointerDown(float x, float y, PointerButton button, int clicks, int modifiers)
      implements WindowEvent {

    /** A press without modifiers. */
    public PointerDown(float x, float y, PointerButton button, int clicks) {
      this(x, y, button, clicks, 0);
    }
  }

  record PointerUp(float x, float y, PointerButton button) implements WindowEvent {}

  /**
   * The wheel or the touchpad scrolled, by {@code dx} and {@code dy} in steps of the wheel, with
   * the pointer at {@code x, y}. Positive {@code dy} scrolls up, away from the user; positive {@code
   * dx} scrolls right. Systems that flip the direction ("natural scrolling") are already accounted
   * for, and so is Shift: a wheel that turns only up and down turns sideways while it is held.
   */
  record Wheel(float dx, float dy, float x, float y) implements WindowEvent {}

  /** A key went down, or repeats while held; {@link Key} lists the names. */
  record KeyDown(Key key) implements WindowEvent {}

  /** A key was released; its {@link Key#repeat} is false. */
  record KeyUp(Key key) implements WindowEvent {}

  /** Text typed with the keyboard or composed with an input method, to insert where the caret is. */
  record TextInput(String text) implements WindowEvent {}

  /**
   * Text that the system made of the key before it while the window's text input was off, which
   * a {@link TextInput} would have carried were it on: on Windows the character of {@code
   * WM_CHAR}, through the keyboard layout with its dead keys and AltGr; on X11 and Wayland the
   * text SDL made of a key while the window had turned text input on only because a press or a
   * key came before it, which might take the focus to a field. Input that comes in one
   * batch, a click into a field and the keys after it, arrives before the window can turn its
   * text input on for the field; {@link WindowLoop} gives this to {@link
   * com.rethinkj.ui.App#keyText}, which types it if the key went to the field. Control
   * characters, and the character of a key held with Control and without Alt, do not come; a
   * character that comes without a key, as one sent with {@code KEYEVENTF_UNICODE}, comes as a
   * {@link TextInput}.
   */
  record KeyText(String text) implements WindowEvent {}

  /**
   * An input method composes text, which is shown where the caret is until it commits it with a
   * {@link TextInput}; empty text ends the composition. Comes only while text input is on, see
   * {@link Window#textInputArea}.
   *
   * @param cursor where the input method's cursor is in {@code text}, in UTF-16 units; -1 for its
   *     end
   */
  record TextEditing(String text, int cursor) implements WindowEvent {}

  /** The window's size changed, in logical pixels. */
  record Resized(float width, float height) implements WindowEvent {}

  /**
   * The window's scale changed, for example because it moved to another display. A {@link
   * Resized} follows if the size in logical pixels changed as well.
   */
  record ScaleChanged(float scale) implements WindowEvent {}

  /**
   * The window moved to another display, or the mode of its display changed: its {@link
   * Window#refreshRate()} may be another, also where the scale stayed the same.
   */
  record DisplayChanged() implements WindowEvent {}

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
   * The system's appearance changed: light or dark, the accent colour or high contrast. Every
   * open window gets it. See {@link Window#systemAppearance()}.
   */
  record AppearanceChanged(SystemAppearance appearance) implements WindowEvent {}

  /**
   * Draw the window now, before the listener returns.
   *
   * @param live true while the user moves or resizes the window on Windows: the system then runs a
   *     loop of its own, and the app's loop does not get to run until the user lets go. This comes
   *     many times a second, and is the time to draw a frame if the size changed or something
   *     moves. False when the system lost what the window showed and it has to be drawn again.
   */
  record Redraw(boolean live) implements WindowEvent {}

  /** Files or text that another application drags are over the window at this point. */
  record FileDragOver(float x, float y) implements WindowEvent {}

  /** What another application dragged over the window left it without a drop. */
  record FileDragLeave() implements WindowEvent {}

  /**
   * Another application dropped files or text on the window at this point.
   *
   * @param files the files, empty for text
   * @param text the text, its lines joined by line feeds, or null for files
   */
  record FileDrop(float x, float y, List<Path> files, String text) implements WindowEvent {
    public FileDrop {
      files = List.copyOf(files);
    }
  }
}
