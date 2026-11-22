package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
          .onPress(() -> {});
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
  void copiesTheTextOfAnElementValue() throws IOException {
    State state = new State();
    Script.run("""
        size 360 180 scale 2
        frame
        move role=listitem label="Owner"
        frame
        advance 1
        frame
        press label="Copy Owner"
        frame""", app(state), out);
    assertEquals("shop_admin", state.clipboard.read());
  }

  @Test
  void aTallerElementGrowsItsRowDownwards() throws IOException {
    App<Object> tall = new App<>(
            new Object(),
            s -> col()
                .wFull()
                .hFull()
                .child(Properties.properties(
                    List.of(Properties.property("Notes", el().size(120, 48))), null)))
        .provide(Theme.CURRENT, s -> Theme.DARK);
    tall.textRendering(TextRendering.LINUX);
    Script.run("size 300 100 scale 2\nframe", tall, out);
    String dump = tall.tree().dump();
    int[] row = box(StructureTest.line(dump, "role=listitem label=\"Notes\""));
    int[] name = box(StructureTest.line(dump, "text \"Notes\""));
    assertEquals(48, row[3], dump);
    assertTrue(name[1] - row[1] < Theme.DARK.size().control(), "name beside first line: " + dump);
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
