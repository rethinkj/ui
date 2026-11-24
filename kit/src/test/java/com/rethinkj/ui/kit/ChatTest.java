package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Clipboard;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Behavior of {@link Chat}: user bubble sizing, copy actions, tool call toggling and working. */
class ChatTest {

  @TempDir
  Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  private static App<?> app(El view) {
    App<Object> app = new App<>(new Object(), s -> view).provide(Theme.CURRENT, s -> Theme.DARK);
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  @Test
  void userBubbleSitsAtEndAndCapsWidth() throws IOException {
    String shortText = "Hi!";
    String longText =
        "Can you check why the orders query takes three seconds after adding the date filter "
            + "and suggest an index that covers both status and created_at?";
    App<?> app = app(col()
        .wFull()
        .gap(Theme.DARK.space().l())
        .children(
            Chat.user(shortText).key("short"),
            Chat.user(longText).key("long"),
            Chat.user("i\t\t\tX").key("tabs"),
            Chat.user("Hi!\r\nThere").key("lines")));
    Script.run("size 500 300\nframe", app, out);

    El shortBubble = app.tree().children().get(0).children().get(0);
    El longBubble = app.tree().children().get(1).children().get(0);

    // Short bubble is narrow and sits flush at the right edge of the 500px container.
    assertTrue(shortBubble.width() < 120, "short bubble width: " + shortBubble.width());
    assertEquals(500f, shortBubble.x() + shortBubble.width(), 1.5f);

    // Long bubble wraps and does not exceed theme.chat().bubbleWidth() (80% of 500 = 400).
    assertTrue(longBubble.width() <= 400.5f, "long bubble width: " + longBubble.width());
    assertTrue(longBubble.height() > shortBubble.height(), "long bubble wraps onto more lines");
    assertEquals(500f, longBubble.x() + longBubble.width(), 1.5f);

    // Short texts, with tabs too, stay on one line; a line break makes a second one.
    El tabsBubble = app.tree().children().get(2).children().get(0);
    El linesBubble = app.tree().children().get(3).children().get(0);
    float line = linesBubble.children().get(0).height() / 2;
    assertEquals(line, shortBubble.children().get(0).height(), 0.5f);
    assertEquals(line, tabsBubble.children().get(0).height(), 0.5f);
  }

  @Test
  void copyActionWritesMessageTextToClipboard() throws IOException {
    Clipboard clipboard = Clipboard.inMemory();
    Button.Copied copied = new Button.Copied();
    App<Object> userApp = new App<>(
            new Object(),
            s -> col()
                .wFull()
                .child(Chat.user(
                    "Why is it slow?",
                    Chat.options().copy(clipboard).copied(copied).time("14:31"))))
        .provide(Theme.CURRENT, s -> Theme.DARK);
    userApp.textRendering(TextRendering.LINUX);
    Script.run(
        "size 500 200\nframe\npress role=button label=\"Copy message\"\nframe", userApp, out);
    assertEquals("Why is it slow?", clipboard.read());
    assertTrue(userApp.tree().dump().contains("14:31"));

    MarkdownDocument reply = Markdown.parse("Add a composite index on `(status, created_at)`.");
    int[] retries = {0};
    App<Object> agentApp = new App<>(
            new Object(),
            s -> col()
                .wFull()
                .child(Chat.agent(
                    reply,
                    Chat.options()
                        .copy(clipboard)
                        .copied(copied)
                        .actions(Button.icon(
                            Button.Variant.GHOST,
                            Symbol.ARROW_CLOCKWISE,
                            "Retry",
                            () -> retries[0]++))
                        .time("14:32"))))
        .provide(Theme.CURRENT, s -> Theme.DARK);
    agentApp.textRendering(TextRendering.LINUX);
    Script.run(
        "size 500 200\nframe\npress role=button label=\"Copy message\"\nframe\n"
            + "press role=button label=\"Retry\"\nframe",
        agentApp,
        out);
    assertEquals("Add a composite index on (status, created_at).", clipboard.read());
    assertEquals(1, retries[0]);
    assertTrue(agentApp.tree().dump().contains("14:32"));
  }

  @Test
  void toolRowShowsStateAndTogglesDetails() throws IOException {
    boolean[] open = {false};
    App<boolean[]> app = new App<>(
            open,
            s -> col()
                .wFull()
                .gap(Theme.DARK.space().m())
                .children(
                    Chat.tool(
                        Symbol.CODE,
                        "Read orders.sql",
                        Chat.ToolState.DONE,
                        s[0],
                        () -> s[0] = !s[0],
                        Label.label(Label.Variant.MONO, "SELECT * FROM orders;")),
                    Chat.tool(Symbol.PLAY, "Run migration", Chat.ToolState.RUNNING, false, null),
                    Chat.tool(
                        Symbol.DATABASE, "Check replica", Chat.ToolState.FAILED, false, null)))
        .provide(Theme.CURRENT, s -> Theme.DARK);
    app.textRendering(TextRendering.LINUX);

    Script.run("size 500 300\nframe", app, out);
    String closed = app.tree().dump();
    assertTrue(StructureTest.line(closed, "label=\"Read orders.sql\"").contains("expanded=false"));
    assertTrue(StructureTest.line(closed, "label=\"Read orders.sql\"").contains("value=\"Done\""));
    assertFalse(closed.contains("SELECT * FROM orders;"));
    assertTrue(StructureTest.line(closed, "label=\"Run migration\"").contains(" busy"));
    assertTrue(StructureTest.line(closed, "label=\"Check replica\"").contains(" invalid"));

    Script.run("press role=button label=\"Read orders.sql\"\nframe", app, out);
    assertTrue(open[0]);
    String opened = app.tree().dump();
    assertTrue(StructureTest.line(opened, "label=\"Read orders.sql\"").contains(" expanded"));
    assertTrue(opened.contains("SELECT * FROM orders;"));

    Script.run("focus role=button label=\"Read orders.sql\"\nkey Enter\nframe", app, out);
    assertFalse(open[0]);
    assertFalse(app.tree().dump().contains("SELECT * FROM orders;"));
  }

  @Test
  void workingIndicatorUsesLocalizedTexts() throws IOException {
    App<Object> app = new App<>(
            new Object(), s -> col().wFull().children(Chat.agent(Chat.working())))
        .provide(Theme.CURRENT, s -> Theme.DARK)
        .provide(Texts.CURRENT, s -> new GermanTexts());
    app.textRendering(TextRendering.LINUX);
    Script.run("size 400 120\nframe", app, out);
    String dump = app.tree().dump();
    assertTrue(dump.contains("role=article label=\"Agentennachricht\""), dump);
    assertTrue(dump.contains("role=status label=\"Arbeitet…\""), dump);
    assertTrue(dump.contains("role=progressbar"), dump);
  }
}
