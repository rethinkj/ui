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
  void anOutlineTellsAPressOnItsCurrentItemAndANavListDoesNot() throws IOException {
    List<NavList.Entry> entries = List.of(NavList.item("A"), NavList.item("B"));
    selected = -1;
    NavList.navList(entries, 1, i -> selected = i).children().get(1).onPress().press(1, 0, 0, 0);
    assertEquals(-1, selected, "a nav list tells only a change");
    NavList.outline(entries, 1, i -> selected = i).children().get(1).onPress().press(1, 0, 0, 0);
    assertEquals(1, selected, "an outline goes back to its current heading");

    List<Integer> presses = new ArrayList<>();
    List<String> outerKeys = new ArrayList<>();
    String[] text = {""};
    App<NavListTest> app = new App<>(
        this,
        s -> col()
            .wFull()
            .hFull()
            .onKey(k -> {
              if (k.name().equals("Enter") || k.name().equals("Space")) {
                outerKeys.add(k.name());
                return true;
              }
              return false;
            })
            .child(NavList.outline(
                    List.of(
                        NavList.item("A"),
                        NavList.item("B")
                            .trailing(TextField.textField(text[0], v -> text[0] = v)
                                .key("field")
                                .w(80))),
                    1,
                    presses::add)
                .key("outline")));
    app.textRendering(TextRendering.LINUX);

    // On the outline itself, Enter and Space tell its current item, also in the frame the focus
    // comes to it.
    Script.run("size 240 200\nframe\nfocus role=listbox\nkey Enter\nkey Space\nframe\n", app, out);
    assertEquals(List.of(1, 1), presses, "Enter and Space on the outline tell its current item");
    assertEquals(List.of(), outerKeys, "the outline takes them");

    // In a field inside an item they go past the outline to a form around it, also in the frame
    // the focus moves there from the list.
    presses.clear();
    Script.run("focus #field\nkey Enter\nkey Space\nframe\n", app, out);
    assertEquals(List.of(), presses, "keys in an item's field do not go to the outline");
    assertTrue(outerKeys.contains("Enter"), "Enter reaches the form around the outline");
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

  @Test
  void aRowMenuOpensOnThatRowWithoutSelectingIt() throws IOException {
    List<String> chosen = new ArrayList<>();
    selected = 0;
    App<NavListTest> app = new App<>(
        this,
        state -> col()
            .wFull()
            .hFull()
            .child(NavList.navList(
                    List.of(
                        NavList.item("General")
                            .contextMenu(
                                state.menu,
                                List.of(Menu.item("Open General", () -> chosen.add("general")))),
                        NavList.item("Plain").key("plain"),
                        NavList.item("Connections")
                            .contextMenu(
                                state.menu,
                                List.of(Menu.item("Open to side", () -> chosen.add("side")))),
                        NavList.item("Offline")
                            .key("offline")
                            .disabled(true)
                            .contextMenu(
                                state.menu,
                                List.of(Menu.item("Never", () -> chosen.add("offline"))))),
                    state.selected,
                    i -> state.selected = i)
                .key("list")
                .grow(1)));
    app.textRendering(TextRendering.LINUX);

    // Right-clicking an unselected row opens its menu on that row without changing selection.
    Script.run("size 240 220\nframe\npress #item-2 right\nframe\nadvance 1\nframe\n", app, out);
    String dump = app.tree().dump();
    assertEquals(0, selected, "right-click must not change selection");
    assertTrue(dump.contains("on #item-2 role=option label=\"Connections\""), dump);
    assertTrue(dump.contains("role=menuitem label=\"Open to side\""), dump);
    Script.run("press label=\"Open to side\"\nframe\n", app, out);
    assertEquals(List.of("side"), chosen);
    assertEquals(0, selected);

    // Rows without a menu, disabled rows and the room below the rows open no row's menu.
    Script.run(
        "press #plain right\nframe\npress #offline right\nframe\npress #list at bottom-2 right\nframe\n",
        app,
        out);
    assertFalse(menu.isOpen());

    // Shift+F10 with the list focused opens the selected row's menu.
    Script.run("focus #list\nframe\nkey F10 shift\nframe\nadvance 1\nframe\n", app, out);
    dump = app.tree().dump();
    assertTrue(dump.contains("on #list role=listbox"), dump);
    assertTrue(dump.contains("role=menuitem label=\"Open General\""), dump);
    Script.run("press label=\"Open General\"\nframe\n", app, out);
    assertEquals(List.of("side", "general"), chosen);
  }

  @Test
  void groupHeadersShowLeadingAndActionsWithoutToggling() throws IOException {
    boolean[] openApi = {true};
    boolean[] openDocs = {false};
    int[] toggles = {0, 0};
    int[] added = {0, 0};
    int[] selectedRow = {1};
    App<NavListTest> app = new App<>(this, state -> {
      Theme theme = Theme.CURRENT.get();
      Button.Options small = Button.options().size(Button.Size.SMALL);
      List<NavList.Entry> entries = new ArrayList<>();
      entries.add(NavList.group("api")
          .leading(Avatar.avatar("api", null, theme.size().controlSmall()))
          .count("2")
          .open(openApi[0], () -> {
            toggles[0]++;
            openApi[0] = !openApi[0];
          })
          .actions(Button.icon(
              Button.Variant.GHOST, Symbol.ADD, "New session in api", () -> added[0]++, small)));
      if (openApi[0]) {
        entries.add(NavList.item("Fix login redirect")
            .detail("feat/auth")
            .trailing(trailing(theme, "2m", Symbol.CHECKMARK_CIRCLE, Icon.Variant.SUCCESS)));
        entries.add(NavList.item("Rate limits")
            .detail("main")
            .trailing(trailing(theme, "7h", Symbol.CLOCK, Icon.Variant.MUTED)));
      }
      entries.add(NavList.group("docs")
          .leading(Avatar.avatar("docs", null, theme.size().controlSmall()))
          .count("3")
          .open(openDocs[0], () -> {
            toggles[1]++;
            openDocs[0] = !openDocs[0];
          })
          .actions(Button.icon(
              Button.Variant.GHOST, Symbol.ADD, "New session in docs", () -> added[1]++, small)));
      if (openDocs[0]) {
        entries.add(NavList.item("Getting started").detail("main"));
      }
      El list =
          NavList.navList(entries, selectedRow[0], i -> selectedRow[0] = i).key("groups");
      return col()
          .wFull()
          .hFull()
          .padding(theme.space().l())
          .fill(theme.surface().window())
          .child(Card.flush(Card.inset(list)).grow(1));
    });
    app.textRendering(TextRendering.LINUX);

    List<Path> files =
        Script.run("size 320 212 scale 2\nframe\nsnap groups\n", app, out.resolve("groups-dark2"));
    Golden.assertSnaps("nav-list-dark-2x-", files, "groups");

    // The tree dump shows the toggle button and the actions container as siblings, not nested.
    String dump = app.tree().dump();
    List<String> lines = dump.lines().toList();
    int toggleIndex = -1;
    int actionIndex = -1;
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).contains("role=button label=\"api\"")) {
        toggleIndex = i;
      } else if (lines.get(i).contains("role=button label=\"New session in api\"")) {
        actionIndex = i;
      }
    }
    assertTrue(toggleIndex >= 0 && actionIndex > toggleIndex, dump);
    assertEquals(
        indent(lines.get(toggleIndex)),
        indent(lines.get(actionIndex)),
        "the action must be a sibling of the toggle button:\n" + dump);
    assertTrue(StructureTest.line(dump, "label=\"api\"").contains(" expanded"), dump);
    assertTrue(StructureTest.line(dump, "label=\"docs\"").contains(" expanded=false"), dump);

    // Pressing the action runs its handler while leaving the group state untouched.
    Script.run("press label=\"New session in api\"\nframe\n", app, out);
    assertEquals(1, added[0]);
    assertEquals(0, toggles[0], "pressing an action must not toggle the group");
    assertTrue(openApi[0]);

    // Pressing on the leading avatar (inert) toggles the group; pressing again restores it.
    Script.run("press role=button label=\"docs\"\nframe\n", app, out);
    assertEquals(1, toggles[1]);
    assertTrue(openDocs[0]);
    Script.run("press role=button label=\"docs\"\nframe\n", app, out);
    assertEquals(2, toggles[1]);
    assertFalse(openDocs[0]);

    // Tab moves from the toggle button to its action, and Enter runs the action without toggling.
    Script.run("focus role=button label=\"api\"\nframe\nkey Tab\nframe\n", app, out);
    String afterTab = app.tree().dump();
    assertTrue(
        StructureTest.line(afterTab, "label=\"New session in api\"").contains(" focused"),
        afterTab);
    Script.run("key Enter\nframe\n", app, out);
    assertEquals(2, added[0]);
    assertEquals(0, toggles[0]);

    // A disabled list leaves the toggle and heading actions disabled and inert, and a plain
    // heading also supports leading and actions.
    int[] disabledToggles = {0};
    int[] disabledAdded = {0};
    App<NavListTest> disabled = new App<>(
        this,
        s -> col()
            .wFull()
            .hFull()
            .child(NavList.navList(
                    List.of(
                        NavList.group("api")
                            .leading(Avatar.avatar("api", null, 16))
                            .open(true, () -> disabledToggles[0]++)
                            .actions(Button.icon(
                                Button.Variant.GHOST,
                                Symbol.ADD,
                                "New in disabled",
                                () -> disabledAdded[0]++,
                                Button.options().size(Button.Size.SMALL))),
                        NavList.heading("Plain")
                            .leading(Icon.icon(Symbol.FOLDER, Icon.Variant.MUTED))
                            .open(true, () -> disabledToggles[0]++)
                            .actions(Button.icon(
                                Button.Variant.GHOST,
                                Symbol.ADD,
                                "Plain action",
                                () -> disabledAdded[0]++,
                                Button.options().size(Button.Size.SMALL)))),
                    -1,
                    null)
                .key("disabled-list")));
    disabled.textRendering(TextRendering.LINUX);
    Script.run("size 240 120 scale 2\nframe\n", disabled, out.resolve("disabled"));
    String disabledDump = disabled.tree().dump();
    assertTrue(
        StructureTest.line(disabledDump, "label=\"api\"").contains(" disabled"), disabledDump);
    assertTrue(
        StructureTest.line(disabledDump, "label=\"New in disabled\"").contains(" disabled"),
        disabledDump);
    assertTrue(
        StructureTest.line(disabledDump, "label=\"Plain action\"").contains(" disabled"),
        disabledDump);
    // A press where the disabled action shows and Tab do nothing; a script refuses to press an
    // inert element by name, so this presses its position.
    String[] box =
        StructureTest.line(disabledDump, "label=\"New in disabled\"").trim().split(" ");
    String[] xy = box[box.length - 3].split(",");
    String[] wh = box[box.length - 2].split("x");
    int x = Integer.parseInt(xy[0]) + Integer.parseInt(wh[0]) / 2;
    int y = Integer.parseInt(xy[1]) + Integer.parseInt(wh[1]) / 2;
    Script.run("press " + x + "," + y + "\nframe\nkey Tab\nframe\n", disabled, out);
    assertEquals(0, disabledAdded[0], "disabled list must leave heading actions inert");
    assertEquals(0, disabledToggles[0], "disabled list must leave heading toggle inert");
    assertFalse(disabled.tree().dump().contains(" focused"), disabled.tree().dump());
  }

  private static int indent(String line) {
    int i = 0;
    while (i < line.length() && line.charAt(i) == ' ') {
      i++;
    }
    return i;
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
