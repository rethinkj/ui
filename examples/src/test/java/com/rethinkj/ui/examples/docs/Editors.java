package com.rethinkj.ui.examples.docs;

import com.rethinkj.ui.App;
import com.rethinkj.ui.CodeWords;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Image;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.CodeEditor;
import com.rethinkj.ui.kit.Completion;
import com.rethinkj.ui.kit.Find;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Markdown;
import com.rethinkj.ui.kit.MarkdownDocument;
import com.rethinkj.ui.kit.Sql;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.TextField;
import com.rethinkj.ui.render.Pixels;
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
  final Image swatch = Image.of(swatch(), 2); // 64 by 40 pixels made for scale 2
  String link = "";
  String ran; // the statement the marker ran last

  Editors() {
    showRun();
    int orders = sql.indexOf("orders");
    refused(sql, orders, orders + "orders".length(), "relation \"orders\" does not exist");
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

  /** The database refused {@code statement}: its error shows while the text is that statement. */
  void refused(String statement, int from, int to, String message) {
    editor.diagnostics(
        statement,
        List.of(new CodeEditor.Diagnostic(from, to, CodeEditor.Severity.ERROR, message)));
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

  static El insertJoinButton(Editors state) {
    return Button.button(Button.Variant.SECONDARY, "Insert join", state::insertJoin);
  }
  // docs:end code-editor-insert

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
            state.readme, Markdown.options().onLink(state::link).copy(state.app.clipboard()))
        .key("readme")
        .grow(1);
    // docs:end markdown
    return Frame.of(Card.flush(document).wFull().grow(1));
  }

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
