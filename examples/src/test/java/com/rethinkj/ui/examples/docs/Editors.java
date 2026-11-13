package com.rethinkj.ui.examples.docs;

import com.rethinkj.ui.App;
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

  final Theme t = Theme.DARK;
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

  static El codeEditor(Editors s) {
    Theme t = s.t;
    // docs:start code-editor
    El editor = CodeEditor.codeEditor(t, s.app, s.sql, s.editor, Sql.of(t), v -> s.sql = v)
        .key("query").label("Query").lines(8).wFull();
    // docs:end code-editor
    // docs:start completion
    El withCompletions = Completion.complete(t, s.app, editor, s.editor.caret(), s.completion,
        word -> TABLES.stream()
            .filter(name -> name.startsWith(word.toLowerCase(Locale.ROOT)))
            .map(name -> new Completion.Entry(name, Symbol.TABLE, "table"))
            .toList());
    // docs:end completion
    return Frame.of(t, Card.flush(t, withCompletions).wFull());
  }

  static El find(Editors s) {
    Theme t = s.t;
    // docs:start find
    El notes = TextField.textArea(t, s.notes, "Notes", v -> s.notes = v)
        .key("notes").lines(6).w(360);
    El withFind = Find.find(t, s.app, notes, s.find); // Control+F, Control+H, F3, Control+G
    // docs:end find
    return Frame.of(t, withFind);
  }

  static El markdown(Editors s) {
    Theme t = s.t;
    // docs:start markdown
    El document = Markdown.markdown(t, s.readme, Markdown.options()
            .onLink(destination -> s.link = destination)
            .copy(s.app.clipboard()))
        .key("readme").grow(1);
    // docs:end markdown
    return Frame.of(t, Card.flush(t, document).wFull().grow(1));
  }

  static El image(Editors s) {
    Theme t = s.t;
    // docs:start image
    El shown = com.rethinkj.ui.kit.Image.image(t, s.swatch, "A swatch of the accent");
    El waiting = com.rethinkj.ui.kit.Image.image(t, null, "Still being read").size(120, 60);
    // docs:end image
    return Frame.of(t, shown, waiting);
  }

  /** A gradient of 64 by 40 pixels. */
  static Pixels swatch() {
    int w = 64;
    int h = 40;
    byte[] rgba = new byte[w * h * 4];
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        int i = (y * w + x) * 4;
        rgba[i] = (byte) (0x3D + x);
        rgba[i + 1] = (byte) (0x6F + y);
        rgba[i + 2] = (byte) 0xE6;
        rgba[i + 3] = (byte) 0xFF;
      }
    }
    return new Pixels(w, h, rgba);
  }
}
