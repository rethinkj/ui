package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.Fields.field;
import static com.rethinkj.ui.Fields.focus;
import static com.rethinkj.ui.Fields.key;
import static com.rethinkj.ui.Fields.show;
import static com.rethinkj.ui.Fields.start;
import static com.rethinkj.ui.Fields.type;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.Fields.Model;
import com.rethinkj.ui.text.TextLibraries;
import com.rethinkj.ui.text.TextRendering;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Editing a text field with the keyboard and the clipboard, its history, input methods, the app's
 * answers to edits, the blinking caret and the tree dump.
 */
class FieldTest {

  private static final int SHIFT = Key.SHIFT;
  private static final int CONTROL = Key.CONTROL;

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  // Keys

  @Test
  void typingGoesInAtTheCaretAndReplacesTheSelection() {
    App<Model> app = start("");
    focus(app);
    type(app, "hello");
    assertEquals("hello|", show(app));
    assertEquals("hello", app.state().value);
    key(app, "ArrowLeft", SHIFT);
    key(app, "ArrowLeft", SHIFT);
    assertEquals("hel|lo^", show(app));
    type(app, "p");
    assertEquals("help|", show(app));
    assertEquals(List.of("hello", "help"), app.state().edits, "one edit for each text input");
  }

  @Test
  void arrowsHomeAndEndMoveAndShiftSelects() {
    App<Model> app = start("hello world");
    focus(app);
    assertEquals("hello world|", show(app), "the caret starts at the end");
    key(app, "ArrowLeft");
    assertEquals("hello worl|d", show(app));
    key(app, "ArrowLeft", SHIFT);
    key(app, "ArrowLeft", SHIFT);
    assertEquals("hello wo|rl^d", show(app));
    key(app, "ArrowLeft");
    assertEquals("hello wo|rld", show(app), "without Shift: to the start of the selection");
    key(app, "ArrowRight", SHIFT);
    key(app, "ArrowRight");
    assertEquals("hello wor|ld", show(app), "to its end");
    key(app, "Home");
    assertEquals("|hello world", show(app));
    key(app, "End", SHIFT);
    assertEquals("^hello world|", show(app));
    key(app, "Home", CONTROL);
    assertEquals("|hello world", show(app));
    key(app, "End", CONTROL | SHIFT);
    assertEquals("^hello world|", show(app));
    key(app, "ArrowLeft", Key.ALT);
    assertEquals("^hello world|", show(app), "Alt+Left is not the field's");
  }

  @Test
  void controlMovesAndDeletesByWords() {
    App<Model> app = start("SELECT name FROM t");
    focus(app);
    key(app, "ArrowLeft", CONTROL);
    assertEquals("SELECT name FROM |t", show(app));
    key(app, "ArrowLeft", CONTROL);
    key(app, "ArrowLeft", CONTROL | SHIFT);
    assertEquals("SELECT |name ^FROM t", show(app));
    key(app, "ArrowRight", CONTROL);
    assertEquals(Editor.WORD_ENDS ? "SELECT name| FROM t" : "SELECT name |FROM t", show(app));
    key(app, "Home");
    key(app, "ArrowRight", CONTROL);
    key(app, "ArrowRight", CONTROL);
    assertEquals(Editor.WORD_ENDS ? "SELECT name| FROM t" : "SELECT name |FROM t", show(app));

    app = start("SELECT name FROM t");
    focus(app);
    for (int i = 0; i < 7; i++) {
      key(app, "ArrowLeft");
    }
    assertEquals("SELECT name| FROM t", show(app));
    key(app, "Backspace", CONTROL);
    assertEquals("SELECT | FROM t", show(app));
    key(app, "Delete", CONTROL);
    assertEquals(Editor.WORD_ENDS ? "SELECT | t" : "SELECT |FROM t", show(app));
  }

