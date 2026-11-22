package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.render.Png;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link ComboBox#comboBox(FilterList.Entries, String, ComboBox.State, Runnable)}. */
class ComboBoxEntriesTest {

  @TempDir
  Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  static final class Picker {
    final ComboBox.State state = new ComboBox.State();
    String current = "pinned_table";
    FilterList.Entries entries;

    El view() {
      Theme theme = Theme.DARK;
      return col()
          .wFull()
          .hFull()
          .padding(theme.space().l())
          .fill(theme.surface().window())
          .child(ComboBox.comboBox(entries, current, state, () -> current = null)
              .key("table")
              .w(220));
    }

    App<Picker> app() {
      App<Picker> app = new App<>(this, Picker::view).provide(Theme.CURRENT, picker -> Theme.DARK);
      app.focusRing(Theme.DARK.accent().edge());
      app.textRendering(TextRendering.LINUX);
      return app;
    }
  }

  private List<Path> snap(App<?> app, String steps, String name) throws IOException {
    return Script.run(steps + "\nframe\nsnap " + name, app, out.resolve(name));
  }

  @Test
  void aBackgroundSearchShowsLoadingUntilItsAnswerAndKeepsTheChoiceShown() throws Exception {
    Picker picker = new Picker();
    App<Picker> app = picker.app();
    CountDownLatch release = new CountDownLatch(1);
    picker.entries = FilterList.Entries.background(app, "tables", query -> {
      try {
        release.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return List.of(
          FilterList.entry("orders", () -> picker.current = "orders").group("Tables"),
          FilterList.entry("archived_orders", null).group("Archive"));
    });
    try {
      List<Path> loading =
          snap(app, "size 300 240 scale 2\nframe\npress #table\nframe\nadvance 0.25", "loading");
      String tree = Files.readString(loading.get(1));
      assertTrue(tree.contains("\"Loading…\""), tree);
      Golden.assertMatches(
          "combobox-entries-dark-2x-loading", Png.decode(Files.readAllBytes(loading.get(0))), tree);

      release.countDown();
      tree = Files.readString(snap(app, "await", "found").get(1));
      assertEquals(List.of("orders", "archived_orders"), ComboBoxTest.options(tree));

      // The choice shows closed although the entries found do not hold it.
      tree = Files.readString(snap(app, "key Escape", "closed").get(1));
      assertTrue(ComboBoxTest.line(tree, "#table").contains("label=\"pinned_table\""), tree);

      // Opened again, it waits for the new answer instead of offering the old one.
      tree = Files.readString(snap(app, "press #table", "again").get(1));
      assertEquals(List.of(), ComboBoxTest.options(tree));
      snap(app, "await\nframe\nkey Enter", "chosen");
      assertEquals("orders", picker.current);
      assertFalse(picker.state.isOpen());
    } finally {
      release.countDown();
    }
  }

  @Test
  void theClearButtonEmptiesTheChoiceWithoutOpeningAndTabReachesIt() throws IOException {
    Picker picker = new Picker();
    picker.current = "orders";
    picker.entries = FilterList.Entries.of(List.of(FilterList.entry("orders", () -> {})));
    App<Picker> app = picker.app();

    List<Path> clear = snap(app, "size 300 90 scale 2\nframe\nfocus #table\nkey Tab", "clear");
    String tree = Files.readString(clear.get(1));
    assertTrue(ComboBoxTest.line(tree, "#clear").contains("label=\"Clear\""), tree);
    assertEquals("clear", app.focused().key());
    Golden.assertMatches(
        "combobox-entries-dark-2x-clear", Png.decode(Files.readAllBytes(clear.get(0))), tree);

    tree = Files.readString(snap(app, "key Space\nframe\nadvance 0.3", "space").get(1));
    assertNull(picker.current);
    assertFalse(picker.state.isOpen());
    assertFalse(tree.contains("#clear"), tree);

    picker.current = "orders";
    app.invalidate();
    snap(app, "frame\nadvance 0.3\nframe\npress #clear", "press");
    assertNull(picker.current);
    assertFalse(picker.state.isOpen());
  }
}
