package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Headless;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link ComboBox} beside a {@link Select}: a combo box of databases, one of host names with
 * suggestions, driven by scripts, with stored images of the open list in both themes.
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
  void closedChoicesShortenLongLabelsAndKeepTheChevronInside() {
    Theme t = Theme.DARK;
    String text = "A long database name that does not fit in the closed choice";
    List<String> choices = List.of(text);
    List<ComboBox.Item> items = List.of(ComboBox.item(text).icon(Symbol.DATABASE));
    for (boolean enabled : new boolean[] {true, false}) {
      IntConsumer choose = enabled ? i -> {} : null;
      El root = col()
          .gap(t.space().s())
          .children(
              Select.select(t, choices, 0, new Menu.State(), choose).w(140),
              ComboBox.comboBox(t, choices, 0, new ComboBox.State(), choose).w(140),
              ComboBox.items(t, items, 0, new ComboBox.State(), choose).w(140));
      Headless.render(root, 180, 160, 1, TextRendering.LINUX);
      for (El box : root.children()) {
        List<El> parts = box.children();
        El label = parts.get(parts.size() - 2);
        El chevron = parts.getLast();
        assertEquals(text, box.label(), "the control keeps the full choice as its label");
        assertEquals(text, label.text().string());
        assertTrue(label.text().shortened(), "the visible label ends in an ellipsis");
        assertTrue(label.width() > 0, "there is room for the label");
        assertTrue(label.x() + label.width() + t.space().s() <= chevron.x() + 0.01);
        assertTrue(chevron.width() > 0, "there is room for the chevron");
        assertTrue(chevron.x() + chevron.width() <= box.width() - t.space().m());
        if (parts.size() == 3) {
          El lead = parts.getFirst();
          assertEquals(t.size().icon(), lead.width());
          assertTrue(lead.x() + lead.width() + t.space().s() <= label.x() + 0.01);
        }
      }
    }
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

  @ParameterizedTest(name = "{0} at {1}x")
  @CsvSource({"dark, 2", "light, 2"})
  void theOpenListLooksLikeASelectsMenu(String theme, int scale) throws IOException {
    App<Form> form = new Form(theme.equals("dark") ? Theme.DARK : Theme.LIGHT).app();
    String script = Files.readString(Path.of("src/test/resources/scripts/combobox.txt"));
    Path folder = out.resolve(theme + scale);
    List<Path> files = Script.run("size 360 260 scale " + scale + "\n" + script, form, folder);
    Golden.assertSnaps("combobox-" + theme + "-" + scale + "x-", files, "typed");
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
