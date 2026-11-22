package com.rethinkj.ui.javascript;

import com.rethinkj.ui.Spans;
import com.rethinkj.ui.editor.DocComment;
import com.rethinkj.ui.editor.Language;
import com.rethinkj.ui.kit.Theme;
import java.util.Objects;

/**
 * A {@link Language} for JavaScript in the colors of {@link Theme.Code}: keywords, single- and
 * double-quoted strings with backslash escapes and line continuation, multi-line template literals
 * with nested {@code ${...}} expressions, regular expression literals distinguished lexically from
 * division, numbers (decimal, hex, binary, octal, {@code _} separators, exponents and BigInt
 * {@code n}), line and block comments, operators and brackets. It marks brackets outside strings,
 * template text, regular expressions and comments ({@link Spans#bracket}), reports line, block and
 * {@code /** ... *}{@code /} doc comments through {@link #comments}, and parses JSDoc comments
 * through {@link #docComment}.
 *
 * <p>A record, so that two instances with equal colors are equal and the editor keeps its cached
 * lines and comment scan across rebuilds.
 */
public record JavaScript(Theme.Code colors) implements Language {

  public JavaScript {
    Objects.requireNonNull(colors, "colors");
  }

  /**
   * The JavaScript language in the colors for code of {@link Theme#CURRENT}; call in the view,
   * where the app provides its theme.
   */
  public static JavaScript of() {
    Theme theme = Theme.CURRENT.get();
    return new JavaScript(theme.code());
  }

  @Override
  public Object line(String line, Object state, Spans out) {
    return JsLexer.highlight(line, state, out, colors, false);
  }

  @Override
  public Object comments(String line, Object state, Out out) {
    return JsLexer.comments(line, state, out, false);
  }

  @Override
  public DocComment docComment(String text) {
    return JsDoc.parse(text, "javascript");
  }

  /** Brackets in strings, template text, regular expressions and comments are not marked. */
  @Override
  public boolean marksBrackets() {
    return true;
  }

  /** JavaScript's line comments start with {@code //}. */
  @Override
  public String lineComment() {
    return "//";
  }

  /** {@code $} is part of JavaScript identifiers. */
  @Override
  public String wordCharacters() {
    return "$";
  }
}
