package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.Fields.field;
import static com.rethinkj.ui.Fields.focus;
import static com.rethinkj.ui.Fields.key;
import static com.rethinkj.ui.Fields.show;
import static com.rethinkj.ui.Fields.start;
import static com.rethinkj.ui.Fields.type;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.Fields.Model;
import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.FontProbe;
import com.rethinkj.ui.text.GlyphRun;
import com.rethinkj.ui.text.TextLibraries;
import com.rethinkj.ui.text.TextRendering;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A masked field, as for a password: it edits the app's string but shows, copies, dumps and
 * shapes only its mask, one character per grapheme.
 */
class MaskedFieldTest {

  private static final int SHIFT = Key.SHIFT;
  private static final int CONTROL = Key.CONTROL;
  private static final String SECRET = "hunter2";
  private static final String DOTS = "\u2022".repeat(SECRET.length());

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  private static App<Model> masked(String value) {
    return start(value, El::masked, 1);
  }

  /** Builds the view again after the test changed what it reads, as a handler's change would. */
  private static void rebuild(App<?> app) {
    app.resize(200, 80, 1);
    app.frame();
  }

  // Editing

  @Test
  void keysEditTheStringWhileTheFieldShowsOnlyBullets() {
    App<Model> app = masked("");
    focus(app);
    type(app, SECRET);
    assertEquals(SECRET, app.state().value);
    assertEquals(SECRET + "|", show(app));
    Text text = field(app).text();
    assertEquals(DOTS, text.string());
    GlyphRun run = text.lines().getFirst().glyphs();
    assertEquals(SECRET.length(), run.count());
    int bullet = Font.sans().shape("\u2022", 14, 1).glyph(0);
    for (int i = 0; i < run.count(); i++) {
      assertEquals(bullet, run.glyph(i), "glyph " + i);
    }

    key(app, "Backspace");
    assertEquals("hunter|", show(app));
    key(app, "ArrowLeft", SHIFT);
    key(app, "ArrowLeft", SHIFT);
    assertEquals("hunt|er^", show(app));
    assertEquals(run.pen(4), field(app).text().selection().getFirst().x(), "from bullet 4");
    key(app, "Delete");
    assertEquals("hunt|", show(app));
    key(app, "Home");
    key(app, "End", SHIFT);
    assertEquals("^hunt|", show(app));
  }

  @Test
  void eachGraphemeIsOneBulletAndOneStepOfTheCaret() {
    String coder = "\uD83D\uDC69\u200D\uD83D\uDCBB"; // woman technologist, a ZWJ sequence
    String value = "a" + coder + "e\u0301b"; // a, the sequence, e with a combining acute, b
    App<Model> app = masked(value);
    Text text = field(app).text();
    assertEquals("\u2022".repeat(4), text.string());
    GlyphRun run = text.lines().getFirst().glyphs();
    assertEquals(4, run.count());
    int afterCoder = 1 + coder.length();
    assertEquals(run.pen(1), text.x(1));
    assertEquals(run.pen(2), text.x(afterCoder));
    assertEquals(run.pen(3), text.x(afterCoder + 2));
    assertEquals(run.width(), text.x(value.length()));

    focus(app);
    key(app, "ArrowLeft");
    key(app, "ArrowLeft");
    assertEquals("a" + coder + "|e\u0301b", show(app), "over the accented e at once");
    key(app, "Backspace");
    assertEquals("a|e\u0301b", show(app), "the whole sequence");
    assertEquals(run.pen(1), field(app).text().caret().x());

    // A press between two bullets lands between the two graphemes they stand for.
    app.pointerDown(14 + run.pen(2) + 1, 22);
    app.pointerUp(14 + run.pen(2) + 1, 22);
    app.frame();
    assertEquals("ae\u0301|b", show(app));
  }

  @Test
  void copyAndCutLeaveTheClipboardAloneAndPasteWorks() {
    App<Model> app = masked(SECRET);
    app.clipboard().write("before");
    focus(app);
    key(app, "A", CONTROL);
    key(app, "C", CONTROL);
    key(app, "X", CONTROL);
    key(app, "Insert", CONTROL);
    key(app, "Delete", SHIFT);
    assertEquals("before", app.clipboard().read());
    assertEquals("^" + SECRET + "|", show(app), "neither cut removed the selection");
    assertEquals(List.of(), app.state().edits);

    key(app, "V", CONTROL);
    assertEquals("before|", show(app));
    key(app, "Home");
    key(app, "Insert", SHIFT);
    assertEquals("before|before", show(app));
  }

