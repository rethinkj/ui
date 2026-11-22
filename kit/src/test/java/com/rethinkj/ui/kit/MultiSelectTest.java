package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.Label.label;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link MultiSelect}: choosing several values as removable badges with fuzzy filtering, removing
 * by Backspace and by each badge's cross, keyboard navigation, disabled and {@link Prop} states,
 * and a stored image of the open list with chosen badges.
 */
class MultiSelectTest {

  static final List<String> SCHEMAS = List.of(
      "accounting",
      "analytics",
      "billing",
      "inventory",
      "marketing",
      "public",
      "reporting",
      "sales",
      "staging",
      "warehouse");

  @TempDir
  Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  static final class View {
    final Theme t;
    final MultiSelect.State schemas = new MultiSelect.State();
    List<String> selected = List.of("public", "sales");
    boolean disabled;

    View(Theme t) {
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
              MultiSelect.multiSelect(
                      SCHEMAS, selected, schemas, disabled ? null : v -> selected = v)
                  .key("schemas")
                  .label("Schemas")
                  .w(220),
              label(Label.Variant.MUTED, selected.isEmpty() ? "none" : String.join(" · ", selected))
                  .key("status"));
    }

    App<View> app() {
      App<View> app = new App<>(this, View::view).provide(Theme.CURRENT, form -> form.t);
      app.focusRing(t.accent().edge());
      app.textRendering(TextRendering.LINUX);
      return app;
    }
  }

  private App<View> app;

  private String run(String steps) throws IOException {
    if (app == null) {
      app = new View(Theme.DARK).app();
      steps = "size 320 280 scale 2\nframe\n" + steps;
    }
    Script.run(steps + "\nframe\nadvance 0.25\nframe\nsnap s", app, out);
    return Files.readString(out.resolve("s.tree.txt"));
  }

  @Test
  void filtersAddsByEnterAndPressAndRemovesByBackspaceAndCross() throws IOException {
    String tree = run("press #input");
    assertTrue(
        tree.contains("el #schemas role=combobox label=\"Schemas\""),
        "frame role and label: " + tree);
    assertTrue(
        line(tree, "#schemas").contains(" expanded ")
            && line(tree, "#schemas").contains("value=\"public, sales\""),
        tree);
    assertTrue(
        tree.contains("text #input role=textbox") && line(tree, "#input").contains(" focused"),
        tree);
    List<String> options = ComboBoxTest.options(tree);
    assertFalse(options.contains("public"), "already chosen: " + options);
    assertFalse(options.contains("sales"), "already chosen: " + options);
    assertEquals("accounting", options.getFirst());

    // Entries stay cached while choices and selected do not change.
    List<FilterList.Entry> cached = app.state().schemas.entries;
    tree = run("type \"ing\"");
    assertSame(cached, app.state().schemas.entries, "entries cached while filtering");
    assertEquals("ing", app.state().schemas.text());
    options = ComboBoxTest.options(tree);
    assertEquals(
        List.of("billing", "staging", "marketing", "reporting", "accounting"), options, tree);
    assertTrue(line(tree, "#option-0").contains(" selected"), "best match highlighted: " + tree);

    // ArrowDown and Enter add the entry, clear the typed text, and keep the list open.
    tree = run("key ArrowDown\nkey Enter");
    assertEquals(List.of("public", "sales", "staging"), app.state().selected);
    assertTrue(app.state().schemas.isOpen(), "list stays open after Enter");
    assertEquals("", app.state().schemas.text(), "typed text clears after Enter");
    assertTrue(line(tree, "#schemas").contains("value=\"public, sales, staging\""), tree);
    assertFalse(ComboBoxTest.options(tree).contains("staging"), "newly chosen excluded: " + tree);

    // Pressing an option adds it too and keeps the list open.
    tree = run("type \"war\"\nframe\npress #option-0");
    assertEquals(List.of("public", "sales", "staging", "warehouse"), app.state().selected);
    assertTrue(app.state().schemas.isOpen(), "list stays open after pressing an option");
    assertEquals("", app.state().schemas.text(), "typed text clears after press");
    assertFalse(
        line(tree, "#schemas").contains("220x24 "),
        "frame grows in height as badges wrap: " + line(tree, "#schemas"));

    // Backspace edits typed text without removing a badge; on empty text it removes the last badge.
    run("type \"b\"\nframe\nkey Backspace");
    assertEquals(
        List.of("public", "sales", "staging", "warehouse"),
        app.state().selected,
        "Backspace on non-empty text does not remove a badge");
    assertEquals("", app.state().schemas.text());
    tree = run("key Backspace");
    assertEquals(List.of("public", "sales", "staging"), app.state().selected);
    assertTrue(app.state().schemas.isOpen(), "list stays open after Backspace");
    assertTrue(
        ComboBoxTest.options(tree).contains("warehouse"),
        "removed value returns to the open list: " + tree);

    // A badge's cross removes that specific badge without closing the list.
    tree = run("press role=button label=\"Remove sales\"");
    assertEquals(List.of("public", "staging"), app.state().selected);
    assertTrue(app.state().schemas.isOpen(), "list stays open after pressing cross");
    assertTrue(
        ComboBoxTest.options(tree).contains("sales"), "removed badge returns to list: " + tree);

    // Empty filter results show "No results", and Enter does nothing.
    tree = run("type \"zzz\"");
    assertTrue(tree.contains("\"No results\""), tree);
    run("key Enter");
    assertEquals(List.of("public", "staging"), app.state().selected);

    // Escape closes and clears typed text, keeping focus in the field.
    tree = run("key Escape");
    assertFalse(app.state().schemas.isOpen());
    assertEquals("", app.state().schemas.text());
    assertFalse(tree.contains("role=listbox"), tree);
    assertTrue(line(tree, "#schemas").contains(" expanded=false"), tree);
    assertTrue(line(tree, "#input").contains(" focused"), "focus stays in the field: " + tree);

    // Typing while closed opens and filters; ArrowDown while closed opens too; press outside
    // closes.
    tree = run("type \"an\"");
    assertTrue(app.state().schemas.isOpen(), "typing while closed opens: " + tree);
    assertEquals("an", app.state().schemas.text());
    assertEquals("analytics", ComboBoxTest.options(tree).getFirst());
    run("press 300,260");
    assertFalse(app.state().schemas.isOpen(), "press outside closes");

    tree = run("focus #input\nkey ArrowDown");
    assertTrue(app.state().schemas.isOpen(), "ArrowDown while closed opens");
    run("key ArrowDown alt");
    assertFalse(app.state().schemas.isOpen(), "Alt+ArrowDown closes when open");
    run("key ArrowDown alt");
    assertTrue(app.state().schemas.isOpen(), "Alt+ArrowDown opens when closed");
  }

  @Test
  void disabledTabAndPropOverload() throws IOException {
    // Tab and Shift+Tab close the open list and move focus.
    MultiSelect.State ms = new MultiSelect.State();
    App<Object> tabs = new App<>(
        new Object(),
        x -> col()
            .children(
                Button.button(Button.Variant.SECONDARY, "Before", () -> {}).key("before"),
                MultiSelect.multiSelect(List.of("a", "b"), List.of(), ms, v -> {})
                    .key("ms"),
                Button.button(Button.Variant.SECONDARY, "After", () -> {}).key("after")));
    tabs.textRendering(TextRendering.LINUX);
    Script.run("size 320 200 scale 2\nframe\npress #ms\nframe\nkey Tab\nframe", tabs, out);
    assertFalse(ms.isOpen(), "Tab closes the list");
    assertEquals("after", tabs.focused().key(), tabs.tree().dump());
    Script.run("press #ms\nframe\nkey Tab shift\nframe", tabs, out);
    assertFalse(ms.isOpen(), "Shift+Tab closes the list");
    assertEquals("before", tabs.focused().key(), tabs.tree().dump());

    // Disabled state takes no input and closes if previously open.
    View form = new View(Theme.DARK);
    form.schemas.open();
    form.disabled = true;
    App<View> off = form.app();
    Script.run("size 320 200 scale 2\nframe\npress #schemas\nframe", off, out);
    String dump = off.tree().dump();
    assertFalse(form.schemas.isOpen());
    assertTrue(line(dump, "#schemas").contains(" disabled"), dump);
    assertFalse(dump.contains("role=listbox"), dump);
    assertFalse(
        dump.contains("role=button label=\"Remove"), "no remove cross when disabled: " + dump);

    // Prop overload writes through Editing, shows its error, and disables when read-only.
    record Group(List<String> roles) {
      static final Part<Group, List<String>> ROLES = Part.of(Group::roles, (g, v) -> new Group(v));
    }
    Editing<Group> editing = Editing.of(new Group(List.of()))
        .rule(Group.ROLES, g -> !g.roles().isEmpty(), "Choose at least one role");
    MultiSelect.State propState = new MultiSelect.State();
    App<Object> bound = new App<>(
        new Object(),
        x -> Form.form(
            Form.Labels.BESIDE,
            Form.field(
                "Roles",
                MultiSelect.multiSelect(
                        List.of("admin", "editor"), editing.at(Group.ROLES), propState)
                    .key("roles"))));
    bound.textRendering(TextRendering.LINUX);
    Script.run("size 360 200 scale 2\nframe", bound, out);
    String invalidDump = bound.tree().dump();
    assertTrue(line(invalidDump, "#roles").contains("invalid"), invalidDump);
    assertTrue(
        line(invalidDump, "#roles").contains("error=\"Choose at least one role\""), invalidDump);

    Script.run("press #roles\nframe\nkey Enter\nframe", bound, out);
    assertEquals(List.of("admin"), editing.value().roles());
    assertTrue(editing.valid());

    // Read-only Prop disables the multi-select.
    Prop<List<String>> readOnly = Prop.of(() -> List.of("admin"), null);
    App<Object> ro = new App<>(
        new Object(),
        x -> MultiSelect.multiSelect(List.of("admin", "editor"), readOnly, new MultiSelect.State())
            .key("ro"));
    ro.textRendering(TextRendering.LINUX);
    Script.run("size 320 120 scale 2\nframe", ro, out);
    assertTrue(line(ro.tree().dump(), "#ro").contains(" disabled"), ro.tree().dump());
  }

  @Test
  void longValueKeepsItsCrossInsideTheFrame() throws IOException {
    View view = new View(Theme.DARK);
    view.selected = List.of("customer_transaction_reconciliation_events");
    App<View> long_ = view.app();
    Script.run("size 320 120 scale 2\nframe\npress role=button\nframe", long_, out);
    assertEquals(List.of(), view.selected, long_.tree().dump());
  }

  @Test
  void openListWithChosenBadgesMatchesStoredImage() throws IOException {
    App<View> form = new View(Theme.DARK).app();
    String script = Files.readString(Path.of("src/test/resources/scripts/multiselect.txt"));
    List<Path> files = Script.run(script, form, out.resolve("dark2"));
    Golden.assertSnaps("multiselect-dark-2x-", files, "open");
  }

  private static String line(String tree, String key) {
    return ComboBoxTest.line(tree, key);
  }
}
