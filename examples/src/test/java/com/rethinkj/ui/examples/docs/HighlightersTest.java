package com.rethinkj.ui.examples.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.rethinkj.ui.Spans;
import org.junit.jupiter.api.Test;

/** The highlighter of the code editor's page, line by line, without native libraries. */
class HighlightersTest {

  private final Highlighters.Ini ini = new Highlighters.Ini(0xFF000001, 0xFF000002, 0xFF000003);

  private String spans(String line, Object state) {
    Spans out = new Spans(line.length());
    ini.line(line, state, out);
    return out.toString();
  }

  @Test
  void sectionsKeysAndComments() {
    assertEquals("color 0-4 FF000001\n", spans("[db]", null));
    assertEquals("color 0-5 FF000002\n", spans("host = localhost", null));
    assertEquals("color 0-8 FF000003\n", spans("; a note", null));
  }

  @Test
  void aValueGoesOnAfterABackslash() {
    Spans out = new Spans(12);
    assertEquals(Boolean.TRUE, ini.line("path = /a:\\", null, out));
    assertEquals("", spans("  key=b \\", Boolean.TRUE)); // no key inside a value
    assertNull(ini.line("  /c", Boolean.TRUE, new Spans(4)));
  }
}
