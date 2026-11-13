package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The entries a {@link FilterList} finds on another thread, in a {@link CommandPalette}. */
class FilterListTest {

  @TempDir
  Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  private String run(App<?> app, String steps) throws IOException {
    Script.run(steps + "\nframe\nadvance 1\nframe\nsnap s", app, out);
    return Files.readString(out.resolve("s.tree.txt"));
  }

  /** A finder that answers "two" at once and waits with every other text until it is let go. */
  static final class Finder {
    final CommandPalette.State palette = new CommandPalette.State();
    final CountDownLatch first = new CountDownLatch(1);
    final CountDownLatch last = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final AtomicInteger interrupted = new AtomicInteger();
    App<Finder> app;

    List<FilterList.Entry> find(String text) {
      if (!text.equals("two")) {
        (text.equals("one") ? first : last).countDown();
        try {
          release.await();
        } catch (InterruptedException e) {
          interrupted.incrementAndGet();
        }
      }
      return List.of(FilterList.entry(text, () -> {}));
    }
  }

  @Test
  void aNewTextAndClosingTheListCancelTheSearchOnItsWay() throws Exception {
    Finder f = new Finder();
    f.app = new App<>(
        f,
        x -> col()
            .overlay(CommandPalette.palette(
                Theme.DARK,
                x.palette,
                List.of(),
                List.of(new CommandPalette.Source(
                    "@", "find", FilterList.Entries.background(x.app, "find", x::find))))));
    try {
      f.palette.open("@one");
      String tree = run(f.app, "size 480 320\nframe");
      assertTrue(f.first.await(5, TimeUnit.SECONDS));
      assertTrue(tree.contains("Loading…"), tree);
      tree = run(f.app, "key A control\ntype \"@two\"\nframe\nawait");
      assertEquals(List.of("two"), ComboBoxTest.options(tree), "the first one cancelled");
      run(f.app, "key A control\ntype \"@closed\"");
      assertTrue(f.last.await(5, TimeUnit.SECONDS));
      tree = run(f.app, "key Escape\nframe\nawait");
      assertFalse(f.palette.isOpen(), tree);
      assertFalse(tree.contains("role=listbox"), tree);
      assertEquals(2, f.interrupted.get(), "both waiting searches were interrupted");
    } finally {
      f.palette.close();
      f.release.countDown();
    }
  }
}
