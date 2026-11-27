package com.rethinkj.ui.examples.docs;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Clipboard;
import com.rethinkj.ui.CodeWords;
import com.rethinkj.ui.El;
import com.rethinkj.ui.FieldState;
import com.rethinkj.ui.Formatted;
import com.rethinkj.ui.Highlighter;
import com.rethinkj.ui.Image;
import com.rethinkj.ui.RowHeights;
import com.rethinkj.ui.ScrollState;
import com.rethinkj.ui.Scrollbars;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.TextDocument;
import com.rethinkj.ui.TextEdit;
import com.rethinkj.ui.editor.CodeEditor;
import com.rethinkj.ui.editor.DiffView;
import com.rethinkj.ui.java.Java;
import com.rethinkj.ui.json.Json;
import com.rethinkj.ui.kit.Avatar;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.Chat;
import com.rethinkj.ui.kit.Completion;
import com.rethinkj.ui.kit.Find;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Markdown;
import com.rethinkj.ui.kit.MarkdownDocument;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.Select;
import com.rethinkj.ui.kit.StatusBar;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.TextField;
import com.rethinkj.ui.kit.Texts;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.render.Pixels;
import com.rethinkj.ui.sql.Sql;
import com.rethinkj.ui.text.Font;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The examples of the pages about the code editor, find, completion, Markdown and images. */
final class Editors {

  static final List<String> TABLES = List.of("orders", "order_items", "customers");

  App<?> app; // set by whoever makes the app: the editor edits and selects through it
  String sql = "SELECT id, total\nFROM orders\nWHERE status = 'paid';";
  final CodeEditor.State editor = new CodeEditor.State();
  String draft = ""; // a query not yet typed
  final CodeEditor.State draftEditor = new CodeEditor.State();
  String definition =
      "CREATE VIEW paid_orders AS\nSELECT id, total\nFROM orders\nWHERE status = 'paid';";
  // docs:start code-editor-read-only
  /** The editor of a statement that is only shown: it takes no edits, so it needs no handler. */
  final CodeEditor.State definitionEditor = new CodeEditor.State().readOnly(true);
  // docs:end code-editor-read-only
  String excerpt = """
      if (order.attempts() >= maxAttempts) {
        return Result.failed(order);
      }
      return retry(order);""";
  // docs:start code-editor-excerpt
  final CodeEditor.State excerptEditor = new CodeEditor.State().readOnly(true).firstLine(42);

  static El excerptView(Editors state) {
    return CodeEditor.codeEditor(state.app, state.excerpt, state.excerptEditor, Java.of(), null)
        .key("excerpt")
        .label("Orders.java")
        .lines(4)
        .wFull();
  }
  // docs:end code-editor-excerpt
  String document = """
      {
        "id": 1042,
        "customer": "Ada Lovelace",
        "paid": true,
        "total": 129.5,
        "coupon": null,
        "items": [{"sku": "A-17", "quantity": 2}]
      }""";
  final CodeEditor.State documentEditor = new CodeEditor.State();
  String payload = """
      {
        "id": 1042,
        "customer": {
          "name": "Ada Lovelace",
          "region": "north"
        },
        "items": [
          {
            "sku": "A-17",
            "quantity": 2
          }
        ]
      }""";
  final CodeEditor.State payloadEditor = new CodeEditor.State();
  String report = """
      WITH paid_orders AS (
        SELECT id, customer_id, total, created_at
        FROM orders
        WHERE status = 'paid'
      ),
      monthly AS (
        SELECT
          customer_id,
          date_trunc('month', created_at) AS month,
          count(*) AS order_count,
          sum(total) AS revenue
        FROM paid_orders
        GROUP BY customer_id, month
      ),
      ranked AS (
        SELECT
          c.name,
          m.customer_id,
          m.month,
          m.order_count,
          m.revenue
        FROM monthly m
        JOIN customers c ON c.id = m.customer_id
        WHERE m.revenue > 0
      )
      SELECT name, month, order_count, revenue
      FROM ranked
      WHERE legacy_flag = true
      ORDER BY revenue DESC;""";
  final CodeEditor.State reportEditor = new CodeEditor.State();
  // docs:start code-editor-folding
  List<El.Fold> savedFolds = List.of();

  void saveFolds() {
    savedFolds = payloadEditor.folds();
  }

  void restoreFolds() {
    payloadEditor.folds(savedFolds);
  }
  // docs:end code-editor-folding
  String views = """
      CREATE VIEW paid_orders AS
      SELECT id, total
      FROM orders
      WHERE status = 'paid';

      CREATE VIEW recent_orders AS
      SELECT id, total
      FROM paid_orders
      WHERE placed_at >= CURRENT_DATE - 30;""";
  final CodeEditor.State visionEditor = new CodeEditor.State();
  String selectedVision;
  String broken = "SELECT id, total\nFROM ordrs\nWHERE status = 'paid';";
  final CodeEditor.State fixesEditor = new CodeEditor.State();
  String inlineSql = """
      SELECT id, tax
      FROM orders
      WHERE status = 'paid'
      ORDER BY created_at DESC;""";
  // docs:start code-editor-inline-diagnostics
  final CodeEditor.State inlineEditor = new CodeEditor.State().inlineDiagnostics(true);

  /** Reports an error and a warning; each line shows its most severe message after its end. */
  void showInlineDiagnostics() {
    int tax = inlineSql.indexOf("tax");
    int status = inlineSql.indexOf("status");
    inlineEditor.diagnostics(
        inlineSql,
        List.of(
            new CodeEditor.Diagnostic(
                tax,
                tax + "tax".length(),
                CodeEditor.Severity.ERROR,
                "column \"tax\" does not exist"),
            new CodeEditor.Diagnostic(
                status,
                status + "status".length(),
                CodeEditor.Severity.WARNING,
                "predicate on status is not indexed")));
  }
  // docs:end code-editor-inline-diagnostics
  String gutterSql = """
      SELECT o.id, o.total, o.tax
      FROM orders o
      JOIN customers c ON c.id = o.customer_id
      WHERE o.status = 'paid'
        AND o.region = 'north'
      ORDER BY o.total DESC
      LIMIT 50;""";
  final CodeEditor.State gutterEditor = new CodeEditor.State();
  // docs:start code-editor-line-action
  CodeEditor.Lines commentedLines;
  final CodeEditor.State lineActionEditor =
      new CodeEditor.State().lineAction(Symbol.ADD, "Add comment", this::commentOnLines);

  void commentOnLines(CodeEditor.Lines lines) {
    commentedLines = lines;
  }
  // docs:end code-editor-line-action

