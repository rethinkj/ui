package com.rethinkj.ui.editor;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.editor.CodeEditor.codeEditor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Highlighter;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.RowHeights;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.TextEdit;
import com.rethinkj.ui.kit.Completion;
import com.rethinkj.ui.kit.Markdown;
import com.rethinkj.ui.kit.MarkdownDocument;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Path;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link Completion} at the caret of a {@link CodeEditor}, driven by scripts. */
class CompletionTest {

  private static final List<String> NAMES =
      List.of("customers", "customer_id", "count", "select", "status");

  @TempDir
  Path out;

  static final class Query {
    String sql = "";
    Highlighter highlighter;
    final CodeEditor.State editor = new CodeEditor.State();
    final Completion.State completion = new Completion.State();
    boolean plain; // the editor without completions
    boolean plainOnEdit; // an edit leaves out the completions
    App<Query> app;
  }

  private static List<Completion.Entry> suggest(String word) {
    return NAMES.stream()
        .filter(n -> n.startsWith(word.toLowerCase(Locale.ROOT)))
        .map(n -> new Completion.Entry(n, Symbol.TABLE, n.startsWith("c") ? "name" : null))
        .toList();
  }

  private App<Query> app() {
    return app(CompletionTest::suggest);
  }

  private App<Query> app(Function<String, List<Completion.Entry>> suggest) {
    return app(suggest, false);
  }

