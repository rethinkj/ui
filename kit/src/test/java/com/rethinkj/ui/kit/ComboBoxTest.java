package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.render.Png;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link ComboBox} beside a {@link Select}: a combo box of databases, one of host names with
 * suggestions, driven by scripts, with stored images of the open list in both themes at scales 1
 * and 2.
 */
class ComboBoxTest {

  static final List<String> DATABASES = List.of(
      "analytics",
      "billing",
      "customer_events",
      "inventory",
      "marketing",
      "orders_archive",
      "orders_total",
      "postgres",
      "reporting",
      "sessions",
      "shop",
      "staging_copy",
      "customer_notes",
      "warehouse");
  static final List<String> HOSTS =
      List.of("localhost", "db.staging.internal", "db-eu-1.example.com", "db-us-1.example.com");

  @TempDir
  Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  static final class Form {
    final Theme t;
    final Menu.State encodings = new Menu.State();
    final ComboBox.State database = new ComboBox.State();
    final ComboBox.State host = new ComboBox.State();
    int encoding;
    int chosen = 10;
    String hostName = "";
    boolean disabled;

    Form(Theme t) {
      this.t = t;
    }

    El view() {
      return col()
          .wFull()
          .hFull()
          .padding(t.space().l())
          .gap(t.space().m())
          .fill(t.surface().window())
          .children(
              row()
                  .gap(t.space().m())
                  .align(Style.Align.CENTER)
                  .children(
                      Select.select(t, Bench.ENCODINGS, encoding, encodings, i -> encoding = i)
                          .key("encoding")
                          .w(140),
                      ComboBox.comboBox(
                              t, DATABASES, chosen, database, disabled ? null : i -> chosen = i)
                          .key("database")
                          .w(180)),
              ComboBox.free(t, hostName, HOSTS, host, v -> hostName = v)
                  .key("host")
                  .w(240),
              label(
                      t,
                      Label.Variant.MUTED,
                      DATABASES.get(chosen) + " on " + (hostName.isEmpty() ? "no host" : hostName))
                  .key("status"));
    }

    App<Form> app() {
      App<Form> app = new App<>(this, Form::view);
      app.focusRing(t.accent().edge());
      app.textRendering(TextRendering.LINUX);
      return app;
    }
  }

  private App<Form> app;

  private String run(String steps) throws IOException {
    if (app == null) {
      app = new Form(Theme.DARK).app();
      steps = "size 360 260\nframe\n" + steps;
    }
    Script.run(steps + "\nframe\nadvance 0.25\nframe\nsnap s", app, out);
    return Files.readString(out.resolve("s.tree.txt"));
  }

  @Test
  void aPressOpensTheChoicesWithTheCurrentOneHighlightedAndTypingFilters() throws IOException {
    String tree = run("press #database");
    assertTrue(tree.contains("el #database role=combobox label=\"shop\""), tree);
    assertTrue(line(tree, "#database").endsWith(" expanded"), tree);
    assertTrue(tree.contains("role=textbox label=\"shop\"") && tree.contains("focused"), tree);
    assertTrue(tree.contains("el #option-10 role=option label=\"shop\""), tree);
    assertTrue(line(tree, "#option-10").contains(" selected"), "the current choice: " + tree);

    tree = run("type \"ot\"");
    List<String> options = options(tree);
    assertEquals(
        List.of("orders_total", "customer_notes", "postgres", "reporting", "customer_events"),
        options,
        tree);
    assertTrue(line(tree, "#option-0").contains(" selected"), "the best match: " + tree);

    run("key ArrowDown\nkey Enter");
    assertEquals(DATABASES.indexOf("customer_notes"), app.state().chosen);
    tree = run("");
    assertTrue(tree.contains("el #database role=combobox label=\"customer_notes\""), tree);
    assertTrue(line(tree, "#database").contains(" focused"), "the focus back on it: " + tree);
    assertFalse(tree.contains("role=listbox"), tree);
  }

