package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The status bar: what a dump says of it, pressing a part, a part's menu, and a window too narrow
 * for it.
 */
class StatusBarTest {

  private static final String MESSAGE = "label=\"Saved 3 changes to public.orders\"";
  private static final String LONG = "label=\"PostgreSQL 17.2 on a host with a long name\"";

  private final int[] presses = {0};
  private final Menu.State encodings = new Menu.State();

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  private El view(Theme t) {
    return col()
        .wFull()
        .hFull()
        .fill(t.surface().window())
        .child(el().grow(1))
        .child(StatusBar.statusBar(
                Arrays.asList(
                    StatusBar.status(StatusBar.State.OK, "localhost / shop", () -> presses[0]++),
                    null, // left out, so that a view writes `connected ? part : null`
                    StatusBar.group("Nothing", (El) null), // a group of none is left out too
                    StatusBar.text("PostgreSQL 17.2 on a host with a long name")),
                StatusBar.text("Saved 3 changes to public.orders"),
                List.of(
                    StatusBar.text("Ln 4, Col 12"),
                    StatusBar.menu(
                        StatusBar.item(null, "UTF-8", encodings::toggle),
                        encodings,
                        List.of(Menu.item("UTF-8", null), Menu.item("ISO-8859-1", null)))))
            .key("bar"));
  }

  private App<Object> run(int width, String steps) throws IOException {
    App<Object> app = new App<>(new Object(), s -> view(Theme.DARK));
    Script.run(
        "size " + width + " 120\nframe\n" + steps + "\nframe",
        app,
        Path.of("target/status-bar-test"));
    return app;
  }

  private static float width(String dump, String part) {
    return StructureTest.rect(StructureTest.line(dump, part))[2];
  }

  @Test
  void theDumpNamesTheBarItsPartsAStatesValueAndTheHairlinesBetween() throws IOException {
    String dump = run(1000, "").tree().dump();
    assertTrue(StructureTest.line(dump, "#bar").contains("role=status label=\"Status bar\""), dump);
    assertTrue(
        StructureTest.line(dump, "label=\"localhost / shop\"").contains("role=button")
            && StructureTest.line(dump, "label=\"localhost / shop\"").endsWith("value=\"ok\""),
        dump);
    assertTrue(
        !StructureTest.line(dump, "label=\"Ln 4, Col 12\"").contains("role="),
        "a part without a handler is no button");
    assertEquals(
        3,
        dump.lines().filter(l -> l.contains("role=separator")).count(),
        "one between the start's two, one before the message, one between the end's two: " + dump);
    assertTrue(!dump.contains("label=\"Nothing\""), dump);
  }

  @Test
  void aPressOnAPartRunsItsHandlerAndLeavesTheFocusWhereItWas() throws IOException {
    App<Object> app = run(640, "press role=button label=\"localhost / shop\"");
    assertEquals(1, presses[0]);
    assertEquals(null, app.focused());
    Script.run(
        "size 640 120\nframe\nkey Tab\nframe\nkey Enter\nframe",
        app,
        Path.of("target/status-bar-test"));
    assertEquals(2, presses[0], "Tab reaches it and Enter presses it");
  }

  @Test
  void aPartWithAMenuOpensItAndSaysSo() throws IOException {
    String closed = run(1000, "").tree().dump();
    assertTrue(StructureTest.line(closed, "label=\"UTF-8\"").contains("expanded=false"), closed);
    String open = run(1000, "press role=button label=\"UTF-8\"").tree().dump();
    assertTrue(StructureTest.line(open, "label=\"UTF-8\"").endsWith("expanded"), open);
    assertTrue(open.contains("label=\"ISO-8859-1\""), "the menu is open: " + open);
  }

  @Test
  void aNarrowWindowCutsTheMessageFirstThenTheStartAndKeepsTheEnd() throws IOException {
    String wide = run(1000, "").tree().dump();
    String middle = run(640, "").tree().dump();
    assertTrue(width(middle, MESSAGE) < width(wide, MESSAGE), middle);
    assertEquals(width(wide, LONG), width(middle, LONG), "the start keeps its size: " + middle);

    String narrow = run(260, "").tree().dump();
    float[] bar = StructureTest.rect(StructureTest.line(narrow, "#bar"));
    float[] end = StructureTest.rect(StructureTest.line(narrow, "label=\"UTF-8\""));
    float[] caret = StructureTest.rect(StructureTest.line(narrow, "label=\"Ln 4, Col 12\""));
    assertTrue(end[0] + end[2] <= bar[0] + bar[2] && caret[0] >= 0, narrow);
    float[] start = StructureTest.rect(StructureTest.line(narrow, LONG));
    assertTrue(start[0] + start[2] <= caret[0], "the start ends before the end begins: " + narrow);
  }

  @Test
  void theHairlineBeforeTheMessageLeavesWithIt() throws IOException {
    for (int width : new int[] {160, 200}) {
      App<Object> app = new App<>(
          new Object(),
          s -> col()
              .wFull()
              .hFull()
              .child(el().grow(1))
              .child(StatusBar.statusBar(
                      List.of(StatusBar.text("localhost")),
                      StatusBar.text("Saved 3 changes to public.orders"),
                      List.of(StatusBar.text("Ln 4, Col 12")))
                  .key("bar")));
      Script.run("size " + width + " 60\nframe", app, Path.of("target/status-bar-test"));
      String dump = app.tree().dump();
      // The bar clips the message to the line of its parts.
      float[] part = StructureTest.rect(StructureTest.line(dump, "label=\"Ln 4, Col 12\""));
      float bottom = part[1] + part[3];
      boolean hairline = StructureTest.rect(StructureTest.line(dump, "role=separator"))[1] < bottom;
      boolean message = StructureTest.rect(StructureTest.line(dump, MESSAGE))[1] < bottom;
      assertEquals(width == 200, message, "a sliver of the message is not shown: " + dump);
      assertEquals(message, hairline, "the hairline goes with the message: " + dump);
    }
  }
}
