package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.Examples.Lights;
import com.rethinkj.ui.render.Png;
import com.rethinkj.ui.text.TextLibraries;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScriptTest {

  private static final Path EXAMPLE = Path.of("src/test/resources/scripts/switch.txt");
  private static final Path LIST = Path.of("src/test/resources/scripts/list.txt");
  private static final Path ROWS = Path.of("src/test/resources/scripts/rows.txt");
  private static final Path BARS = Path.of("src/test/resources/scripts/scrollbars.txt");
  private static final Path FOCUS = Path.of("src/test/resources/scripts/focus.txt");
  private static final Path HOVER = Path.of("src/test/resources/scripts/hover.txt");
  private static final Path FIELD = Path.of("src/test/resources/scripts/field.txt");
  private static final Path FIELD_ROWS = Path.of("src/test/resources/scripts/field-rows.txt");
  private static final Path OVERLAY = Path.of("src/test/resources/scripts/overlay.txt");
  private static final Path SHAPES = Path.of("src/test/resources/scripts/shapes.txt");

  @TempDir
  Path out;

  @Test
  void reproducesTheStoredFramesOfTheSwitch() throws IOException {
    TextLibraries.assumeLoaded();
    String script = """
        size 200 120
        frame
        snap switch-off
        size 200 120 scale 2
        press #switch
        frame
        advance 0.04
        frame
        snap switch-moving-040ms
        advance 0.04
        frame
        snap switch-moving-080ms
        advance 0.04
        frame
        snap switch-moving-120ms
        advance 0.04
        frame
        snap switch-moving-160ms
        advance 0.04
        frame
        snap switch-moving-200ms
        advance 1
        frame
        snap switch-on
        """;
    List<String> names = List.of(
        "switch-off",
        "switch-moving-040ms",
        "switch-moving-080ms",
        "switch-moving-120ms",
        "switch-moving-160ms",
        "switch-moving-200ms",
        "switch-on");
    App<Lights> app = new App<>(new Lights(), Examples::lights);
    app.textRendering(TextRendering.LINUX);
    List<Path> written = Script.run(script, app, out);

    List<Path> expected = new ArrayList<>();
    for (String name : names) {
      expected.add(out.resolve(name + ".png"));
      expected.add(out.resolve(name + ".tree.txt"));
    }
    assertEquals(expected, written);
    assertSnapsMatchGolden(names);
  }

  @Test
  void theCommandLineRunsTheExample() throws IOException {
    TextLibraries.assumeLoaded();
    Examples.main(new String[] {"--script", EXAMPLE.toString(), "--out", out.toString()});

    assertSnapsMatchGolden(List.of("switch-off", "switch-moving-040ms", "switch-on"));
  }

  @Test
  void bordersPerSideRadiiPerCornerAndARingWithAGapDrawAsStored() throws IOException {
    TextLibraries.assumeLoaded();
    Examples.main(new String[] {"shapes", "--script", SHAPES.toString(), "--out", out.toString()});

    assertSnapsMatchGolden(List.of("shapes-1x", "shapes-2x"));
    assertTrue(Files.readString(out.resolve("shapes-2x.tree.txt"))
        .contains("el #ringed role=button label=\"Run\" 148,106 56x24 focused"));
  }

  @Test
  void theListScrollsAsTheStoredFramesShow() throws IOException {
    TextLibraries.assumeLoaded();
    Examples.main(new String[] {"list", "--script", LIST.toString(), "--out", out.toString()});

    assertSnapsMatchGolden(List.of("list-top", "list-scrolled", "list-end"));
    assertTrue(Files.readString(out.resolve("list-scrolled.tree.txt")).contains(" scroll 0,90\n"));
  }

  @Test
  void theMillionRowsScrollAsTheStoredFramesShow() throws IOException {
    TextLibraries.assumeLoaded();
    Examples.main(new String[] {"rows", "--script", ROWS.toString(), "--out", out.toString()});

    assertSnapsMatchGolden(List.of("rows-top", "rows-scrolled", "rows-far"));
    assertTrue(Files.readString(out.resolve("rows-far.tree.txt"))
        .contains(
            "el #rows role=grid 16,44 248x160 rows 765429-765440/1000000 scroll 0,21432096\n"));
  }

  @Test
  void theScrollbarDragsToTheLastRowAndBack() throws IOException {
    TextLibraries.assumeLoaded();
    Examples.main(new String[] {"bars", "--script", BARS.toString(), "--out", out.toString()});

    assertSnapsMatchGolden(List.of("bars-top", "bars-end", "bars-paged", "bars-back"));
    assertTrue(Files.readString(out.resolve("bars-top.tree.txt"))
        .contains("el #rows role=grid 16,44 248x160 rows 0-8/1000000 thumb-y 256,46 6x24\n"));
    assertTrue(Files.readString(out.resolve("bars-end.tree.txt"))
        .contains("el #rows role=grid 16,44 248x160 rows 999991-999999/1000000 scroll 0,27999840"
            + " thumb-y 256,178 6x24 hovered\n"));
    assertTrue(Files.readString(out.resolve("bars-end.tree.txt"))
        .contains("el #row-999999 role=row label=\"suppliers\" 16,176 248x28"
            + " pos=1000000/1000000\n"));
    assertTrue(
        Files.readString(out.resolve("bars-paged.tree.txt")).contains(" scroll 0,27999680 "));
    assertTrue(Files.readString(out.resolve("bars-back.tree.txt"))
        .contains("el #rows role=grid 16,44 248x160 rows 0-8/1000000 thumb-y 256,46 6x24\n"));
  }

  @Test
  void theFocusExampleTabsToTheSwitchAndPressesItWithSpace() throws IOException {
    TextLibraries.assumeLoaded();
    Examples.main(new String[] {"--script", FOCUS.toString(), "--out", out.toString()});

    assertSnapsMatchGolden(List.of("switch-focused", "switch-focused-on"));
    assertTrue(Files.readString(out.resolve("switch-focused.tree.txt"))
        .contains(
            "el #switch role=switch label=\"Lights\" 39,49 38x22 checked=false" + " focused\n"));
  }

  @Test
  void theHoverExampleShowsTheLooksOfTheSwitch() throws IOException {
    TextLibraries.assumeLoaded();
    Examples.main(new String[] {"--script", HOVER.toString(), "--out", out.toString()});

    assertSnapsMatchGolden(
        List.of("switch-hovered", "switch-pressed", "switch-hovered-on", "switch-on"));
    assertTrue(Files.readString(out.resolve("switch-hovered.tree.txt"))
        .contains(
            "el #switch role=switch label=\"Lights\" 39,49 38x22 checked=false" + " hovered\n"));
  }

  @Test
  void theFieldExampleTypesSelectsAndScrolls() throws IOException {
    TextLibraries.assumeLoaded();
    Examples.main(new String[] {"field", "--script", FIELD.toString(), "--out", out.toString()});

    assertSnapsMatchGolden(List.of(
        "field-empty",
        "field-typed",
        "field-word",
        "field-home",
        "field-dragged",
        "field-composing"));
    assertTrue(Files.readString(out.resolve("field-word.tree.txt"))
        .contains(" \"SELECT name FROM customers\" 40,26 160x28 focused caret 17 anchor 26\n"));
  }

  @Test
  void theStepsOfListsAndFieldsDriveTheFieldsInTheRowsOfAList() throws IOException {
    TextLibraries.assumeLoaded();
    Examples.main(
        new String[] {"field-rows", "--script", FIELD_ROWS.toString(), "--out", out.toString()});

    assertSnapsMatchGolden(
        List.of("field-rows-pasted", "field-rows-wheeled", "field-rows-away", "field-rows-back"));
    assertEquals("ledger", Files.readString(out.resolve("field-rows-pasted.clipboard.txt")));
    assertTrue(Files.readString(out.resolve("field-rows-pasted.tree.txt"))
        .contains(" \"ledger\" 60,112 200x24 focused caret 6\n"));
    assertTrue(Files.readString(out.resolve("field-rows-wheeled.tree.txt"))
        .contains(" \"ledger\" 60,92 200x24 focused caret 6\n"));
    String away = Files.readString(out.resolve("field-rows-away.tree.txt"));
    assertTrue(away.contains(" rows 497-507/1000 scroll 0,16000\n"), away);
    assertFalse(away.contains("focused"), "the focused row does not exist");
    assertTrue(Files.readString(out.resolve("field-rows-back.tree.txt"))
        .contains(" \"ledger\" 60,112 200x24 focused caret 5 anchor 6 composing \"ni\"\n"));
  }

  @Test
  void theSecondaryButtonOpensAMenuAboveTheTreeWithASubmenuAndADialog() throws IOException {
    TextLibraries.assumeLoaded();
    Examples.main(
        new String[] {"overlay", "--script", OVERLAY.toString(), "--out", out.toString()});

    assertSnapsMatchGolden(
        List.of("overlay-menu", "overlay-submenu", "overlay-dialog", "overlay-closed"));
    String menu = Files.readString(out.resolve("overlay-menu.tree.txt"));
    assertTrue(
        menu.contains("\noverlay at 120,85 bottom start on #list role=list"
            + " label=\"Tables\" 16,16 208x146\n  el #menu role=menu 120,85 81x86\n"),
        menu);
    assertTrue(menu.contains(" #open role=menuitem label=\"Open\" 125,90 71x24 focused\n"), menu);
    String submenu = Files.readString(out.resolve("overlay-submenu.tree.txt"));
    assertTrue(
        submenu.contains("\noverlay right start gap 6 on #export role=menuitem"
            + " label=\"Export\" 125,116 71x24\n  el #formats role=menu 53,116 66x60\n"),
        submenu);
    String dialog = Files.readString(out.resolve("overlay-dialog.tree.txt"));
    assertTrue(dialog.contains("\noverlay center modal scrim on 0,0 240x200\n"), dialog);
    assertTrue(
        dialog.contains(" #cancel role=button label=\"Cancel\" 51,101 67x28 focused\n"), dialog);
    assertTrue(Files.readString(out.resolve("overlay-closed.tree.txt"))
        .contains(" #list role=list label=\"Tables\" 16,16 208x146 scroll 0,40 focused\n"));
  }

  @Test
  void pressesCountClicksAndModifiersAndTheClipboardCanBeSetAndRead() throws IOException {
    TextLibraries.assumeLoaded();
    App<Examples.Form> app = new App<>(new Examples.Form(), Examples::form);
    app.textRendering(TextRendering.LINUX);
    List<Path> written = Script.run("""
        size 240 80
        frame
        clipboard "SELECT\\n1"       # put on the app's clipboard; a step that needs no frame
        press #name
        key V control
        frame
        press 60,40 clicks 2 # the word there
        frame
        key C control
        frame
        snap copied
        press 50,40
        press 150,40 shift
        frame
        """, app, out);
    assertEquals("SELECT 1", app.state().name, "the line break became a space");
    assertEquals(
        List.of(
            out.resolve("copied.png"),
            out.resolve("copied.tree.txt"),
            out.resolve("copied.clipboard.txt")),
        written);
    assertEquals("SELECT", Files.readString(out.resolve("copied.clipboard.txt")));
    El field = app.tree().children().getFirst();
    assertTrue(field.editor().anchor() < field.editor().caret(), "Shift extended the selection");
  }

  @Test
  void composeShowsACompositionUntilItEnds() throws IOException {
    TextLibraries.assumeLoaded();
    App<Examples.Form> app = new App<>(new Examples.Form(), Examples::form);
    app.textRendering(TextRendering.LINUX);
    Script.run("size 240 80\nframe\nfocus #name\ncompose \"ni\"\nframe\n", app, out);
    assertTrue(app.tree().dump().contains(" focused caret 0 composing \"ni\"\n"));
    assertEquals("", app.state().name);
    Script.run("size 240 80\ncompose \"\"\nframe\n", app, out);
    assertFalse(app.tree().dump().contains("composing"));
  }

  @Test
  void moveAndLeaveReachTheApp() throws IOException {
    App<Lights> app = switchApp();
    Script.run("size 200 120\nframe\nmove #knob\nframe\n", app, out);
    assertEquals("knob", app.hovered().key());
    assertEquals(Cursor.POINTER, app.cursor());
    Script.run("size 200 120\nmove 10,10\nframe\n", app, out);
    assertSame(app.tree(), app.hovered(), "the root");
    Script.run("size 200 120\nleave\nframe\n", app, out);
    assertNull(app.hovered());
  }

  @Test
  void keyTypeAndFocusReachTheApp() throws IOException {
    List<String> log = new ArrayList<>();
    App<List<String>> app = new App<>(
        log,
        l -> row()
            .onKey(k -> l.add(k.name() + " " + k.modifiers()) && false)
            .children(
                el().key("a").focusable().size(20, 20),
                el().key("b").focusable().size(20, 20).onText(l::add)));
    Script.run(
        "size 40 20\nframe\nkey Tab\nkey Tab shift control\nfocus #b\ntype \"say \\\"hi\\\"\"\n"
            + "frame\n",
        app,
        out);
    assertEquals(List.of("Tab 0", "Tab 3", "say \"hi\""), log);
    assertEquals("b", app.focused().key());
    Script.run("size 40 20\nfocus 5,5\nframe\n", app, out);
    assertEquals("a", app.focused().key());
  }

  @Test
  void wheelScrollsTheAreaUnderItsTarget() throws IOException {
    TextLibraries.assumeLoaded();
    App<Object> app = new App<>(new Object(), s -> Examples.list());
    Script.run("size 240 200\nframe\nwheel 100,100 0 30.5\nwheel #row-1 0 -0.5\nframe\n", app, out);
    El list = app.tree().children().getFirst();
    assertEquals(30, list.scrollY());
    Script.run("size 240 200\nwheel label=\"audit_log\" 0 -100\nframe\n", app, out);
    assertEquals(0, list.scrollY());
  }

  @Test
  void withoutScriptArgumentsTheCommandLineIsLeftToTheApp() throws IOException {
    App<Lights> app = switchApp();
    assertFalse(Script.handle(new String[0], app));
    assertFalse(Script.handle(new String[] {"--fullscreen"}, app));
    assertNull(app.tree(), "nothing ran");
    try (var files = Files.list(out)) {
      assertEquals(0, files.count());
    }
  }

  @Test
  void theCommandLineRefusesIncompleteArguments() throws IOException {
    Files.writeString(out.resolve("s.txt"), "frame\n");
    String script = out.resolve("s.txt").toString();
    assertEquals(
        "Run a script with --script <file> --out <folder>: it runs the steps in the file and"
            + " writes the snaps into the folder.",
        cli("--script", script));
    String unknown = cli("--script", script, "--out", "x", "--fast");
    assertTrue(unknown.startsWith("Unknown argument --fast. Run a script with"), unknown);
    String missing = cli("--script", "missing.txt", "--out", "x");
    assertTrue(missing.startsWith("There is no script missing.txt. Run a script with"), missing);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "100,60",
        "#switch",
        "#knob", // has no handler: the press goes to the switch around it
        "role=switch",
        "label=\"Lights\"",
        "role=switch label=\"Lights\"",
        "#switch role=switch label=\"Lights\" # with a comment",
      })
  void everyKindOfTargetPressesTheSwitch(String target) throws IOException {
    App<Lights> app = switchApp();
    Script.run("size 200 120\nframe\npress " + target + "\nframe\n", app, out);
    assertTrue(app.state().on);
  }

  @Test
  void aSelectorPressesTheCentreOfItsElement() throws IOException {
    List<String> log = new ArrayList<>();
    App<List<String>> app = new App<>(
        log,
        l -> el().size(100, 100)
            .onPress(() -> l.add("outside"))
            .child(el().absolute(40, 40)
                .size(20, 20)
                .label("Say \"hi\" \\ bye")
                .onPress(() -> l.add("centre"))));
    Script.run("size 100 100\nframe\npress label=\"Say \\\"hi\\\" \\\\ bye\"\nframe\n", app, out);
    assertEquals(List.of("centre"), log);
  }

  @Test
  void downAndUpPressOnlyWhenTheyMeetTheSameElement() throws IOException {
    App<Lights> app = switchApp();
    Script.run("size 200 120\nframe\ndown #switch\nup 10,10\nframe\n", app, out);
    assertFalse(app.state().on, "a press that ends beside the switch");
    Script.run("size 200 120\ndown 10,10\nup #switch\nframe\n", app, out);
    assertFalse(app.state().on, "a press that begins beside the switch");
    Script.run("size 200 120\ndown #switch\nup #knob\nframe\n", app, out);
    assertTrue(app.state().on, "the knob is part of the switch");
  }

  @Test
  void onlyFrameRunsAFrame() throws IOException {
    App<Lights> app = switchApp();
    Script.run("size 200 120 scale 2\nframe\npress #switch\n", app, out);
    assertFalse(app.state().on, "press only queues the handler");

    Script.run("size 200 120 scale 2\nframe\nadvance 0.04\n", app, out);
    assertTrue(app.state().on);
    El knob = app.tree().children().getFirst().children().getFirst();
    assertEquals(2, knob.x(), "advance alone moves nothing on screen");

    app.frame();
    assertTrue(knob.x() > 2);
  }

  @Test
  void snapRefusesAFrameThatDoesNotShowTheStepsBeforeIt() {
    assertEquals("""
        line 4: snap pressed
          The last frame does not show line 3 (press #switch) yet. Add `frame` before this line:\
         only `frame` runs the handlers, rebuilds the view and moves what is animated.""", failure("size 200 120\nframe\npress #switch\nsnap pressed\n"));
    assertFailure("size 200 120\nframe\nadvance 1\nsnap later\n", "line 3 (advance 1)");
    assertFailure("size 200 120\nframe\nsize 300 120\nsnap wider", "line 3 (size 300 120)");
    assertFailure("size 200 120\nsnap first\n", "line 2: snap first\n  No frame has run yet");
  }

  @Test
  void anUnknownStepListsTheKnownOnes() {
    assertEquals("""
        line 3: pres #switch
          There is no step pres. The steps are size, window, close, press, down, up, move,\
         leave, wheel, scroll, key, type, compose, clipboard, dialog, drop, focus, advance,\
         await, frame and snap.""", failure("size 200 120\nframe\npres #switch\n"));
  }

  @Test
  void aSelectorThatMatchesNothingListsWhatThereIs() {
    assertEquals(
        """
        line 5: press #swich # typo
          No element of the last frame matches #swich. It has
            keys:   #knob #switch
            roles:  role=switch
            labels: label="Lights"
            states: checked=false
          Select with these, or press a position x,y. The .tree.txt of a snap shows every element\
         with its rectangle.""",
        failure("# comments and blank lines count\nsize 200 120\n\nframe\npress #swich # typo\n"));
  }

  @Test
  void statesSelectAsTheTreeDumpWritesThem() throws IOException {
    List<String> log = new ArrayList<>();
    App<List<String>> app = new App<>(
        log,
        l -> col()
            .wFull()
            .children(
                el().role("row").label("a").h(10).onPress(() -> l.add("a")),
                el().role("row").label("b").h(10).selected(true).onPress(() -> l.add("b")),
                el().role("checkbox").label("c").h(10).checked(false).onPress(() -> l.add("c")),
                el().role("checkbox").label("d").h(10).checked(true).onPress(() -> l.add("d")),
                el().role("treeitem")
                    .label("e")
                    .h(10)
                    .expanded(false)
                    .level(2)
                    .onPress(() -> l.add("e")),
                el().role("separator").h(10).value("first 240").onPress(() -> l.add("f"))));
    Script.run(
        "size 100 60\nframe\npress role=row selected\npress role=checkbox checked=false\n"
            + "press checked=true\npress expanded=false level=2\npress value=\"first 240\"\nframe\n",
        app,
        out);
    assertEquals(List.of("b", "c", "d", "e", "f"), log);

    assertEquals("""
        line 3: press role=checkbox checked=mixed
          2 elements of the last frame match role=checkbox, but none has the states of\
         role=checkbox checked=mixed:
            el role=checkbox label="c" 0,20 100x10 checked=false
            el role=checkbox label="d" 0,30 100x10 checked
          Their lines show the states they have after the rectangle.""", failure("size 100 60\nframe\npress role=checkbox checked=mixed\n", app));
    assertFailure("size 100 60\nframe\npress selected=false\n", "selected=false is not a target");
    assertFailure("size 100 60\nframe\npress level=x\n", "level=x is not a target");
    assertFailure(
        "size 100 60\nframe\npress expanded\n",
        "states: checked checked=false expanded=false level=2 selected\n",
        app);
  }

  @Test
  void aSelectorThatMatchesSeveralListsThemWithTheirRectangles() {
    App<Object> app = new App<>(
        new Object(),
        s -> row()
            .children(
                el().key("ok").role("button").size(50, 20).onPress(() -> {}),
                el().key("cancel").role("button").size(50, 20).onPress(() -> {})));
    assertEquals("""
        line 3: press role=button
          2 elements match role=button:
            el #ok role=button 0,0 50x20
            el #cancel role=button 50,0 50x20
          Add #key, role=, label= or a state to the selector until it matches one, or press a\
         position x,y inside one of these rectangles.""", failure("size 100 20\nframe\npress role=button\n", app));
  }

  @Test
  void aSnapCannotLeaveTheOutputFolderOrOverwriteAnother() throws IOException {
    Path inside = out.resolve("inside");
    App<Lights> app = switchApp();
    Script.Failure failure = assertThrows(
        Script.Failure.class,
        () -> Script.run("size 200 120\nframe\nsnap ../outside\n", app, inside));
    assertEquals("""
        line 3: snap ../outside
          ../outside leads out of the output folder. Use a name inside it, such as after-press or\
         press/after.""", failure.getMessage());
    assertFalse(Files.exists(out.resolve("outside.png")));

    assertFailure("size 200 120\nframe\nsnap /outside\n", "leads out of the output folder");
    assertEquals(
        "line 4: snap a/../b\n  The snap on line 3 wrote these files. Use another name.",
        failure("size 200 120\nframe\nsnap b\nsnap a/../b\n"));
    Script.run("size 200 120\nframe\nsnap shots/off\n", switchApp(), out);
    assertTrue(Files.exists(out.resolve("shots/off.png")));
  }

  @Test
  void otherMistakesNameTheLineAndWhatToWrite() {
    assertEquals(
        "line 1: frame\n  The window has no size yet. Add `size <width> <height>` before this"
            + " line, for example `size 200 120`.",
        failure("frame"));
    assertEquals(
        "line 1: press #switch\n  No frame has run yet, so there is nothing to press. Add `frame`"
            + " before this line.",
        failure("press #switch"));
    assertFailure("size 200\n", "line 1: size 200\n  Write `size <width> <height>` or");
    assertFailure("size 0 120\n", "line 1: size 0 120\n  Write `size");
    assertFailure("size 200 120 zoom 2\n", "line 1: size 200 120 zoom 2\n  Write `size");
    assertFailure("advance -1\n", "line 1: advance -1\n  Write `advance <seconds>`");
    assertFailure("frame now\n", "line 1: frame now\n  Write `frame` with nothing");
    assertFailure("snap\n", "line 1: snap\n  Write `snap <name>`");
    assertFailure(
        "up\n",
        "line 1: up\n  Write `up <target>`, or `up <target> right` for the"
            + " secondary button. A target is a position");
    assertFailure("move\n", "line 1: move\n  Write `move <target>`. A target is a position");
    assertEquals(
        "line 1: move #switch\n  No frame has run yet, so there is nothing to move over. Add"
            + " `frame` before this line.",
        failure("move #switch\n"));
    assertFailure(
        "size 200 120\nframe\nmove #swich\n",
        "line 3: move #swich\n  No element of the last frame matches #swich.");
    assertEquals(
        "line 1: leave now\n  Write `leave` with nothing after it.", failure("leave now\n"));
    assertFailure("size 200 120\nframe\nleave\nsnap out\n", "line 3 (leave)");
    assertEquals(
        "line 1: wheel #list 40\n  Write `wheel <target> <deltaX> <deltaY>`, with distances in"
            + " logical pixels that are positive to scroll down or right, for example `wheel #list"
            + " 0 40`. A target is a position x,y in logical pixels such as 90,60, or a selector"
            + " of #key, role=<role>, label=\"<text>\", text=\"<string>\" and states as a"
            + " .tree.txt writes them (selected, checked=false, expanded, level=2,"
            + " value=\"<text>\") such as role=switch label=\"Lights\".",
        failure("wheel #list 40\n"));
    assertFailure("wheel #list 0 down\n", "line 1: wheel #list 0 down\n  Write `wheel <target>");
    assertFailure("wheel 0 40\n", "Write `wheel <target>");
    assertEquals(
        "line 1: wheel #switch 0 40\n  No frame has run yet, so there is nothing to scroll. Add"
            + " `frame` before this line.",
        failure("wheel #switch 0 40\n"));
    assertFailure(
        "size 200 120\nframe\nwheel #lst 0 40\n",
        "line 3: wheel #lst 0 40\n  No element of the last frame matches #lst.");
    assertFailure(
        "size 200 120\nframe\npress name=Lights\n",
        "line 3: press name=Lights\n  name=Lights is not a target. A target is");
    assertFailure(
        "size 200 120\nframe\npress label=\"Lights\n",
        "line 3: press label=\"Lights\n  A quote is not closed.");
  }

  @Test
  void mistakesInKeysTextAndFocusNameTheLineAndWhatToWrite() {
    assertEquals(
        "line 3: key Entr\n  There is no key Entr. Keys have the names of the web's key values,"
            + " such as Enter, Tab, Space, Escape, Backspace, ArrowDown, PageUp, Home, F5 and"
            + " Numpad1, and keys that type a character are named by it in upper case, such as A,"
            + " 1 and -. The Javadoc of com.rethinkj.ui.Key lists them all.",
        failure("size 200 120\nframe\nkey Entr\n"));
    assertFailure("key enter\n", "line 1: key enter\n  There is no key enter. Write Enter.");
    assertFailure(
        "key a\n",
        "There is no key a. Keys that type a character are named by it in"
            + " upper case, with or without shift: write A.");
    assertEquals(
        "line 1: key Tab shfit\n  Write `key <name>`, followed by any of shift, control, alt and"
            + " meta that are held, for example `key Enter` or `key Tab shift` for Shift+Tab,"
            + " and `down` or `up` for one half of the press, as `key Space down` to hold it.",
        failure("key Tab shfit\n"));
    assertFailure("key\n", "line 1: key\n  Write `key <name>`");
    assertEquals(
        "line 1: key Tab\n  No frame has run yet, so nothing can take the key. Add `frame` before"
            + " this line.",
        failure("key Tab\n"));
    assertEquals(
        "line 1: type hello\n  Write `type \"<text>\"`, with \\\" for a quote and \\\\ for a"
            + " backslash in the text, for example `type \"hello\"`.",
        failure("type hello\n"));
    assertFailure("type \"\"\n", "Write `type");
    assertFailure("type \"a\" \"b\"\n", "Write `type");
    assertFailure("type \"hello\"\n", "No frame has run yet, so nothing can take the text.");
    assertEquals(
        "line 1: compose ni\n  Write `compose \"<text>\"`, the composition of an input method,"
            + " quoted as for `type`, for example `compose \"ni\"`; `compose \"\"` ends it.",
        failure("compose ni\n"));
    assertFailure(
        "compose \"ni\"\n", "No frame has run yet, so nothing can take the" + " composition.");
    assertEquals(
        "line 1: clipboard\n  Write `clipboard \"<text>\"`, the text to put on the clipboard,"
            + " quoted as for `type`, for example `clipboard \"SELECT 1\"`.",
        failure("clipboard\n"));
    assertEquals(
        "line 3: press #switch clicks two\n  Write `press <target>`, then `clicks <n>` for the"
            + " second or third press in a row and any of shift, control, alt and meta that are"
            + " held, for example `press #name clicks 2` or `press #name shift`, or `press"
            + " <target> right` for the secondary button, without them. A target is a"
            + " position x,y in logical pixels such as 90,60, or a selector of #key, role=<role>,"
            + " label=\"<text>\", text=\"<string>\" and states as a .tree.txt writes them"
            + " (selected, checked=false, expanded, level=2, value=\"<text>\") such as"
            + " role=switch label=\"Lights\".",
        failure("size 200 120\nframe\npress #switch clicks two\n"));
    assertFailure("size 200 120\nframe\ndown shift\n", "Write `down <target>`, then `clicks");
    assertFailure("size 200 120\nframe\nup #switch shift\n", "shift is not a target");
    assertFailure("focus\n", "line 1: focus\n  Write `focus <target>`. A target is");
    assertEquals("""
        line 3: focus 10,10
          Neither the element at 10,10 nor one around it is focusable, so the focus cannot go\
         there. These elements of the last frame are:
            el #switch role=switch label="Lights" 81,49 38x22 checked=false
          Select one of them.""", failure("size 200 120\nframe\nfocus 10,10\n"));
    App<Object> plain = new App<>(new Object(), o -> el().key("box").size(10, 10));
    assertFailure(
        "size 10 10\nframe\nfocus #box\n",
        "focus cannot go there. No element of the last frame is; El.focusable() marks one.",
        plain);
  }

  @Test
  void anExceptionOfTheAppNamesTheFrameItHappenedIn() {
    App<Object> app =
        new App<>(new Object(), s -> el().children(el().key("twice"), el().key("twice")));
    Script.Failure failure =
        assertThrows(Script.Failure.class, () -> Script.run("size 10 10\nframe\n", app, out));
    assertTrue(failure.getMessage().startsWith("line 2: frame\n  The app threw"));
    assertInstanceOf(IllegalStateException.class, failure.getCause());
  }

  /** The switch without its label: these tests need no text libraries. */
  private static App<Lights> switchApp() {
    return new App<>(new Lights(), Examples::switchAlone);
  }

  private String failure(String script) {
    return failure(script, switchApp());
  }

  private String failure(String script, App<?> app) {
    return assertThrows(Script.Failure.class, () -> Script.run(script, app, out))
        .getMessage();
  }

  /** Asserts that the script fails with a message that contains {@code part}. */
  private void assertFailure(String script, String part) {
    assertFailure(script, part, switchApp());
  }

  private void assertFailure(String script, String part, App<?> app) {
    String message = failure(script, app);
    assertTrue(message.contains(part), message);
  }

  private String cli(String... args) {
    return assertThrows(Script.Failure.class, () -> Script.handle(args, switchApp()))
        .getMessage();
  }

  private void assertSnapsMatchGolden(List<String> names) throws IOException {
    for (String name : names) {
      Golden.assertMatches(
          name,
          Png.decode(Files.readAllBytes(out.resolve(name + ".png"))),
          Files.readString(out.resolve(name + ".tree.txt")));
    }
  }
}