  @Test
  void backspaceAndDeleteRemoveWholeGraphemes() {
    App<Model> app = start("a😀b");
    focus(app);
    key(app, "Backspace");
    assertEquals("a😀|", show(app));
    key(app, "Backspace");
    assertEquals("a|", show(app), "the surrogate pair at once");

    app = start("éx");
    focus(app);
    key(app, "Home");
    key(app, "ArrowRight");
    assertEquals("é|x", show(app), "the letter and its mark are one step");
    key(app, "Home");
    key(app, "Delete");
    assertEquals("|x", show(app));

    app = start("👍🏽!");
    focus(app);
    key(app, "ArrowLeft");
    key(app, "ArrowLeft");
    assertEquals("|👍🏽!", show(app), "the emoji with its modifier is one step");
    key(app, "Delete");
    assertEquals("|!", show(app));

    app = start("ab");
    focus(app);
    key(app, "ArrowLeft", SHIFT);
    key(app, "Backspace");
    assertEquals("a|", show(app), "a selection goes first");
    key(app, "Home");
    key(app, "Backspace");
    key(app, "End");
    key(app, "Delete");
    assertEquals("a|", show(app), "nothing before the start or after the end");
  }

  @Test
  void enterAndEscapeGoOnToTheHandlersAndTypingKeysDoNot() {
    List<String> log = new ArrayList<>();
    App<List<String>> app = new App<>(log, l -> col()
        .onKey(k -> l.add("app " + k.name()))
        .child(El.text("x").editable(s -> {}).key("f").w(100)
            .onKey(k -> {
              l.add("field " + k.name());
              return false;
            })));
    app.textRendering(TextRendering.LINUX);
    app.resize(200, 40, 1);
    app.frame();
    focus(app);
    for (String name : List.of("Enter", "Escape", "A", "Space", "ArrowLeft", "F5", "Numpad1")) {
      key(app, name);
    }
    key(app, "S", CONTROL);
    assertEquals(List.of(
        "field Enter", "app Enter",
        "field Escape", "app Escape",
        "field A",
        "field Space",
        "field ArrowLeft",
        "field F5", "app F5",
        "field Numpad1",
        "field S", "app S"), log);
  }

  @Test
  void tabMovesTheFocusAndSelectsTheFieldItEnters() {
    App<Model> app = start("abc");
    key(app, "Tab");
    assertEquals("f", app.focused().key());
    assertEquals("^abc|", show(app));
    key(app, "Tab");
    assertEquals("other", app.focused().key());
  }

  // Clipboard

  @Test
  void copyCutAndPasteGoThroughTheClipboard() {
    App<Model> app = start("hello world");
    focus(app);
    key(app, "A", CONTROL);
    key(app, "C", CONTROL);
    assertEquals("hello world", app.clipboard().read());
    assertEquals("^hello world|", show(app));
    key(app, "End");
    key(app, "V", CONTROL);
    assertEquals("hello worldhello world|", show(app));

    app = start("abc");
    focus(app);
    key(app, "ArrowLeft", SHIFT);
    key(app, "X", CONTROL);
    assertEquals("ab|", show(app));
    assertEquals("c", app.clipboard().read());
    key(app, "Home", SHIFT);
    key(app, "V", CONTROL);
    assertEquals("c|", show(app), "a paste replaces the selection");

    key(app, "C", CONTROL);
    key(app, "X", CONTROL);
    assertEquals("c", app.clipboard().read(), "without a selection neither copies");
    assertEquals("c|", show(app));
  }

  @Test
  void insertAndDeleteWithModifiersCopyPasteAndCut() {
    App<Model> app = start("abc");
    focus(app);
    key(app, "ArrowLeft", SHIFT);
    key(app, "Insert", CONTROL);
    assertEquals("c", app.clipboard().read());
    key(app, "Delete", SHIFT);
    assertEquals("ab|", show(app));
    key(app, "Insert", SHIFT);
    key(app, "Insert", SHIFT);
    assertEquals("abcc|", show(app));
  }

  @Test
  void pastedLineBreaksBecomeSpaces() {
    App<Model> app = start("");
    focus(app);
    app.clipboard().write("SELECT *\r\nFROM t\nWHERE x\r");
    key(app, "V", CONTROL);
    assertEquals("SELECT * FROM t WHERE x |", show(app));
    type(app, "\n");
    assertEquals("SELECT * FROM t WHERE x  |", show(app), "typed ones too");
  }

  @Test
  void theAppCanUseAClipboardOfItsOwn() {
    List<String> written = new ArrayList<>();
    App<Model> app = start("abc");
    app.clipboard(new Clipboard() {
      @Override
      public String read() {
        return "!";
      }

      @Override
      public void write(String text) {
        written.add(text);
      }
    });
    focus(app);
    key(app, "A", CONTROL);
    key(app, "C", CONTROL);
    key(app, "V", CONTROL);
    assertEquals(List.of("abc"), written);
    assertEquals("!|", show(app));
  }

  // History