  // docs:start code-editor-gutter
  /** Places an error, two markers on line 1, and VCS changes in the gutter. */
  void showGutter() {
    int tax = gutterSql.indexOf("o.tax");
    gutterEditor.diagnostics(
        gutterSql,
        List.of(new CodeEditor.Diagnostic(
            tax,
            tax + "o.tax".length(),
            CodeEditor.Severity.ERROR,
            "column o.tax does not exist")));
    gutterEditor.markup(
        "app",
        gutterSql,
        List.of(
            new CodeEditor.Marker(
                0, Symbol.PLAY, Icon.Variant.ACCENT, "Run statement", this::runGutterQuery),
            new CodeEditor.Marker(0, Symbol.RECORD, Icon.Variant.DANGER, "Breakpoint", null)));
    int selectTo = gutterSql.indexOf("\nFROM");
    int joinFrom = gutterSql.indexOf("JOIN");
    int joinTo = gutterSql.indexOf("\nWHERE");
    int whereFrom = joinTo + 1;
    int whereTo = gutterSql.indexOf("\nORDER");
    int orderFrom = whereTo + 1;
    gutterEditor.markup(
        "vcs",
        gutterSql,
        List.of(
            new CodeEditor.Change(0, selectTo, CodeEditor.Change.Kind.MODIFIED),
            new CodeEditor.Change(joinFrom, joinTo, CodeEditor.Change.Kind.MODIFIED),
            new CodeEditor.Change(whereFrom, whereTo, CodeEditor.Change.Kind.ADDED),
            new CodeEditor.Change(orderFrom, orderFrom, CodeEditor.Change.Kind.DELETED)));
  }
  // docs:end code-editor-gutter

  // docs:start code-editor-fixes
  /** Reports an unknown table name with two quick fixes to replace it. */
  void showFixes() {
    int from = broken.indexOf("ordrs");
    int to = from + "ordrs".length();
    fixesEditor.diagnostics(
        broken,
        List.of(new CodeEditor.Diagnostic(
            from,
            to,
            CodeEditor.Severity.ERROR,
            "relation \"ordrs\" does not exist",
            List.of(
                new CodeEditor.Fix("Replace with 'orders'", from, to, "orders"),
                new CodeEditor.Fix("Replace with 'order_items'", from, to, "order_items")))));
  }
  // docs:end code-editor-fixes

  // docs:start code-editor-vision
  void showPaidUsages() {
    selectedVision = "paid_orders";
  }

  void showRecentUsages() {
    selectedVision = "recent_orders";
  }

  /** Places code vision entries above each view declaration. */
  void showVision() {
    visionEditor.codeVision(List.of(
        new CodeEditor.Vision(0, null, "3 usages", this::showPaidUsages),
        new CodeEditor.Vision(0, Symbol.PERSON, "Ada Lovelace", null),
        new CodeEditor.Vision(5, null, "1 usage", this::showRecentUsages),
        new CodeEditor.Vision(5, Symbol.PERSON, "Grace Hopper", null)));
  }
  // docs:end code-editor-vision
  // docs:start code-editor-documentation
  final CodeEditor.State documentedEditor =
      new CodeEditor.State().documentation(this::documentationAt);

  MarkdownDocument documentationAt(int offset) {
    int start = CodeWords.start(sql, offset);
    int end = CodeWords.end(sql, offset);
    return switch (sql.substring(start, end)) {
      case "orders" -> Markdown.parse("""
          **Table `orders`**

          Customer orders placed in the store. Columns: `id`, `total`, `status`.""");
      case "total" -> Markdown.parse("Order total in cents, including tax.");
      default -> null;
    };
  }
  // docs:end code-editor-documentation

  String javaSource = """
      public class Orders {
        /**
         * Finds paid orders in a date range, newest first.
         * Uses the {@code orders_placed_at} index; see {@link Order}.
         *
         * @param from start of the range, inclusive, in UTC
         * @param to end of the range, exclusive; must lie after {@code from}
         * @return matching orders by {@code placed_at} descending
         * @throws IllegalArgumentException when {@code to} is not after {@code from}
         */
        public List<Order> findOrders(Instant from, Instant to) {
          /** Inline doc comments stay raw. */ int limit = 100;
          return query(from, to, limit);
        }

        /**
         * Counts open orders for a customer.
         *
         * @param customerId the customer identifier
         * @return number of open orders
         */
        public int countOpen(long customerId) {
          return count(customerId);
        }
      }""";
  // docs:start code-editor-reader-mode-state
  /** Doc comments rendered; a link in one goes to {@link #link}. */
  final CodeEditor.State javaEditor = new CodeEditor.State().readerMode(true).onLink(this::link);
  // docs:end code-editor-reader-mode-state
  String cteQuery = """
      WITH paid_orders AS (
        SELECT id, total
        FROM orders
      )
      SELECT id, total
      FROM paid_orders
      WHERE total > (SELECT avg(total) FROM paid_orders);""";
  CodeEditor.Location openedLocation;
  // docs:start code-editor-navigation
  final CodeEditor.State navigatedEditor = new CodeEditor.State()
      .declaration(offset -> declarationIn(cteQuery, offset))
      .usages(offset -> usagesIn(cteQuery, offset))
      .onOpen(this::openLocation);

  CodeEditor.Location declarationIn(String sql, int offset) {
    int start = CodeWords.start(sql, offset);
    int end = CodeWords.end(sql, offset);
    return switch (sql.substring(start, end)) {
      case "paid_orders" -> {
        int declared = sql.indexOf("paid_orders");
        yield new CodeEditor.Location(declared, declared + "paid_orders".length());
      }
      case "orders" -> new CodeEditor.Location("schema.sql", 14, 20, null);
      default -> null;
    };
  }

  List<CodeEditor.Location> usagesIn(String sql, int offset) {
    int start = CodeWords.start(sql, offset);
    int end = CodeWords.end(sql, offset);
    if (!sql.substring(start, end).equals("paid_orders")) {
      return List.of();
    }
    List<CodeEditor.Location> found = new ArrayList<>();
    String symbol = "paid_orders";
    for (int at = sql.indexOf(symbol); at >= 0; at = sql.indexOf(symbol, at + symbol.length())) {
      found.add(new CodeEditor.Location(at, at + symbol.length()));
    }
    found.add(new CodeEditor.Location("report.sql", 8, 19, null));
    return found;
  }

  void openLocation(CodeEditor.Location location) {
    openedLocation = location;
  }
  // docs:end code-editor-navigation
  // docs:start code-editor-navigation-background
  final CodeEditor.State remoteEditor = new CodeEditor.State().onOpen(this::openLocation);

  // Called on each build, before CodeEditor.codeEditor.
  void lookUpInBackground() {
    String sql = cteQuery; // the text the offsets are in; the work reads it on another thread
    remoteEditor
        .declarationInBackground("declaration", offset -> declarationIn(sql, offset))
        .usagesInBackground("usages", offset -> usagesIn(sql, offset));
  }
  // docs:end code-editor-navigation-background
  // docs:start line-blocks
  String source = "/** Summarizes paid orders. */\nint total() {\n  return 0;\n}";
  List<El.Block> lineBlocks = new ArrayList<>(List.of(El.Block.replace(0, 0), El.Block.insert(1)));

