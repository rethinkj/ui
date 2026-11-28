package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link FilterTree}: filtering, manual open-state restore, keyboard navigation and submenus. */
class FilterTreeTest {

  @TempDir
  Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  private static final class Switcher {
    final Popover.State popover = new Popover.State();
    final FilterTree.State tree = new FilterTree.State().open("local");
    final List<String> log = new ArrayList<>();
    int rootEnters;

    List<Menu.Item> branchMenu(String branch) {
      return List.of(
          Menu.item("Checkout", () -> {
            log.add("checkout:" + branch);
            popover.close();
          }),
          Menu.item("Rename…", () -> log.add("rename:" + branch)),
          Menu.separator(),
          Menu.item("Delete", () -> log.add("delete:" + branch)).danger());
    }

    List<FilterTree.Row> rows() {
      return List.of(
          FilterTree.action("Update project", () -> log.add("update"))
              .icon(Symbol.ARROW_SYNC)
              .shortcut("Ctrl+T")
              .key("update"),
          FilterTree.action("Commit…", () -> log.add("commit"))
              .icon(Symbol.CHECKMARK)
              .key("commit"),
          FilterTree.separator(),
          FilterTree.action("New branch…", () -> log.add("new-branch"))
              .icon(Symbol.ADD)
              .key("new"),
          FilterTree.branch(
                  "Local",
                  List.of(
                      FilterTree.action("main", () -> log.add("choose:main"))
                          .icon(Symbol.BRANCH)
                          .meta(() -> Badge.badge(Badge.Kind.ACCENT, "current"))
                          .submenu(branchMenu("main"))
                          .key("main"),
                      FilterTree.branch(
                              "feat/",
                              List.of(
                                  FilterTree.action("auth", null)
                                      .icon(Symbol.BRANCH)
                                      .detail("2 commits")
                                      .submenu(branchMenu("feat/auth"))
                                      .key("auth"),
                                  FilterTree.action("search", () -> log.add("choose:search"))
                                      .icon(Symbol.BRANCH)
                                      .submenu(branchMenu("feat/search"))
                                      .key("search")))
                          .key("feat")))
              .key("local"),
          FilterTree.branch(
                  "Remote",
                  List.of(FilterTree.action("origin/main", () -> log.add("choose:origin/main"))
                      .icon(Symbol.BRANCH)
                      .submenu(branchMenu("origin/main"))
                      .key("origin-main")))
              .key("remote"));
    }

    List<Menu.Item> fieldActions() {
      return List.of(
          Menu.item("Collapse all", tree::collapseAll).icon(Symbol.CHEVRON_DOUBLE_UP),
          Menu.item("Settings", () -> log.add("settings")).icon(Symbol.SETTINGS));
    }

    El embeddedView() {
      Theme theme = Theme.CURRENT.get();
      return col()
          .wFull()
          .hFull()
          .fill(theme.surface().window())
          .padding(theme.space().l())
          .onKey(k -> k.name().equals("Enter") && ++rootEnters > 0)
          .child(FilterTree.filterTree(tree, "Search branches", rows(), fieldActions())
              .w(300));
    }

    El popoverView() {
      Theme theme = Theme.CURRENT.get();
      return col()
          .wFull()
          .hFull()
          .fill(theme.surface().window())
          .padding(theme.space().l())
          .align(Style.Align.START)
          .child(Popover.popover(
              Button.button(Button.Variant.SECONDARY, Symbol.BRANCH, "main", popover::toggle)
                  .key("trigger"),
              popover,
              "Branches",
              FilterTree.filterTree(tree, "Search branches", rows(), fieldActions())
                  .w(300)));
    }
  }

  private String run(App<?> app, String steps) throws IOException {
    Script.run(steps + "\nframe\nadvance 0.3\nframe\nsnap s", app, out);
    return Files.readString(out.resolve("s.tree.txt"));
  }