  @Test
  void undoAndRedoGoByRunsOfTypingAndDeletingAndByPastes() {
    App<Model> app = start("");
    focus(app);
    for (String letter : List.of("a", "b", "c")) {
      type(app, letter);
    }
    key(app, "Backspace");
    key(app, "Backspace");
    app.clipboard().write("XY");
    key(app, "V", CONTROL);
    assertEquals("aXY|", show(app));

    key(app, "Z", CONTROL);
    assertEquals("a|", show(app), "the paste");
    key(app, "Z", CONTROL);
    assertEquals("abc|", show(app), "both deletions at once");
    key(app, "Z", CONTROL);
    assertEquals("|", show(app), "the typing");
    key(app, "Z", CONTROL);
    assertEquals("|", show(app), "nothing left to undo");
    assertEquals("", app.state().value);

    key(app, "Y", CONTROL);
    assertEquals("abc|", show(app));
    key(app, "Z", CONTROL | SHIFT);
    assertEquals("a|", show(app));
    key(app, "Y", CONTROL);
    assertEquals("aXY|", show(app));
    key(app, "Y", CONTROL);
    assertEquals("aXY|", show(app), "nothing left to redo");

    key(app, "Z", CONTROL);
    type(app, "!");
    key(app, "Y", CONTROL);
    assertEquals("a!|", show(app), "a new edit drops what could be redone");
  }

  @Test
  void movingTheCaretEndsARunAndUndoRestoresTheSelection() {
    App<Model> app = start("");
    focus(app);
    type(app, "a");
    type(app, "b");
    key(app, "ArrowLeft");
    type(app, "c");
    assertEquals("ac|b", show(app));
    key(app, "Z", CONTROL);
    assertEquals("a|b", show(app));
    key(app, "Z", CONTROL);
    assertEquals("|", show(app));

    app = start("hello");
    focus(app);
    key(app, "ArrowLeft", SHIFT);
    key(app, "ArrowLeft", SHIFT);
    type(app, "p");
    key(app, "Z", CONTROL);
    assertEquals("hel|lo^", show(app), "the selection as it was");
  }

  @Test
  void theHistoryIsBounded() {
    App<Model> app = start("");
    focus(app);
    for (int i = 0; i < Editor.HISTORY + 20; i++) {
      type(app, "x");
      key(app, "ArrowLeft"); // each letter a step of its own
      key(app, "ArrowRight");
    }
    for (int i = 0; i < Editor.HISTORY + 20; i++) {
      key(app, "Z", CONTROL);
    }
    assertEquals("x".repeat(20), app.state().value, "the oldest steps are gone");
  }

  // The app's answer

  @Test
  void theFieldShowsWhatTheAppMakesOfAnEdit() {
    App<Model> app = start("12");
    app.state().accept = s -> s.replaceAll("\\D", "");
    focus(app);
    type(app, "a");
    assertEquals("12|", show(app), "a letter at the end, dropped");
    key(app, "ArrowLeft");
    type(app, "x");
    assertEquals("1|2", show(app), "in the middle");
    type(app, "3");
    assertEquals("13|2", show(app));
    app.clipboard().write("4a5");
    key(app, "V", CONTROL);
    assertEquals("1345|2", show(app), "what is left of a paste");

    app = start("");
    app.state().accept = String::toUpperCase;
    focus(app);
    type(app, "ab");
    key(app, "Home");
    type(app, "x");
    assertEquals("X|AB", show(app), "changed throughout");
  }

  @Test
  void anEditTheAppRefusesLeavesTheFieldAsItWas() {
    App<Model> app = start("abc");
    app.state().accept = s -> s.length() <= 3 ? s : null;
    focus(app);
    key(app, "ArrowLeft");
    key(app, "ArrowLeft", SHIFT);
    type(app, "XY");
    assertEquals("a|b^c", show(app), "caret and selection as before");
    assertEquals("abc", app.state().value);
    key(app, "Z", CONTROL);
    assertEquals("a|b^c", show(app), "and no step to undo");
    type(app, "X");
    assertEquals("aX|c", show(app));
  }