  void updateSource(String value) {
    source = value;
  }

  void revealBlock(El.Block block) {
    lineBlocks.remove(block);
  }

  El renderBlock(El.Block block) {
    return block.placement() == El.Block.Placement.REPLACE
        ? El.text("Summarizes paid orders.").fontSize(12)
        : El.text("2 usages").fontSize(11);
  }

  El sourceField() {
    return El.text(source)
        .editable(this::updateSource)
        .multiline()
        .blocks(lineBlocks, this::renderBlock, this::revealBlock);
  }
  // docs:end line-blocks
  // docs:start line-ends
  final Map<Integer, String> problems = Map.of(2, "missing return value");

  List<El> problemAt(int line) {
    String problem = problems.get(line);
    return problem == null ? List.of() : List.of(El.text(problem).fontSize(11));
  }

  El annotatedField() {
    return El.text(source).editable(this::updateSource).multiline().lineEnds(this::problemAt);
  }
  // docs:end line-ends
  // docs:start shared-document
  final TextDocument shared = new TextDocument();
  final FieldState leftPane = new FieldState(shared);
  final FieldState rightPane = new FieldState(shared);
  final ScrollState leftScroll = new ScrollState();
  final ScrollState rightScroll = new ScrollState();

  {
    leftScroll.scrollWith(rightScroll, List.of()); // line n beside line n
  }

  El splitView() {
    return El.row()
        .wFull()
        .gap(8)
        .child(pane(leftPane, leftScroll))
        .child(pane(rightPane, rightScroll));
  }

  El pane(FieldState pane, ScrollState scroll) {
    return El.text(source)
        .editable(this::updateSource)
        .multiline()
        .fieldState(pane)
        .scrollState(scroll)
        .grow(1);
  }
  // docs:end shared-document
  // docs:start inlay-hints
  String call = "var files = scan(root, true);";
  List<El.Hint> callHints = List.of(
      new El.Hint(
          call.indexOf("files") + "files".length(), El.Hint.Affinity.BEFORE, ": List<Path>"),
      new El.Hint(call.indexOf("root"), El.Hint.Affinity.AFTER, "folder:"),
      new El.Hint(call.indexOf("true"), El.Hint.Affinity.AFTER, "recursive:"));

  void updateCall(String value) {
    call = value;
    callHints = List.of(); // their offsets are stale; the analysis computes them for the new text
  }

  El renderHint(El.Hint hint) {
    return El.row()
        .padding(0, 4)
        .margin(0, 2, 0, 2)
        .radius(4)
        .child(El.text(hint.text()).fontSize(11));
  }

  El hintedField() {
    return El.text(call).editable(this::updateCall).multiline().hints(callHints, this::renderHint);
  }
  // docs:end inlay-hints
  String hintedJava = """
      class Scanner {
        void run(Path root) {
          var files = scan(root, true);
          var count = files.size();
        }
      }""";
  final CodeEditor.State hintedEditor = new CodeEditor.State();

  // docs:start code-editor-inlay-hints
  /** Attaches inferred-type and parameter-name hints to a markup layer. */
  void showHints() {
    int filesEnd = hintedJava.indexOf("files") + "files".length();
    int rootAt = hintedJava.indexOf("root,");
    int trueAt = hintedJava.indexOf("true");
    int countEnd = hintedJava.indexOf("count") + "count".length();
    hintedEditor.markup(
        "hints",
        hintedJava,
        List.of(
            new CodeEditor.InlayHint(filesEnd, El.Hint.Affinity.BEFORE, ": List<Path>"),
            new CodeEditor.InlayHint(rootAt, El.Hint.Affinity.AFTER, "folder:"),
            new CodeEditor.InlayHint(trueAt, El.Hint.Affinity.AFTER, "recursive:"),
            new CodeEditor.InlayHint(countEnd, El.Hint.Affinity.BEFORE, ": int")));
  }
  // docs:end code-editor-inlay-hints
  String reviewSql = """
      SELECT id, total
      FROM orders
      WHERE status = 'paid'
      ORDER BY placed_at DESC;""";
  final CodeEditor.State reviewEditor = new CodeEditor.State();
  String reviewReply = "";

  // docs:start code-editor-blocks
  void reviewReply(String value) {
    reviewReply = value;
  }

  /** Attaches a review card below the WHERE line; its supplier reads live state on each build. */
  void showReview() {
    int lineStart = reviewSql.indexOf("WHERE");
    reviewEditor.markup(
        "review", reviewSql, List.of(new CodeEditor.Block(lineStart, this::reviewCard)));
  }

  El reviewCard() {
    Theme theme = Theme.CURRENT.get();
    El header = El.row()
        .align(Style.Align.CENTER)
        .gap(theme.space().s())
        .child(Avatar.avatar("Ada Lovelace", null, 20))
        .child(Label.label(Label.Variant.STRONG, "Ada Lovelace"))
        .child(Label.label(Label.Variant.SMALL, "2m ago"));
    El card = El.col()
        .fill(theme.surface().raised())
        .border(theme.size().stroke(), theme.border().subtle())
        .radius(theme.radius().large())
        .padding(theme.space().m())
        .gap(theme.space().s())
        .w(420)
        .child(header)
        .child(Label.label("Should this also exclude refunded orders?").wrap())
        .child(TextField.textField(reviewReply, "Write a reply", this::reviewReply)
            .key("reply")
            .wFull());
    return El.col().key("review-1").padding(theme.space().s(), 0).child(card);
  }
  // docs:end code-editor-blocks
  final Completion.State completion = new Completion.State();
  String completionSql = "SELECT id, total\nFROM ";
  final CodeEditor.State completionEditor = new CodeEditor.State();
  // docs:start completion-documentation
  final Completion.State documentedCompletion =
      new Completion.State().documentation("completion-doc", Editors::tableDocumentation);

  static MarkdownDocument tableDocumentation(Completion.Entry entry) {
    return switch (entry.text()) {
      case "orders" -> Markdown.parse("""
          **Table `orders`**

          Customer orders placed in the store. Columns: `id`, `customer_id`, `total`, `status`.""");
      case "order_items" -> Markdown.parse("""
          **Table `order_items`**

          Line items of an order. Columns: `order_id`, `sku`, `quantity`, `price`.""");
      default -> null;
    };
  }
  // docs:end completion-documentation
  String notes = "Ship by Friday.\nCall the north warehouse on Monday.";
  final Find.State find = new Find.State();
  final MarkdownDocument readme = Markdown.parse("""
      # Orders

      Each **order** belongs to a customer. See [customers](#customers).

      ```sql
      SELECT count(*) FROM orders;
      ```
      """);
  final MarkdownDocument summary = Markdown.parse("""
      Finds orders placed in a date range, newest first.

      Uses the `orders_placed_at` index; see [indexes](#indexes).""");
  final MarkdownDocument fromDoc = Markdown.parse("Start of the range, inclusive, in UTC.");
  final MarkdownDocument toDoc =
      Markdown.parse("End of the range, exclusive; must lie after `from`.");
  final MarkdownDocument returnsDoc =
      Markdown.parse("Matching orders ordered by `placed_at` descending.");
  final MarkdownDocument throwsDoc = Markdown.parse("When `to` is not after `from`.");
  final Image swatch = Image.of(swatch(), 2); // 64 by 40 pixels made for scale 2
  String link = "";
  String ran; // the statement the marker ran last

