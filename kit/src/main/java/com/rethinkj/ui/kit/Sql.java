package com.rethinkj.ui.kit;

import com.rethinkj.ui.Highlighter;
import com.rethinkj.ui.Spans;
import java.util.Locale;
import java.util.Set;

/**
 * A {@link Highlighter} of SQL in the colors of {@link Theme.Code}: keywords, strings with doubled
 * quotes ({@code 'it''s'}), PostgreSQL's escape strings, in which the backslash escapes too ({@code
 * E'it\'s'}), and its dollar-quoted strings ({@code $$...$$}, {@code $fn$...$fn$}), numbers and
 * parameters ({@code $1}), line comments ({@code --}), block comments, nested as in PostgreSQL,
 * and operators, brackets and punctuation. It marks the brackets outside
 * strings, identifiers in quotes and comments ({@link Spans#bracket}), which the editor pairs.
 * Identifiers in double quotes keep the text's color, and a keyword inside them is none. Strings,
 * quoted identifiers, dollar-quoted strings and block comments go on across lines, which the
 * state of a line carries.
 *
 * <p>A record, so that two highlighters of the same colors are equal and a field keeps what it
 * asked of one while the view passes it again: {@code field.highlight(sql, sql)}. It scans each
 * line once, character by character, without regular expressions.
 *
 * <p>The {@link Dialect} says how the database writes what differs: {@link Dialect#POSTGRES}, the
 * default, as above; {@link Dialect#MYSQL} for MySQL and MariaDB.
 */
public record Sql(Theme.Code colors, Dialect dialect) implements Highlighter {

  /** How a database writes identifiers, strings and comments. */
  public enum Dialect {
    /** PostgreSQL, and the SQL standard where they agree. */
    POSTGRES,
    /**
     * MySQL and MariaDB in their default mode: identifiers in backticks ({@code `order`}, a
     * keyword inside is none), strings in single and in double quotes with the backslash as their
     * escape ({@code 'it\'s'}), comments from {@code #} to the end of the line, and from {@code --}
     * only where a space, a tab or another control character or the line's end follows it ({@code
     * 5--2} is 5 minus -2), block comments that do not nest, and no dollar quotes. MySQL runs what
     * stands in {@code /*! ... *}{@code /} and reads {@code /*+ ... *}{@code /} as optimizer
     * hints; both are colored as comments.
     */
    MYSQL
  }

  public Sql {
    java.util.Objects.requireNonNull(dialect, "dialect");
  }

  /** PostgreSQL's highlighter in these colors. */
  public Sql(Theme.Code colors) {
    this(colors, Dialect.POSTGRES);
  }

  /**
   * The highlighter in the colors for code of {@link Theme#CURRENT}, as PostgreSQL writes SQL; in
   * the view, where the app provides its theme.
   */
  public static Sql of() {
    Theme theme = Theme.CURRENT.get();
    return new Sql(theme.code());
  }

  /** The highlighter in the theme's colors for code, as {@code dialect} writes SQL. */
  public static Sql of(Dialect dialect) {
    Theme theme = Theme.CURRENT.get();
    return new Sql(theme.code(), dialect);
  }

  // What a line ends inside of; null for nothing.
  private record State(int kind, int depth, String tag) {}

  private static final int NONE = 0;
  private static final int COMMENT = 1;
  private static final int STRING = 2;
  private static final int QUOTED = 3;
  private static final int DOLLAR = 4;
  private static final int BACKTICK = 5;
  private static final int ESCAPED = 6; // PostgreSQL's E'...'

  private static final State IN_STRING = new State(STRING, 0, null);
  private static final State IN_QUOTED = new State(QUOTED, 0, null);
  private static final State IN_COMMENT = new State(COMMENT, 1, null);
  private static final State IN_BACKTICK = new State(BACKTICK, 0, null);
  private static final State IN_ESCAPED = new State(ESCAPED, 0, null);

  private static final String OPERATORS = "+-*/%<>=!~^&|#@:;,.()[]{}";
  private static final String BRACKETS = "()[]{}";