  @Test
  void editsBetweenTwoFramesEachGetTheAppsAnswer() {
    // Input that reaches the app between two frames, as from a fast burst of keys or during a
    // slow frame: the letter is refused, and what comes after it still builds on the digits.
    App<Model> app = start("");
    app.state().accept = s -> s.chars().allMatch(Character::isDigit) ? s : null;
    focus(app);
    app.textInput("8");
    app.textInput("a");
    app.textInput("8");
    app.frame();
    assertEquals("88|", show(app));
    assertEquals(List.of("8", "8a", "88"), app.state().edits);

    app.textInput("x");
    app.keyDown(new Key("Backspace"));
    app.textInput("9");
    app.frame();
    assertEquals("89|", show(app), "the Backspace after the refused letter took a digit");
    key(app, "Z", CONTROL);
    assertEquals("8|", show(app));
    key(app, "Z", CONTROL);
    assertEquals("88|", show(app));
  }

  @Test
  void aChangeByTheAppKeepsTheCaretNearItsTextAndEndsTheHistory() {
    App<Model> app = start("");
    focus(app);
    type(app, "world");
    key(app, "Home");
    app.state().value = "hello world"; // say, from a button
    app.frame(); // nothing asked for a rebuild yet
    key(app, "ArrowRight");
    assertEquals("hello w|orld", show(app), "the caret stayed before the w");
    key(app, "Z", CONTROL);
    assertEquals("hello world", app.state().value, "the history was of another text");
  }

  // Limits

  @Test
  void theMaximumLengthCutsWhatIsTypedAtAWholeGrapheme() {
    App<Model> app = start("", f -> f.maxLength(5), 1);
    focus(app);
    type(app, "abcdefg");
    assertEquals("abcde|", show(app));
    type(app, "x");
    assertEquals("abcde|", show(app));
    key(app, "ArrowLeft", SHIFT);
    key(app, "ArrowLeft", SHIFT);
    type(app, "👍🏽!");
    assertEquals("abc👍🏽|", show(app), "two code points take the room of the two selected");

    app = start("", f -> f.maxLength(2), 1);
    focus(app);
    type(app, "a👍🏽");
    assertEquals("a|", show(app), "the emoji's two code points do not fit into one");

    app = start("toolong", f -> f.maxLength(3), 1);
    focus(app);
    type(app, "x");
    assertEquals("toolong|", show(app), "a longer string from the app stays");
    key(app, "Backspace");
    assertEquals("toolon|", show(app));
  }

  @Test
  void aReadOnlyFieldSelectsAndCopiesButDoesNotChange() {
    App<Model> app = start("hello", El::readOnly, 1);
    key(app, "Tab");
    assertEquals("^hello|", show(app));
    key(app, "C", CONTROL);
    assertEquals("hello", app.clipboard().read());
    key(app, "ArrowLeft");
    key(app, "ArrowRight", SHIFT);
    assertEquals("^h|ello", show(app));
    type(app, "x");
    for (String name : List.of("Backspace", "Delete")) {
      key(app, name);
    }
    for (String name : List.of("X", "V", "Z", "Y")) {
      key(app, name, CONTROL);
    }
    app.textComposition("ni", -1);
    app.frame();
    assertEquals("^h|ello", show(app));
    assertEquals(List.of(), app.state().edits);
    assertNull(app.inputArea(), "no text input for a field that takes none");
    assertNotNull(field(app).text().caret(), "it shows the caret, for selecting with keys");
  }

  @Test
  void aDisabledFieldTakesNeitherFocusNorPresses() {
    List<String> log = new ArrayList<>();
    App<List<String>> app = new App<>(log, l -> col().onPress(() -> l.add("parent"))
        .child(El.text("x").editable(null).key("f").w(100).h(20)));
    app.textRendering(TextRendering.LINUX);
    app.resize(200, 40, 1);
    app.frame();
    key(app, "Tab");
    assertNull(app.focused());
    app.pointerDown(20, 10);
    app.pointerUp(20, 10);
    app.frame();
    assertNull(app.focused());
    assertEquals(List.of("parent"), log);
    app.pointerMove(20, 10);
    app.frame();
    assertEquals(Cursor.DEFAULT, app.cursor());
    assertTrue(app.tree().dump().contains("text #f role=textbox \"x\" 0,0 100x20 disabled\n"));
  }

  // Input methods