  Editors() {
    showRun();
    showVision();
    showFixes();
    showInlineDiagnostics();
    showGutter();
    showHints();
    showReview();
    showDiffReview();
    showDiffReviewUnified();
    int orders = sql.indexOf("orders");
    refused(sql, orders, orders + "orders".length(), "relation \"orders\" does not exist");
    int unindexed = report.indexOf("m.revenue > 0");
    int missing = report.indexOf("legacy_flag");
    reportEditor.diagnostics(
        report,
        List.of(
            new CodeEditor.Diagnostic(
                unindexed,
                unindexed + "m.revenue > 0".length(),
                CodeEditor.Severity.WARNING,
                "Predicate on revenue is not indexed"),
            new CodeEditor.Diagnostic(
                missing,
                missing + "legacy_flag".length(),
                CodeEditor.Severity.ERROR,
                "column \"legacy_flag\" does not exist")));
  }

  void sql(String value) {
    sql = value;
  }

  // docs:start code-editor-marks
  void run() {
    ran = sql;
  }

  /** A marker at the statement's first line that runs it. */
  void showRun() {
    editor.markers(List.of(
        new CodeEditor.Marker(0, Symbol.PLAY, Icon.Variant.ACCENT, "Run statement", this::run)));
  }

  /** The database refused {@code statement}: its error moves with edits elsewhere. */
  void refused(String statement, int from, int to, String message) {
    editor.diagnostics(
        statement,
        List.of(new CodeEditor.Diagnostic(from, to, CodeEditor.Severity.ERROR, message)));
  }

  /** Highlights references to a symbol in a layer of its own alongside diagnostics. */
  void showReferences(String source, List<CodeEditor.Highlight> usages) {
    editor.markup("references", source, usages);
  }
  // docs:end code-editor-marks

  void draft(String value) {
    draft = value;
  }

  // docs:start code-editor-insert
  /** Puts a join in place of the selection, as one step that Control+Z takes back. */
  void insertJoin() {
    editor.replaceSelection("JOIN customers c ON c.id = o.customer_id\n");
  }

  /** Puts in a count with the cursor between its parentheses. */
  void insertCount() {
    editor.replaceSelection("count()", "count(".length());
  }

  static El insertJoinButton(Editors state) {
    return Button.button(Button.Variant.SECONDARY, "Insert join", state::insertJoin);
  }
  // docs:end code-editor-insert

  void document(String value) {
    document = value;
  }

  void payload(String value) {
    payload = value;
  }

  void views(String value) {
    views = value;
  }

  void report(String value) {
    report = value;
  }

  void broken(String value) {
    broken = value;
  }

  void inlineSql(String value) {
    inlineSql = value;
  }

  void gutterSql(String value) {
    gutterSql = value;
  }

  void runGutterQuery() {
    ran = gutterSql;
  }

  void javaSource(String value) {
    javaSource = value;
  }

  void hintedJava(String value) {
    hintedJava = value;
  }

  void reviewSql(String value) {
    reviewSql = value;
  }

  // docs:start code-editor-json-format
  /** A JSON value of a database's column, in an editor that only reads it. */
  String cell = "";

  final CodeEditor.State cellEditor = new CodeEditor.State().readOnly(true);

  /**
   * Shows the value formatted; JSON that is not valid as it is, with its first problem in the texts
   * of the view the handler came from: {@code Texts.CURRENT.get()} where the view builds.
   */
  void show(String value, Texts texts) {
    Formatted formatted = Json.format(value);
    if (formatted.valid()) {
      cell = formatted.text();
      cellEditor.noDiagnostics();
      return;
    }
    Formatted.Problem problem = formatted.problem();
    String message = texts.formatProblem(problem.kind());
    cell = value;
    cellEditor.diagnostics(
        value,
        List.of(new CodeEditor.Diagnostic(
            problem.from(), problem.to(), CodeEditor.Severity.ERROR, message)));
  }
  // docs:end code-editor-json-format

  void notes(String value) {
    notes = value;
  }

  void link(String destination) {
    link = destination;
  }

  static String codeWord(String text, int caret) {
    // docs:start code-words
    int from = CodeWords.start(text, caret);
    int to = CodeWords.end(text, caret);
    return text.substring(from, to);
    // docs:end code-words
  }

  static El codeEditor(Editors state) {
    // docs:start code-editor
    El editor = CodeEditor.codeEditor(state.app, state.sql, state.editor, Sql.of(), state::sql)
        .key("query")
        .label("Query")
        .lines(8)
        .wFull();
    // docs:end code-editor
    // docs:start completion
    El withCompletions = Completion.complete(
        state.app,
        editor,
        state.editor.caret(),
        state.completion,
        word -> TABLES.stream()
            .filter(name -> name.startsWith(word.toLowerCase(Locale.ROOT)))
            .map(name -> new Completion.Entry(name, Symbol.TABLE, "table"))
            .toList());
    // docs:end completion
    // docs:start code-editor-embedded
    El card = Card.flush(Card.toolbar("orders.sql", null), CodeEditor.embedded(withCompletions));
    // docs:end code-editor-embedded
    return Frame.of(card.wFull());
  }

  void completionSql(String value) {
    completionSql = value;
  }

  static El completionDocumentation(Editors state) {
    El editor = CodeEditor.codeEditor(
            state.app, state.completionSql, state.completionEditor, Sql.of(), state::completionSql)
        .key("query")
        .label("Query")
        .lines(6)
        .wFull();
    El withCompletions = Completion.complete(
        state.app,
        editor,
        state.completionEditor.caret(),
        state.documentedCompletion,
        word -> TABLES.stream()
            .filter(name -> name.startsWith(word.toLowerCase(Locale.ROOT)))
            .map(name -> new Completion.Entry(name, Symbol.TABLE, "table"))
            .toList());
    return Frame.of(withCompletions);
  }

  // docs:start code-editor-completion
  final Completion.State javaCompletion = new Completion.State().openAfter(".");

  List<Completion.Entry> suggestJava(Completion.Context context) {
    if (context.trigger() == Completion.Trigger.OPEN_AFTER && ".".equals(context.character())) {
      return List.of(new Completion.Entry(
          "size", Symbol.CODE, "method", "size()", "() : int", -1, -1, List.of()));
    }
    return List.of(new Completion.Entry(
        "Instant",
        Symbol.CODE,
        "class",
        "Instant",
        "java.time",
        context.wordFrom(),
        context.wordTo(),
        List.of(new TextEdit(0, 0, "import java.time.Instant;\n"))));
  }