  @Test
  void wordKeysAndADoublePressTakeTheWholeStringAsOneWord() {
    App<Model> app = masked("correct horse battery");
    focus(app);
    key(app, "ArrowLeft", CONTROL);
    assertEquals("|correct horse battery", show(app));
    key(app, "ArrowRight", CONTROL);
    assertEquals("correct horse battery|", show(app));
    key(app, "ArrowLeft", CONTROL | SHIFT);
    assertEquals("|correct horse battery^", show(app));
    key(app, "ArrowRight");
    for (int i = 0; i < 8; i++) {
      key(app, "ArrowLeft");
    }
    key(app, "Backspace", CONTROL);
    assertEquals("| battery", show(app), "to the start");
    key(app, "End");
    key(app, "ArrowLeft");
    key(app, "ArrowLeft");
    key(app, "Delete", CONTROL);
    assertEquals(" batte|", show(app), "to the end");

    app.state().value = "correct horse";
    rebuild(app);
    float x = 14 + field(app).text().x(2);
    app.pointerDown(x, 22, 1, 0);
    app.pointerUp(x, 22);
    app.pointerDown(x, 22, 2, 0);
    app.pointerUp(x, 22);
    app.frame();
    assertEquals("^correct horse|", show(app), "a double press selects everything");
  }

  @Test
  void undoRedoAndTheMaximumLengthWorkAsInAnyField() {
    App<Model> app = start("", f -> f.masked().maxLength(5), 1);
    focus(app);
    type(app, "abc");
    key(app, "ArrowLeft");
    type(app, "\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00"); // three emoji, two code points left
    assertEquals("ab\uD83D\uDE00\uD83D\uDE00|c", show(app), "cut at a whole grapheme");
    assertEquals("\u2022".repeat(5), field(app).text().string());
    key(app, "Z", CONTROL);
    assertEquals("ab|c", show(app));
    key(app, "Z", CONTROL);
    assertEquals("|", show(app));
    key(app, "Y", CONTROL);
    key(app, "Z", CONTROL | SHIFT);
    assertEquals("ab\uD83D\uDE00\uD83D\uDE00|c", show(app));
  }

  @Test
  void aCompositionIsMaskedToo() {
    App<Model> app = masked("ab");
    focus(app);
    app.textComposition("ni", 1);
    app.frame();
    Text text = field(app).text();
    assertEquals(4, text.lines().getFirst().glyphs().count(), "two bullets and two composing");
    assertEquals(text.x(3), text.caret().x(), "at the input method's cursor");
    String dump = app.tree().dump();
    assertTrue(dump.contains(" password focused caret 2 composing \"\u2022\u2022\"\n"), dump);
    assertFalse(dump.contains("ni"), dump);
  }

  @Test
  void theFieldTellsTheWindowItTakesAPassword() {
    boolean[] masked = {true};
    App<Model> app = start(SECRET, f -> f.masked(masked[0]), 1);
    focus(app);
    assertTrue(app.inputArea().password());
    masked[0] = false;
    rebuild(app);
    assertFalse(app.inputArea().password());
  }

  // Nothing leaks

  @Test
  void theDumpSelectorsAndScriptErrorsSeeOnlyTheMask(@TempDir Path out) throws Exception {
    App<Model> app = masked("");
    Script.run("size 200 80\nframe\npress #f\ntype \"" + SECRET + "\"\nkey ArrowLeft shift\n"
        + "frame\nsnap typed\n", app, out);
    assertEquals(SECRET, app.state().value);
    String dump = app.tree().dump();
    assertTrue(dump.contains("text #f role=textbox \"" + DOTS + "\" 10,10 100x24 password"
        + " focused caret 6 anchor 7\n"), dump);
    try (Stream<Path> files = Files.list(out)) {
      for (Path file : files.toList()) {
        assertFalse(Files.readString(file, java.nio.charset.StandardCharsets.ISO_8859_1)
            .contains(SECRET), file.toString());
      }
    }

    Script.run("size 200 80\nframe\npress role=textbox password\npress text=\"" + DOTS + "\"\n"
        + "frame\n", app, out);
    String[] failing = {
        "press #nowhere\n", // lists the texts there are
        "press role=textbox disabled\n", // lists the lines of the textboxes
        "press role=textbox label=\"x\"\n",
        "press text=\"" + SECRET + "\"\n", // its value finds nothing
    };
    for (String step : failing) {
      Script.Failure failure = assertThrows(Script.Failure.class,
          () -> Script.run("size 200 80\nframe\n" + step, app, out));
      // Without the step, which quotes what the script says.
      String message = failure.getMessage().replace(step.strip(), "")
          .replace(step.strip().substring("press ".length()), "");
      assertFalse(message.contains(SECRET), message);
      assertTrue(message.contains(DOTS), message);
    }
  }

