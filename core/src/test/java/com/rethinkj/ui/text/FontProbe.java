package com.rethinkj.ui.text;

import java.io.IOException;

/** What tests in other packages may ask a {@link Font} about its cached runs. */
public final class FontProbe {

  private FontProbe() {}

  /** Inter as a font of its own, with a cache that nothing else fills; the caller closes it. */
  public static Font inter() throws IOException {
    return Font.of(FontTest.defaultFontBytes("Inter-Regular.ttf"));
  }

  /** Whether the text of a run the font keeps, at any size, contains {@code part}. */
  public static boolean shaped(Font font, String part) {
    return font.shaped(part);
  }
}
