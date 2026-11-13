package com.rethinkj.ui;

import static com.rethinkj.ui.Fields.field;
import static com.rethinkj.ui.Fields.focus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.Fields.Model;
import com.rethinkj.ui.text.TextLibraries;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A multi-line field with a long text: 100 000 lines, and 5 MB. Counters show that each frame
 * shapes the lines in view and a few around them, however long the text; the test prints how long
 * the keys take.
 */
class TextAreaLargeTest {

  private static final int LINES = 100_000;
  // Five rows in view and three more above and below them, for each layout of a frame: a frame
  // lays out once or twice, and the caret's line may be one more.
  private static final int PER_FRAME = 2 * (5 + 2 * 3 + 2) + 2;

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  private static String text(int lines, int width) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < lines; i++) {
      String line = "SELECT c" + i + " FROM t" + (i % 97) + " WHERE id = " + i + ";";
      out.append(line.length() < width ? line + " -".repeat((width - line.length()) / 2) : line);
      if (i + 1 < lines) {
        out.append('\n');
      }
    }
    return out.toString();
  }

  /** Runs a step a number of times, each with a frame; checks what each shapes, returns times. */
  private static double[] steps(App<Model> app, int times, Runnable step) {
    double[] millis = new double[times];
    for (int i = 0; i < times; i++) {
      Lines lines = field(app).editor().lineIndex();
      int before = lines.shaped;
      long start = System.nanoTime();
      step.run();
      app.frame();
      millis[i] = (System.nanoTime() - start) / 1e6;
      int shaped = field(app).editor().lineIndex() == lines ? lines.shaped - before : 0;
      assertTrue(shaped <= PER_FRAME, "a frame shaped " + shaped + " lines");
    }
    return millis;
  }

  private static String median(double[] millis) {
    double[] sorted = millis.clone();
    Arrays.sort(sorted);
    return String.format(Locale.ROOT, "%.2f ms", sorted[sorted.length / 2]);
  }

  @Test
  void aHundredThousandLinesCostAFrameWhatTheLinesInViewCost() {
    for (boolean wraps : new boolean[] {false, true}) {
      String text = text(LINES, 30);
      App<Model> app = Areas.start(text, f -> f.wrapText(wraps), 1);
      focus(app);
      El f = field(app);
      assertTrue(Areas.line(app).contains(wraps ? " lines 1-3/100000 " : " lines 1-5/100000 "),
          Areas.line(app)); // wrapped, a line takes two rows
      assertTrue(f.editor().lineIndex().shaped < 40, "the first frames shaped what they show");

      app.scrollTo(f, 0, (wraps ? 100_000 : 50_000) * 18.0); // wrapped, a line takes two rows
      app.frame();
      app.pointerDown(30, 30, 1, 0);
      app.pointerUp(30, 30);
      app.frame();
      int middle = field(app).editor().lineAndColumn()[0];
      assertTrue(middle > 40_000 && middle < 60_000, "in the middle: line " + middle);
      double[] typing = steps(app, 200, () -> app.textInput("x"));
      assertTrue(app.state().value.contains("x".repeat(200)));
      double[] breaking = steps(app, 20, () -> {
        app.keyDown(new Key("Enter", 0, false));
        app.textInput("y");
      });
      assertEquals(LINES + 20, field(app).editor().lineIndex().count());

      double[] paging = steps(app, 300, () -> app.keyDown(new Key("PageDown", 0, false)));
      int paged = field(app).editor().lineAndColumn()[0];
      // 300 pages of five rows: 1500 lines, or 750 of two rows each.
      assertEquals(middle + 20 + (wraps ? 750 : 1500), paged, "PageDown went on");
      double[] end = steps(app, 1, () -> app.keyDown(new Key("End", Key.CONTROL, false)));
      assertTrue(Areas.line(app).contains(
          wraps ? " lines 100018-100020/100020 " : " lines 100016-100020/100020 "),
          Areas.line(app));
      double[] home = steps(app, 1, () -> app.keyDown(new Key("Home", Key.CONTROL, false)));
      assertTrue(Areas.line(app).contains(wraps ? " lines 1-3/100020 " : " lines 1-5/100020 "),
          Areas.line(app));
      double[] all = steps(app, 1, () -> {
        app.keyDown(new Key("A", Key.CONTROL, false));
        app.keyDown(new Key("Delete", 0, false));
      });
      assertEquals("", app.state().value);
      double[] undo = steps(app, 1, () -> app.keyDown(new Key("Z", Key.CONTROL, false)));
      assertEquals(LINES + 20, field(app).editor().lineIndex().count());
      System.out.println("TextAreaLargeTest, " + LINES + " lines of 30 characters"
          + (wraps ? ", wrapped" : "") + ": a key typed " + median(typing)
          + ", Enter and a key " + median(breaking) + ", PageDown " + median(paging)
          + ", Control+End " + median(end) + ", Control+Home " + median(home)
          + ", Control+A and Delete " + median(all) + ", undo " + median(undo)
          + " (medians, each with its frame)");
    }
  }

  @Test
  void fiveMegabytesCostAKeyTheCopyOfTheString() {
    String text = text(100_000, 52);
    assertTrue(text.length() > 5_000_000, "length " + text.length());
    App<Model> app = Areas.start(text);
    focus(app);
    app.scrollTo(field(app), 0, 50_000 * 18.0);
    app.frame();
    app.pointerDown(30, 30, 1, 0);
    app.pointerUp(30, 30);
    app.frame();
    long[] handler = new long[1];
    double[] copy = new double[200];
    for (int i = 0; i < copy.length; i++) { // what an edit costs before the app and the view
      long start = System.nanoTime();
      String next = text.substring(0, 2_500_000) + "x" + text.substring(2_500_000);
      copy[i] = (System.nanoTime() - start) / 1e6;
      handler[0] += next.length();
    }
    double[] typing = steps(app, 200, () -> app.textInput("x"));
    double[] wheel = steps(app, 200, () -> app.wheel(30, 30, 0, 18));
    System.out.println("TextAreaLargeTest, " + text.length() + " characters: a copy of the"
        + " string with one more character " + median(copy) + ", a key typed with its frame "
        + median(typing) + ", a wheel step " + median(wheel) + " (medians)");
    assertTrue(handler[0] > 0);
  }

  @Test
  void anAppThatKeepsItsStringStillNeedsNoWorkPerFrame() {
    App<Model> app = Areas.start(text(LINES, 30), UnaryOperator.identity(), 2);
    focus(app);
    double[] idle = steps(app, 200, () -> app.wheel(30, 30, 0, 9));
    System.out.println("TextAreaLargeTest, " + LINES + " lines at scale 2: a wheel step "
        + median(idle) + " (median)");
  }
}
