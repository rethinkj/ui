package com.rethinkj.ui.editor;

import com.rethinkj.ui.Highlighter;
import java.util.Objects;

/**
 * A language for {@link CodeEditor}: wraps a {@link Highlighter} and adds comment and doc-comment
 * ranges and doc-comment parsing.
 *
 * <p>Implementations should implement {@link Object#equals} (for example, as a record), because
 * views construct them each frame and caches such as {@link Comments#scan} compare them.
 */
public interface Language {

  /** The highlighter that colors tokens, marks brackets, comments lines out and formats code. */
  Highlighter highlighter();

  /**
   * Reports the comments and doc comments on one line, in the same line-by-line protocol as {@link
   * Highlighter#line}.
   *
   * <p>Called line by line with the state returned for the line before ({@code null} for the first
   * line), reporting each comment's column range within the line to {@code out} and returning the
   * state at the end of the line (compared with {@link Object#equals}, or {@code null} when nothing
   * goes on to the next line). When a line returns a non-null state, the line's last comment goes
   * on in the first comment the next line reports (such as a block comment across lines or a run of
   * {@code ///} lines), and the two pieces form one comment.
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
     */
    void comment(int from, int to, boolean doc);
  }

  /**
   * A language with only {@code highlighter} and no comments or doc comments. Returns equal objects
   * for equal highlighters.
   *
   * @param highlighter the highlighter to wrap
   * @return a language wrapping {@code highlighter}
   */
  static Language of(Highlighter highlighter) {
    return new HighlighterLanguage(highlighter);
  }
}

record HighlighterLanguage(Highlighter highlighter) implements Language {
  HighlighterLanguage {
    Objects.requireNonNull(highlighter, "highlighter");
  }
}
