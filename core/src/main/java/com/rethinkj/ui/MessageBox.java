package com.rethinkj.ui;

import java.util.List;
import java.util.Objects;

/**
 * A message box of the system: a title, a text and up to three buttons, as {@link
 * App#messageBox} shows it. It is for what has no window of the app to show it in, such as a
 * failure before the window could draw; inside a working window, the kit's {@code Dialog} asks.
 *
 * @param kind its icon
 * @param buttons the buttons' texts, in the order they stand
 * @param defaultButton the index of the button that Enter presses
 * @param cancelButton the index of the button that Escape presses, and the answer when the box
 *     is closed without a button
 */
public record MessageBox(
    Kind kind,
    String title,
    String text,
    List<String> buttons,
    int defaultButton,
    int cancelButton) {

  public enum Kind {
    INFORMATION,
    WARNING,
    ERROR
  }

  public MessageBox {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(text, "text");
    buttons = List.copyOf(buttons);
    if (buttons.isEmpty() || buttons.size() > 3) {
      throw new IllegalArgumentException("A message box has one to three buttons");
    }
    if (defaultButton < 0
        || defaultButton >= buttons.size()
        || cancelButton < 0
        || cancelButton >= buttons.size()) {
      throw new IllegalArgumentException("The default and cancel buttons are indexes of buttons");
    }
  }

  public static MessageBox information(String title, String text) {
    return new MessageBox(Kind.INFORMATION, title, text, List.of("OK"), 0, 0);
  }

  public static MessageBox warning(String title, String text) {
    return new MessageBox(Kind.WARNING, title, text, List.of("OK"), 0, 0);
  }

  public static MessageBox error(String title, String text) {
    return new MessageBox(Kind.ERROR, title, text, List.of("OK"), 0, 0);
  }

  /** This box with other buttons: Enter presses the first, Escape the last. */
  public MessageBox buttons(String... buttons) {
    return new MessageBox(kind, title, text, List.of(buttons), 0, buttons.length - 1);
  }

  public MessageBox defaultButton(int index) {
    return new MessageBox(kind, title, text, buttons, index, cancelButton);
  }

  public MessageBox cancelButton(int index) {
    return new MessageBox(kind, title, text, buttons, defaultButton, index);
  }

  /** The box as a script's errors name it. */
  @Override
  public String toString() {
    return "a message box titled \"" + title + "\" with the buttons "
        + String.join(", ", buttons.stream().map(b -> "\"" + b + "\"").toList());
  }
}
