package com.rethinkj.ui.kit;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Measure;
import com.rethinkj.ui.Text;
import java.text.BreakIterator;
import java.util.Locale;

/** Text measurements shared by components, without deciding how their parts give way. */
final class TextSizing {

  private TextSizing() {}

  /** The width a text element takes on one line, in logical pixels at its current scale. */
  static float natural(El text) {
    return text.text() == null
        ? 0
        : text.text()
            .measure(Float.NaN, Float.NaN, Measure.MAX_CONTENT, Measure.MAX_CONTENT)
            .width();
  }

  /** The first grapheme and an ellipsis, or the text itself where it has no more than one. */
  static String least(String text) {
    if (text.length() <= 1) {
      return text;
    }
    int first = 1;
    // Ordinary ASCII cells need no iterator; CR LF is the one ASCII grapheme of two chars.
    if (text.charAt(0) > 0x7f
        || text.charAt(1) > 0x7f
        || text.charAt(0) == '\r' && text.charAt(1) == '\n') {
      BreakIterator chars = BreakIterator.getCharacterInstance(Locale.ROOT);
      chars.setText(text);
      first = chars.next();
    }
    return first < text.length() ? text.substring(0, first) + Text.ELLIPSIS : text;
  }
}