  @Test
  void escapeLeavesTheChoiceAsItWasAndAPressChooses() throws IOException {
    String tree = run("focus #database\nkey W");
    assertTrue(tree.contains("\"w\""), "the letter typed opens and filters: " + tree);
    assertEquals("warehouse", options(tree).getFirst());
    tree = run("key Escape");
    assertEquals(10, app.state().chosen);
    assertFalse(tree.contains("role=listbox"), tree);
    assertTrue(line(tree, "#database").contains(" focused"), tree);

    run("key ArrowDown alt\nframe\ntype \"bil\"\nframe\nadvance 0.25\nframe\npress #option-0");
    assertEquals(DATABASES.indexOf("billing"), app.state().chosen);

    tree = run("press #database\nframe\ntype \"zzz\"");
    assertTrue(tree.contains("\"No results\""), tree);
    run("key Enter\nframe\nkey Tab");
    assertEquals(DATABASES.indexOf("billing"), app.state().chosen, "nothing chosen");
    tree = run("");
    assertFalse(tree.contains("role=listbox"), "Tab closed it: " + tree);
  }

  @Test
  void aFreeComboBoxTakesWhatIsTypedAndOffersWhatMatches() throws IOException {
    String tree = run("press #host\ntype \"db\"");
    assertEquals("db", app.state().hostName, "each edit is the value");
    assertEquals(
        List.of("db-eu-1.example.com", "db-us-1.example.com", "db.staging.internal"),
        options(tree).stream().sorted().toList());
    assertFalse(tree.contains(" selected"), "nothing highlighted: " + tree);
    run("key Enter");
    assertEquals("db", app.state().hostName, "Enter keeps what was typed");
    assertFalse(app.state().host.isOpen());

    tree = run("type \"-e\"\nframe\nkey ArrowDown\nframe\nkey Enter");
    assertEquals("db-eu-1.example.com", app.state().hostName);
    tree = run("type \"x\"");
    assertFalse(tree.contains("role=listbox"), "no suggestion, no list: " + tree);
    assertEquals("db-eu-1.example.comx", app.state().hostName);
  }

  /** Nothing is highlighted in a free one until a key does it, also when the view is rebuilt. */
  @Test
  void aFreeComboBoxHighlightsNothingAcrossRebuildsUntilArrowDown() throws IOException {
    run("press #host\ntype \"db\"");
    app.invalidate(); // another handler or posted task rebuilds the view
    String tree = run("");
    assertFalse(tree.contains(" selected"), "still nothing highlighted: " + tree);
    run("key Enter");
    assertEquals("db", app.state().hostName, "Enter keeps what was typed");

    run("key ArrowDown alt");
    app.invalidate();
    tree = run("");
    assertTrue(tree.contains("role=listbox"), tree);
    assertFalse(tree.contains(" selected"), "all of them, none highlighted: " + tree);
    tree = run("key ArrowDown");
    assertTrue(line(tree, "#option-0").contains(" selected"), "Arrow Down, the first: " + tree);
  }

  @Test
  void aDisabledComboBoxTakesNothing() throws IOException {
    String tree = run("");
    app.state().disabled = true;
    tree = run("press #database");
    assertTrue(line(tree, "#database").contains(" disabled"), tree);
    assertFalse(tree.contains("role=listbox"), tree);
  }

