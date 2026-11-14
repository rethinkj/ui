package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A list with the theme's scrollbars, {@code .scrollbars(theme.scrollbars())}: stored images of its
 * thumb at rest, under the pointer and dragged,.
 */
class ScrollbarsTest {

  private static final String[] TABLES = {
    "accounts",
    "addresses",
    "audit_log",
    "carts",
    "categories",
    "customers",
    "deliveries",
    "discounts",
    "employees",
    "inventory"
  };

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  /**
   * Forty rows of tables that a press selects, in a framed list of 216 by 176 at 12,12 in a window
   * of 240 by 200.
   */
  static El view(Theme t, int[] selected) {
    El list = col()
        .key("tables")
        .role("list")
        .label("Tables")
        .grow(1)
        .padding(t.space().s())
        .radius(t.radius().large())
        .fill(t.surface().panel())
        .border(t.size().stroke(), t.border().subtle())
        .rows(40, t.size().control(), i -> {
          int fill = i == selected[0] ? t.grid().selected() : t.surface().panel();
          El r = row()
              .key("table-" + i)
              .role("listitem")
              .label(TABLES[i % TABLES.length])
              .padding(0, t.space().m())
              .align(Style.Align.CENTER)
              .radius(t.radius().medium())
              .fill(fill)
              .onPress(() -> selected[0] = i)
              .child(Label.label(t, TABLES[i % TABLES.length] + "_" + i));
          return r.whenHovered(e -> e.fill(Theme.over(fill, t.surface().hover())));
        })
        .scrollbars(t.scrollbars());
    return col()
        .wFull()
        .hFull()
        .padding(t.space().l())
        .fill(t.surface().window())
        .child(list);
  }

  @ParameterizedTest(name = "{0} at {1}x")
  @CsvSource({"dark, 2"})
  void looksAsStored(String name, int scale) throws IOException {
    Theme t = name.equals("dark") ? Theme.DARK : Theme.LIGHT;
    int[] selected = {1};
    App<int[]> app = new App<>(selected, s -> view(t, s));
    app.textRendering(TextRendering.LINUX);
    String script = Files.readString(Path.of("src/test/resources/scripts/scrollbars.txt"));
    Path folder = Path.of("target/scrollbars", name + "-" + scale + "x");
    List<Path> files = Script.run("size 240 200 scale " + scale + "\n" + script, app, folder);
    assertEquals(6, files.size());
    Golden.assertSnaps("scrollbars-" + name + "-" + scale + "x-", files);
    String rest = Files.readString(folder.resolve("rest.tree.txt"));
    assertTrue(rest.contains(" rows 0-10/40 thumb-y 219,15 6x30.85\n"), rest);
    String dragged = Files.readString(folder.resolve("dragged.tree.txt"));
    assertTrue(dragged.contains(" scroll 0,399.41 thumb-y 219,85 6x30.85 hovered\n"), dragged);
    assertEquals(1, selected[0], "a press on the bar selects no row");
  }
}
