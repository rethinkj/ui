package com.rethinkj.ui;

import java.util.Objects;

/**
 * What formatting a text of code made of it: the formatted text, or the first problem that kept it
 * from formatting, where it is and of which kind. A language's formatter returns it, such as
 * {@code Json.format} of the module {@code rethinkj-ui-json}; one of an app's own language returns
 * it too, and its highlighter gives it to the code editor's format command ({@link
 * Highlighter#formatter}). A formatter that finds a problem changes nothing, and the app can show
 * the problem as a diagnostic of the code editor, with the message the kit's texts give its kind
 * ({@code Texts.formatProblem}).
 *
 * <pre>{@code
 * Json.format("[1,2]")   // Formatted[text=[\n    1,\n    2\n], problem=null]
 * Json.format("[1,]")    // Formatted[text=null, problem=Problem[from=3, to=4, kind=UNEXPECTED]]
 * }</pre>
 *
 * @param text the formatted text; null when there is a problem
 * @param problem what kept the text from formatting; null when it formatted
 */
public record Formatted(String text, Formatted.Problem problem) {

  /**
   * A text or a problem.
   *
   * @throws IllegalArgumentException with both or neither
   */
  public Formatted {
    if ((text == null) == (problem == null)) {
      throw new IllegalArgumentException("A text or a problem: " + text + ", " + problem);
    }
  }

  /** What kept a text from formatting. */
  public enum Kind {
    /**
     * A character or a token where it may not stand, such as a second comma or a word the
     * language does not know.
     */
    UNEXPECTED,
    /** The end of the text where more has to come, such as a closing bracket; also no text. */
    UNEXPECTED_END,
    /** A string without its closing quote on its line. */
    UNCLOSED_STRING,
    /** An escape in a string that the language does not know, such as {@code \x41} in JSON. */
    INVALID_ESCAPE,
    /** A number the language does not write so, such as {@code 01} or {@code 1.} in JSON. */
    INVALID_NUMBER,
    /**
     * A valid text whose formatted text would be longer than the formatter keeps, such as one
     * nested so deep that its indentation alone would take gigabytes; at the start of the text.
     */
    TOO_LARGE
  }

  /**
   * Where a problem is and of which kind: characters {@code from} to {@code to} of the text, as
   * the code editor counts them ({@link El#multiline}), a carriage return before a line feed as
   * one character with it. {@code from == to} is a place between two characters, such as the end
   * of the text.
   */
  public record Problem(int from, int to, Kind kind) {

    /**
     * @throws IllegalArgumentException when the range is not one
     */
    public Problem {
      Objects.requireNonNull(kind, "kind");
      if (from < 0 || to < from) {
        throw new IllegalArgumentException("Not a range: " + from + " to " + to);
      }
    }
  }

  /** Whether the text formatted: {@link #text} is the formatted text and {@link #problem} null. */
  public boolean valid() {
    return problem == null;
  }
}
