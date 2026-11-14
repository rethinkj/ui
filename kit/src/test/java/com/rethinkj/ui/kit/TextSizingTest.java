package com.rethinkj.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rethinkj.ui.Text;
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
}
