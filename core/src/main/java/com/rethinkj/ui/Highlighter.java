package com.rethinkj.ui;

import java.util.function.Function;

/**
 * Colors a text line by line, as a tokenizer does for code: given with {@link El#highlight}.
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
   * @param out takes the colors and fonts of the line's ranges, and also backgrounds and
   *     underlines, which are kept with them
   * @return the state at the end of the line, such as "inside a block comment", compared with
   *     {@link Object#equals}; null when nothing goes on to the next line
   */
  Object line(String line, Object state, Spans out);

  /**
   * Whether {@link #line} gives the brackets of the code with {@link Spans#bracket}, so that
   * bracket matching ({@link El#matchingBracket}) pairs only those, and not one in a string or a
   * comment; false, the default, pairs every bracket of the text.
   */
  default boolean marksBrackets() {
    return false;
  }

  /**
   * What starts a comment to the end of the line in the language, such as {@code --} or {@code
   * //}, which a code editor comments lines out with; null, the default, for a language without.
   */
  default String lineComment() {
    return null;
  }

  /**
   * The characters that belong to a word of the language besides letters, decimal digits, marks
   * and {@code _}, such as {@code "$"} for JavaScript's {@code $gt}; none, the default. A field
   * with {@link El#codeWords} takes them into its words for Control+Arrow and a double press, and
   * the kit's completion, find bar and code editor into theirs ({@link CodeWords}).
   */
  default String wordCharacters() {
    return "";
  }

  /**
   * Formats a whole text of the language, which a code editor's format command runs on its
   * string; null, the default, for a language without. The function gets the string as the editor
   * shows it, with line feeds only, and returns the formatted text, or, for a text it cannot
   * format, the first problem at the place the editor counts, without throwing: {@code Json}
   * returns {@code Json::format}. The editor keeps the caret and the selection at the same
   * characters that are not white space, counted from the start: exactly where the formatter
   * changes white space only, and only near them where it changes other characters.
   */
  default Function<String, Formatted> formatter() {
    return null;
  }
}
