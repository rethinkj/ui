package com.rethinkj.ui.examples.docs;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Decorator;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Highlighter;
import com.rethinkj.ui.Spans;
import com.rethinkj.ui.kit.CodeEditor;
import com.rethinkj.ui.kit.Theme;
import java.util.function.Consumer;

/** The examples of a highlighter and a decorator of one's own, for the code editor's page. */
final class Highlighters {

  final Theme t = Theme.DARK;
  App<?> app;
  String ini = "; the connection\n[db]\nhost = localhost\npath = /a:\\\n  /b # TODO\n";
  final CodeEditor.State state = new CodeEditor.State();

  static El view(Highlighters s) {
    return Frame.of(
        s.t, editor(s.t, s.app, s.ini, s.state, v -> s.ini = v).lines(6).wFull());
  }

  // docs:start highlighter
  /** Colours INI files: sections, keys and comments; a value goes on after a backslash. */
  record Ini(int section, int key, int comment) implements Highlighter, CodeEditor.LineComments {

    static Ini of(Theme t) {
      return new Ini(t.code().keyword(), t.code().operator(), t.code().comment());
    }

    @Override
    public Object line(String line, Object state, Spans out) {
      boolean goesOn = line.endsWith("\\");
      if (state == null) { // not inside a value that the line before went on with
        String text = line.strip();
        if (text.startsWith(";") || text.startsWith("#")) {
          out.color(0, line.length(), comment);
        } else if (text.startsWith("[")) {
          out.color(0, line.length(), section);
        } else if (line.indexOf('=') > 0) {
          out.color(0, line.indexOf('='), key);
        }
      }
      return goesOn ? Boolean.TRUE : null; // compared with equals: TRUE again keeps the spans
    }

    @Override
    public String lineComment() { // Control+/ comments lines out with it
      return ";";
    }
  }
  // docs:end highlighter

  static El editor(
      Theme t, App<?> app, String value, CodeEditor.State state, Consumer<String> onChange) {
    // docs:start decorator
    El editor = CodeEditor.codeEditor(t, app, value, state, Ini.of(t), onChange);
    Decorator own = editor.decorator(); // the caret's line, brackets, occurrences, matches
    editor.decorate((line, start, text, out) -> {
      own.line(line, start, text, out);
      int todo = text.indexOf("TODO");
      if (todo >= 0) {
        out.underline(todo, todo + 4, t.status().warning());
      }
      if (text.length() > 120) {
        out.background(120, text.length(), t.code().match()); // what is beyond column 120
      }
    });
    // docs:end decorator
    return editor;
  }
}
