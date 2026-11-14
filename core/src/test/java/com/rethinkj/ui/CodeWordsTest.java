package com.rethinkj.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Code-word classification and UTF-16 ranges, without text or native libraries. */
class CodeWordsTest {

  @Test
  void lettersDecimalDigitsUnderscoreAndAllThreeKindsOfMarkBelongToCodeWords() {
    for (int c : new int[] {'a', 'é', '_', '1', 0x0661, 0x1D400, 0x0301, 0x0903, 0x20DD}) {
      assertTrue(CodeWords.isWordChar(c), () -> Integer.toHexString(c));
    }
    for (int c : new int[] {'.', ' ', '\n', 0x200D, 0x1F600, 0x2163, 0x00B2}) {
      assertFalse(CodeWords.isWordChar(c), () -> Integer.toHexString(c));
    }
  }

  @Test
  void scansTheAdjacentRunWithoutSkippingPunctuationOrSpaces() {
    String text = "SELECT a._x1";
    assertEquals(7, CodeWords.start(text, 8));
    assertEquals(9, CodeWords.start(text, 9), "after a point: no word yet");
    assertEquals(9, CodeWords.start(text, text.length()));
    assertEquals(7, CodeWords.start(text, 7), "does not skip the space");
    assertEquals(6, CodeWords.end(text, 2));
    assertEquals(6, CodeWords.end(text, 6), "does not skip the space");
    assertEquals(8, CodeWords.end(text, 8), "does not skip the point");
    assertEquals(text.length(), CodeWords.end(text, 10));
    assertEquals(text.length(), CodeWords.end(text, text.length()));
    assertEquals(0, CodeWords.start(text, 0));
    assertEquals(0, CodeWords.start("", 0));
    assertEquals(0, CodeWords.end("", 0));
    assertThrows(IndexOutOfBoundsException.class, () -> CodeWords.start(text, -1));
    assertThrows(IndexOutOfBoundsException.class, () -> CodeWords.end(text, text.length() + 1));
  }

  @Test
  void scansCodePointsIncludingSupplementaryLettersAndStandaloneMarks() {
    String text = ".a\uD835\uDC00e\u0301_\u0661.";
    assertEquals(1, CodeWords.start(text, 8));
    assertEquals(8, CodeWords.end(text, 1));
    assertEquals(1, CodeWords.start(text, 4), "after the supplementary letter");
    assertEquals(8, CodeWords.end(text, 2), "before the supplementary letter");
    assertEquals(1, CodeWords.start(".\u0301a", 3), "a mark counts even after punctuation");
    assertEquals(3, CodeWords.end(".\u0301a", 1));
    assertEquals(1, CodeWords.start("❤️foo", 5), "code-point scans do not attach marks to emoji");
    assertEquals(2, CodeWords.start("a\u200Db", 3), "a joiner ends the run");
    assertEquals(1, CodeWords.end("a\u200Db", 0));
  }
}
