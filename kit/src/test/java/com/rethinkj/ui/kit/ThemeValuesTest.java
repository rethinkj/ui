package com.rethinkj.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Every value of the default themes, and of the defaults the shorter constructors of their token
 * groups fill in, written out as text and compared with {@code src/test/resources/theme/values.txt}:
 * how a theme is written may change, its values may not. {@code -Dgolden.update=true} writes the
 * file anew after a deliberate change.
 */
class ThemeValuesTest {

  private static final Path STORED = Path.of("src/test/resources/theme/values.txt");

  @Test
  void theValuesOfTheDefaultThemesStayAsTheyWere() throws IOException {
    StringBuilder out = new StringBuilder();
    write(out, "DARK", Theme.DARK);
    write(out, "LIGHT", Theme.LIGHT);
    write(out, "Code.DARK", Theme.Code.DARK);
    write(out, "Code.LIGHT", Theme.Code.LIGHT);
    write(out, "Code(7) dark", new Theme.Code(1, 2, 3, 4, 5, 0x04FFFFFF, 7));
    write(out, "Code(7) light", new Theme.Code(1, 2, 3, 4, 5, 0x10000000, 7));
    write(out, "Badges.DARK", Theme.Badges.DARK);
    write(out, "Badges.LIGHT", Theme.Badges.LIGHT);
    write(out, "Prose.DARK", Theme.Prose.DARK);
    write(out, "Prose.LIGHT", Theme.Prose.LIGHT);
    for (Theme t : List.of(Theme.DARK, Theme.LIGHT)) {
      String name = t == Theme.DARK ? "dark" : "light";
      Theme.Borders b = t.border();
      write(out, "Borders(3) " + name, new Theme.Borders(b.subtle(), b.strong(), b.control()));
      Theme.Accent a = t.accent();
      write(out, "Accent(3) " + name, new Theme.Accent(a.color(), a.hover(), a.pressed()));
      Theme.Grid g = t.grid();
      write(
          out,
          "Grid(12) " + name,
          new Theme.Grid(
              g.rowHeight(),
              g.minColumn(),
              g.maxColumn(),
              g.handle(),
              g.mark(),
              g.header(),
              g.row(),
              g.stripe(),
              g.selected(),
              g.selectedInactive(),
              g.cursor(),
              g.cursorWidth()));
      Theme.Surfaces s = t.surface();
      write(
          out,
          "Theme(13) " + name,
          new Theme(
              s,
              t.text(),
              b,
              a,
              t.status(),
              t.space(),
              t.radius(),
              t.type(),
              t.size(),
              t.motion(),
              g,
              t.scrollbars(),
              t.elevation()));
    }
    write(out, "Caption(2)", new Theme.Caption(0xFFC42B1C, 0xFFFFFFFF));
    String text = out.toString();
    if (Boolean.getBoolean("golden.update")) {
      Files.createDirectories(STORED.getParent());
      Files.writeString(STORED, text);
      return;
    }
    assertEquals(Files.readString(STORED), text);
  }

  private static void write(StringBuilder out, String path, Object value) {
    switch (value) {
      case Integer i ->
        out.append(path).append(" = ").append(String.format("%08X", i)).append('\n');
      case Float f -> out.append(path).append(" = ").append(f).append('\n');
      case Double d -> out.append(path).append(" = ").append(d).append('\n');
      case Supplier<?> s -> {}
      case List<?> list -> {
        for (int i = 0; i < list.size(); i++) {
          write(out, path + "[" + i + "]", list.get(i));
        }
      }
      case Record r -> {
        for (RecordComponent c : r.getClass().getRecordComponents()) {
          try {
            write(out, path + "." + c.getName(), c.getAccessor().invoke(r));
          } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
          }
        }
      }
      default -> out.append(path).append(" = ").append(value).append('\n');
    }
  }
}