  @Test
  void filteringRevealsNestedMatchesAndClearingRestoresManualOpenState() throws IOException {
    Switcher state = new Switcher();
    App<Switcher> app = new App<>(state, Switcher::embeddedView);
    String initial = run(app, "size 360 360\nframe\nfocus #query");
    assertTrue(initial.contains("#row-local role=treeitem label=\"Local\""), initial);
    assertTrue(initial.contains("#row-main role=treeitem label=\"main\""), initial);
    assertTrue(initial.contains("#row-feat role=treeitem label=\"feat/\""), initial);
    assertFalse(initial.contains("#row-auth "), "feat/ starts closed");
    assertFalse(initial.contains("#row-origin-main "), "Remote starts closed");

    // Typing a deeply nested branch reveals it with ancestors expanded and hides separators.
    String filtered = run(app, "type \"auth\"");
    assertEquals("auth", state.tree.text());
    assertTrue(filtered.contains("#row-local ") && filtered.contains(" expanded "), filtered);
    assertTrue(filtered.contains("#row-feat ") && filtered.contains(" expanded "), filtered);
    assertTrue(StructureTest.line(filtered, "#row-auth ").contains(" selected"), filtered);
    assertFalse(filtered.contains("role=separator"), filtered);
    assertFalse(filtered.contains("#row-update "), filtered);
    assertTrue(StructureTest.line(filtered, "#query ").contains(" focused"), filtered);

    // Toggling a branch while filtering does not corrupt the manual open set.
    String collapsedInFilter = run(app, "press #row-local");
    assertFalse(collapsedInFilter.contains("#row-auth "), collapsedInFilter);
    assertTrue(state.tree.isOpen("local"), "manual open set untouched");
    assertFalse(state.tree.isOpen("feat"), "manual open set untouched");

    // Clearing the query restores the exact manual open/closed state.
    String cleared = run(app, "key A control\nkey Backspace");
    assertEquals("", state.tree.text());
    assertTrue(cleared.contains("#row-main "), "local is open again");
    assertFalse(cleared.contains("#row-auth "), "feat/ is closed again");
    assertFalse(cleared.contains("#row-origin-main "), "remote is still closed");
    assertTrue(cleared.contains("role=separator"), cleared);

    // No matches shows the muted empty note and swallows Enter.
    String empty = run(app, "type \"zzz\"\nframe\nkey Enter");
    assertTrue(empty.contains("No results"), empty);
    assertEquals(0, state.rootEnters, "Enter stays in the field");
  }

  @Test
  void keyboardNavigationSkipsSeparatorsExpandsCollapsesAndKeepsFocusInField() throws IOException {
    Switcher state = new Switcher();
    App<Switcher> app = new App<>(state, Switcher::embeddedView);
    // Initial highlight is #row-update (index 0). Down -> commit, Down -> skips separator -> new.
    String tree = run(app, """
        size 360 360
        frame
        focus #query
        key ArrowDown
        frame
        key ArrowDown""");
    assertTrue(StructureTest.line(tree, "#row-new ").contains(" selected"), tree);
    assertTrue(StructureTest.line(tree, "#query ").contains(" focused"), tree);

    // Down to Local (open), Down to main, Down to feat/ (closed), Right opens feat/.
    tree = run(app, "key ArrowDown\nkey ArrowDown\nkey ArrowDown\nframe\nkey ArrowRight");
    assertTrue(state.tree.isOpen("feat"), "Right opened feat/");
    assertTrue(tree.contains("#row-auth "), tree);
    assertTrue(StructureTest.line(tree, "#row-feat ").contains(" selected"), tree);
    assertTrue(StructureTest.line(tree, "#query ").contains(" focused"), tree);

    // Right again moves into first child (auth); Left moves back to parent (feat/); Left collapses.
    tree = run(app, "key ArrowRight\nframe");
    assertTrue(StructureTest.line(tree, "#row-auth ").contains(" selected"), tree);
    tree = run(app, "key ArrowLeft\nframe");
    assertTrue(StructureTest.line(tree, "#row-feat ").contains(" selected"), tree);
    tree = run(app, "key ArrowLeft\nframe");
    assertFalse(state.tree.isOpen("feat"), "Left collapsed feat/");
    assertFalse(tree.contains("#row-auth "), tree);

    // End jumps to Remote, Home jumps back to Update project; Enter runs action; field actions
    // work.
    tree = run(app, "key End\nframe");
    assertTrue(StructureTest.line(tree, "#row-remote ").contains(" selected"), tree);
    tree = run(app, "key Home\nframe\nkey Enter");
    assertEquals(List.of("update"), state.log);
    assertTrue(StructureTest.line(tree, "#query ").contains(" focused"), tree);

    tree = run(
        app,
        "press role=button label=\"Settings\"\nframe\npress role=button label=\"Collapse all\"");
    assertEquals(List.of("update", "settings"), state.log);
    assertFalse(state.tree.isOpen("local"));
    assertFalse(tree.contains("#row-main "), tree);
    assertTrue(StructureTest.line(tree, "#query ").contains(" focused"), tree);
  }