  static El javaCodeCompletion(Editors state) {
    El editor = CodeEditor.codeEditor(
            state.app, state.javaSource, state.javaEditor, Java.of(), state::javaSource)
        .key("java")
        .lines(8)
        .wFull();
    return Completion.completeWithContext(
        state.app, editor, state.javaEditor.caret(), state.javaCompletion, state::suggestJava);
  }
  // docs:end code-editor-completion

  static El placeholderAndReadOnly(Editors state) {
    // docs:start code-editor-placeholder
    El draft = CodeEditor.codeEditor(
            state.app, state.draft, "Type a query", state.draftEditor, Sql.of(), state::draft)
        .key("draft")
        .label("Query")
        .lines(3)
        .wFull();
    El definition = CodeEditor.codeEditor(
            state.app, state.definition, state.definitionEditor, Sql.of(), null)
        .key("definition")
        .label("Definition")
        .lines(4)
        .wFull();
    // docs:end code-editor-placeholder
    return Frame.of(draft, definition);
  }

  static El excerpt(Editors state) {
    return Frame.of(excerptView(state));
  }

  // docs:start code-editor-position
  /** The cursor's line and column, such as "Ln 3, Col 9", for the window's status bar. */
  static El cursorPosition(Editors state) {
    return StatusBar.text("Ln " + state.editor.line() + ", Col " + state.editor.column());
  }
  // docs:end code-editor-position

  static El json(Editors state) {
    // docs:start code-editor-json
    El editor = CodeEditor.codeEditor(
            state.app, state.document, state.documentEditor, Json.of(), state::document)
        .key("document")
        .label("Document")
        .lines(8)
        .wFull();
    // docs:end code-editor-json
    return Frame.of(editor);
  }

  static El folding(Editors state) {
    El editor = CodeEditor.codeEditor(
            state.app, state.payload, state.payloadEditor, Json.of(), state::payload)
        .key("payload")
        .label("Payload")
        .lines(9)
        .wFull();
    return Frame.of(editor);
  }

  static El vision(Editors state) {
    El editor = CodeEditor.codeEditor(
            state.app, state.views, state.visionEditor, Sql.of(), state::views)
        .key("views")
        .label("Views")
        .lines(12)
        .wFull();
    if (state.selectedVision != null) {
      return Frame.of(
          editor,
          Label.label(Label.Variant.MUTED, "Usages of " + state.selectedVision).key("usages"));
    }
    return Frame.of(editor);
  }

  static El scrollbarMarks(Editors state) {
    // docs:start code-editor-scrollbar-marks
    El editor = CodeEditor.codeEditor(
            state.app, state.report, state.reportEditor, Sql.of(), state::report)
        .key("report")
        .label("Report")
        .lines(10)
        .wFull();
    // docs:end code-editor-scrollbar-marks
    return Frame.of(editor);
  }

  static El gutter(Editors state) {
    El editor = CodeEditor.codeEditor(
            state.app, state.gutterSql, state.gutterEditor, Sql.of(), state::gutterSql)
        .key("gutterQuery")
        .label("Query")
        .lines(7)
        .wFull();
    return Frame.of(editor);
  }

  static El lineAction(Editors state) {
    El editor = CodeEditor.codeEditor(
            state.app, state.gutterSql, state.lineActionEditor, Sql.of(), state::gutterSql)
        .key("lineActionQuery")
        .label("Query")
        .lines(5)
        .wFull();
    return Frame.of(editor);
  }

  /** Marks lines the app knows as changed, such as from version control. */
  static El changes(El editor, int changeStart, int changeEnd) {
    // docs:start scroll-marks
    Theme theme = Theme.CURRENT.get();
    editor.scrollMarks(
        List.of(new Scrollbars.Mark(changeStart, changeEnd, theme.status().success())));
    // docs:end scroll-marks
    return editor;
  }

  static El fixes(Editors state) {
    El editor = CodeEditor.codeEditor(
            state.app, state.broken, state.fixesEditor, Sql.of(), state::broken)
        .key("fixes")
        .label("Query")
        .lines(7)
        .wFull();
    return Frame.of(editor);
  }

  static El inlineDiagnostics(Editors state) {
    El editor = CodeEditor.codeEditor(
            state.app, state.inlineSql, state.inlineEditor, Sql.of(), state::inlineSql)
        .key("inline")
        .label("Query")
        .lines(4)
        .wFull();
    return Frame.of(editor);
  }

  static El documentation(Editors state) {
    El editor = CodeEditor.codeEditor(
            state.app, state.sql, state.documentedEditor, Sql.of(), state::sql)
        .key("documented")
        .label("Query")
        .lines(6)
        .wFull();
    return Frame.of(editor);
  }

  static El readerMode(Editors state) {
    // docs:start code-editor-reader-mode
    El editor = CodeEditor.codeEditor(
            state.app, state.javaSource, state.javaEditor, Java.of(), state::javaSource)
        .key("orders")
        .label("Orders")
        .lines(16)
        .wFull();
    // docs:end code-editor-reader-mode
    return Frame.of(editor);
  }

  void cteQuery(String value) {
    cteQuery = value;
  }

  String oldQuery = """
      -- Monthly revenue report
      -- Owner: analytics
      -- Warehouse: main
      -- Schedule: daily
      -- Retention: 90 days

      SELECT
        o.id,
        o.total
      FROM orders o
      WHERE o.status = 'pending'
        AND o.total > 0
        AND o.legacy = false
      ORDER BY o.total DESC
      LIMIT 50;""";
  String newQuery = """
      -- Monthly revenue report
      -- Owner: analytics
      -- Warehouse: main
      -- Schedule: daily
      -- Retention: 90 days

      SELECT
        o.id,
        o.tax,
        o.total
      FROM orders o
      WHERE o.status = 'paid'
        AND o.total > 0
      ORDER BY o.total DESC
      LIMIT 50;""";
  final DiffView.State diffState = new DiffView.State();
  // docs:start diff-view-review
  final DiffView.State reviewedDiff = new DiffView.State();
  DiffView.Lines reviewedLines;
  String diffReply = "";

  void commentOnDiff(DiffView.Lines lines) {
    reviewedLines = lines;
  }

  void diffReply(String value) {
    diffReply = value;
  }

  /** Adds a gutter comment action, a noted range and a review card to the new side. */
  void showDiffReview() {
    int rangeFrom = newQuery.indexOf("WHERE");
    int lineStart = newQuery.indexOf("  AND o.total > 0");
    int rangeTo = lineStart + "  AND o.total > 0".length();
    reviewedDiff
        .lineAction(Symbol.ADD, "Add comment", this::commentOnDiff)
        .markup(
            DiffView.Side.NEW,
            "review",
            newQuery,
            List.of(
                new CodeEditor.Highlight(rangeFrom, rangeTo, CodeEditor.Highlight.Tone.NOTE),
                new CodeEditor.Block(lineStart, this::diffReviewCard)));
  }

