package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A navigation list with headings, a disabled item and a key of its own, a virtual list of many
 * items, and a session-like list with a second line, trailing elements, hover actions and
 * collapsible headings.
 */
class NavListTest {

  private static final List<NavList.Entry> PAGES = List.of(
      NavList.heading("Workbench"),
      NavList.item(Symbol.SETTINGS, "General"),
      NavList.item(Symbol.DATABASE, "Connections").count("3"),
      NavList.heading("Help"),
      NavList.item("Updates").disabled(true),
      NavList.item(Symbol.INFO, "About").key("about"));

  @TempDir
  Path out;

  int selected = 1;
  boolean openActive = true;
  boolean openRecent;
  int activeToggles;
  int recentToggles;
  int pinned;
  int renamed;
  final Menu.State menu = new Menu.State();

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  private App<NavListTest> app(boolean virtual) {
    App<NavListTest> app = new App<>(
        this,
        s -> col()
            .wFull()
            .hFull()
            .child(
                virtual
                    ? NavList.navList(
                            100_000,
                            i -> i % 10 == 0
                                ? NavList.heading("Group " + i / 10)
                                : NavList.item("Item " + i),
                            selected,
                            i -> selected = i)
                        .key("list")
                        .grow(1)
                    : NavList.navList(PAGES, selected, i -> selected = i).key("list")));
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  private static void run(App<?> app, String steps) throws IOException {
    Script.run("size 200 300\nframe\n" + steps + "\nframe", app, Path.of("target/nav-test"));
  }

  @Test
  void theKeysMovePastTheHeadingsAndDisabledItemsAndStopAtTheEnds() throws IOException {
    App<NavListTest> app = app(false);
    run(app, "focus #list\nkey ArrowDown\nframe\nkey ArrowDown");
    assertEquals(5, selected, "past the heading Help and the disabled Updates");
    run(app, "key ArrowDown");
    assertEquals(5, selected, "the last stays");
    run(app, "key Home");
    assertEquals(1, selected, "the first item, not the heading");
    run(app, "key ArrowUp\nframe\nkey End");
    assertEquals(5, selected);
    run(app, "press role=option label=\"Connections\"");
    assertEquals(2, selected);
    String dump = app.tree().dump();
    assertTrue(dump.contains("el #list role=listbox "), dump);
    assertTrue(
        StructureTest.line(dump, "label=\"Connections\"").contains(" selected pos=3/6"), dump);
    assertTrue(dump.contains("text \"3\""), "the count");
    assertTrue(StructureTest.line(dump, "label=\"Updates\"").contains(" disabled"), dump);
    assertTrue(StructureTest.line(dump, "label=\"About\"").contains("#about "), dump);
  }

  @Test
  void anOutlineTellsAPressOnItsCurrentItemAndANavListDoesNot() {
    List<NavList.Entry> entries = List.of(NavList.item("A"), NavList.item("B"));
    selected = -1;
    NavList.navList(entries, 1, i -> selected = i).children().get(1).onPress().press(1, 0, 0, 0);
    assertEquals(-1, selected, "a nav list tells only a change");
    NavList.outline(entries, 1, i -> selected = i).children().get(1).onPress().press(1, 0, 0, 0);
    assertEquals(1, selected, "an outline goes back to its current heading");
    selected = -1;
    NavList.outline(entries, 1, i -> selected = i).onKey().test(new Key("Enter"));
    assertEquals(1, selected, "and so does Enter");
  }

  @Test
  void aPageKeyGoesOnPastAPageOfDisabledItems() {
    List<NavList.Entry> entries = List.of(
        NavList.item("A"),
        NavList.item("B").disabled(true),
        NavList.item("C").disabled(true),
        NavList.item("D"));
    // Not laid out, so a page is one row.
    NavList.navList(entries, 0, i -> selected = i).onKey().test(new Key("PageDown"));
    assertEquals(3, selected);
    NavList.navList(entries, 3, i -> selected = i).onKey().test(new Key("PageUp"));
    assertEquals(0, selected);
  }

  @Test
  void aVirtualListBuildsOnlyTheItemsInViewAndFollowsTheSelection() throws IOException {
    App<NavListTest> app = app(true);
    run(app, "focus #list\nkey End");
    assertEquals(99_999, selected);
    run(app, "key PageUp\nframe\nkey ArrowDown");
    assertTrue(selected < 99_999 && selected % 10 != 0, "a page up, past headings: " + selected);
    String dump = app.tree().dump();
    assertTrue(StructureTest.line(dump, "#list").contains("/100000"), dump);
    assertTrue(dump.lines().filter(l -> l.contains("role=option")).count() < 30, dump);
    assertTrue(dump.contains("label=\"Item " + selected + "\""), "in view: " + dump);
  }

  @Test
  void sessionsShowDetailTrailingActionsAndCollapsibleHeadings() throws IOException {
    App<NavListTest> app = new App<>(this, state -> {
      Theme theme = Theme.CURRENT.get();
      Button.Options small = Button.options().size(Button.Size.SMALL);
      List<NavList.Entry> entries = new ArrayList<>();
      entries.add(NavList.heading("Active").count("2").open(state.openActive, () -> {
        state.activeToggles++;
        state.openActive = !state.openActive;
      }));
      if (state.openActive) {
        entries.add(NavList.item("Fix login redirect")
            .detail("api · feat/auth")
            .trailing(trailing(theme, "2m", Symbol.CHECKMARK_CIRCLE, Icon.Variant.SUCCESS))
            .actions(
                Button.icon(Button.Variant.GHOST, Symbol.PIN, "Pin login", () -> {}, small),
                Button.icon(
                    Button.Variant.GHOST, Symbol.MORE_HORIZONTAL, "More login", () -> {}, small)));
        entries.add(NavList.item("Add NavList second line and groups")
            .detail("ui · feat/navlist-sessions")
            .trailing(trailing(theme, "7h", Symbol.CLOCK, Icon.Variant.MUTED))
            .actions(
                Button.icon(
                    Button.Variant.GHOST, Symbol.PIN, "Pin navlist", () -> state.pinned++, small),
                Menu.dropdown(
                    Button.icon(
                        Button.Variant.GHOST,
                        Symbol.MORE_HORIZONTAL,
                        "More navlist",
                        state.menu::toggle,
                        small),
                    state.menu,
                    List.of(Menu.item("Rename", () -> state.renamed++)))));
      }
      entries.add(NavList.heading("Recent").count("4").open(state.openRecent, () -> {
        state.recentToggles++;
        state.openRecent = !state.openRecent;
      }));
      if (state.openRecent) {
        entries.add(NavList.item("Upgrade text shaper").detail("core · main"));
      }
      El list =
          NavList.navList(entries, state.selected, i -> state.selected = i).key("sessions");
      return col()
          .wFull()
          .hFull()
          .padding(theme.space().l())
          .fill(theme.surface().window())
          .child(Card.flush(Card.inset(list)).grow(1));
    });
    app.textRendering(TextRendering.LINUX);

    List<Path> files = Script.run(
        "size 320 212 scale 2\nframe\nmove #item-2\nframe\nadvance 1\nframe\nsnap sessions\n",
        app,
        out.resolve("dark2"));
    Golden.assertSnaps("nav-list-dark-2x-", files, "sessions");

    String dump = app.tree().dump();
    assertTrue(dump.contains("text \"api · feat/auth\""), dump);
    assertTrue(dump.contains("text \"7h\""), dump);
    assertTrue(StructureTest.line(dump, "label=\"Active\"").contains("role=button"), dump);
    assertTrue(StructureTest.line(dump, "label=\"Active\"").contains(" expanded"), dump);
    assertTrue(StructureTest.line(dump, "label=\"Recent\"").contains(" expanded=false"), dump);

    // Pressing an action on the unselected item runs its handler and keeps selection unchanged.
    Script.run("press label=\"Pin navlist\"\nframe\n", app, out);
    assertEquals(1, pinned);
    assertEquals(1, selected, "pressing an action must not select the item");
    Script.run("""
        press label="More navlist"
        frame
        advance 1
        frame
        move label="Rename"
        frame
        press label="Rename"
        frame
        """, app, out);
    assertEquals(1, renamed, "the menu of an action stays while the pointer is in it");
    assertEquals(1, selected);
    // Pointer and press arrive in one frame, before the actions show up under the pointer.
    Script.run(
        "leave\nframe\ndown #item-2 at right-12\nframe\nup #item-2 at right-12\nframe\n", app, out);
    assertEquals(2, selected, "the press began on the row");
    selected = 1;

    // Pressing a collapsible heading runs onToggle and opens the group.
    Script.run("press role=button label=\"Recent\"\nframe\n", app, out);
    assertEquals(1, recentToggles);
    assertTrue(openRecent);
    assertTrue(
        app.tree().dump().contains("label=\"Upgrade text shaper\""), app.tree().dump());

    // Enter while a collapsible heading is focused runs onToggle and closes the group.
    Script.run("focus role=button label=\"Active\"\nframe\nkey Enter\nframe\n", app, out);
    assertEquals(1, activeToggles);
    assertFalse(openActive);
    assertFalse(
        app.tree().dump().contains("label=\"Fix login redirect\""), app.tree().dump());

    // Virtual form uses the two-line row height when its first Item has a detail.
    App<NavListTest> virtual = new App<>(
        this,
        s -> col()
            .wFull()
            .hFull()
            .child(NavList.navList(
                    100,
                    i -> i == 0
                        ? NavList.heading("Active")
                        : NavList.item("Session " + i).detail("repo · branch"),
                    1,
                    i -> {})
                .key("virtual")
                .grow(1)));
    virtual.textRendering(TextRendering.LINUX);
    Script.run("size 240 200 scale 2\nframe\n", virtual, out.resolve("virtual"));
    String virtualDump = virtual.tree().dump();
    assertTrue(
        StructureTest.line(virtualDump, "label=\"Session 1\"").contains("x44 "), virtualDump);
  }

  private static El trailing(Theme theme, String time, Symbol symbol, Icon.Variant variant) {
    return row()
        .gap(theme.space().xs())
        .align(Style.Align.CENTER)
        .children(
            Label.label(Label.Variant.SMALL, time),
            Icon.icon(symbol, variant, theme.type().small().size()));
  }
}