  /** The keywords of SQL and PostgreSQL that queries and definitions use most, and the types. */
  static final Set<String> KEYWORDS = Set.of(
      "ADD",
      "ALL",
      "ALTER",
      "ANALYZE",
      "AND",
      "ANY",
      "ARRAY",
      "AS",
      "ASC",
      "BEGIN",
      "BETWEEN",
      "BIGINT",
      "BOOLEAN",
      "BY",
      "CASCADE",
      "CASE",
      "CAST",
      "CHAR",
      "CHECK",
      "COLLATE",
      "COLUMN",
      "COMMIT",
      "CONFLICT",
      "CONSTRAINT",
      "CREATE",
      "CROSS",
      "CURRENT",
      "DATABASE",
      "DATE",
      "DECIMAL",
      "DECLARE",
      "DEFAULT",
      "DELETE",
      "DESC",
      "DISTINCT",
      "DO",
      "DOUBLE",
      "DROP",
      "ELSE",
      "END",
      "ESCAPE",
      "EXCEPT",
      "EXISTS",
      "EXPLAIN",
      "FALSE",
      "FETCH",
      "FILTER",
      "FIRST",
      "FOLLOWING",
      "FOR",
      "FOREIGN",
      "FROM",
      "FULL",
      "FUNCTION",
      "GRANT",
      "GROUP",
      "HAVING",
      "IF",
      "ILIKE",
      "IN",
      "INDEX",
      "INNER",
      "INSERT",
      "INT",
      "INTEGER",
      "INTERSECT",
      "INTERVAL",
      "INTO",
      "IS",
      "JOIN",
      "JSON",
      "JSONB",
      "KEY",
      "LANGUAGE",
      "LAST",
      "LATERAL",
      "LEFT",
      "LIKE",
      "LIMIT",
      "MATERIALIZED",
      "MERGE",
      "NATURAL",
      "NEXT",
      "NOT",
      "NOTHING",
      "NULL",
      "NULLS",
      "NUMERIC",
      "OF",
      "OFFSET",
      "ON",
      "ONLY",
      "OR",
      "ORDER",
      "OUTER",
      "OVER",
      "PARTITION",
      "PRECEDING",
      "PRECISION",
      "PRIMARY",
      "PROCEDURE",
      "RANGE",
      "REAL",
      "RECURSIVE",
      "REFERENCES",
      "RENAME",
      "REPLACE",
      "RESTRICT",
      "RETURNING",
      "RETURNS",
      "REVOKE",
      "RIGHT",
      "ROLLBACK",
      "ROW",
      "ROWS",
      "SCHEMA",
      "SELECT",
      "SEQUENCE",
      "SET",
      "SMALLINT",
      "SOME",
      "TABLE",
      "TEMP",
      "TEMPORARY",
      "TEXT",
      "THEN",
      "TIME",
      "TIMESTAMP",
      "TIMESTAMPTZ",
      "TO",
      "TRANSACTION",
      "TRIGGER",
      "TRUE",
      "TRUNCATE",
      "UNBOUNDED",
      "UNION",
      "UNIQUE",
      "UPDATE",
      "USING",
      "UUID",
      "VALUES",
      "VARCHAR",
      "VIEW",
      "WHEN",
      "WHERE",
      "WINDOW",
      "WITH",
      "ZONE");

  private static final int LONGEST = 12; // MATERIALIZED

  /** The keywords this highlighter colors, in upper case, such as for completions. */
  public static Set<String> keywords() {
    return KEYWORDS;
  }

