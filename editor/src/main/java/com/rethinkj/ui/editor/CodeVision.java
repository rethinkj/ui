package com.rethinkj.ui.editor;

import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.Text;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Theme;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds the code vision rows inserted above lines of a {@link CodeEditor}: small muted entries
 * with an optional leading icon, aligned with the target line's indentation in the code font.
 */
final class CodeVision {

  private CodeVision() {}

  /** The entries of one line and the white space its text starts with. */
  record Row(String indent, List<CodeEditor.Vision> entries) {}

  /**
   * The rows of {@code entries} by line, in one pass over {@code text}: each line that exists
   * gets its entries in list order; entries of lines past the end get none.
   */
  static Map<Integer, Row> rows(String text, List<CodeEditor.Vision> entries) {
    if (entries.isEmpty()) {
      return Map.of();
    }
    Map<Integer, List<CodeEditor.Vision>> byLine = new TreeMap<>();
    for (CodeEditor.Vision entry : entries) {
      byLine.computeIfAbsent(entry.line(), k -> new ArrayList<>()).add(entry);
    }
    Map<Integer, Row> rows = new TreeMap<>();
    int line = 0;
    int start = 0;
    for (Map.Entry<Integer, List<CodeEditor.Vision>> e : byLine.entrySet()) {
      for (; line < e.getKey(); line++) {
        start = text.indexOf('\n', start) + 1;
        if (start == 0) {
          return rows;
        }
      }
      int end = start;
      while (end < text.length() && (text.charAt(end) == ' ' || text.charAt(end) == '\t')) {
        end++;
      }
      rows.put(line, new Row(text.substring(start, end), e.getValue()));
    }
    return rows;
  }

  /** Builds one inserted block row containing the entries of {@code line}. */
  static El row(Theme theme, El editor, int line, Row row) {
    El el = El.row()
        .key("vision-" + line)
        .align(Style.Align.CENTER)
        .gap(theme.space().l())
        .padding(theme.space().xs(), 0, 0, indentWidth(editor.text(), row.indent()));
    for (CodeEditor.Vision entry : row.entries()) {
      el.child(item(theme, entry));
    }
    return el;
  }

  private static El item(Theme theme, CodeEditor.Vision entry) {
    El icon = entry.symbol() == null
        ? null
        : Icon.icon(entry.symbol(), Icon.Variant.MUTED, theme.type().small().size());
    El label = Label.label(Label.Variant.SMALL, entry.label());
    El item = El.row().align(Style.Align.CENTER).gap(theme.space().xs());
    if (icon != null) {
      item.child(icon);
    }
    item.child(label);
    if (entry.onPress() == null) {
      return item;
    }
    return item.role("button")
        .label(entry.label())
        .radius(theme.radius().small())
        .cursor(Cursor.POINTER)
        .keepFocus()
        .motion(theme.motion().feedback())
        .onPress(entry.onPress())
        .whenHovered(e -> {
          e.fill(theme.surface().hover());
          label.color(theme.text().primary());
          if (icon != null && icon.text() != null) {
            icon.color(theme.text().primary());
          }
        });
  }

  /** The width of {@code indent}, spaces and tabs, in the field's font, in logical pixels. */
  private static float indentWidth(Text text, String indent) {
    int columns = 0;
    for (int i = 0; i < indent.length(); i++) {
      columns =
          indent.charAt(i) == '\t' ? (columns / text.tabSize() + 1) * text.tabSize() : columns + 1;
    }
    if (columns == 0) {
      return 0;
    }
    float scale = text.scale() > 0 ? text.scale() : 1;
    String[] features = text.features().toArray(String[]::new);
    return columns * text.font().shape(" ", text.size(), scale, features).width() / scale;
  }
}