  @Test
  void aCompositionIsShownButNotPutIntoTheString() {
    App<Model> app = start("ab");
    focus(app);
    app.textComposition("ni", -1);
    app.frame();
    assertEquals("ab", app.state().value);
    assertEquals("ab|", show(app));
    El field = field(app);
    assertEquals(4, field.text().lines().getFirst().glyphs().count(), "a, b, n and i");
    assertNotNull(field.text().composition(), "underlined");
    assertTrue(app.tree().dump().contains("focused caret 2 composing \"ni\"\n"), app.tree().dump());
    assertEquals(field.text().x(4), field.text().caret().x(), "the caret after it");

    app.textComposition("nihon", 2);
    app.frame();
    assertEquals(field(app).text().x(4), field(app).text().caret().x(), "at its cursor");
    key(app, "Backspace");
    assertEquals("ab", app.state().value, "the input method has the keys");

    app.textInput("日本"); // committed
    app.textComposition("", -1);
    app.frame();
    assertEquals("ab日本|", show(app));
    assertNull(field(app).text().composition());
    assertFalse(app.tree().dump().contains("composing"));
  }

  @Test
  void aCancelledCompositionLeavesTheSelectionAsItWas() {
    App<Model> app = start("abc");
    focus(app);
    key(app, "Home", SHIFT);
    app.textComposition("x", -1);
    app.frame();
    assertEquals(1, field(app).text().lines().getFirst().glyphs().count(), "in place of abc");
    assertTrue(field(app).text().selection().isEmpty());
    app.textComposition("", -1);
    app.frame();
    assertEquals("|abc^", show(app));
    assertEquals(3, field(app).text().lines().getFirst().glyphs().count());
    assertEquals(List.of(), app.state().edits);

    app.textComposition("x", -1);
    app.frame();
    key(app, "Tab"); // the focus moves on: the composition ends
    assertEquals("abc", field(app).text().string());
    assertEquals(3, field(app).text().lines().getFirst().glyphs().count());
  }

  // The caret's blinking

  @Test
  void theCaretBlinksOnTheAppsClockAndOnlyThatDrawsFrames() {
    App<Model> app = start("abc");
    assertEquals(Double.POSITIVE_INFINITY, app.nextChange(), "nothing blinks without focus");
    focus(app);
    assertNotNull(field(app).text().caret());
    assertEquals(App.BLINK, app.nextChange());

    List<Double> drawn = new ArrayList<>();
    for (int i = 1; i <= 16; i++) {
      app.advance(0.125);
      if (app.frame()) {
        drawn.add(app.time());
      }
    }
    assertEquals(List.of(0.5, 1.0, 1.5, 2.0), drawn, "a frame for each change, none between");
    assertNotNull(field(app).text().caret(), "on at 2 seconds");
    app.advance(0.625);
    app.frame();
    assertNull(field(app).text().caret(), "off at 2.625");
    assertEquals(3.0, app.nextChange());

    type(app, "d");
    assertNotNull(field(app).text().caret(), "on again after a change");
    assertEquals(3.125, app.nextChange());

    key(app, "Tab");
    assertNull(field(app).text().caret());
    assertEquals(Double.POSITIVE_INFINITY, app.nextChange());
  }

  @Test
  void anInactiveWindowShowsNoCaretAndDrawsNothingUntilItIsActiveAgain() {
    App<Model> app = start("abc");
    focus(app);
    key(app, "End");
    key(app, "ArrowLeft", SHIFT); // a selection, which stays
    String before = app.tree().dump();
    assertTrue(before.contains("focused caret 2 anchor 3"), before);
    assertNotNull(field(app).text().caret());
    assertEquals(1, field(app).text().selection().size());

    app.windowActive(false);
    app.advance(0.2);
    assertTrue(app.frame(), "the caret goes off at once");
    assertNull(field(app).text().caret());
    assertEquals(1, field(app).text().selection().size(), "the selection stays");
    assertSame(field(app), app.focused(), "and so does the focus");
    assertEquals(Double.POSITIVE_INFINITY, app.nextChange(), "nothing blinks");
    for (int i = 0; i < 10; i++) {
      app.advance(0.3);
      assertFalse(app.frame(), "an idle inactive app draws nothing");
      assertNull(field(app).text().caret());
    }

    app.windowActive(true);
    app.advance(0.1); // 3.3 seconds: off in the old rhythm, but the caret starts afresh
    assertTrue(app.frame());
    assertNotNull(field(app).text().caret(), "on at once when the window is active again");
    assertEquals(app.time() + App.BLINK, app.nextChange());
    assertEquals(before, app.tree().dump(), "focus, caret and selection as they were");
  }

