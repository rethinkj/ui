package com.rethinkj.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.render.Png;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The workbench's Rename dialog ({@link Bench}): modal over a scrim, the focus in its field, Tab
 * around inside it, Escape and a press on the scrim dismiss it, and the focus goes back to the
 * button that opened it; stored images in both themes at scales 1 and 2.
 */
class DialogTest {

  @TempDir
  Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  /** Runs these steps of a script in the workbench's window of 360 by 240. */
  private void run(App<?> app, String steps) throws IOException {
    Script.run("size 360 240\n" + steps, app, out);
  }

  @ParameterizedTest(name = "{0} at {1}x")
  @CsvSource({"dark, 1", "dark, 2", "light, 1", "light, 2"})
  void opensWithTheFocusInsideKeepsItThereAndGivesItBack(String theme, int scale)
      throws IOException {
    App<Bench> app = Bench.assertScript("dialog", theme, scale, out);
    String open = Bench.tree(out, "dialog", theme, scale, "open");
    assertTrue(open.contains("\noverlay center modal scrim on "), open);
    assertTrue(open.contains(" role=dialog label=\"Rename table\" "), open);
    assertTrue(open.contains(" \"orders\" ") && open.contains(" focused caret "), open);
    String cycled = Bench.tree(out, "dialog", theme, scale, "cycled");
    assertTrue(cycled.contains("\"orders_2026\""), cycled);
    assertTrue(cycled.contains(" focused caret 11 anchor 0\n"), "Tab came around: " + cycled);
    String closed = Bench.tree(out, "dialog", theme, scale, "closed");
    assertFalse(closed.contains("overlay"), closed);
    assertTrue(closed.contains("label=\"Rename…\" ") && closed.contains(" focused\n"), closed);
    assertEquals("rename", app.focused().key());
    assertEquals("orders", app.state().name, "dismissed: nothing renamed");
  }

  @Test
  void theScrimDismissesAndRenameRenames() throws IOException {
    Bench bench = new Bench(Theme.DARK);
    App<Bench> app = bench.app();
    run(app, "frame\npress #rename\nframe\npress 10,230\nframe\n");
    assertFalse(bench.renaming, "a press on the scrim dismisses it");
    assertEquals("Ready", bench.status, "and presses nothing under it");
    run(
        app,
        "press #rename\nframe\nadvance 0.25\nframe\nkey A control\ntype \"order_lines\"\n"
            + "press #ok\nframe\n");
    assertFalse(bench.renaming);
    assertEquals("order_lines", bench.name);
  }

  /** A dialog with a field, a text area, Cancel and OK, OK the default while the name is set. */
  private static final class Ask {
    String name = "orders";
    String note = "";
    final List<String> log = new ArrayList<>();

    El view() {
      Theme t = Theme.DARK;
      El ok = Button.button(
              t, Button.Variant.PRIMARY, "OK", name.isBlank() ? null : () -> log.add("ok"))
          .key("ok");
      return El.col()
          .wFull()
          .hFull()
          .overlay(Dialog.dialog(
              t,
              "Ask",
              El.col()
                  .children(
                      TextField.textField(t, name, "Name", v -> name = v).key("name"),
                      TextField.textArea(t, note, "Note", v -> note = v)
                          .key("note")
                          .h(60)),
              List.of(
                  Button.button(t, Button.Variant.SECONDARY, "Cancel", () -> log.add("cancel"))
                      .key("cancel"),
                  ok),
              ok,
              () -> log.add("dismiss")));
    }
  }

  @Test
  void enterInAFieldPressesTheDefaultButtonAndOnAButtonThatButton() throws IOException {
    Ask ask = new Ask();
    App<Ask> app = new App<>(ask, Ask::view);
    run(app, "frame\nadvance 0.25\nframe\nkey Enter\nframe\n");
    assertEquals("name", app.focused().key(), "the dialog opened with the focus in its field");
    assertEquals(List.of("ok"), ask.log, "Enter in the field pressed OK");

    run(app, "focus #cancel\nkey Enter\nframe\n");
    assertEquals(List.of("ok", "cancel"), ask.log, "Enter on Cancel pressed Cancel");

    run(app, "focus #note\nkey Enter\nframe\n");
    assertEquals("\n", ask.note, "the text area keeps Enter for a line break");
    assertEquals(List.of("ok", "cancel"), ask.log);

    run(app, "focus #name\nframe\n");
    app.keyDown(new Key("Enter", 0, true));
    app.frame();
    assertEquals(List.of("ok", "cancel"), ask.log, "a repeat presses nothing");

    run(app, "key A control\nkey Backspace\nframe\nkey Enter\nframe\n");
    assertEquals("", ask.name);
    assertEquals(List.of("ok", "cancel"), ask.log, "a disabled default is not pressed");
  }

