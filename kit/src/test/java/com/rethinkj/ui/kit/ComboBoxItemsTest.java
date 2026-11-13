package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.Label.label;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.render.Pixels;
import com.rethinkj.ui.render.Png;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link ComboBox#items}: a chooser of database products whose choices have an image, an icon or
 * an element of their own before their text and a detail after it.
 */
class ComboBoxItemsTest {

  /** A logo for the test: a square of one colour with a lighter corner, at scale 2. */
  private static com.rethinkj.ui.Image logo(int argb) {
    int side = 32;
    byte[] rgba = new byte[side * side * 4];
    for (int i = 0; i < side * side; i++) {
      boolean corner = i % side < side / 2 && i / side < side / 2;
      rgba[4 * i] = (byte) (corner ? 0xFF : argb >> 16);
      rgba[4 * i + 1] = (byte) (corner ? 0xFF : argb >> 8);
      rgba[4 * i + 2] = (byte) (corner ? 0xFF : argb);
      rgba[4 * i + 3] = (byte) 0xFF;
    }
    return com.rethinkj.ui.Image.of(new Pixels(side, side, rgba), 2);
  }

  private static final com.rethinkj.ui.Image POSTGRES = logo(0xFF336791);
  private static final com.rethinkj.ui.Image MYSQL = logo(0xFFE48E00);

  @TempDir
  Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  static final class Form {
    final Theme t;
    final ComboBox.State state = new ComboBox.State();
    int chosen;
    boolean disabled;

    Form(Theme t) {
      this.t = t;
    }

    List<ComboBox.Item> items() {
      return List.of(
          ComboBox.item("PostgreSQL").image(POSTGRES).detail("9.6 or later"),
          ComboBox.item("MySQL").image(MYSQL).detail("8.0"),
          ComboBox.item("SQLite").icon(Symbol.DATABASE).detail("a file"),
          ComboBox.item("Custom driver")
              .leading(th -> Avatar.avatar(th, "Custom driver", null, th.size().icon())),
          ComboBox.item("Plain"));
    }

    // The same list on every build, as an app keeps it, so the combo box keeps its entries.
    final List<ComboBox.Item> items = items();

    El view() {
      return col()
          .wFull()
          .hFull()
          .padding(t.space().l())
          .gap(t.space().m())
          .fill(t.surface().window())
          .children(
              ComboBox.items(t, items, chosen, state, disabled ? null : i -> chosen = i)
                  .key("provider")
                  .w(240),
              label(t, Label.Variant.MUTED, items.get(chosen).text()).key("status"));
    }

    App<Form> app() {
      App<Form> app = new App<>(this, Form::view);
      app.focusRing(t.accent().edge());
      app.textRendering(TextRendering.LINUX);
      return app;
    }
  }

  private Form form;
  private App<Form> app;

  private String run(String steps) throws IOException {
    if (app == null) {
      form = new Form(Theme.DARK);
      app = form.app();
      steps = "size 300 260\nframe\n" + steps;
    }
    Script.run(steps + "\nframe\nadvance 0.25\nframe\nsnap s", app, out);
    return Files.readString(out.resolve("s.tree.txt"));
  }

  @Test
  void theBoxShowsTheChoiceWithWhatStandsBeforeItAndTheListEachItemsPartsAndTheCheck()
      throws IOException {
    String closed = run("");
    assertTrue(closed.contains("el #provider role=combobox label=\"PostgreSQL\""), closed);
    assertTrue(closed.contains("el #lead ") && closed.contains("image role=img"), closed);

    String open = run("press #provider");
    assertEquals(
        List.of("PostgreSQL", "MySQL", "SQLite", "Custom driver", "Plain"),
        ComboBoxTest.options(open));
    assertTrue(open.contains("\"9.6 or later\"") && open.contains("\"a file\""), open);
    assertEquals(
        3,
        open.lines().filter(l -> l.contains("image role=img")).count(),
        "the box's and two rows' images: " + open);
    assertTrue(open.contains("label=\"database\""), "SQLite's icon: " + open);
    assertTrue(open.contains("role=img label=\"Custom driver\""), "the avatar: " + open);
    assertEquals(
        1,
        open.lines().filter(l -> l.contains("label=\"checkmark\"")).count(),
        "one check mark, on the current choice: " + open);
  }

  @Test
  void typingFiltersByTheTextsAndEnterChooses() throws IOException {
    String typed = run("press #provider\nframe\ntype \"sq\"");
    assertEquals(List.of("SQLite", "MySQL", "PostgreSQL"), ComboBoxTest.options(typed));
    String chosen = run("key Enter");
    assertEquals(2, form.chosen);
    assertTrue(chosen.contains("el #provider role=combobox label=\"SQLite\""), chosen);
    assertFalse(chosen.contains("role=listbox"), chosen);
    assertTrue(chosen.contains("label=\"database\""), "the box shows SQLite's icon: " + chosen);
  }

  @Test
  void aDisabledOneTakesNothing() throws IOException {
    form = new Form(Theme.DARK);
    form.disabled = true;
    app = form.app();
    String tree = run("size 300 260\nframe\npress #provider");
    assertTrue(ComboBoxTest.line(tree, "#provider").contains(" disabled"), tree);
    assertFalse(tree.contains("role=listbox"), tree);
  }

  @ParameterizedTest(name = "{0} at {1}x")
  @CsvSource({"dark, 1", "dark, 2", "light, 1", "light, 2"})
  void theOpenListOfItems(String theme, int scale) throws IOException {
    App<Form> a = new Form(theme.equals("dark") ? Theme.DARK : Theme.LIGHT).app();
    Path folder = out.resolve(theme + scale);
    List<Path> files = Script.run(
        "size 300 260 scale " + scale
            + "\nframe\npress #provider\nframe\nadvance 0.25\nframe\nkey ArrowDown\nframe\nsnap open",
        a,
        folder);
    Golden.assertMatches(
        "combobox-items-" + theme + "-" + scale + "x-open",
        Png.decode(Files.readAllBytes(files.get(0))),
        Files.readString(files.get(1)));
  }
}
