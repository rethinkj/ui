package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Clipboard;
import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.render.Png;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Element values in {@link Properties}: alignment, copy button, tree dump and taller values. */
class PropertiesTest {

  private static final Pattern BOX = Pattern.compile(" (-?\\d+),(-?\\d+) (\\d+)x(\\d+)");

  @TempDir
  Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  private static final class State {
    final Clipboard clipboard = Clipboard.inMemory();
    final Button.Copied copied = new Button.Copied();
    String opened = "";

    El view() {
      Theme theme = Theme.CURRENT.get();
      float dot = theme.space().m();
      El status = row()
          .key("status-value")
          .gap(theme.space().s())
          .align(Style.Align.CENTER)
          .children(
              el().size(dot, dot).radius(dot / 2).fill(theme.status().success()),
              label("Connected"));
      El owner = label("shop_admin")
          .role("link")
          .color(theme.prose().link())
          .focusable()
          .focusRing(theme.accent().edge())
          .cursor(Cursor.POINTER)
          .onPress(() -> opened = "shop_admin");
      El list = Properties.properties(
          List.of(
              Properties.property("Table", "orders"),
              Properties.property("OID", "16384").mono(),
              Properties.property("Key", Badge.badge(Badge.Kind.ACCENT, "Primary key")),
              Properties.property("Owner", owner, "shop_admin"),
              Properties.property("Status", status)),
          clipboard,
          copied);
      return col()
          .wFull()
          .hFull()
          .padding(theme.space().l())
          .fill(theme.surface().window())
          .child(Card.card(list));
    }
  }

  private static App<State> app(State state) {
    App<State> app = new App<>(state, State::view).provide(Theme.CURRENT, s -> Theme.DARK);
    app.focusRing(Theme.DARK.accent().edge());
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  @Test
  void looksAsStored() throws IOException {
    App<State> app = app(new State());
    String image = "properties-elements-dark-2x";
    Script.run("size 360 180 scale 2\nframe\nframe\nsnap " + image, app, out);
    String tree = Files.readString(out.resolve(image + ".tree.txt"));
    Golden.assertMatches(image, Png.decode(Files.readAllBytes(out.resolve(image + ".png"))), tree);
  }

  @Test
  void elementValuesAlignCopyAndGrowDownwards() throws IOException {
    State state = new State();
    App<State> app = app(state);
    Script.run("size 360 180 scale 2\nframe\nframe", app, out);
    String dump = app.tree().dump();

    String keyRow = StructureTest.line(dump, "role=listitem label=\"Key\"");
    String ownerRow = StructureTest.line(dump, "role=listitem label=\"Owner\"");
    String statusRow = StructureTest.line(dump, "role=listitem label=\"Status\"");
    assertFalse(keyRow.contains("value="), "no tree value without copy text: " + keyRow);
    assertTrue(ownerRow.contains("value=\"shop_admin\""), ownerRow);
    assertFalse(statusRow.contains("value="), "no tree value without copy text: " + statusRow);
    assertFalse(dump.contains("label=\"Copy Key\""), "no copy button without text: " + dump);
    assertFalse(dump.contains("label=\"Copy Status\""), "no copy button without text: " + dump);
    assertTrue(dump.contains("label=\"Copy Owner\""), dump);

    // Every one-line row is control-high, and element values start at the same x as text values.
    int control = Math.round(Theme.DARK.size().control());
    int textX = box(StructureTest.line(dump, "role=textbox \"orders\""))[0];
    assertEquals(control, box(StructureTest.line(dump, "role=listitem label=\"Table\""))[3]);
    assertEquals(control, box(keyRow)[3]);
    assertEquals(control, box(ownerRow)[3]);
    assertEquals(control, box(statusRow)[3]);
    assertEquals(textX, box(StructureTest.line(dump, "label=\"Primary key\""))[0]);
    assertEquals(textX, box(StructureTest.line(dump, "role=link \"shop_admin\""))[0]);
    assertEquals(textX, box(StructureTest.line(dump, "#status-value"))[0]);

    // Pressing the link runs its handler, and the copy button on the Owner row copies its text.
    Script.run(
        "press role=link\nframe\n"
            + "move role=listitem label=\"Owner\"\nframe\nadvance 1\nframe\n"
            + "press label=\"Copy Owner\"\nframe",
        app,
        out);
    assertEquals("shop_admin", state.opened);
    assertEquals("shop_admin", state.clipboard.read());

    // A taller element grows the row downwards with the name staying at the top.
    App<Object> tall = new App<>(
            new Object(),
            s -> col()
                .wFull()
                .hFull()
                .child(Properties.properties(
                    List.of(Properties.property("Notes", el().key("tall").size(120, 48))), null)))
        .provide(Theme.CURRENT, s -> Theme.DARK);
    tall.textRendering(TextRendering.LINUX);
    Script.run("size 300 100 scale 2\nframe", tall, out);
    String tallDump = tall.tree().dump();
    int[] rowBox = box(StructureTest.line(tallDump, "role=listitem label=\"Notes\""));
    int[] nameTextBox = box(StructureTest.line(tallDump, "text \"Notes\""));
    assertEquals(48, rowBox[3], tallDump);
    assertTrue(nameTextBox[1] - rowBox[1] < control, "name beside first line: " + tallDump);
  }

  private static int[] box(String line) {
    Matcher m = BOX.matcher(line);
    assertTrue(m.find(), "no box in " + line);
    return new int[] {
      Integer.parseInt(m.group(1)),
      Integer.parseInt(m.group(2)),
      Integer.parseInt(m.group(3)),
      Integer.parseInt(m.group(4))
    };
  }
}
