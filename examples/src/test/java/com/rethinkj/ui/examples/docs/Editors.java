package com.rethinkj.ui.examples.docs;

import com.rethinkj.ui.App;
import com.rethinkj.ui.CodeWords;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Formatted;
import com.rethinkj.ui.Highlighter;
import com.rethinkj.ui.Image;
import com.rethinkj.ui.Scrollbars;
import com.rethinkj.ui.editor.CodeEditor;
import com.rethinkj.ui.json.Json;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.Completion;
import com.rethinkj.ui.kit.Find;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Markdown;
import com.rethinkj.ui.kit.MarkdownDocument;
import com.rethinkj.ui.kit.StatusBar;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.TextField;
import com.rethinkj.ui.kit.Texts;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.render.Pixels;
import com.rethinkj.ui.sql.Sql;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
  final Completion.State completion = new Completion.State();
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

  /** Marks lines the app knows as changed, such as from version control. */
  static El changes(El editor, int changeStart, int changeEnd) {
    // docs:start scroll-marks
    Theme theme = Theme.CURRENT.get();
    editor.scrollMarks(
        List.of(new Scrollbars.Mark(changeStart, changeEnd, theme.status().success())));
    // docs:end scroll-marks
    return editor;
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

  // docs:start markdown-languages
  /** Colors the code blocks that say they are SQL, in the theme's colors for code. */
  static Highlighter highlighter(String language) {
    return switch (language.toLowerCase(Locale.ROOT)) {
      case "sql", "pgsql", "postgresql" -> Sql.of();
      default -> null;
    };
  }
  // docs:end markdown-languages

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