  private App<Query> app(Function<String, List<Completion.Entry>> suggest, boolean background) {
    Theme t = Theme.DARK;
    Query q = new Query();
    App<Query> app = new App<>(q, s -> {
      El editor = codeEditor(s.sql, s.editor, s.highlighter, v -> {
            s.sql = v;
            s.plain |= s.plainOnEdit;
          })
          .key("query")
          .lines(4)
          .wFull();
      return col()
          .wFull()
          .hFull()
          .padding(12)
          .fill(t.surface().window())
          .onKey(key -> switch (key.name()) { // edits of the app's at the end
            case "F6" -> { // an s, the caret where it is
              s.app.edit(editor, s.sql.length(), s.sql.length(), "s", s.editor.caret());
              yield true;
            }
            case "F7" -> { // the last character gone
              s.editor.replace(s.sql.length() - 1, s.sql.length(), "", 0);
              yield true;
            }
            default -> false;
          })
          .child(
              s.plain
                  ? editor
                  : background
                      ? Completion.completeInBackground(
                          s.app, editor, s.editor.caret(), s.completion, "suggest", suggest)
                      : Completion.complete(
                          s.app, editor, s.editor.caret(), s.completion, suggest));
    });
    q.app = app;
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  /** The x, y, width and height of the element with {@code key} in a tree dump. */
  private static int[] box(String tree, String key) {
    var m = java.util.regex.Pattern.compile("el #" + key + " .*? (\\d+),(\\d+) (\\d+)x(\\d+)")
        .matcher(tree);
    assertTrue(m.find(), tree);
    return new int[] {
      Integer.parseInt(m.group(1)),
      Integer.parseInt(m.group(2)),
      Integer.parseInt(m.group(3)),
      Integer.parseInt(m.group(4))
    };
  }

  /** Asserts that the documentation panel lies off the completion list; returns its box. */
  private static int[] assertOffTheList(String tree) {
    int[] list = box(tree, "completions");
    int[] panel = box(tree, "documentation");
    assertTrue(
        panel[0] >= list[0] + list[2]
            || panel[0] + panel[2] <= list[0]
            || panel[1] >= list[1] + list[3]
            || panel[1] + panel[3] <= list[1],
        tree);
    return panel;
  }

  /** The scroll offset of the documentation panel in a tree dump. */
  private static String scroll(String tree) {
    var m =
        java.util.regex.Pattern.compile("el #documentation .*? scroll (\\S+)").matcher(tree);
    assertTrue(m.find(), tree);
    return m.group(1);
  }

  private String run(App<Query> app, String script) throws IOException {
    Script.run("size 420 220\n" + script + "\nframe\nadvance 0.25\nframe\nsnap s", app, out);
    return java.nio.file.Files.readString(out.resolve("s.tree.txt"));
  }

  @Test
  void aLanguagesWordCharactersBelongToItsWords() throws IOException {
    TextLibraries.assumeLoaded();
    List<String> asked = new ArrayList<>();
    App<Query> app = app(word -> {
      asked.add(word);
      return List.of(new Completion.Entry("$gt"));
    });
    app.state().highlighter =
        new Highlighter() { // JavaScript's operators, such as $gt
          @Override
          public Object line(String line, Object state, com.rethinkj.ui.Spans out) {
            return null;
          }

          @Override
          public String wordCharacters() {
            return "$";
          }
        };
    run(app, "frame\npress #query\ntype \"{ $g\"");
    assertTrue(asked.contains("$g"), asked::toString);

    run(app, "key Escape\ntype \"t\"\nkey Escape\nframe\nkey ArrowLeft control");
    assertEquals(2, app.state().editor.caret(), "Control+Left stops before the $");

    run(app, "press #query at right-20 clicks 2"); // past the end of the line: on $gt
    assertEquals(2, app.state().editor.anchor());
    assertEquals(5, app.state().editor.caret(), "a double press selects $gt");
  }

  @Test
  void typingOpensFromTheSecondCharacterAndKeysChoose() throws IOException {
    TextLibraries.assumeLoaded();
    App<Query> app = app();
    String tree = run(app, "frame\npress #query\ntype \"SELECT c\"\nframe");
    assertFalse(tree.contains("role=listbox"), "one character opens nothing");

    tree = run(app, "type \"u\"");
    assertTrue(tree.contains("el #completions role=listbox label=\"Completions\""), tree);
    assertTrue(tree.contains("el #option-0 role=option label=\"customers\""), tree);
    assertTrue(tree.contains("label=\"customers\"") && tree.contains(" selected"), tree);
    assertFalse(tree.contains("label=\"count\""), "narrowed to what starts with cu");
    assertTrue(
        tree.contains("text #query role=textbox") && tree.contains("focused caret 9"),
        "the focus stays in the field");

    tree = run(app, "key ArrowDown\nframe\nkey Enter");
    assertEquals("SELECT customer_id", app.state().sql);
    assertFalse(tree.contains("role=listbox"));
    assertEquals(18, app.state().editor.caret(), "after what it put in");

    run(app, "key Z control");
    assertEquals("SELECT cu", app.state().sql, "one step of the history");
  }

  @Test
  void aDeletionInsideAWordAsksForThePartBeforeTheCaret() throws IOException {
    TextLibraries.assumeLoaded();
    List<String> asked = new ArrayList<>();
    App<Query> app = app(word -> {
      asked.add(word);
      return List.of(new Completion.Entry("count"));
    });
    run(
        app,
        "frame\npress #query\ntype \"SELECT cooo\"\nkey ArrowLeft\nkey ArrowLeft\nkey Space control");
    asked.clear();
    run(app, "key Backspace");
    assertEquals("SELECT coo", app.state().sql);
    assertEquals(List.of("c"), List.copyOf(new java.util.LinkedHashSet<>(asked)), "c|oo");

    run(app, "key A control\ntype \"SELECT coount\"" + "\nkey ArrowLeft".repeat(5));
    run(app, "key ArrowRight shift\nkey Space control"); // SELECT c[o]ount
    asked.clear();
    run(app, "key Delete");
    assertEquals("SELECT count", app.state().sql);
    assertEquals(List.of("c"), List.copyOf(new java.util.LinkedHashSet<>(asked)), "c|ount");

    app.state().plain = true; // completions come once the selection is there: no anchor told
    run(app, "key A control\ntype \"SELECT count\"" + "\nkey ArrowLeft".repeat(3));
    run(app, "key ArrowRight shift\nkey ArrowRight shift"); // SELECT co[un]t
    app.state().plain = false;
    app.invalidate();
    run(app, "frame\nkey Space control");
    asked.clear();
    run(app, "key Delete");
    assertEquals("SELECT cot", app.state().sql);
    assertEquals(List.of("co"), List.copyOf(new java.util.LinkedHashSet<>(asked)), "co|t");
  }

  @Test
  void anEditOfTheAppThatLeavesTheCaretKeepsTheWordToReplace() throws IOException {
    TextLibraries.assumeLoaded();
    App<Query> app = app();
    run(
        app,
        "frame\npress #query\ntype \"SELECT cu FROM order\"\nkey Home"
            + "\nkey ArrowRight".repeat(9));
    run(app, "key Space control\nkey F6"); // SELECT cu| FROM orders
    run(app, "key Enter");
    assertEquals("SELECT customers FROM orders", app.state().sql);

    String tree =
        run(app, "key A control\ntype \"SELECT cu\"\nkey Escape\nkey F6\nkey Space control");
    assertEquals("SELECT cus", app.state().sql);
    assertTrue(tree.contains("label=\"customers\""), "the list for cu, the caret before the s");
  }

  @Test
  void anEntryProviderSlicesTheAppsTextAtTheAppsCaret() throws IOException {
    TextLibraries.assumeLoaded();
    List<Query> self = new ArrayList<>();
    App<Query> app = app(word -> {
      Query q = self.getFirst();
      String before = q.sql.substring(0, q.editor.caret()); // throws for a caret beyond the text
      return CompletionTest.suggest(before.substring(before.lastIndexOf(' ') + 1));
    });
    self.add(app.state());
    run(app, "frame\npress #query\ntype \"SELECT cus\"");
    String tree = run(app, "key F7");
    assertEquals("SELECT cu", app.state().sql);
    assertTrue(tree.contains("label=\"customers\""), tree);
  }

  @Test
  void controlSpaceOpensEscapeClosesAndAPressTakes() throws IOException {
    TextLibraries.assumeLoaded();
    App<Query> app = app();
    String tree = run(app, "frame\npress #query\ntype \"SELECT \"\nframe\nkey Space control");
    assertTrue(tree.contains("label=\"status\""), "every entry for no word yet");

    tree = run(app, "key Escape");
    assertFalse(tree.contains("role=listbox"));

    tree = run(app, "type \"s\"\nframe\nkey Space control");
    assertTrue(tree.contains("label=\"select\"") && tree.contains("label=\"status\""), tree);
    run(app, "press #option-1");
    assertEquals("SELECT status", app.state().sql);
    run(
        app,
        "key ArrowLeft\nkey ArrowLeft\nkey ArrowLeft\nframe\nkey Space control\nframe\nkey Enter");
    assertEquals("SELECT status", app.state().sql, "the whole word, not SELECT statustus");

    tree = run(app, "type \" co\"\nframe\ntype \"u\"\nframe\nkey ArrowLeft");
    assertFalse(tree.contains("role=listbox"), "moving the caret sideways closes it");
    assertEquals("SELECT status cou", app.state().sql);
  }

  @Test
  void aListWithoutEntriesClosesAndDoesNotComeBackWhereTheCaretGoes() throws IOException {
    TextLibraries.assumeLoaded();
    App<Query> app = app();
    String tree = run(app, "frame\npress #query\ntype \"SELECT 2\"\nframe\ntype \"0\"");
    assertFalse(tree.contains("role=listbox"), "a number has no entries");
    // The caret where every entry would fit, as Home, a press or a find bar puts it: nothing
    // opens there.
    tree = run(app, "key Home");
    assertEquals(0, app.state().editor.caret());
    assertFalse(tree.contains("role=listbox"), tree);
  }

  @Test
  void aKeyWithControlThatTheListDoesNotTakeClosesIt() throws IOException {
    TextLibraries.assumeLoaded();
    App<Query> app = app();
    String tree = run(app, "frame\npress #query\ntype \"SELECT c\"\nframe\ntype \"u\"");
    assertTrue(tree.contains("role=listbox"), tree);
    tree = run(app, "key F control"); // as Control+F, which opens a find bar
    assertFalse(tree.contains("role=listbox"), tree);
  }

  @Test
  void tabTakesTheEntryAndNothingElse() throws IOException {
    TextLibraries.assumeLoaded();
    App<Query> app = app();
    run(app, "frame\npress #query\ntype \"s\"\nframe\ntype \"e\"\nframe\nkey Tab");
    assertEquals("select", app.state().sql, "Tab took it, and indented nothing");
  }

  @Test
  void manySuggestionsBuildOnlyRowsInViewAndKeysRevealAndChooseInProviderOrder()
      throws IOException {
    TextLibraries.assumeLoaded();
    int count = 10_000;
    BitSet built = new BitSet();
    List<Completion.Entry> entries = new AbstractList<>() {
      @Override
      public Completion.Entry get(int index) {
        built.set(index);
        return new Completion.Entry("item_" + (count - index), Symbol.TABLE, "name");
      }

      @Override
      public int size() {
        return count;
      }
    };
    App<Query> app = app(word -> entries); // The provider decides what matches and in what order.
    String tree = run(app, "frame\npress #query\ntype \"SELECT z\"\nframe\ntype \"z\"");
    assertTrue(tree.contains("role=listbox"), tree);
    assertEquals("item_10000", options(tree).getFirst());
    assertTrue(built.cardinality() > 0);
    assertTrue(
        built.cardinality() <= Completion.ROWS + 2 * El.OVERSCAN + 2,
        "only visible rows and overscan built, not all " + count + ": " + built.cardinality());
    assertFalse(tree.contains("#option-9999"), tree);

    tree = run(app, "key ArrowUp");
    assertEquals(count - 1, app.state().completion.highlight(), "Up wraps to the last entry");
    assertTrue(line(tree, "#option-9999").contains(" selected"), tree);
    assertTrue(tree.contains("label=\"item_1\""), tree);

    tree = run(app, "key PageUp");
    assertEquals(count - 1 - Completion.ROWS, app.state().completion.highlight());
    assertTrue(line(tree, "#option-9991").contains(" selected"), tree);
    run(app, "key PageDown");
    assertEquals(count - 1, app.state().completion.highlight(), "Page Down stops at the end");
    run(app, "key ArrowDown");
    assertEquals(0, app.state().completion.highlight(), "Down wraps to the first entry");
    tree = run(app, "key PageDown");
    assertEquals(Completion.ROWS, app.state().completion.highlight());
    assertTrue(line(tree, "#option-8").contains(" selected"), tree);

    tree = run(app, "key Tab");
    assertEquals("SELECT item_9992", app.state().sql);
    assertEquals(app.state().sql.length(), app.state().editor.caret());
    assertFalse(tree.contains("role=listbox"), tree);

    tree = run(app, "key Space control\nframe\nkey Home");
    assertEquals(0, app.state().editor.caret(), "Home closes the list and moves the caret");
    assertFalse(tree.contains("role=listbox"), tree);
    tree = run(app, "key Space control\nframe\nkey End");
    assertEquals(app.state().sql.length(), app.state().editor.caret());
    assertFalse(tree.contains("role=listbox"), tree);
  }

  @Test
  void aDotOpensTheListAndAnEntryPutsInItsOwnText() throws IOException {
    TextLibraries.assumeLoaded();
    List<Query> self = new ArrayList<>();
    App<Query> app = app(word -> {
      Query q = self.getFirst(); // the qualifier before the caret, as the app keeps it
      return q.sql.substring(0, Math.min(q.editor.caret(), q.sql.length())).endsWith("o.")
          ? List.of(new Completion.Entry("order date", Symbol.TABLE, "timestamp", "\"order date\""))
          : List.of();
    });
    self.add(app.state());
    app.state().completion.openAfter(".");
    String tree = run(app, "frame\npress #query\ntype \"o.\"");
    assertTrue(tree.contains("role=option label=\"order date\""), tree);
    run(app, "key Enter");
    assertEquals("o.\"order date\"", app.state().sql);
  }

  @Test
  void entriesFromTheBackgroundShowLoadingUntilTheyArrive() throws IOException {
    TextLibraries.assumeLoaded();
    CountDownLatch release = new CountDownLatch(1);
    App<Query> app = app(
        word -> {
          try {
            release.await();
          } catch (InterruptedException _) {
            Thread.currentThread().interrupt(); // a newer word cancelled it
          }
          return suggest(word);
        },
        true);
    String tree = run(app, "frame\npress #query\ntype \"SELECT cu\"");
    assertTrue(tree.contains("el #loading"), "open with nothing but the waiting row: " + tree);
    release.countDown();
    tree = run(app, "await");
    assertTrue(tree.contains("label=\"customers\"") && !tree.contains("#loading"), tree);
  }

  @Test
  void documentationFromTheBackgroundShowsBesideTheListAndCancelsWhenHighlightMoves()
      throws IOException, InterruptedException {
    TextLibraries.assumeLoaded();
    CountDownLatch customersStarted = new CountDownLatch(1);
    CountDownLatch customersHold = new CountDownLatch(1);
    CountDownLatch customersCancelled = new CountDownLatch(1);
    App<Query> app = app();
    app.state().completion.documentation("doc", entry -> switch (entry.text()) {
      case "customers" -> {
        customersStarted.countDown();
        try {
          customersHold.await();
        } catch (InterruptedException _) {
          customersCancelled.countDown();
          Thread.currentThread().interrupt();
          yield Markdown.parse("Stale customers documentation.");
        }
        yield Markdown.parse("Customer accounts.");
      }
      case "customer_id" -> Markdown.parse("Primary key of `customers`.");
      default -> null;
    });

    // Open with "c" -> customers, customer_id, count; before tooltipDelay nothing shows.
    Script.run(
        "size 640 220\nframe\npress #query\ntype \"SELECT c\"\nkey Space control\nframe\nsnap s",
        app,
        out);
    String tree = java.nio.file.Files.readString(out.resolve("s.tree.txt"));
    assertTrue(tree.contains("el #completions role=listbox"), tree);
    assertFalse(tree.contains("#documentation"), "not shown before tooltipDelay: " + tree);

    // Past tooltipDelay: background lookup for "customers" starts; moving highlight cancels it.
    Script.run("advance 0.6\nframe", app, out);
    assertTrue(
        customersStarted.await(5, TimeUnit.SECONDS), "background work started for customers");
    Script.run("key ArrowDown\nframe\nsnap s", app, out);
    assertTrue(customersCancelled.await(5, TimeUnit.SECONDS), "moving highlight cancelled work");
    customersHold.countDown();
    tree = java.nio.file.Files.readString(out.resolve("s.tree.txt"));
    assertFalse(tree.contains("Stale customers documentation."), tree);
    assertFalse(tree.contains("#documentation"), tree);

    // After tooltipDelay on "customer_id", its documentation arrives beside the list.
    Script.run("advance 0.6\nframe\nawait\nframe\nadvance 0.25\nframe\nsnap s", app, out);
    tree = java.nio.file.Files.readString(out.resolve("s.tree.txt"));
    assertTrue(tree.contains("el #documentation role=dialog label=\"Documentation\""), tree);
    assertTrue(tree.contains("Primary key of customers."), tree);
    assertTrue(
        tree.contains("text #query role=textbox") && tree.contains("focused"),
        "focus stays in the field");
    // Where neither side of the list has room, the panel goes below it, not over its entries.
    Script.run("size 480 320\nframe\nsnap s", app, out);
    tree = java.nio.file.Files.readString(out.resolve("s.tree.txt"));
    int[] list = box(tree, "completions");
    int[] panel = box(tree, "documentation");
    assertTrue(panel[1] >= list[1] + list[3], tree);

    // Moving to an entry whose provider returns null hides the panel and shows nothing.
    Script.run(
        "key ArrowDown\nframe\nadvance 0.6\nframe\nawait\nframe\nadvance 0.25\nframe\nsnap s",
        app,
        out);
    tree = java.nio.file.Files.readString(out.resolve("s.tree.txt"));
    assertTrue(tree.contains("el #completions role=listbox"), tree);
    assertFalse(tree.contains("#documentation"), "null shows nothing: " + tree);
  }

  @Test
  void longDocumentationInASmallWindowShrinksOffTheListSoEntriesStayClickable() throws IOException {
    TextLibraries.assumeLoaded();
    String paragraph = "Long documentation paragraph describing the columns, constraints and "
        + "indexes of the highlighted table in detail.\n\n";
    MarkdownDocument longDoc = Markdown.parse(paragraph.repeat(12));
    App<Query> app = app();
    app.state().completion.documentation("doc", entry -> longDoc);

    Script.run(
        "size 480 320\nframe\npress #query\ntype \"SELECT c\"\nkey Space control\nframe\n"
            + "advance 0.6\nframe\nawait\nframe\nadvance 0.25\nframe\nsnap s",
        app,
        out);
    String tree = java.nio.file.Files.readString(out.resolve("s.tree.txt"));
    int[] panel = assertOffTheList(tree);

    // Where the list leaves too little room on every side, it is not shown rather than over it.
    Script.run("size 204 100\nframe\nsnap s", app, out);
    tree = java.nio.file.Files.readString(out.resolve("s.tree.txt"));
    assertTrue(tree.contains("el #completions") && !tree.contains("el #documentation"), tree);
    Script.run("size 480 320\nframe", app, out);
    // Scrolled to its end, it stays there when a press on it rebuilds the view.
    Script.run("wheel #documentation 0 5000\nframe\nsnap s", app, out);
    String scrolled = scroll(java.nio.file.Files.readString(out.resolve("s.tree.txt")));
    Script.run("press #documentation\nframe\nsnap s", app, out);
    assertEquals(scrolled, scroll(java.nio.file.Files.readString(out.resolve("s.tree.txt"))));

    // Leaving as the highlight moves, it fades out where it was, not over the entries.
    Script.run("key ArrowDown\nframe\nadvance 0.05\nframe\nsnap s", app, out);
    tree = java.nio.file.Files.readString(out.resolve("s.tree.txt"));
    assertTrue(tree.contains("exiting"), tree);
    assertOffTheList(tree);

    // In a larger window, the next entry's panel has its full size beside the list.
    Script.run(
        "size 800 500\nadvance 0.6\nframe\nawait\nframe\nadvance 0.25\nframe\nsnap s", app, out);
    tree = java.nio.file.Files.readString(out.resolve("s.tree.txt"));
    int[] grownList = box(tree, "completions");
    int[] grownPanel = box(tree, "documentation");
    assertTrue(grownPanel[0] >= grownList[0] + grownList[2], tree);
    assertTrue(grownPanel[2] > panel[2] || grownPanel[3] > panel[3], tree);

    // Back in the small window, pressing an entry still hits the list and inserts it.
    Script.run("size 480 320\nframe\npress #option-1\nframe", app, out);
    assertEquals("SELECT customer_id", app.state().sql);
  }

  @Test
  void entriesTheAppLoadsIntoItsOwnStateShowLoadingUntilTheyArrive() throws IOException {
    TextLibraries.assumeLoaded();
    CountDownLatch release = new CountDownLatch(1);
    var catalog = new Object() {
      App<Query> app;
      List<String> names; // the app's own state, which its work fills; null until it has
      boolean asked;

      List<Completion.Entry> suggest(String word) {
        if (!asked) {
          asked = true;
          app.background(
              "names",
              () -> {
                release.await();
                return NAMES;
              },
              found -> names = found,
              error -> {});
        }
        app.state().completion.loading(names == null);
        return names == null ? List.of() : CompletionTest.suggest(word);
      }
    };
    catalog.app = app(catalog::suggest);
    String tree = run(catalog.app, "frame\npress #query\ntype \"SELECT cu\"");
    assertTrue(tree.contains("el #loading"), "open with nothing but the waiting row: " + tree);
    release.countDown();
    tree = run(catalog.app, "await");
    assertTrue(tree.contains("label=\"customers\"") && !tree.contains("#loading"), tree);
  }

  @Test
  void anEditAnsweredInABuildWithoutTheCompletionsEndsTheirWait() throws IOException {
    TextLibraries.assumeLoaded();
    App<Query> app = app();
    String tree = run(app, "frame\npress #query\ntype \"SELECT s\"\nframe\ntype \"t\"");
    assertTrue(tree.contains("label=\"status\"") && !tree.contains("label=\"select\""), tree);
    app.state().plainOnEdit = true; // the build that answers the next edit has no completions
    run(app, "key Backspace");
    app.state().plain = false;
    app.state().plainOnEdit = false;
    app.invalidate();
    tree = run(app, "frame");
    assertEquals("SELECT s", app.state().sql);
    assertTrue(tree.contains("label=\"select\""), "the entries for s, not those for st: " + tree);
  }

  static final class Rows {
    String sql = "";
    int caret;
    boolean growOnEdit; // an edit makes the row above tall, which pushes the field's out of view
    boolean grown;
    boolean plain; // the field without completions
    final Completion.State completion = new Completion.State();
    App<Rows> app;
    El field;
    El other;

    // The entries for the app's text sliced at the app's caret: they throw for one beyond it.
    List<Completion.Entry> suggest(String word) {
      String before = sql.substring(0, caret);
      return CompletionTest.suggest(before.substring(before.lastIndexOf(' ') + 1));
    }
  }

  private static boolean shows(El el, El field) {
    return el == field || el.children().stream().anyMatch(child -> shows(child, field));
  }

  /** A field in a list row with the list for "st" open, and Backspace, whose answer leaves the row out. */
  private static Rows backspaceThatLeavesItsRowOut() {
    TextLibraries.assumeLoaded();
    Rows rows = new Rows();
    rows.app = new App<>(rows, r -> {
      El list = El.el()
          .wFull()
          .h(200)
          .rows(100, new RowHeights(index -> index == 0 && r.grown ? 2000 : 24), index -> {
            if (index != 5) {
              return El.el().h(index == 0 && r.grown ? 2000 : 24);
            }
            El field = El.text(r.sql)
                .h(24)
                .wFull()
                .editable(value -> {
                  r.sql = value;
                  r.grown |= r.growOnEdit;
                })
                .onSelect((caret, anchor) -> r.caret = caret);
            r.field = field;
            return r.plain
                ? field
                : Completion.complete(r.app, field, r.caret, r.completion, r::suggest);
          });
      r.other = El.el().h(20).focusable();
      return col().wFull().hFull().child(r.other).child(list);
    });
    App<Rows> app = rows.app;
    app.textRendering(TextRendering.LINUX);
    app.resize(420, 240, 1);
    app.frame();
    app.focus(rows.field);
    app.frame();
    app.textInput("SELECT s");
    app.frame();
    app.textInput("t");
    app.frame();
    assertTrue(rows.completion.isOpen(), "the list for st");
    rows.growOnEdit = true;
    app.keyDown(new Key("Backspace"));
    app.frame();
    assertFalse(shows(app.tree(), rows.field), "the row is out of view");
    rows.growOnEdit = false;
    return rows;
  }

  @Test
  void aKeyAfterAnEditsRowScrolledBackTakesTheAnsweredEntries() {
    Rows rows = backspaceThatLeavesItsRowOut();
    // Back to it, with no list in view to close, and the next key in the same frame.
    rows.app.wheel(30, 120, 0, 2100);
    rows.app.keyDown(new Key("Enter"));
    rows.app.frame();
    assertEquals("SELECT select", rows.sql, "the first entry for s, not status for st");
  }

  @Test
  void anEditWhoseFieldLostTheFocusOutOfTheTreeIsAnsweredWhenItIsBack() {
    for (boolean plain : new boolean[] {false, true}) {
      Rows rows = backspaceThatLeavesItsRowOut();
      App<Rows> app = rows.app;
      app.focus(rows.other); // the focus no longer keeps the field's editor
      app.frame();
      rows.plain = plain; // back with its completions, or without, which then hear nothing
      app.wheel(30, 120, 0, 2100);
      app.frame();
      rows.plain = false;
      app.invalidate();
      app.frame();
      app.focus(rows.field);
      app.frame();
      app.keyDown(new Key("Enter"));
      app.frame();
      assertEquals("SELECT select", rows.sql, "plain " + plain + ": not status for st");
    }
  }

  @Test
  void contextReportsTriggerAndEntryAppliesRangeAndAdditionalEditAsOneUndo() throws IOException {
    TextLibraries.assumeLoaded();
    List<Completion.Context> contexts = new ArrayList<>();
    Theme theme = Theme.DARK;
    Query query = new Query();
    App<Query> app = new App<>(query, s -> {
      El editor = codeEditor(s.sql, s.editor, s.highlighter, v -> s.sql = v)
          .key("query")
          .lines(4)
          .wFull();
      return col()
          .wFull()
          .hFull()
          .padding(12)
          .fill(theme.surface().window())
          .child(Completion.completeWithContext(
              s.app, editor, s.editor.caret(), s.completion, context -> {
                contexts.add(context);
                int from = Math.max(0, context.wordFrom() - 2); // replace "p." + word
                return List.of(new Completion.Entry(
                    "Path",
                    Symbol.CODE,
                    "class",
                    "Path.of",
                    "(String first) : Path",
                    from,
                    context.wordTo(),
                    List.of(new TextEdit(0, 0, "import java.nio.file.Path;\n"))));
              }));
    });
    query.app = app;
    app.textRendering(TextRendering.LINUX);

    run(app, "frame\npress #query\ntype \"var x = p.pa\"");
    assertTrue(
        contexts.stream().anyMatch(c -> c.trigger() == Completion.Trigger.TYPED),
        "typing opens with TYPED: " + contexts);
    contexts.clear();

    String tree = run(app, "key Space control");
    assertFalse(contexts.isEmpty());
    Completion.Context invoked = contexts.getLast();
    assertEquals(Completion.Trigger.INVOKED, invoked.trigger());
    assertNull(invoked.character());
    assertEquals("var x = p.pa", invoked.text());
    assertEquals(12, invoked.caret());
    assertEquals(10, invoked.wordFrom());
    assertEquals(12, invoked.wordTo());
    assertEquals("pa", invoked.word());
    assertTrue(tree.contains("label=\"Path\""), tree);

    run(app, "key Enter");
    assertEquals("import java.nio.file.Path;\nvar x = Path.of", app.state().sql);
    assertEquals(app.state().sql.length(), app.state().editor.caret());

    run(app, "key Z control");
    assertEquals("var x = p.pa", app.state().sql, "one undo removes both edits");
  }

  /** The options of the open list, each its label. */
  private static List<String> options(String tree) {
    return tree.lines()
        .filter(l -> l.contains("role=option label=\""))
        .map(l -> l.substring(l.indexOf("label=\"") + 7, l.indexOf('"', l.indexOf("label=\"") + 7)))
        .toList();
  }

  /** The line of the element with this key. */
  private static String line(String tree, String key) {
    return tree.lines().filter(l -> l.contains(key + " ")).findFirst().orElse("");
  }
}