  El diffReviewCard() {
    Theme theme = Theme.CURRENT.get();
    El header = El.row()
        .align(Style.Align.CENTER)
        .gap(theme.space().s())
        .child(Avatar.avatar("Ada Lovelace", null, 20))
        .child(Label.label(Label.Variant.STRONG, "Ada Lovelace"))
        .child(Label.label(Label.Variant.SMALL, "2m ago"));
    El card = El.col()
        .fill(theme.surface().raised())
        .border(theme.size().stroke(), theme.border().subtle())
        .radius(theme.radius().large())
        .padding(theme.space().m())
        .gap(theme.space().s())
        .wFull()
        .child(header)
        .child(Label.label("Should this also exclude refunded orders?").wrap())
        .child(TextField.textField(diffReply, "Write a reply", this::diffReply)
            .key("diff-reply")
            .wFull());
    return El.col()
        .key("diff-review-1")
        .wFull()
        .padding(theme.space().s(), theme.space().m(), theme.space().s(), 0)
        .child(card);
  }
  // docs:end diff-view-review

  final DiffView.State reviewedUnifiedDiff = new DiffView.State();

  /** Adds a gutter comment action, a noted removed line and a review card on the old side. */
  void showDiffReviewUnified() {
    int lineStart = oldQuery.indexOf("  AND o.legacy = false");
    int lineEnd = lineStart + "  AND o.legacy = false".length();
    reviewedUnifiedDiff
        .lineAction(Symbol.ADD, "Add comment", this::commentOnDiff)
        .markup(
            DiffView.Side.OLD,
            "review",
            oldQuery,
            List.of(
                new CodeEditor.Highlight(lineStart, lineEnd, CodeEditor.Highlight.Tone.NOTE),
                new CodeEditor.Block(lineStart, this::diffReviewCard)));
  }

  static El diffSideBySide(Editors state) {
    // docs:start diff-view-side-by-side
    El diff = DiffView.sideBySide(
            state.app, state.oldQuery, state.newQuery, state.diffState, Sql.of())
        .wFull()
        .h(224);
    // docs:end diff-view-side-by-side
    return Frame.of(diff);
  }

  static El diffReview(Editors state) {
    El diff = DiffView.sideBySide(
            state.app, state.oldQuery, state.newQuery, state.reviewedDiff, Sql.of())
        .wFull()
        .h(316);
    return Frame.of(diff);
  }

  static El diffReviewUnified(Editors state) {
    El diff = DiffView.unified(
            state.app, state.oldQuery, state.newQuery, state.reviewedUnifiedDiff, Sql.of())
        .wFull()
        .h(336);
    return Frame.of(diff);
  }

  static El diffUnified(Editors state) {
    // docs:start diff-view-unified
    El diff = DiffView.unified(state.app, state.oldQuery, state.newQuery, state.diffState, Sql.of())
        .wFull()
        .h(260);
    // docs:end diff-view-unified
    return Frame.of(diff);
  }

  static El navigation(Editors state) {
    El editor = CodeEditor.codeEditor(
            state.app, state.cteQuery, state.navigatedEditor, Sql.of(), state::cteQuery)
        .key("navigation")
        .label("Query")
        .lines(8)
        .wFull();
    return Frame.of(editor);
  }

  static El inlayHints(Editors state) {
    El editor = CodeEditor.codeEditor(
            state.app, state.hintedJava, state.hintedEditor, Java.of(), state::hintedJava)
        .key("scanner")
        .label("Scanner")
        .lines(6)
        .wFull();
    return Frame.of(editor);
  }

  static El blocks(Editors state) {
    El editor = CodeEditor.codeEditor(
            state.app, state.reviewSql, state.reviewEditor, Sql.of(), state::reviewSql)
        .key("review")
        .label("Query")
        .lines(10)
        .wFull();
    return Frame.of(editor);
  }

  static El find(Editors state) {
    // docs:start find
    El notes = TextField.textArea(state.notes, "Notes", state::notes)
        .key("notes")
        .lines(6)
        .w(360);
    // Control+F, Control+H, F3, Control+G
    El withFind = Find.find(state.app, notes, state.find);
    // docs:end find
    return Frame.of(withFind);
  }

  static El markdown(Editors state) {
    // docs:start markdown
    El document = Markdown.markdown(
            state.readme,
            Markdown.options()
                .onLink(state::link)
                .copy(state.app.clipboard())
                .languages(Editors::highlighter))
        .key("readme")
        .grow(1);
    // docs:end markdown
    return Frame.of(Card.flush(document).wFull().grow(1));
  }

  static El markdownFragment(Editors state) {
    // docs:start markdown-fragment
    Texts texts = Texts.CURRENT.get();
    Markdown.Options options = Markdown.options().onLink(state::link);
    El body = Markdown.fragment(state.summary, options);
    El sections = Markdown.definitions(
        List.of(
            new Markdown.Section(
                texts.parameters(),
                List.of(
                    new Markdown.Definition("from", state.fromDoc),
                    new Markdown.Definition("to", state.toDoc))),
            new Markdown.Section(
                texts.returns(), List.of(new Markdown.Definition(null, state.returnsDoc))),
            new Markdown.Section(
                texts.thrown(),
                List.of(new Markdown.Definition("IllegalArgumentException", state.throwsDoc)))),
        options);
    El card = Card.card(body, sections).wFull();
    // docs:end markdown-fragment
    return Frame.of(card);
  }

  // docs:start markdown-tasks
  String checklist = """
      - [x] Review schema migration
      - [ ] Run backfill on staging
        - [ ] Verify row counts
      - [ ] Deploy release""";
  MarkdownDocument checklistDocument = Markdown.parse(checklist);

  void toggle(Markdown.Task task) {
    checklist = task.applyTo(checklist);
    checklistDocument = checklistDocument.next(checklist);
  }

  static El checklistCard(Editors state) {
    return Card.card(
            Markdown.fragment(state.checklistDocument, Markdown.options().onTask(state::toggle)))
        .wFull();
  }
  // docs:end markdown-tasks

  static El markdownTasks(Editors state) {
    return Frame.of(checklistCard(state));
  }

  // docs:start markdown-alerts
  final MarkdownDocument alerts = Markdown.parse("""
      > [!NOTE]
      > Read-only replicas serve reports without touching the primary.

      > [!TIP]
      > Use `EXPLAIN (ANALYZE, BUFFERS)` to see where time goes.

      > [!IMPORTANT]
      > Migrations must be backward-compatible during a rolling deploy.

      > [!WARNING]
      > Dropping an index takes a lock on the table.

      > [!CAUTION]
      > `TRUNCATE` removes every row and cannot be undone.""");