  @Test
  void theFontsShapeOnlyTheMask() throws Exception {
    Font font = FontProbe.inter(); // with a cache of its own, which nothing else fills
    try {
      boolean[] masked = {true};
      App<Model> app = start("", f -> f.font(font).masked(masked[0]), 2);
      focus(app);
      type(app, SECRET);
      app.textComposition("zebra", -1);
      app.frame();
      app.textComposition("", -1);
      key(app, "A", CONTROL);
      app.resize(240, 80, 1.5f);
      app.frame();
      assertFalse(FontProbe.shaped(font, SECRET));
      assertFalse(FontProbe.shaped(font, "zebra"));
      assertTrue(FontProbe.shaped(font, DOTS), "the mask is");

      masked[0] = false; // shown: now its string is shaped, as any field's
      app.resize(240, 80, 1.5f);
      app.frame();
      assertTrue(FontProbe.shaped(font, SECRET));
    } finally {
      font.close();
    }
  }

  // Showing the string

  @Test
  void showingAndHidingKeepCaretSelectionScrollAndHistory() {
    boolean[] masked = {true};
    App<Model> app = start("", f -> f.masked(masked[0]), 1);
    focus(app);
    type(app, "open");
    key(app, "End"); // ends the run of typing: two steps of the history
    type(app, " sesame please");
    key(app, "ArrowLeft", SHIFT);
    key(app, "ArrowLeft", SHIFT);
    assertEquals("open sesame plea|se^", show(app));
    assertTrue(field(app).text().scroll() > 0, "18 bullets are wider than the field");

    masked[0] = false;
    rebuild(app);
    assertEquals("open sesame plea|se^", show(app));
    String dump = app.tree().dump();
    assertTrue(dump.contains("\"open sesame please\" 10,10 100x24 focused caret 16 anchor 18\n"),
        dump);
    Text text = field(app).text();
    float caret = text.caret().x() - text.scroll();
    assertTrue(caret > 0 && caret < 92, "the caret is still in view: " + caret);
    key(app, "Z", CONTROL);
    assertEquals("open|", show(app), "the history from before");

    masked[0] = true;
    rebuild(app);
    key(app, "Y", CONTROL);
    assertEquals("open sesame plea|se^", show(app), "with the selection undo left");
    assertEquals("\u2022".repeat(18), field(app).text().string());
  }

  @Test
  void theDefaultFontsHaveTheBullet() {
    for (Font font : List.of(Font.sans(), Font.sansBold(), Font.mono(), Font.monoBold())) {
      GlyphRun run = font.shape("\u2022", 13, 1);
      assertTrue(run.glyph(0) != 0 && run.width() > 0, font.toString());
    }
  }

  @Test
  void aMaskCharacterOfItsOwn() {
    App<Model> app = start("abc", f -> f.masked().maskCharacter('*'), 1);
    assertEquals("***", field(app).text().string());
    El field = El.text("").editable(s -> {});
    assertThrows(IllegalArgumentException.class, () -> field.maskCharacter(' '));
    assertThrows(IllegalArgumentException.class, () -> field.maskCharacter(0x301), "a mark");
    assertThrows(IllegalArgumentException.class, () -> field.maskCharacter(0x1F1E9), "a flag's half");
    assertThrows(IllegalStateException.class, () -> El.text("x").masked(), "only for fields");
  }

  // In rows

  @Test
  void aMaskedFieldInARowOfAVirtualList() {
    Map<Integer, String> values = new HashMap<>();
    App<Map<Integer, String>> app = new App<>(values, v -> col().wFull().hFull().children(
        col().key("rows").grow(1).rows(1000, 30, i ->
            El.text(v.getOrDefault(i, "")).editable(s -> v.put(i, s)).masked().key("pin-" + i)
                .h(30).padding(0, 4))));
    app.textRendering(TextRendering.LINUX);
    app.resize(200, 120, 1);
    app.frame();
    app.pointerDown(50, 75);
    app.pointerUp(50, 75);
    app.frame();
    app.textInput(SECRET);
    app.frame();
    assertEquals(Map.of(2, SECRET), values);
    String dump = app.tree().dump();
    assertTrue(dump.contains("text #pin-2 role=textbox \"" + DOTS + "\" 0,60 200x30 password"
        + " pos=3/1000 focused caret 7\n"), dump);
    assertFalse(dump.contains(SECRET), dump);

    app.scrollToRow(app.tree().children().getFirst(), 500);
    app.frame();
    app.scrollToRow(app.tree().children().getFirst(), 0);
    app.frame();
    app.textInput("!");
    app.frame();
    assertEquals(SECRET + "!", values.get(2), "the focus and the caret came back with the row");
  }
}