  @Test
  void anInactiveWindowsFieldKeepsItsScrollCaretAndSelectionAndTakesText() {
    App<Model> app = start("SELECT name FROM customers WHERE id = 1"); // wider than the field
    focus(app);
    key(app, "End");
    key(app, "ArrowLeft", SHIFT);
    float scroll = field(app).text().scroll();
    assertTrue(scroll > 0, "scrolled to the end");
    assertEquals("SELECT name FROM customers WHERE id = |1^", show(app));

    app.windowActive(false);
    app.frame();
    assertEquals(scroll, field(app).text().scroll(), "the scroll stays");
    assertEquals("SELECT name FROM customers WHERE id = |1^", show(app), "caret and selection too");
    assertEquals(1, field(app).text().selection().size());
    assertNull(field(app).text().caret(), "only the caret is not drawn");
    assertSame(field(app), app.focused());

    app.textInput("2"); // text that reaches the app while its window is inactive
    app.frame();
    assertEquals("SELECT name FROM customers WHERE id = 2", app.state().value);
    assertEquals("SELECT name FROM customers WHERE id = 2|", show(app));
    assertNull(field(app).text().caret(), "still none while inactive");
    assertEquals(Double.POSITIVE_INFINITY, app.nextChange(), "nothing blinks");
    float typed = field(app).text().scroll();
    assertTrue(typed >= scroll, "the caret stays in view");

    app.windowActive(true);
    app.textInput("3"); // in the frame that activates it
    app.frame();
    assertEquals("SELECT name FROM customers WHERE id = 23|", show(app));
    assertNotNull(field(app).text().caret(), "on again");
    assertTrue(field(app).text().scroll() >= typed);
    assertEquals(app.time() + App.BLINK, app.nextChange());
  }

  @Test
  void aFieldThatFadesOutHasNoCaretSelectionOrCompositionAndNoFocus() {
    boolean[] hidden = {false};
    App<Model> app = start("hello", f -> hidden[0] ? el().size(0, 0)
        : f.motion(com.rethinkj.ui.motion.Spring.SNAP), 1);
    focus(app);
    key(app, "ArrowLeft", SHIFT);
    El field = field(app);
    assertFalse(field.text().selection().isEmpty());
    app.textComposition("ni", -1);
    app.frame();
    assertNotNull(field(app).text().composition());

    hidden[0] = true; // the view leaves the field out: it fades out where it was
    app.resize(200, 80, 1);
    app.frame();
    field = field(app); // back where it was, before what the view put there
    assertEquals("f", field.key());
    assertTrue(field.isExiting(), app.tree().dump());
    assertNull(field.text().caret());
    assertEquals(List.of(), field.text().selection());
    assertNull(field.text().composition());
    assertEquals(5, field.text().lines().getFirst().glyphs().count(), "\"hello\" without \"ni\"");
    assertNull(app.focused());
    assertFalse(field.dump().contains("focused"), field.dump());
    assertEquals(Double.POSITIVE_INFINITY, app.nextChange(), "nothing blinks");
    app.advance(App.BLINK);
    app.frame();
    assertNull(field.text().caret());
  }

  // What the window needs

  @Test
  void theInputAreaIsTheFocusedFieldWithItsCaret() {
    App<Model> app = start("abc");
    assertNull(app.inputArea());
    focus(app);
    App.InputArea area = app.inputArea();
    El field = field(app);
    assertEquals(new App.InputArea(10, 10, 100, 24, 4 + field.text().x(3)), area);
    key(app, "Tab");
    assertNull(app.inputArea(), "a focusable box takes no text");

    App<Object> plain = new App<>(new Object(), s -> el().size(10, 10).focusable().onText(t -> {}));
    plain.resize(20, 20, 1);
    plain.frame();
    plain.focus(plain.tree());
    plain.frame();
    assertEquals(new App.InputArea(0, 0, 10, 10, 0), plain.inputArea(), "but one with onText");
  }

  @Test
  void theDumpShowsTheValueCaretAndSelectionOfTheFocusedField() {
    App<Model> app = start("hello");
    assertTrue(app.tree().dump().contains("  text #f role=textbox \"hello\" 10,10 100x24\n"));
    focus(app);
    assertTrue(app.tree().dump().contains("\"hello\" 10,10 100x24 focused caret 5\n"));
    key(app, "ArrowLeft", SHIFT);
    assertTrue(app.tree().dump().contains("\"hello\" 10,10 100x24 focused caret 4 anchor 5\n"));
  }
}