  static El alertsCard(Editors state) {
    return Card.card(Markdown.fragment(state.alerts, Markdown.options())).wFull();
  }
  // docs:end markdown-alerts

  static El markdownAlerts(Editors state) {
    return Frame.of(alertsCard(state));
  }

  final MarkdownDocument queryChange = Markdown.parse("""
      Include `tax` and filter paid orders:

      ```diff
       SELECT
         id,
      +  tax,
         total
       FROM orders
      -WHERE status = 'pending';
      +WHERE status = 'paid';
      ```
      """);

  // docs:start markdown-blocks
  final DiffView.State blockDiff = new DiffView.State();

  /** Renders {@code ```diff} blocks with {@link DiffView}; other languages keep the default. */
  El renderCodeBlock(MarkdownDocument.CodeBlock block) {
    if (!block.language().equalsIgnoreCase("diff")) {
      return null;
    }
    List<String> before = new ArrayList<>();
    List<String> after = new ArrayList<>();
    block.code().lines().forEach(line -> {
      if (line.startsWith("-")) {
        before.add(line.substring(1));
      } else if (line.startsWith("+")) {
        after.add(line.substring(1));
      } else {
        String text = line.startsWith(" ") ? line.substring(1) : line;
        before.add(text);
        after.add(text);
      }
    });
    return DiffView.unified(
            app, String.join("\n", before), String.join("\n", after), blockDiff, Sql.of())
        .wFull()
        .h(132);
  }

  static El markdownBlocksView(Editors state) {
    return Markdown.markdown(
            state.queryChange,
            Markdown.options().languages(Editors::highlighter).codeBlocks(state::renderCodeBlock))
        .grow(1);
  }
  // docs:end markdown-blocks

  static El markdownBlocks(Editors state) {
    return Frame.of(Card.flush(markdownBlocksView(state)).wFull().grow(1));
  }

  final Button.Copied questionCopied = new Button.Copied();
  final Button.Copied answerCopied = new Button.Copied();
  boolean queryToolOpen = true;
  boolean checkToolOpen;
  boolean indexToolOpen;
  final MarkdownDocument chatReply = Markdown.parse("""
      The `orders` table has no composite index on `status` and `created_at`, \
      so PostgreSQL falls back to a sequential scan:

      ```sql
      CREATE INDEX orders_status_created ON orders (status, created_at);
      ```""");

  void toggleQueryTool() {
    queryToolOpen = !queryToolOpen;
  }

  void toggleCheckTool() {
    checkToolOpen = !checkToolOpen;
  }

  void toggleIndexTool() {
    indexToolOpen = !indexToolOpen;
  }

  static El chat(Editors state) {
    // docs:start chat
    Clipboard clipboard = state.app.clipboard();
    El question = Chat.user(
        "Why did the orders query slow down?",
        Chat.options().copy(clipboard).copied(state.questionCopied).time("14:31"));
    El inspect = Chat.tool(
        Symbol.CODE,
        "Inspect EXPLAIN plan",
        Chat.ToolState.DONE,
        state.queryToolOpen,
        state::toggleQueryTool,
        Label.label(Label.Variant.MONO, "Seq Scan on orders  (cost=0.00..48210.00 rows=1840)"));
    El check = Chat.tool(
        Symbol.DATABASE,
        "Check replica lag",
        Chat.ToolState.FAILED,
        state.checkToolOpen,
        state::toggleCheckTool,
        Label.label(Label.Variant.MUTED, "Replica connection timed out."));
    El answer = Chat.agent(
        Chat.options()
            .copy(clipboard, Markdown.text(state.chatReply))
            .copied(state.answerCopied)
            .time("14:32"),
        inspect,
        check,
        Markdown.fragment(
            state.chatReply,
            Markdown.options()
                .copy(clipboard)
                .copied(state.answerCopied)
                .languages(Editors::highlighter)));
    El followUp = Chat.agent(
        Chat.tool(
            Symbol.PLAY,
            "Verify index size",
            Chat.ToolState.RUNNING,
            state.indexToolOpen,
            state::toggleIndexTool,
            Label.label(
                Label.Variant.MONO,
                "SELECT pg_size_pretty(pg_relation_size('orders_status_created'));")),
        Chat.working());
    El transcript = Card.card(question, answer, followUp).wFull();
    // docs:end chat
    return Frame.of(transcript);
  }

  final Button.Copied reviewCopied = new Button.Copied();
  boolean ordersContextOpen = true;
  boolean retryContextOpen;
  boolean tableContextOpen;
  boolean slowQueryContextOpen = true;
  final String slowQuery =
      "SELECT id, total FROM orders\nWHERE status = 'open'\nORDER BY created_at DESC;";
  final CodeEditor.State slowQueryEditor = new CodeEditor.State().readOnly(true);

  void toggleOrdersContext() {
    ordersContextOpen = !ordersContextOpen;
  }

  void toggleRetryContext() {
    retryContextOpen = !retryContextOpen;
  }

  void toggleTableContext() {
    tableContextOpen = !tableContextOpen;
  }

  void toggleSlowQueryContext() {
    slowQueryContextOpen = !slowQueryContextOpen;
  }

  static El chatContext(Editors state) {
    // docs:start chat-context
    Clipboard clipboard = state.app.clipboard();
    String rawPrompt =
        "Please review these two spots before merging.\n\nOrders.java (L42–58)\nOrderRetry.java (L12–19)";
    El review = Chat.user(
        Chat.options().copy(clipboard, rawPrompt).copied(state.reviewCopied).time("14:31"),
        Chat.text("Please review these two spots before merging."),
        Chat.context(
            Symbol.CODE,
            "Orders.java",
            "L42–58",
            state.ordersContextOpen,
            state::toggleOrdersContext,
            Label.label(
                Label.Variant.MONO,
                "if (order.attempts() >= maxAttempts) {\n  return Result.failed(order);\n}")),
        Chat.context(
            Symbol.CODE,
            "OrderRetry.java",
            "L12–19",
            state.retryContextOpen,
            state::toggleRetryContext,
            Label.label(
                Label.Variant.MONO, "Duration backoff = Duration.ofSeconds(1L << attempt);")));
    El query = Chat.user(
        Chat.options().time("14:34"),
        Chat.context(
            Symbol.TABLE,
            "orders",
            "12 columns",
            state.tableContextOpen,
            state::toggleTableContext,
            Label.label(Label.Variant.MONO, "id, customer_id, status, total, created_at")),
        Chat.context(
            Symbol.DATABASE,
            "Slow query",
            "1.8 s",
            state.slowQueryContextOpen,
            state::toggleSlowQueryContext,
            CodeEditor.codeEditor(state.app, state.slowQuery, state.slowQueryEditor, Sql.of(), null)
                .lines(3)
                .wFull()),
        Chat.context(Symbol.DOCUMENT, "explain-plan.txt", "4 KB", false, null),
        Chat.text("Why is this query slow?"));
    El transcript = Card.card(review, query).wFull();
    // docs:end chat-context
    return Frame.of(transcript);
  }

