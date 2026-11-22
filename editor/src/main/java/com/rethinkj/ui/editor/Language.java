package com.rethinkj.ui.editor;

import com.rethinkj.ui.Formatted;
import com.rethinkj.ui.Highlighter;
import com.rethinkj.ui.Spans;
import java.util.Objects;
import java.util.function.Function;

/**
 * A language for {@link CodeEditor}: a {@link Highlighter} that also finds the comments and doc
 * comments of a text ({@link Comments#scan}) and parses doc comments. The code editor takes it
 * where it takes a highlighter.
 *
 * <p>Implementations should implement {@link Object#equals} (for example, as a record), because
 * views construct them each frame and caches such as {@link Comments#scan} compare them.
 */
public interface Language extends Highlighter {

  /**
   * Reports the comments and doc comments on one line, in the same line-by-line protocol as {@link
   * Highlighter#line}.
   *
   * <p>Called line by line with the state returned for the line before ({@code null} for the first
   * line), reporting each comment's column range within the line to {@code out} and returning the
   * state at the end of the line (compared with {@link Object#equals}, or {@code null} when nothing
   * goes on to the next line). A comment that {@code out} gets as {@code continued} goes on the
   * comment found before it, such as the next line of a block comment or of a run of {@code ///}
   * lines, and the pieces form one comment.
   *
   * @param line the line's string, without its line feed
   * @param state what this method returned for the line before; {@code null} for the first line
   * @param out receives each comment's column range within the line
   * @return the state at the end of the line, compared with {@link Object#equals}; {@code null}
   *     when nothing goes on to the next line
   */
  default Object comments(String line, Object state, Out out) {
    return null;
  }

  /**
   * Parses the full text of one doc comment (including its delimiters, as covered by a range from
   * {@link #comments}) into a {@link DocComment}; {@code null} by default, for a language without
   * doc comments.
   *
   * @param text the full text of one doc comment, with line feeds only
   * @return the parsed doc comment, or {@code null} when this language has no doc comments
   */
  default DocComment docComment(String text) {
    return null;
  }

  /** Receives the column ranges of comments on a line from {@link #comments}. */
  @FunctionalInterface
  interface Out {

    /**
     * Reports one comment range within the current line.
     *
     * @param from start column in the line, inclusive
     * @param to end column in the line, exclusive
     * @param doc {@code true} for a doc comment (such as a {@code /**} block or a run of {@code
     *     ///}), {@code false} for an ordinary comment
     * @param continued whether this piece goes on the comment found before it, on an earlier line
     */
    void comment(int from, int to, boolean doc, boolean continued);
  }

  /**
   * The language that highlights as {@code highlighter} does and has no comments or doc comments,
   * such as {@code Language.of(Sql.of())}; equal for equal highlighters.
   */
  static Language of(Highlighter highlighter) {
    return new Wrapped(Objects.requireNonNull(highlighter, "highlighter"));
  }
}

/** {@link Language#of}: hands each method of {@link Highlighter} to the highlighter. */
record Wrapped(Highlighter highlighter) implements Language {

  @Override
  public Object line(String line, Object state, Spans out) {
    return highlighter.line(line, state, out);
  }

  @Override
  public boolean marksBrackets() {
    return highlighter.marksBrackets();
  }

  @Override
  public String lineComment() {
    return highlighter.lineComment();
  }

  @Override
  public String wordCharacters() {
    return highlighter.wordCharacters();
  }

  @Override
  public Function<String, Formatted> formatter() {
    return highlighter.formatter();
  }
}
