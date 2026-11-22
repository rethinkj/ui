package com.rethinkj.ui;

import java.util.List;
import java.util.Objects;

/**
 * Where the pointer of an {@link App} is and what it is over: whether it is in the window and
 * where, the elements under it in the tree of the last frame, the one of them a press would go
 * to, and the scroll bar under it or dragged, whose thumb shows another color.
 */
final class Hover {

  private boolean in;
  private float x;
  private float y;
  private int modifiers; // held, as the last pointer move or key reported them
  private List<El> under = List.of(); // the elements under the pointer in the current tree
  private El marked; // the one of them a press would go to, marked for the dump
  private El barArea; // the scroll area whose bar is under the pointer or dragged, in this tree
  private Hot hot; // the bar that the last frame shows hovered or dragged

  /** A bar that shows another color than at rest. */
  private record Hot(El area, boolean vertical, int color) {}

  /** The pointer moved to this point of the window. */
  void move(float x, float y) {
    move(x, y, 0);
  }

  /** The pointer moved to this point of the window with these modifiers held. */
  void move(float x, float y, int modifiers) {
    in = true;
    this.x = x;
    this.y = y;
    this.modifiers = modifiers;
  }

  /** The pointer left the window. */
  void leave() {
    in = false;
  }

  /** A key went down: what it says is held, with itself if it is a modifier. */
  void keyDown(Key key) {
    modifiers = key.modifiers() | modifierBit(key.name());
  }

  /** A key was released: what it says is still held. */
  void keyUp(Key key) {
    modifiers = key.modifiers(); // without the released key, also with its twin still held
  }

  /** Clears the held modifiers, as when the window loses the keyboard. */
  void clearModifiers() {
    modifiers = 0;
  }

  /** The modifiers held, a combination of {@link Key}'s modifier bits. */
  int modifiers() {
    return modifiers;
  }

  private static int modifierBit(String name) {
    return switch (name) {
      case "Shift" -> Key.SHIFT;
      case "Control" -> Key.CONTROL;
      case "Alt" -> Key.ALT;
      case "Meta" -> Key.META;
      default -> 0;
    };
  }

  /** Whether the pointer is in the window. */
  boolean in() {
    return in;
  }

  float x() {
    return x;
  }

  float y() {
    return y;
  }

  /** The topmost element under the pointer; see {@link App#hovered}. */
  El hovered() {
    return under.isEmpty() ? null : under.getLast();
  }

  /** The shape of the pointer; see {@link App#cursor}. */
  Cursor cursor() {
    if (barArea != null) {
      return Cursor.DEFAULT;
    }
    for (El el : under.reversed()) {
      if (el.cursor() != null) {
        return el.cursor();
      }
    }
    return Cursor.DEFAULT;
  }

  /**
   * Takes what is under the pointer in the tree of this frame, for the getters and the dump, and
   * gives the thumb under it or dragged its color.
   *
   * @param under the elements under the pointer, from the root down, as {@code App} finds them
   * @param grabbed the scroll area whose thumb the pointer drags, or null
   * @param vertical whether that is its vertical bar's
   * @param free whether the pointer holds no selection in a field and no drag, so that a bar
   *     under it counts as hovered
   * @return whether a thumb changed its color
   */
  boolean update(List<El> under, El grabbed, boolean vertical, boolean free) {
    this.under = under;
    if (marked != null) {
      marked.hovered(false);
    }
    marked = null;
    barArea = null;
    Hot now = null;
    El last = under.isEmpty() ? null : under.getLast();
    if (grabbed != null) {
      barArea = grabbed;
      now = new Hot(
          grabbed,
          vertical,
          grabbed.scrollbars() == null ? 0 : grabbed.scrollbars().thumbDragged());
    } else if (in && last != null && free) {
      Scrollbars.Bar bar = last.barAt(x, y);
      if (bar != null) {
        barArea = last;
        boolean onVertical = bar == last.verticalBar();
        if (App.onThumb(bar, onVertical, x, y)) {
          now = new Hot(last, onVertical, last.scrollbars().thumbHovered());
        }
      }
    }
    if (hot != null && !hot.equals(now) && hot.area().scrollbars() != null) {
      hot.area().barColor(hot.vertical(), hot.area().scrollbars().thumb());
    }
    if (now != null) {
      now.area().barColor(now.vertical(), now.color());
    }
    boolean changed = !Objects.equals(now, hot);
    hot = now;
    if (barArea != null) { // a press would go to its bar
      marked = barArea;
      barArea.hovered(true);
      return changed;
    }
    for (El el : under.reversed()) {
      if (App.takesPress(el) || el.takesEdits()) {
        marked = el;
        el.hovered(true);
        break;
      }
    }
    return changed;
  }
}
