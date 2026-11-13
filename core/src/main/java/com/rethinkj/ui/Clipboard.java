package com.rethinkj.ui;

import java.util.Objects;

/**
 * Where cut and copied text goes and where paste takes it from. An {@link App} starts with one of
 * its own in memory ({@link #inMemory}), which scripts and tests use; {@link
 * com.rethinkj.ui.window.WindowLoop} gives the app the system's clipboard instead.
 */
public interface Clipboard {

  /** The text on the clipboard, or "" when it holds none. */
  String read();

  /** Puts text on the clipboard, replacing what was there. */
  void write(String text);

  /** A clipboard of its own, empty at first, that only this process sees. */
  static Clipboard inMemory() {
    return new Clipboard() {
      private String text = "";

      @Override
      public String read() {
        return text;
      }

      @Override
      public void write(String text) {
        this.text = Objects.requireNonNull(text, "text");
      }
    };
  }
}
