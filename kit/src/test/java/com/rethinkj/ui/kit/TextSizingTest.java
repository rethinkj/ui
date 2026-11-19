package com.rethinkj.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Text;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TextSizingTest {

  @ParameterizedTest
  @ValueSource(strings = {"", "a", "😀", "e\u0301", "👩🏽‍💻", "🇩🇪", "\r\n"})
  void theMinimumKeepsAWholeFirstGraphemeAndAcceptsEmptyText(String grapheme) {
    assertEquals(grapheme, TextSizing.least(grapheme));
    if (!grapheme.isEmpty()) {
      assertEquals(grapheme + Text.ELLIPSIS, TextSizing.least(grapheme + "rest"));
    }
  }

  @Test
  void aBuildMeasuresTextAtTheWindowsScale() {
    TextLibraries.assumeLoaded();
    String words = "The quick brown fox jumps over the lazy dog, twice over and once more";
    float[] measured = {0};
    App<Object> app = new App<>(new Object(), state -> {
      measured[0] = TextSizing.natural(Label.label(Label.Variant.MUTED, words));
      return El.row().child(Label.label(Label.Variant.MUTED, words));
    });
    app.resize(800, 100, 1.25f);
    app.frame();
    El shown = app.tree().children().getFirst();
    assertEquals(1.25f, shown.text().scale());
    assertEquals(TextSizing.measured(shown), measured[0]);
  }
}