  @Override
  public Object line(String line, Object state, Spans out) {
    State s = (State) state;
    int kind = s == null ? NONE : s.kind;
    int depth = s == null ? 0 : s.depth;
    String tag = s == null ? null : s.tag;
    int start = 0; // where the string, identifier or comment the scan is inside began
    int n = line.length();
    int i = 0;
    boolean mysql = dialect == Dialect.MYSQL;
    while (i < n) {
      char c = line.charAt(i);
      switch (kind) {
        case COMMENT -> {
          if (line.startsWith("*/", i)) {
            i += 2;
            if (--depth == 0) {
              out.color(start, i, colors.comment());
              kind = NONE;
            }
          } else if (!mysql && line.startsWith("/*", i)) {
            depth++;
            i += 2;
          } else {
            i++;
          }
        }
        case STRING, ESCAPED, QUOTED, BACKTICK -> {
          char quote = kind == QUOTED ? '"' : kind == BACKTICK ? '`' : '\'';
          if (c == '\\' && (kind == ESCAPED || mysql && kind != BACKTICK)) {
            i = Math.min(i + 2, n); // the character after it is itself, a quote too
          } else if (c != quote) {
            i++;
          } else if (i + 1 < n && line.charAt(i + 1) == quote) {
            i += 2; // doubled: the quote itself
          } else {
            i++;
            if (kind == STRING || kind == ESCAPED || mysql && kind == QUOTED) {
              out.color(start, i, colors.string());
            }
            kind = NONE;
          }
        }
        case DOLLAR -> {
          if (line.startsWith(tag, i)) {
            i += tag.length();
            out.color(start, i, colors.string());
            kind = NONE;
          } else {
            i++;
          }
        }
        default -> {
          start = i;
          char next = i + 1 < n ? line.charAt(i + 1) : 0;
          if (c == '-' && next == '-' && (!mysql || i + 2 == n || line.charAt(i + 2) <= ' ')
              || mysql && c == '#') {
            out.color(i, n, colors.comment());
            i = n;
          } else if (c == '/' && next == '*') {
            kind = COMMENT;
            depth = 1;
            i += 2;
          } else if (c == '\'') {
            kind = STRING;
            i++;
          } else if (!mysql && (c == 'E' || c == 'e') && next == '\'') {
            kind = ESCAPED;
            i += 2;
          } else if (c == '"') {
            kind = QUOTED;
            i++;
          } else if (mysql && c == '`') {
            kind = BACKTICK;
            i++;
          } else if (!mysql && c == '$' && dollarTag(line, i) > i) {
            int end = dollarTag(line, i);
            tag = line.substring(i, end);
            kind = DOLLAR;
            i = end;
          } else if (!mysql && c == '$' && isDigit(next)) { // a parameter, $1
            i = number(line, i + 1);
            out.color(start, i, colors.number());
          } else if (isDigit(c) || c == '.' && isDigit(next)) {
            i = number(line, i);
            out.color(start, i, colors.number());
          } else if (Character.isLetter(c) || c == '_') {
            i++;
            while (i < n && isWordPart(line.charAt(i))) {
              i++;
            }
            if (i - start <= LONGEST
                && KEYWORDS.contains(line.substring(start, i).toUpperCase(Locale.ROOT))) {
              out.color(start, i, colors.keyword());
            }
          } else if (OPERATORS.indexOf(c) >= 0) {
            out.color(i, i + 1, colors.operator());
            if (BRACKETS.indexOf(c) >= 0) {
              out.bracket(i);
            }
            i++;
          } else {
            i++;
          }
        }
      }
    }
    switch (kind) {
      case COMMENT -> {
        out.color(start, n, colors.comment());
        return depth == 1 ? IN_COMMENT : new State(COMMENT, depth, null);
      }
      case STRING, ESCAPED -> {
        out.color(start, n, colors.string());
        return kind == STRING ? IN_STRING : IN_ESCAPED;
      }
      case QUOTED -> {
        if (mysql) { // a string there
          out.color(start, n, colors.string());
        }
        return IN_QUOTED;
      }
      case BACKTICK -> {
        return IN_BACKTICK;
      }
      case DOLLAR -> {
        out.color(start, n, colors.string());
        return new State(DOLLAR, 0, tag);
      }
      default -> {
        return null;
      }
    }
  }

  /**
   * The end of a dollar quote's opening tag at {@code at}, {@code $$} or {@code $name$} with a name
   * that does not start with a digit; {@code at} itself where there is none, as for {@code $1}.
   */
  private static int dollarTag(String line, int at) {
    int i = at + 1;
    if (i < line.length() && (Character.isLetter(line.charAt(i)) || line.charAt(i) == '_')) {
      while (i < line.length()
          && (Character.isLetterOrDigit(line.charAt(i)) || line.charAt(i) == '_')) {
        i++;
      }
    }
    return i < line.length() && line.charAt(i) == '$' ? i + 1 : at;
  }

  /** The end of a number at {@code at}: digits, a fraction and an exponent. */
  private static int number(String line, int at) {
    int n = line.length();
    int i = at;
    while (i < n && isDigit(line.charAt(i))) {
      i++;
    }
    if (i < n && line.charAt(i) == '.') {
      i++;
      while (i < n && isDigit(line.charAt(i))) {
        i++;
      }
    }
    if (i < n && (line.charAt(i) == 'e' || line.charAt(i) == 'E')) {
      int e = i + 1;
      if (e < n && (line.charAt(e) == '+' || line.charAt(e) == '-')) {
        e++;
      }
      if (e < n && isDigit(line.charAt(e))) {
        i = e;
        while (i < n && isDigit(line.charAt(i))) {
          i++;
        }
      }
    }
    return i;
  }

  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  private static boolean isWordPart(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '$';
  }

  /** Brackets in strings, quoted identifiers and comments are not marked. */
  @Override
  public boolean marksBrackets() {
    return true;
  }

  /** SQL's comments to the end of a line start with {@code --}. */
  @Override
  public String lineComment() {
    return "--";
  }
}
