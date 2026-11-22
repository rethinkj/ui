package com.rethinkj.ui.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The comment and doc-comment ranges of a text found so far by {@link #scan}, resumable across
 * frames with bounded work per call.
 *
 * <p>When the per-call line budget stops a scan early, {@link #complete} is {@code false} and only
 * the first {@link #scannedLines} lines have been examined. Passing the result back to {@link
 * #scan} with the same text and an equal language goes on from where it stopped; once complete,
 * {@link #scan} returns it as-is without work.
 */
public final class Comments {

  /** The most lines one {@link #scan} call asks a language about before leaving the rest. */
  static final int LINE_BUDGET = 4_000;

  /**
   * One comment or doc comment in the text. A multi-line comment (such as a block comment across
   * lines or a run of {@code ///} lines) is one {@code Comment}.
   *
   * @param start start offset in the whole text, inclusive
   * @param end end offset in the whole text, exclusive
   * @param doc {@code true} for a doc comment, {@code false} for an ordinary comment
   */
  public record Comment(int start, int end, boolean doc) {
    public Comment {
      if (start < 0 || end < start) {
        throw new IllegalArgumentException("start=" + start + ", end=" + end);
      }
    }
  }

  private final String text;
  private final Language language;
  private final List<Comment> comments;
  private final int scannedLines;
  private final boolean complete;
  // Private resume state for the next scan call when complete is false.
  private final int nextPos;
  private final Object state;
  private final int openStart;
  private final int openEnd;
  private final boolean openDoc;

  private Comments(
      String text,
      Language language,
      List<Comment> comments,
      int scannedLines,
      boolean complete,
      int nextPos,
      Object state,
      int openStart,
      int openEnd,
      boolean openDoc) {
    this.text = text;
    this.language = language;
    this.comments = comments;
    this.scannedLines = scannedLines;
    this.complete = complete;
    this.nextPos = nextPos;
    this.state = state;
    this.openStart = openStart;
    this.openEnd = openEnd;
    this.openDoc = openDoc;
  }

  /** The completed comments found so far, in order of appearance. */
  public List<Comment> comments() {
    return comments;
  }

  /** The number of lines examined so far from the start of the text. */
  public int scannedLines() {
    return scannedLines;
  }

  /** Whether the whole text has been scanned. */
  public boolean complete() {
    return complete;
  }

  /**
   * Scans {@code text} (with line feeds only, as in {@link CodeEditor}) for comments and doc
   * comments using {@code language}, doing at most a fixed number of lines of work per call.
   *
   * <p>When {@code before} was made for an equal text and an equal language, scanning goes on from
   * where {@code before} left off, or returns {@code before} as-is if it is already complete.
   * Otherwise scanning starts over from the first line.
   *
   * @param text the text to scan, with line feeds only
   * @param language the language whose {@link Language#comments} reports comment ranges
   * @param before the result of an earlier call to go on from, or {@code null} to start over
   * @return the comments found so far, with {@link #complete} {@code true} once the whole text has
   *     been scanned
   */
  public static Comments scan(String text, Language language, Comments before) {
    Objects.requireNonNull(text, "text");
    Objects.requireNonNull(language, "language");
    boolean resume =
        before != null && before.text.equals(text) && Objects.equals(before.language, language);
    if (resume && before.complete) {
      return before;
    }
    Scanner scanner = resume ? new Scanner(before) : new Scanner();
    int pos = resume ? before.nextPos : 0;
    int scanned = resume ? before.scannedLines : 0;
    Object state = resume ? before.state : null;
    for (int step = 0; step < LINE_BUDGET && pos >= 0; step++) {
      int feed = text.indexOf('\n', pos);
      int lineEnd = feed < 0 ? text.length() : feed;
      String line = text.substring(pos, lineEnd);
      scanner.beginLine(pos, line);
      state = language.comments(line, state, scanner);
      scanner.endLine(state);
      scanned++;
      pos = feed < 0 ? -1 : feed + 1;
    }
    boolean complete = pos < 0;
    if (complete) {
      scanner.finish();
    }
    return new Comments(
        text,
        language,
        List.copyOf(scanner.found),
        scanned,
        complete,
        pos,
        complete ? null : state,
        scanner.openStart,
        scanner.openEnd,
        scanner.openDoc);
  }

  private static final class Scanner implements Language.Out {
    final List<Comment> found;
    int openStart;
    int openEnd;
    boolean openDoc;

    private int lineStart;
    private String line;
    private boolean firstOnLine;
    private boolean openFromEarlierLine;
    private int lastTo;

    Scanner() {
      this.found = new ArrayList<>();
      this.openStart = -1;
    }

    Scanner(Comments before) {
      this.found = new ArrayList<>(before.comments);
      this.openStart = before.openStart;
      this.openEnd = before.openEnd;
      this.openDoc = before.openDoc;
    }

    void beginLine(int lineStart, String line) {
      this.lineStart = lineStart;
      this.line = line;
      this.firstOnLine = true;
      this.openFromEarlierLine = openStart >= 0;
      this.lastTo = -1;
    }

    @Override
    public void comment(int from, int to, boolean doc) {
      int len = line.length();
      int start = Math.max(0, Math.min(from, len));
      int end = Math.max(start, Math.min(to, len));
      if (firstOnLine && openFromEarlierLine && doc == openDoc && isBlank(line, 0, start)) {
        openEnd = lineStart + end;
        lastTo = end;
        firstOnLine = false;
        return;
      }
      if (openStart >= 0) {
        found.add(new Comment(openStart, openEnd, openDoc));
        openStart = -1;
        openFromEarlierLine = false;
      }
      if (end > start) {
        openStart = lineStart + start;
        openEnd = lineStart + end;
        openDoc = doc;
        lastTo = end;
        firstOnLine = false;
      }
    }

    void endLine(Object state) {
      if (openStart < 0) {
        return;
      }
      if (firstOnLine) {
        // An open comment from an earlier line stays open across a blank line while state holds.
        if (state == null || !isBlank(line, 0, line.length())) {
          flush();
        }
      } else if (state == null || !isBlank(line, lastTo, line.length())) {
        flush();
      }
    }

    void finish() {
      if (openStart >= 0) {
        flush();
      }
    }

    private void flush() {
      found.add(new Comment(openStart, openEnd, openDoc));
      openStart = -1;
    }

    private static boolean isBlank(String text, int from, int to) {
      for (int i = from; i < to; i++) {
        if (!Character.isWhitespace(text.charAt(i))) {
          return false;
        }
      }
      return true;
    }
  }
}