  /** A dialog and, over it, a question, each with one button. */
  private static El stacked(List<String> log, String secondKey) {
    Theme t = Theme.DARK;
    El question = Dialog.dialog(
        t,
        "Discard changes?",
        El.col(),
        List.of(Button.button(t, Button.Variant.PRIMARY, "Discard", () -> log.add("discard"))
            .key("discard-button")),
        () -> log.add("keep"));
    return El.col()
        .wFull()
        .hFull()
        .overlay(Dialog.dialog(
            t,
            "Edit",
            El.col(),
            List.of(Button.button(t, Button.Variant.SECONDARY, "Close", () -> log.add("close"))
                .key("close")),
            () -> log.add("dismiss")))
        .overlay(secondKey == null ? question : question.key(secondKey));
  }

  @Test
  void aQuestionOverADialogNeedsAKeyOfItsOwn() throws IOException {
    List<String> log = new ArrayList<>();
    App<List<String>> app = new App<>(log, l -> stacked(l, "discard"));
    run(app, "frame\nadvance 0.25\nframe\nkey Escape\nframe\n");
    assertEquals(List.of("keep"), log, "Escape went to the question on top, not to the dialog");
    assertEquals("discard-button", app.focused().key());
    String dump = app.tree().dump();
    assertTrue(dump.indexOf("label=\"Edit\"") < dump.indexOf("label=\"Discard changes?\""), dump);

    App<List<String>> same = new App<>(new ArrayList<>(), l -> stacked(l, null));
    Script.Failure refused = assertThrows(Script.Failure.class, () -> run(same, "frame\n"));
    assertTrue(refused.getMessage().contains("another with key(...)"), refused.getMessage());
  }

  @Test
  void aDialogWithoutButtonsHasNoRowForThem() throws IOException {
    Theme t = Theme.DARK;
    App<Object> app = new App<>(
        new Object(),
        s -> El.col()
            .wFull()
            .hFull()
            .overlay(
                Dialog.dialog(t, "Note", El.el().key("body").size(120, 40), List.of(), () -> {})));
    run(app, "frame\nadvance 0.25\nframe\n");
    String dump = app.tree().dump();
    float[] dialog = StructureTest.rect(StructureTest.line(dump, "#dialog"));
    float[] body = StructureTest.rect(StructureTest.line(dump, "#body"));
    // Below the content only the dialog's padding and its border: no empty row, no gap.
    assertEquals(
        t.space().xl() + t.size().stroke(),
        dialog[1] + dialog[3] - (body[1] + body[3]),
        0.01,
        dump);
  }

  @Test
  void theDefaultButtonIsOneOfTheButtons() {
    Theme t = Theme.DARK;
    El ok = Button.button(t, Button.Variant.PRIMARY, "OK", () -> {});
    El other = Button.button(t, Button.Variant.PRIMARY, "OK", () -> {});
    assertThrows(
        IllegalArgumentException.class,
        () -> Dialog.dialog(t, "Ask", El.col(), List.of(ok), other, () -> {}));
  }

  /** A connection's dialog with every option: a colour mark, a badge and Close, Test at the start. */
  private static El head(Theme t, List<String> log, String title) {
    El save =
        Button.button(t, Button.Variant.PRIMARY, "Save", () -> log.add("save")).key("save");
    return El.col()
        .wFull()
        .hFull()
        .overlay(Dialog.dialog(
            t,
            title,
            El.col()
                .gap(t.space().m())
                .children(
                    TextField.textField(t, "prod-eu", "Name", v -> {}).key("name"),
                    TextField.textField(t, "db.example.com", "Host", v -> {}).key("host")),
            List.of(
                Button.button(t, Button.Variant.SECONDARY, "Cancel", () -> log.add("cancel"))
                    .key("cancel"),
                save),
            () -> log.add("dismiss"),
            Dialog.options()
                .leading(HuePicker.swatch(t, Theme.Hue.RED).key("mark"))
                .trailing(
                    Badge.badge(t, "Read only").key("badge"),
                    Button.icon(
                            t,
                            Button.Variant.GHOST,
                            Symbol.DISMISS,
                            "Close",
                            () -> log.add("close"))
                        .key("close"))
                .start(Button.button(
                        t, Button.Variant.SECONDARY, "Test connection", () -> log.add("test"))
                    .key("test"))
                .defaultButton(save)));
  }

