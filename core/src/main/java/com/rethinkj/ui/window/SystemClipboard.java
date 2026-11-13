package com.rethinkj.ui.window;

import com.rethinkj.ui.Clipboard;
import java.util.Objects;

/**
 * The system's clipboard through SDL, which {@link WindowLoop} gives its app. Used on the thread
 * of the windows while one is open.
 */
final class SystemClipboard implements Clipboard {

  @Override
  public String read() {
    return Sdl.getClipboardText();
  }

  @Override
  public void write(String text) {
    Objects.requireNonNull(text, "text");
    if (!Sdl.setClipboardText(text)) {
      throw new IllegalStateException("SDL_SetClipboardText failed: " + Sdl.getError());
    }
  }
}
