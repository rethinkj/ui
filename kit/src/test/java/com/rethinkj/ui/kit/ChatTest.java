package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Clipboard;
import com.rethinkj.ui.El;
import com.rethinkj.ui.RowHeights;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.ScrollState;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    Script.run("""
        size 500 200
        frame
        press role=button label="Copy message"
        frame
        press role=button label="Retry"
        frame""", agentApp, out);
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

  private record TranscriptMessage(int id, boolean fromUser, String text, MarkdownDocument doc) {}

  private static final class TranscriptState {
    final List<TranscriptMessage> messages = new ArrayList<>();
    final ScrollState scroll = new ScrollState();
    final RowHeights heights =
        new RowHeights(index -> 64, index -> messages.get(index).id());
    final StringBuilder lastSource = new StringBuilder("Initial streaming summary for `orders`.");
    int olderRequests;
    boolean hasOlder = true;

    TranscriptState() {
      for (int id = 6; id <= 12; id++) {
        if (id % 2 == 0) {
          messages.add(new TranscriptMessage(
              id, true, "Question " + id + ": check query plan for orders table.", null));
        } else {
          messages.add(
              new TranscriptMessage(
                  id,
                  false,
                  null,
                  Markdown.parse(
                      "Answer "
                          + id
                          + ": the planner scanned `orders` sequentially because no composite index covers `status` and `created_at`.")));
        }
      }
      messages.add(new TranscriptMessage(13, false, null, Markdown.parse(lastSource.toString())));
    }

    void appendToLast(String extra) {
      int last = messages.size() - 1;
      TranscriptMessage current = messages.get(last);
      lastSource.append("\n\n").append(extra);
      messages.set(
          last,
          new TranscriptMessage(
              current.id(), false, null, current.doc().next(lastSource.toString())));
    }

    void requestOlder() {
      olderRequests++;
    }

    void prependOlder() {
      List<TranscriptMessage> older = new ArrayList<>();
      for (int id = 1; id <= 5; id++) {
        older.add(new TranscriptMessage(
            id,
            id % 2 == 1,
            id % 2 == 1 ? "Older question " + id + " from earlier in the session." : null,
            id % 2 == 1
                ? null
                : Markdown.parse("Older answer "
                    + id
                    + ": checked index bloat and vacuum stats across all partitions.")));
      }
      messages.addAll(0, older);
      hasOlder = false;
    }

    El render(int index) {
      TranscriptMessage entry = messages.get(index);
      El element = entry.fromUser() ? Chat.user(entry.text()) : Chat.agent(entry.doc());
      return element.key("msg-" + entry.id());
    }
  }

  private static El rowContaining(El root, String key) {
    El list = findByKey(root, "list");
    for (El row : list.children()) {
      if (findByKey(row, key) != null) {
        return row;
      }
    }
    return null;
  }

  private static El findByKey(El root, String key) {
    if (key.equals(root.key())) {
      return root;
    }
    for (El child : root.children()) {
      El found = findByKey(child, key);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  @Test
  void transcriptFollowsStreamingShowsJumpButtonAndLoadsOlder() throws IOException {
    TranscriptState state = new TranscriptState();
    App<TranscriptState> app = new App<>(
            state,
            s -> col()
                .wFull()
                .hFull()
                .fill(Theme.DARK.surface().panel())
                .child(Chat.transcript(
                        s.messages.size(),
                        s::render,
                        s.scroll,
                        s.heights,
                        s.hasOlder ? s::requestOlder : null)
                    .key("transcript")
                    .grow(1)))
        .provide(Theme.CURRENT, s -> Theme.DARK);
    app.textRendering(TextRendering.LINUX);

    // Starts at the bottom, centered to Theme.Prose.measure(), with no jump button and no older
    // load.
    Script.run("size 820 320 scale 2\nframe", app, out);
    assertTrue(state.scroll.atEnd(), "starts following at end");
    assertEquals(0, state.olderRequests, "does not ask for older messages at bottom");
    String initialDump = app.tree().dump();
    assertFalse(initialDump.contains("label=\"Jump to newest\""), initialDump);
    El lastMessage = findByKey(app.tree(), "msg-13");
    assertEquals(
        Theme.DARK.prose().measure(), lastMessage.width(), 1.0f, "capped to prose measure");
    assertTrue(lastMessage.x() > 20f, "centered in 820px viewport: x=" + lastMessage.x());

    // Streaming into the last message keeps the transcript at the end.
    double yBeforeStream = state.scroll.y();
    state.appendToLast("""
        Adding a composite index on `(status, created_at)` drops execution time from 4.2 s to 12 ms.

        ```sql
        CREATE INDEX CONCURRENTLY orders_status_created ON orders (status, created_at);
        ```""");
    app.invalidate();
    Script.run("frame", app, out);
    assertTrue(state.scroll.atEnd(), "still at end while streaming");
    assertTrue(state.scroll.y() > yBeforeStream, "scrolled further down as last message grew");
    assertFalse(
        app.tree().dump().contains("label=\"Jump to newest\""), app.tree().dump());

    // Scrolling up stops following, shows the floating jump button, and stays put while streaming.
    Script.run("wheel role=log 0 -160\nframe\nadvance 1\nframe", app, out);
    assertFalse(state.scroll.atEnd(), "scrolled away from end");
    double stayedAt = state.scroll.y();
    assertTrue(
        app.tree().dump().contains("role=button label=\"Jump to newest\""),
        app.tree().dump());
    state.appendToLast("Run `ANALYZE orders;` after creating the index so the planner uses it.");
    app.invalidate();
    List<Path> scrolledFiles = Script.run("frame\nsnap scrolled", app, out);
    assertEquals(stayedAt, state.scroll.y(), 0.5, "scroll position stayed put while streaming");
    assertTrue(
        app.tree().dump().contains("role=button label=\"Jump to newest\""),
        app.tree().dump());
    Golden.assertSnaps("chat-transcript-dark-2x-", scrolledFiles, "scrolled");

    // Pressing the floating button jumps back to the end, hides the button, and resumes following.
    List<Path> bottomFiles = Script.run(
        "press role=button label=\"Jump to newest\"\nframe\nadvance 1\nframe\nsnap bottom",
        app,
        out);
    assertTrue(state.scroll.atEnd(), "back at end after pressing jump button");
    assertFalse(
        app.tree().dump().contains("label=\"Jump to newest\""), app.tree().dump());
    Golden.assertSnaps("chat-transcript-dark-2x-", bottomFiles, "bottom");

    // The wheel over the button scrolls the transcript, and the button goes at the end.
    Script.run("wheel role=log 0 -60\nframe\nadvance 1\nframe", app, out);
    double away = state.scroll.y();
    Script.run("wheel role=button label=\"Jump to newest\" 0 20\nframe", app, out);
    assertEquals(away + 20, state.scroll.y(), 0.5);
    Script.run(
        "wheel role=button label=\"Jump to newest\" 0 2000\nframe\nadvance 1\nframe", app, out);
    assertTrue(state.scroll.atEnd());
    assertFalse(
        app.tree().dump().contains("label=\"Jump to newest\""), app.tree().dump());

    // At the top it asks for older messages, and those that arrive keep the first one in view: it
    // moves up by the padding above it, which scrolls away.
    Script.run("wheel role=log 0 -2000\nframe\nframe", app, out);
    assertTrue(state.olderRequests > 0, "asked for older messages at the top");
    assertEquals(Theme.DARK.space().m(), rowContaining(app.tree(), "msg-6").y(), 0.5f);
    state.prependOlder();
    app.invalidate();
    Script.run("frame", app, out);
    assertEquals(0, rowContaining(app.tree(), "msg-6").y(), 0.5f, app.tree().dump());

    // Away from the end, the button goes once the window grows until all messages fit.
    assertTrue(app.tree().dump().contains("label=\"Jump to newest\""));
    Script.run("size 820 3000 scale 2\nframe\nframe", app, out);
    assertFalse(
        app.tree().dump().contains("label=\"Jump to newest\""), app.tree().dump());
  }

  @Test
  void transcriptSelectionSpansMessagesAndOutOfViewRows() throws IOException {
    List<MarkdownDocument> replies = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      replies.add(Markdown.parse("Reply " + i + " with sample words."));
    }
    ScrollState scroll = new ScrollState();
    RowHeights heights = new RowHeights(index -> 64, index -> index);
    App<Object> app = new App<>(
            new Object(),
            state -> col()
                .wFull()
                .hFull()
                .fill(Theme.DARK.surface().panel())
                .child(Chat.transcript(
                        replies.size(),
                        index ->
                            Chat.agent(Markdown.fragment(replies.get(index), Markdown.options())),
                        scroll,
                        heights)
                    .grow(1)))
        .provide(Theme.CURRENT, state -> Theme.DARK);
    app.textRendering(TextRendering.LINUX);

    // A drag from one message into another copies the messages between them in order.
    Script.run("""
        size 640 240 scale 2
        frame
        scroll role=log row 0
        frame
        down text="Reply 1 with sample words." at left+40
        move text="Reply 3 with sample words." at left+40
        up text="Reply 3 with sample words." at left+40
        frame
        key C control
        frame""", app, out);
    List<String> dragged = app.clipboard().read().lines().toList();
    assertEquals(3, dragged.size(), app.clipboard().read());
    assertEquals("Reply 2 with sample words.", dragged.get(1));

    // Shift+press extends from an anchor whose message scrolled out of the built rows, and the
    // copy holds every message it covers, also the ones never built.
    Script.run("""
        press text="Reply 1 with sample words." at left+40
        frame
        scroll role=log row 31
        frame""", app, out);
    assertFalse(app.tree().dump().contains("Reply 1 with"), app.tree().dump());
    Script.run("""
        press text="Reply 31 with sample words." at left+90 shift
        frame
        key C control
        frame""", app, out);
    List<String> spanned = app.clipboard().read().lines().toList();
    assertEquals(31, spanned.size(), app.clipboard().read());
    assertTrue(spanned.getFirst().endsWith(" with sample words."), spanned.getFirst());
    assertEquals("Reply 15 with sample words.", spanned.get(14));
    assertTrue(spanned.getLast().startsWith("Reply 31 "), spanned.getLast());
  }

  @Test
  void transcriptSelectionIncludesUserMessagesAndPagingScrollsAfterClick() throws IOException {
    List<TranscriptMessage> messages = new ArrayList<>();
    for (int i = 0; i < 16; i++) {
      if (i % 2 == 0) {
        messages.add(new TranscriptMessage(i, true, "Prompt " + i + " about orders.", null));
      } else {
        messages.add(new TranscriptMessage(
            i, false, null, Markdown.parse("Answer " + i + " about the index.")));
      }
    }
    ScrollState scroll = new ScrollState();
    RowHeights heights = new RowHeights(index -> 64, index -> index);
    App<Object> app = new App<>(
            new Object(),
            state -> col()
                .wFull()
                .hFull()
                .fill(Theme.DARK.surface().panel())
                .child(Chat.transcript(
                        messages.size(),
                        index -> {
                          TranscriptMessage entry = messages.get(index);
                          return entry.fromUser()
                              ? Chat.user(entry.text())
                              : Chat.agent(entry.doc());
                        },
                        scroll,
                        heights)
                    .grow(1)))
        .provide(Theme.CURRENT, state -> Theme.DARK);
    app.textRendering(TextRendering.LINUX);

    // Drag across a user prompt, an agent answer and the next user prompt: copied in order.
    Script.run("""
        size 640 240 scale 2
        frame
        scroll role=log row 0
        frame
        down text="Prompt 0 about orders." at left+20
        move text="Prompt 2 about orders." at left+60
        up text="Prompt 2 about orders." at left+60
        frame
        key C control
        frame""", app, out);
    List<String> copied = app.clipboard().read().lines().toList();
    assertEquals(3, copied.size(), app.clipboard().read());
    assertTrue(copied.get(0).endsWith("about orders."), copied.get(0));
    assertEquals("Answer 1 about the index.", copied.get(1));
    assertTrue(copied.get(2).startsWith("Prompt 2"), copied.get(2));

    // Clicking inside a user message and pressing PageDown/PageUp still scrolls the transcript.
    Script.run("""
        press text="Prompt 0 about orders." at left+20
        frame
        key PageDown
        frame""", app, out);
    assertTrue(scroll.y() > 0, "PageDown scrolled transcript: " + scroll.y());
    Script.run("key PageUp\nframe", app, out);
    assertEquals(0, scroll.y(), 0.5);
  }

  @Test
  void findReusesUnchangedOutOfViewRowsAndFindsChangedOnes() throws IOException {
    final class Entry {
      final int id;
      MarkdownDocument doc;
      int revision;

      Entry(int id, String markdown) {
        this.id = id;
        this.doc = Markdown.parse(markdown);
      }

      void update(String markdown) {
        this.doc = doc.next(markdown);
        this.revision++;
      }
    }

    List<Entry> messages = new ArrayList<>();
    for (int i = 0; i < 60; i++) {
      messages.add(new Entry(
          i, i == 5 || i == 57 ? "Message " + i + " has needle." : "Message " + i + " filler."));
    }
    ScrollState scroll = new ScrollState();
    RowHeights heights = new RowHeights(
        index -> 64, index -> messages.get(index).id, index -> messages.get(index).revision);
    Find.State find = new Find.State();
    Set<Integer> builtRows = new HashSet<>();
    App<?>[] holder = new App<?>[1];
    App<Object> app = new App<>(
            new Object(),
            state -> col()
                .wFull()
                .hFull()
                .fill(Theme.DARK.surface().panel())
                .child(Find.find(
                        holder[0],
                        Chat.transcript(
                                messages.size(),
                                index -> {
                                  builtRows.add(index);
                                  return Chat.agent(messages.get(index).doc);
                                },
                                scroll,
                                heights)
                            .grow(1),
                        find)
                    .grow(1)))
        .provide(Theme.CURRENT, state -> Theme.DARK);
    holder[0] = app;
    app.textRendering(TextRendering.LINUX);

    // Open the find bar over the transcript; typing "needle" jumps to row 5, leaving row 45 out of
    // view.
    Script.run("""
        size 560 220 scale 2
        frame
        press text="Message 57 has needle." at left+20
        frame
        key F control
        frame
        type "needle"
        frame""", app, out);
    String opened = app.tree().dump();
    assertTrue(
        opened.lines().anyMatch(l -> l.contains("#count ") && l.contains("\"1 of 2\"")), opened);
    assertFalse(opened.contains("Message 45 filler."), "row 45 is out of view");

    // A plain view build with nothing changed builds only the rows in view, not out-of-view rows.
    builtRows.clear();
    app.invalidate();
    Script.run("frame", app, out);
    El list = findByKey(app.tree(), "list");
    int first = list.firstRow();
    int last = first + list.children().size();
    assertTrue(last < 30 && list.children().size() < messages.size(), "most rows are unbuilt");
    for (int row : builtRows) {
      assertTrue(
          row >= first && row < last,
          "rebuilt out-of-view row " + row + " outside [" + first + ", " + last + ")");
    }

    // Changing an out-of-view message and its version rebuilds it and updates the match count.
    messages.get(45).update("Message 45 now contains needle.");
    builtRows.clear();
    app.invalidate();
    Script.run("frame", app, out);
    String updated = app.tree().dump();
    assertTrue(
        updated.lines().anyMatch(l -> l.contains("#count ") && l.contains("\"1 of 3\"")), updated);
    assertTrue(builtRows.contains(45), "changed out-of-view row was rebuilt");
    assertFalse(builtRows.contains(44), "unchanged out-of-view row stayed cached");
    assertFalse(builtRows.contains(57), "unchanged out-of-view row stayed cached");
  }

  @Test
  void findAdvancesPastCurrentMatchAfterRowsInsertedAtTop() throws IOException {
    List<Entry> messages = needleMessages();
    App<Object> app = findApp(messages);

    // Open Find and land on the first match (message 8).
    Script.run("""
        size 560 240 scale 2
        frame
        press text="Message 19." at left+20
        frame
        key F control
        frame
        type "needle"
        frame""", app, out);
    String first = app.tree().dump();
    assertTrue(
        first.lines().anyMatch(l -> l.contains("#count ") && l.contains("\"1 of 2\"")), first);
    assertTrue(first.contains("\"Message 8 has needle.\""), first);

    // Prepend older messages (including one with a match) above the current match: the count
    // stays on message 8 as 2 of 3, and Enter advances straight to message 15 (3 of 3).
    List<Entry> older = new ArrayList<>();
    for (int id = 1; id <= 5; id++) {
      String body = id == 2 ? "Message 2 has needle." : "Message " + id + " older.";
      older.add(new Entry(id, Markdown.parse(body)));
    }
    messages.addAll(0, older);
    // The last reply grows in the same frame, as a streamed one does.
    messages.set(messages.size() - 1, new Entry(19, Markdown.parse("Message 19 grew longer.")));
    app.invalidate();
    Script.run("frame", app, out);
    String afterInsert = app.tree().dump();
    assertTrue(
        afterInsert.lines().anyMatch(l -> l.contains("#count ") && l.contains("\"2 of 3\"")),
        afterInsert);

    Script.run("key Enter\nframe", app, out);
    String next = app.tree().dump();
    assertTrue(next.lines().anyMatch(l -> l.contains("#count ") && l.contains("\"3 of 3\"")), next);
    assertTrue(next.contains("\"Message 15 has needle.\""), next);

    // Shift+Enter steps back through message 8 to the newly inserted message 2.
    Script.run("key Enter shift\nframe\nkey Enter shift\nframe", app, out);
    String earlier = app.tree().dump();
    assertTrue(
        earlier.lines().anyMatch(l -> l.contains("#count ") && l.contains("\"1 of 3\"")), earlier);
    assertTrue(earlier.contains("\"Message 2 has needle.\""), earlier);

    // Appending a message at the bottom leaves the current match on message 2.
    messages.add(new Entry(20, Markdown.parse("Message 20 has needle.")));
    app.invalidate();
    Script.run("frame", app, out);
    String afterAppend = app.tree().dump();
    assertTrue(
        afterAppend.lines().anyMatch(l -> l.contains("#count ") && l.contains("\"1 of 4\"")),
        afterAppend);
  }

  @Test
  void findAdvancesPastCurrentMatchAfterQueryPressAndInserts() throws IOException {
    List<Entry> messages = needleMessages();
    App<Object> app = findApp(messages);

    // Open Find, land on the first of two matches (message 8), then press into the query field
    // without editing it, which clears the scope's selection.
    Script.run("""
        size 560 240 scale 2
        frame
        press text="Message 19." at left+20
        frame
        key F control
        frame
        type "needle"
        frame
        advance 1
        frame
        press #find-field at left+20
        frame""", app, out);
    String first = app.tree().dump();
    assertTrue(
        first.lines().anyMatch(l -> l.contains("#count ") && l.contains("\"1 of 2\"")), first);
    assertTrue(first.contains("\"Message 8 has needle.\""), first);

    // Prepend keyed rows that begin as the text up to the current match does, with a match of
    // their own: the count stays on message 8 (2 of 3), and Enter advances to message 15 (3 of 3)
    // rather than selecting message 8 again.
    List<Entry> older = new ArrayList<>();
    for (String body : List.of("Message 6.", "Message 7.", "Message 8 has needle too.")) {
      older.add(new Entry(older.size() + 1, Markdown.parse(body)));
    }
    messages.addAll(0, older);
    app.invalidate();
    Script.run("frame", app, out);
    String afterInsert = app.tree().dump();
    assertTrue(
        afterInsert.lines().anyMatch(l -> l.contains("#count ") && l.contains("\"2 of 3\"")),
        afterInsert);

    Script.run("key Enter\nframe", app, out);
    String next = app.tree().dump();
    assertTrue(next.lines().anyMatch(l -> l.contains("#count ") && l.contains("\"3 of 3\"")), next);
    assertTrue(
        next.lines()
            .anyMatch(l -> l.contains("\"Message 15 has needle.\"") && l.contains("underline")),
        next);
  }

  @Test
  void findAdvancesPastCurrentMatchAfterQueryPressAndChangesAboveAndBelow() throws IOException {
    List<Entry> messages = needleMessages();
    App<Object> app = findApp(messages);

    // Open Find, land on the first match (message 8), then press into the query field without
    // editing it, which clears the scope's selection.
    Script.run("""
        size 560 240 scale 2
        frame
        press text="Message 19." at left+20
        frame
        key F control
        frame
        type "needle"
        frame
        advance 1
        frame
        press #find-field at left+20
        frame""", app, out);
    String first = app.tree().dump();
    assertTrue(
        first.lines().anyMatch(l -> l.contains("#count ") && l.contains("\"1 of 2\"")), first);
    assertTrue(first.contains("\"Message 8 has needle.\""), first);

    // Edit a message above and the last reply below in one build, the one above keeping its
    // length: the match's offsets stay as they were, and it is still the current one.
    messages.set(0, new Entry(6, Markdown.parse("Message 6!")));
    messages.set(messages.size() - 1, new Entry(19, Markdown.parse("Message 19 streams.")));
    app.invalidate();
    Script.run("frame", app, out);
    String edited = app.tree().dump();
    assertTrue(
        edited.lines().anyMatch(l -> l.contains("#count ") && l.contains("\"1 of 2\"")), edited);

    // In one build, prepend older messages (with a match) above message 8 and grow the last reply
    // below it: the kept position still follows message 8 by row key (2 of 3), and Enter advances
    // straight to message 15 (3 of 3).
    List<Entry> older = new ArrayList<>();
    for (int id = 1; id <= 3; id++) {
      String body = id == 2 ? "Message 2 has needle." : "Message " + id + " older.";
      older.add(new Entry(id, Markdown.parse(body)));
    }
    messages.addAll(0, older);
    messages.set(messages.size() - 1, new Entry(19, Markdown.parse("Message 19 grew longer.")));
    app.invalidate();
    Script.run("frame", app, out);
    String afterChange = app.tree().dump();
    assertTrue(
        afterChange.lines().anyMatch(l -> l.contains("#count ") && l.contains("\"2 of 3\"")),
        afterChange);

    Script.run("key Enter\nframe", app, out);
    String next = app.tree().dump();
    assertTrue(next.lines().anyMatch(l -> l.contains("#count ") && l.contains("\"3 of 3\"")), next);
    assertTrue(
        next.lines()
            .anyMatch(l -> l.contains("\"Message 15 has needle.\"") && l.contains("underline")),
        next);
  }

  @Test
  void findAdvancesPastCurrentMatchWhenRowReshapes() throws IOException {
    // Message 8 is a paragraph whose code span holds a pipe; it becomes a table while it streams,
    // which splits the code span into two cells. The match's old offsets then fall on the first
    // "x" in the row, while it is the second that is current.
    List<Entry> messages = needleMessages();
    messages.set(2, new Entry(8, Markdown.parse("| ````x x|y```` |")));
    messages.set(9, new Entry(15, Markdown.parse("Message 15 has x.")));
    App<Object> app = findApp(messages);

    // Open Find, go to the second match in message 8, then press into the query field without
    // editing it, which clears the scope's selection.
    Script.run("""
        size 560 240 scale 2
        frame
        press text="Message 19." at left+20
        frame
        key F control
        frame
        type "x"
        frame
        key Enter
        frame
        advance 1
        frame
        press #find-field at left+20
        frame""", app, out);
    String first = app.tree().dump();
    assertTrue(
        first.lines().anyMatch(l -> l.contains("#count ") && l.contains("\"2 of 3\"")), first);

    // In one build, prepend older messages (with a match) and make message 8 a table: the count
    // stays on its second match (3 of 4), and Enter goes on to message 15 (4 of 4).
    List<Entry> older = new ArrayList<>();
    for (int id = 1; id <= 3; id++) {
      String body = id == 2 ? "Message 2 has x." : "Message " + id + " older.";
      older.add(new Entry(id, Markdown.parse(body)));
    }
    messages.addAll(0, older);
    messages.set(5, new Entry(8, Markdown.parse("""
                | ````x x|y```` |
                | --- | --- |
                | A | value |""")));
    app.invalidate();
    Script.run("frame", app, out);
    String afterReshape = app.tree().dump();
    assertTrue(
        afterReshape.lines().anyMatch(l -> l.contains("#count ") && l.contains("\"3 of 4\"")),
        afterReshape);

    Script.run("key Enter\nframe", app, out);
    String next = app.tree().dump();
    assertTrue(next.lines().anyMatch(l -> l.contains("#count ") && l.contains("\"4 of 4\"")), next);
    assertTrue(
        next.lines().anyMatch(l -> l.contains("\"Message 15 has x.\"") && l.contains("underline")),
        next);
  }

  private record Entry(int id, MarkdownDocument doc) {}

  /** Messages 6 to 19, of which 8 and 15 have a match for "needle". */
  private static List<Entry> needleMessages() {
    List<Entry> messages = new ArrayList<>();
    for (int id = 6; id <= 19; id++) {
      String body = id == 8 || id == 15 ? "Message " + id + " has needle." : "Message " + id + ".";
      messages.add(new Entry(id, Markdown.parse(body)));
    }
    return messages;
  }

  /** A transcript of {@code messages}, keyed by their ids, with a find bar. */
  private static App<Object> findApp(List<Entry> messages) {
    ScrollState scroll = new ScrollState();
    RowHeights heights =
        new RowHeights(index -> 64, index -> messages.get(index).id());
    Find.State find = new Find.State();
    App<?>[] holder = new App<?>[1];
    App<Object> app = new App<>(
            new Object(),
            state -> col()
                .wFull()
                .hFull()
                .fill(Theme.DARK.surface().panel())
                .child(Find.find(
                        holder[0],
                        Chat.transcript(
                                messages.size(),
                                index -> Chat.agent(messages.get(index).doc()),
                                scroll,
                                heights)
                            .grow(1),
                        find)
                    .grow(1)))
        .provide(Theme.CURRENT, state -> Theme.DARK);
    holder[0] = app;
    app.textRendering(TextRendering.LINUX);
    return app;
  }
}