  /** Shift+Tab closes the list as Tab does, in each kind, and the focus moves back. */
  @Test
  void shiftTabClosesTheListAndMovesTheFocusBack() throws IOException {
    List<String> values = List.of("A", "B");
    List<ComboBox.Item> items = List.of(ComboBox.item("A"), ComboBox.item("B"));
    List<Function<ComboBox.State, El>> kinds = List.of(
        box -> ComboBox.comboBox(Theme.DARK, values, 0, box, i -> {}),
        box -> ComboBox.items(Theme.DARK, items, 0, box, i -> {}),
        box -> ComboBox.free(Theme.DARK, "", values, box, v -> {}));
    for (Function<ComboBox.State, El> kind : kinds) {
      ComboBox.State box = new ComboBox.State();
      App<Object> other = new App<>(
          new Object(),
          x -> col()
              .children(
                  Button.button(Theme.DARK, Button.Variant.SECONDARY, "Before", () -> {})
                      .key("before"),
                  kind.apply(box).key("box")));
      Script.run(
          "size 320 200\nframe\nfocus #box\nkey ArrowDown alt\nframe\nkey Tab shift\nframe",
          other,
          out);
      assertFalse(box.isOpen(), other.tree().dump());
      assertEquals("before", other.focused().key(), other.tree().dump());
    }
  }

  /** The app changes the choices while the list is open: the highlight goes with its entry. */
  @Test
  void theHighlightStaysOnItsEntryWhenTheChoicesChange() throws IOException {
    final class Choices {
      List<String> values = List.of("A", "B", "C");
      String value = "B";
      final ComboBox.State box = new ComboBox.State();
    }
    Choices c = new Choices();
    App<Choices> choices = new App<>(
        c,
        x -> col()
            .child(ComboBox.comboBox(Theme.DARK, x.values, x.value, v -> v, x.box, v -> {
                  x.value = v;
                })
                .key("box")));
    Script.run("size 320 200\nframe\npress #box\nframe", choices, out);
    c.values = List.of("B", "C");
    choices.invalidate();
    Script.run("frame\nkey Enter\nframe", choices, out);
    assertEquals("B", c.value, "the current choice, now in the first row");

    c.values = List.of("A", "B", "C");
    Script.run("press #box\nframe\nkey ArrowDown\nframe", choices, out);
    c.values = List.of("C", "B");
    choices.invalidate();
    Script.run("frame\nsnap s", choices, out);
    String tree = Files.readString(out.resolve("s.tree.txt"));
    assertTrue(line(tree, "#option-0").contains(" selected"), "C, where it went: " + tree);
    c.values = List.of("A", "B");
    choices.invalidate();
    Script.run("frame\nsnap s", choices, out);
    tree = Files.readString(out.resolve("s.tree.txt"));
    assertTrue(line(tree, "#option-1").contains(" selected"), "C gone: the current one " + tree);
    Script.run("key Enter\nframe", choices, out);
    assertEquals("B", c.value);
  }

  @ParameterizedTest(name = "{0} at {1}x")
  @CsvSource({"dark, 1", "dark, 2", "light, 1", "light, 2"})
  void theOpenListLooksLikeASelectsMenu(String theme, int scale) throws IOException {
    App<Form> form = new Form(theme.equals("dark") ? Theme.DARK : Theme.LIGHT).app();
    String script = Files.readString(Path.of("src/test/resources/scripts/combobox.txt"));
    Path folder = out.resolve(theme + scale);
    List<Path> files = Script.run("size 360 260 scale " + scale + "\n" + script, form, folder);
    for (int i = 0; i < files.size(); i += 2) {
      String snap = files.get(i).getFileName().toString().replace(".png", "");
      Golden.assertMatches(
          "combobox-" + theme + "-" + scale + "x-" + snap,
          Png.decode(Files.readAllBytes(files.get(i))),
          Files.readString(files.get(i + 1)));
    }
  }

  /** The labels of the options, in order. */
  static List<String> options(String tree) {
    return tree.lines()
        .filter(l -> l.contains("role=option label=\""))
        .map(l -> l.substring(l.indexOf("label=\"") + 7, l.indexOf('"', l.indexOf("label=\"") + 7)))
        .toList();
  }

  /** The line of the element with this key. */
  static String line(String tree, String key) {
    return tree.lines().filter(l -> l.contains(key + " ")).findFirst().orElse("");
  }
}
