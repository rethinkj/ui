package com.rethinkj.ui.examples.docs;

import com.rethinkj.ui.App;
import com.rethinkj.ui.CodeWords;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Image;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.CodeEditor;
import com.rethinkj.ui.kit.Completion;
import com.rethinkj.ui.kit.Find;
import com.rethinkj.ui.kit.Markdown;
import com.rethinkj.ui.kit.MarkdownDocument;
import com.rethinkj.ui.kit.Sql;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.TextField;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.render.Pixels;
import java.util.List;
import java.util.Locale;

/** The examples of the pages about the code editor, find, completion, Markdown and images. */
final class Editors {

  static final List<String> TABLES = List.of("orders", "order_items", "customers");

  Theme theme = Theme.DARK;
  App<?> app; // set by whoever makes the app: the editor edits and selects through it
  String sql = "SELECT id, total\nFROM orders\nWHERE status = 'paid';";
  final CodeEditor.State editor = new CodeEditor.State();
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

  void sql(String value) {
    sql = value;
  }

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
    Theme theme = state.theme;
    // docs:start code-editor
    El editor = CodeEditor.codeEditor(
            theme, state.app, state.sql, state.editor, Sql.of(theme), state::sql)
        .key("query")
        .label("Query")
        .lines(8)
        .wFull();
    // docs:end code-editor
    // docs:start completion
    El withCompletions = Completion.complete(
        theme,
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
    El card = Card.flush(
        theme,
        Card.toolbar(theme, "orders.sql", null),
        CodeEditor.embedded(theme, withCompletions));
    // docs:end code-editor-embedded
    return Frame.of(theme, card.wFull());
  }

  static El find(Editors state) {
    Theme theme = state.theme;
    // docs:start find
    El notes = TextField.textArea(theme, state.notes, "Notes", state::notes)
        .key("notes")
        .lines(6)
        .w(360);
    // Control+F, Control+H, F3, Control+G
    El withFind = Find.find(theme, state.app, notes, state.find);
    // docs:end find
    return Frame.of(theme, withFind);
  }

  static El markdown(Editors state) {
    Theme theme = state.theme;
    // docs:start markdown
    El document = Markdown.markdown(
            theme, state.readme, Markdown.options().onLink(state::link).copy(state.app.clipboard()))
        .key("readme")
        .grow(1);
    // docs:end markdown
    return Frame.of(theme, Card.flush(theme, document).wFull().grow(1));
  }

  static El image(Editors state) {
    Theme theme = state.theme;
    // docs:start image
    El shown = com.rethinkj.ui.kit.Image.image(theme, state.swatch, "A swatch of the accent");
    El waiting =
        com.rethinkj.ui.kit.Image.image(theme, null, "Still being read").size(120, 60);
    // docs:end image
    return Frame.of(theme, shown, waiting);
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