  record ChatEntry(int id, boolean fromUser, String text, MarkdownDocument document) {}

  final List<ChatEntry> transcriptMessages = new ArrayList<>(
      List.of(
          new ChatEntry(
              1, true, "Why did the orders query slow down after Monday's release?", null),
          new ChatEntry(
              2,
              false,
              null,
              Markdown.parse(
                  "The `orders` table grew past 1.8M rows and has no composite index on `(status, created_at)`, "
                      + "so PostgreSQL switched from an index scan to a sequential scan.")),
          new ChatEntry(3, true, "Can we add the index without blocking writes?", null),
          new ChatEntry(4, false, null, Markdown.parse("""
              Yes — build it concurrently so writes keep running:

              ```sql
              CREATE INDEX CONCURRENTLY orders_status_created
                ON orders (status, created_at);
              ```""")),
          new ChatEntry(5, true, "How much space will that take?", null),
          new ChatEntry(
              6,
              false,
              null,
              Markdown.parse(
                  "About 68 MB for the current 1.8M rows. Run `ANALYZE orders;` afterward so the planner picks it up immediately.")),
          new ChatEntry(7, true, "Should we drop the single-column index on status?", null),
          new ChatEntry(
              8,
              false,
              null,
              Markdown.parse(
                  "Yes — `orders_status_created` leads with `status`, so it serves equality lookups on `status` alone too."))));

  final List<ChatEntry> olderMessages = List.of(
      new ChatEntry(-2, true, "Which queries got slower this week?", null),
      new ChatEntry(-1, false, null, Markdown.parse("Only the open orders query, since Monday.")));

  // docs:start chat-transcript
  final ScrollState transcriptScroll = new ScrollState().followEnd();
  final RowHeights transcriptHeights =
      new RowHeights(index -> 72, index -> transcriptMessages.get(index).id());
  boolean hasOlderMessages = true;

  void loadOlderMessages() {
    if (hasOlderMessages) { // may be called again before the next build
      // Here from memory; an app asks its store for the page before the first message.
      transcriptMessages.addAll(0, olderMessages);
      hasOlderMessages = false;
    }
  }

  El renderMessage(int index) {
    ChatEntry entry = transcriptMessages.get(index);
    return entry.fromUser() ? Chat.user(entry.text()) : Chat.agent(entry.document());
  }

  static El chatTranscriptView(Editors state) {
    return Chat.transcript(
            state.transcriptMessages.size(),
            state::renderMessage,
            state.transcriptScroll,
            state.transcriptHeights,
            state.hasOlderMessages ? state::loadOlderMessages : null)
        .grow(1);
  }
  // docs:end chat-transcript

  static El chatTranscript(Editors state) {
    return Frame.of(Card.flush(chatTranscriptView(state)).wFull().grow(1));
  }

  String composerText = "Explain the sequential scan on orders\nand suggest a composite index.";
  final List<String> composerFiles = new ArrayList<>(List.of("orders.sql", "explain.json"));
  final Menu.State modelMenu = new Menu.State();
  int modelIndex;
  boolean composerWorking;

  void composerText(String value) {
    composerText = value;
  }

  void sendComposer() {
    composerText = "";
    composerWorking = true;
  }

  void stopComposer() {
    composerWorking = false;
  }

  void attachFile() {
    composerFiles.add("schema.sql");
  }

  void removeComposerFile(int index) {
    composerFiles.remove(index);
  }

  void addComposerFiles(List<Path> files) {
    for (Path path : files) {
      composerFiles.add(path.getFileName().toString());
    }
  }

  void selectModel(int index) {
    modelIndex = index;
  }

  static El chatComposer(Editors state) {
    // docs:start chat-composer
    El composer = Chat.composer(
        state.composerText,
        state::composerText,
        state::sendComposer,
        Chat.composerOptions()
            .placeholder("Ask about your queries")
            .working(state.composerWorking, state::stopComposer)
            .attachments(state.composerFiles, state::removeComposerFile)
            .start(Button.icon(Button.Variant.GHOST, Symbol.ADD, "Attach file", state::attachFile))
            .end(Select.select(
                    List.of("Sonnet", "Opus"),
                    state.modelIndex,
                    state.modelMenu,
                    state::selectModel)
                .label("Model")
                .w(120))
            .onFiles(state::addComposerFiles));
    // docs:end chat-composer
    return Frame.of(composer);
  }

  // docs:start markdown-languages
  /** Colors the code blocks that say they are SQL, in the theme's colors for code. */
  static Highlighter highlighter(String language) {
    return switch (language.toLowerCase(Locale.ROOT)) {
      case "sql", "pgsql", "postgresql" -> Sql.of();
      default -> null;
    };
  }
  // docs:end markdown-languages

  // docs:start prose-fonts
  // The app's theme and the reading theme derived from it, derived again only when the app's
  // theme changes, since Markdown keeps what it measured for the theme it was built with
  Theme base;
  Theme reading;

  // Larger text and code for the answers
  static Theme readingTheme(Theme theme) {
    Theme.Prose prose = theme.prose();
    return theme.withProse(new Theme.Prose(
        new Theme.TextStyle(Font::sans, 15),
        prose.headings(),
        prose.emphasis(),
        prose.strong(),
        prose.strongEmphasis(),
        prose.headingStrong(),
        prose.tableHeader(),
        new Theme.TextStyle(Font::mono, 13),
        prose.monoStrong(),
        prose.link(),
        prose.code(),
        prose.quote(),
        prose.measure()));
  }

  static El answer(Editors state) {
    Theme theme = Theme.CURRENT.get();
    if (theme != state.base) {
      state.base = theme;
      state.reading = readingTheme(theme);
    }
    return Theme.CURRENT.with(
        state.reading, () -> Markdown.fragment(state.summary, Markdown.options()));
  }
  // docs:end prose-fonts

  static El image(Editors state) {
    // docs:start image
    El shown = com.rethinkj.ui.kit.Image.image(state.swatch, "A swatch of the accent");
    El waiting = com.rethinkj.ui.kit.Image.image(null, "Still being read").size(120, 60);
    // docs:end image
    return Frame.of(shown, waiting);
  }

  /** A gradient of 64 by 40 pixels. */
  static Pixels swatch() {
    int width = 64;
    int height = 40;
    byte[] rgba = new byte[width * height * 4];
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        int index = (y * width + x) * 4;
        rgba[index] = (byte) (0x3D + x);
        rgba[index + 1] = (byte) (0x6F + y);
        rgba[index + 2] = (byte) 0xE6;
        rgba[index + 3] = (byte) 0xFF;
      }
    }
    return new Pixels(width, height, rgba);
  }
}
