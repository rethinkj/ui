package com.rethinkj.ui;

/**
 * Colours a text line by line, as a tokenizer does for code: given with {@link El#highlight}.
 *
 * <p>A {@link El#multiline multi-line} field asks it about the lines it shapes, those in view and
 * a few around them, and keeps the answers per line, with the state each line ended in, until the
 * line changes, the line's state from the lines before changes, or the view gives another key.
 * The first line gets the state {@code null}. To shape a line, the field needs the state it starts
 * in, so it asks about the lines before it that it has not asked about since they changed, from
 * the first such line on, with a {@link Spans} that keeps nothing: an edit at the top of a long
 * text costs the lines from the edit down to the view, and the first view of the end of a long
 * text costs a pass over every line before it, once.
 *
 * <p>Other text, and a single-line field, is one line: its whole string, asked again whenever the
 * view is built.
 */
@FunctionalInterface
public interface Highlighter {

  /**
   * Says how the line looks.
   *
   * @param line the line's string, without its line feed
   * @param state what this function returned for the line before; null for the first line
   * @param out takes the colours and fonts of the line's ranges, and also backgrounds and
   *     underlines, which are kept with them
   * @return the state at the end of the line, such as "inside a block comment", compared with
   *     {@link Object#equals}; null when nothing goes on to the next line
   */
  Object line(String line, Object state, Spans out);
}