  @Test
  void submenuOpensWithRightOrEnterClosesWithLeftAndEscapeAndRunsItem() throws IOException {
    Switcher state = new Switcher();
    App<Switcher> app = new App<>(state, Switcher::popoverView);
    String open = run(app, "size 520 400\nframe\nfocus #trigger\nkey Enter");
    assertTrue(state.popover.isOpen(), open);
    assertTrue(StructureTest.line(open, "#query ").contains(" focused"), open);

    // Filter to "auth" (caret is at end of query), press Right to open its submenu.
    String menu = run(app, "type \"auth\"\nframe\nkey ArrowRight");
    assertTrue(state.tree.submenu.isOpen(), menu);
    assertTrue(menu.contains("#menu role=menu"), menu);
    assertEquals("menu", app.focused().key());

    // ArrowLeft closes the submenu and returns focus to #query (popover stays open).
    String afterLeft = run(app, "key ArrowLeft");
    assertFalse(state.tree.submenu.isOpen(), afterLeft);
    assertTrue(state.popover.isOpen(), afterLeft);
    assertEquals("query", app.focused().key());

    // Enter on auth (no onChoose, only submenu) opens submenu; Escape closes submenu first.
    String byEnter = run(app, "key Enter");
    assertTrue(state.tree.submenu.isOpen(), byEnter);
    assertEquals("menu", app.focused().key());
    String afterEsc = run(app, "key Escape");
    assertFalse(state.tree.submenu.isOpen(), afterEsc);
    assertTrue(state.popover.isOpen(), afterEsc);
    assertEquals("query", app.focused().key());

    // Open submenu again, move to Checkout (already first) and press Enter -> runs item & closes
    // popover.
    run(app, "key ArrowRight\nframe\nadvance 0.3\nframe\nkey Enter");
    assertEquals(List.of("checkout:feat/auth"), state.log);
    assertFalse(state.tree.submenu.isOpen());
    assertFalse(state.popover.isOpen());
    assertEquals("trigger", app.focused().key());

    // Reopen popover and verify Escape from #query closes the popover.
    run(app, "press #trigger\nframe\nadvance 0.3\nframe\nkey Escape");
    assertFalse(state.popover.isOpen());
  }

  @Test
  void rowsWithTheSameTextStayApart() throws IOException {
    List<String> log = new ArrayList<>();
    FilterTree.State tree = new FilterTree.State();
    List<FilterTree.Row> rows = List.of(
        FilterTree.action("app", () -> log.add("first")),
        FilterTree.action("app", () -> log.add("second")));
    App<FilterTree.State> app =
        new App<>(tree, state -> FilterTree.filterTree(state, "Search worktrees", rows));
    run(app, "size 360 200\nframe\nfocus #query\nkey ArrowDown\nframe\nkey Enter");
    assertEquals(List.of("second"), log);
  }

  @Test
  void looksAsStored() throws IOException {
    Switcher state = new Switcher();
    state.tree.open("local", "feat");
    App<Switcher> app = new App<>(state, Switcher::popoverView);
    app.textRendering(TextRendering.LINUX);
    String script = """
        size 520 360
        frame
        press #trigger
        frame
        advance 0.3
        frame
        key ArrowDown
        key ArrowDown
        key ArrowDown
        key ArrowDown
        frame
        key ArrowRight
        frame
        advance 0.3
        frame
        snap open
        """;
    List<Path> files = Script.run(script, app, Path.of("target/filter-tree"));
    Golden.assertSnaps("filter-tree-dark-", files, "open");
  }
}