  @ParameterizedTest(name = "{0} at {1}x")
  @CsvSource({"dark, 1", "dark, 2", "light, 1", "light, 2"})
  void theHeadHasAMarkABadgeAndCloseAndTheButtonsAStart(String theme, int scale)
      throws IOException {
    Theme t = theme.equals("dark") ? Theme.DARK : Theme.LIGHT;
    List<String> log = new ArrayList<>();
    App<List<String>> app = new App<>(log, l -> head(t, l, "Edit connection"));
    String name = "dialog-head-" + theme + "-" + scale + "x";
    Script.run(
        "size 480 240 scale " + scale + "\nframe\nadvance 0.25\nframe\nsnap " + name + "\n",
        app,
        out);
    String tree = Files.readString(out.resolve(name + ".tree.txt"));
    Golden.assertMatches(name, Png.decode(Files.readAllBytes(out.resolve(name + ".png"))), tree);

    String heading = StructureTest.line(tree, "role=heading");
    assertTrue(heading.contains("label=\"Edit connection\"") && heading.contains("level=2"), tree);
    assertTrue(tree.contains(" role=dialog label=\"Edit connection\" "), tree);
    float[] mark = StructureTest.rect(StructureTest.line(tree, "#mark "));
    float[] title = StructureTest.rect(heading);
    float[] close = StructureTest.rect(StructureTest.line(tree, "#close "));
    float[] dialog = StructureTest.rect(StructureTest.line(tree, "#dialog "));
    assertEquals(title[0], mark[0] + mark[2] + t.space().m(), 0.01, "the mark before the title");
    assertEquals(
        title[1] + title[3] / 2, mark[1] + mark[3] / 2, 0.5, "centred on the title's line");
    float end = dialog[0] + dialog[2] - t.space().xl() - t.size().stroke();
    assertEquals(end, close[0] + close[2], 0.01, "Close at the head's end: " + tree);
    float[] test = StructureTest.rect(StructureTest.line(tree, "#test "));
    float[] save = StructureTest.rect(StructureTest.line(tree, "#save "));
    assertEquals(dialog[0] + t.space().xl() + t.size().stroke(), test[0], 0.01, "Test at the left");
    assertEquals(end, save[0] + save[2], 0.01, "Save at the right");
    assertEquals(test[1], save[1], 0.01, "in one row");
    assertEquals("name", app.focused().key(), "the dialog opens in its content, not on Close");
  }

  @Test
  void tabGoesThroughTheContentAndTheButtonsAndThenTheHead() throws IOException {
    List<String> log = new ArrayList<>();
    App<List<String>> app = new App<>(log, l -> head(Theme.DARK, l, "Edit connection"));
    run(app, "frame\nadvance 0.25\nframe\n");
    List<String> order = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      order.add(app.focused().key());
      run(app, "key Tab\nframe\n");
    }
    assertEquals(List.of("name", "host", "test", "cancel", "save", "close"), order);
    assertEquals("name", app.focused().key(), "and around");
    run(app, "key Enter\nframe\npress #close\nframe\n");
    assertEquals(List.of("save", "close"), log);
  }

  @Test
  void aDialogWithNothingToFocusInItsContentOpensOnItsFirstButton() throws IOException {
    Theme t = Theme.DARK;
    App<Object> app = new App<>(
        new Object(),
        s -> El.col()
            .wFull()
            .hFull()
            .overlay(Dialog.dialog(
                t,
                "Note",
                Label.label(t, "Nothing to type here."),
                List.of(
                    Button.button(t, Button.Variant.SECONDARY, "OK", () -> {}).key("ok")),
                () -> {},
                Dialog.options()
                    .trailing(
                        Button.icon(t, Button.Variant.GHOST, Symbol.DISMISS, "Close", () -> {})
                            .key("close")))));
    run(app, "frame\nadvance 0.25\nframe\n");
    assertEquals("ok", app.focused().key());
  }

  @Test
  void aLongTitleEndsInAnEllipsisWithTheWholeInATooltip() throws IOException {
    List<String> log = new ArrayList<>();
    String title = "Edit the connection to the production database in Frankfurt, read only";
    App<List<String>> app = new App<>(log, l -> head(Theme.DARK, l, title));
    run(app, "frame\nadvance 0.25\nframe\n");
    String dump = app.tree().dump();
    float[] mark = StructureTest.rect(StructureTest.line(dump, "#mark "));
    float[] close = StructureTest.rect(StructureTest.line(dump, "#close "));
    float[] badge = StructureTest.rect(StructureTest.line(dump, "#badge "));
    assertEquals(Theme.DARK.size().control(), close[2], 0.01, "Close keeps its size");
    assertEquals(mark[2], mark[3], 0.01, "the mark keeps its size");
    assertTrue(badge[2] > 40, "the badge keeps its size: " + dump);
    float[] heading = StructureTest.rect(StructureTest.line(dump, "role=heading"));
    assertTrue(heading[0] + heading[2] <= badge[0], "the title gives way: " + dump);

    run(app, "move role=heading\nframe\nadvance 1\nframe\n");
    String shown = app.tree().dump();
    assertTrue(shown.contains("role=tooltip label=\"" + title + "\""), shown);
  }

  @Test
  void aStartWithoutButtonsHasARowOfItsOwn() throws IOException {
    Theme t = Theme.DARK;
    App<Object> app = new App<>(
        new Object(),
        s -> El.col()
            .wFull()
            .hFull()
            .overlay(Dialog.dialog(
                t,
                "Export",
                El.el().key("body").size(200, 40),
                List.of(),
                () -> {},
                Dialog.options().start(Label.label(t, "Writing 3 of 7").key("status")))));
    run(app, "frame\nadvance 0.25\nframe\n");
    String dump = app.tree().dump();
    float[] body = StructureTest.rect(StructureTest.line(dump, "#body "));
    float[] status = StructureTest.rect(StructureTest.line(dump, "#status "));
    assertEquals(body[0], status[0], 0.01, dump);
    assertTrue(status[1] >= body[1] + body[3] + Theme.DARK.space().l(), "below the content");
  }
}
