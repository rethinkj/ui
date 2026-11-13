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

/** The status bar: what a dump says of it, pressing a part, and a window too narrow for it. */
class StatusBarTest {

  private final int[] presses = {0};

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
                t,
                Arrays.asList(
                    StatusBar.status(
                        t, StatusBar.State.CONNECTED, "localhost / shop", () -> presses[0]++),
                    null, // left out, so that a view writes `connected ? part : null`
                    StatusBar.text(t, "PostgreSQL 17.2 on a host with a long name")),
                List.of(
                    StatusBar.text(t, "Ln 4, Col 12"),
                    StatusBar.item(t, Symbol.DATABASE, "UTF-8", null)))
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

  @Test
  void theDumpNamesTheBarItsPartsAndAStatesValue() throws IOException {
    String dump = run(640, "").tree().dump();
    assertTrue(StructureTest.line(dump, "#bar").contains("role=status label=\"Status bar\""), dump);
    assertTrue(
        StructureTest.line(dump, "label=\"localhost / shop\"").contains("role=button")
            && StructureTest.line(dump, "label=\"localhost / shop\"")
                .endsWith("value=\"connected\""),
        dump);
    assertTrue(
        !StructureTest.line(dump, "label=\"UTF-8\"").contains("role="),
        "a part without a handler is no button");
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
  void aNarrowWindowCutsTheStartAndKeepsTheEnd() throws IOException {
    String dump = run(260, "").tree().dump();
    float[] bar = StructureTest.rect(StructureTest.line(dump, "#bar"));
    float[] end = StructureTest.rect(StructureTest.line(dump, "label=\"UTF-8\""));
    float[] caret = StructureTest.rect(StructureTest.line(dump, "label=\"Ln 4, Col 12\""));
    assertTrue(end[0] + end[2] <= bar[0] + bar[2] && caret[0] >= 0, dump);
    float[] start = StructureTest.rect(
        StructureTest.line(dump, "label=\"PostgreSQL 17.2 on a host with a long name\""));
    assertTrue(start[0] + start[2] <= caret[0], "the start ends before the end begins: " + dump);
  }
}
