package com.rethinkj.ui.javascript;

import com.rethinkj.ui.Spans;
import com.rethinkj.ui.editor.DocComment;
import com.rethinkj.ui.editor.Language;
import com.rethinkj.ui.kit.Theme;
import java.util.Objects;

/**
 * A {@link Language} for TypeScript in the colors of {@link Theme.Code}: shares the lexer and
 * JSDoc/TSDoc parser of {@link JavaScript}, adding TypeScript's keywords and primitive type names
 * (such as {@code interface}, {@code type}, {@code enum}, {@code readonly}, {@code keyof}, {@code
 * satisfies}, {@code string}, {@code number}, {@code boolean}, {@code unknown}, {@code never}) in
 * the keyword color and fencing {@code @example} blocks as {@code typescript}.
 *
 * <p>A record, so that two instances with equal colors are equal and the editor keeps its cached
 * lines and comment scan across rebuilds.
 */
public record TypeScript(Theme.Code colors) implements Language {

  public TypeScript {
    Objects.requireNonNull(colors, "colors");
  }

  /**
   * The TypeScript language in the colors for code of {@link Theme#CURRENT}; call in the view,
   * where the app provides its theme.
   */
  public static TypeScript of() {
    Theme theme = Theme.CURRENT.get();
    return new TypeScript(theme.code());
  }

  @Override
  public Object line(String line, Object state, Spans out) {
    return JsLexer.highlight(line, state, out, colors, true);
  }

  @Override
  public Object comments(String line, Object state, Out out) {
    return JsLexer.comments(line, state, out, true);
  }

  @Override
  public DocComment docComment(String text) {
    return JsDoc.parse(text, "typescript");
  }

  /** Brackets in strings, template text, regular expressions and comments are not marked. */
  @Override
  public boolean marksBrackets() {
    return true;
  }

  /** TypeScript's line comments start with {@code //}. */
  @Override
  public String lineComment() {
    return "//";
  }

  /** {@code $} is part of TypeScript identifiers. */
  @Override
  public String wordCharacters() {
    return "$";
  }
}
