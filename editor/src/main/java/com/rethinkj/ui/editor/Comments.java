package com.rethinkj.ui.editor;

import java.util.ArrayList;
import java.util.Collections;
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
  private final int boundary;
  private final boolean complete;
  // Private resume state for the next scan call when complete is false.
  private final int nextPos;
  private final Object state;

  private Comments(
      String text,
      Language language,
      List<Comment> comments,
      int scannedLines,
      int boundary,
      boolean complete,
      int nextPos,
      Object state) {
    this.text = text;
    this.language = language;
    this.comments = comments;
    this.scannedLines = scannedLines;
    this.boundary = boundary;
    this.complete = complete;
    this.nextPos = nextPos;
    this.state = state;
  }

  /**
   * The comments found so far, in order; until {@link #complete}, the last may go on in lines not
   * scanned yet.
   */
  public List<Comment> comments() {
    return comments;
  }

  /** The number of lines examined so far from the start of the text. */
  public int scannedLines() {
    return scannedLines;
  }

  /**
   * Where the last line scanned starts, or the end of the last comment found when only blank lines
   * follow it and the language's state says something goes on, or {@link Integer#MAX_VALUE} once
   * {@link #complete}: a comment that ends before it is whole, one that reaches it may go on.
   */
  int boundary() {
    return boundary;
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
    List<Comment> found = resume ? new ArrayList<>(before.comments) : new ArrayList<>();
    int pos = resume ? before.nextPos : 0;
    int scanned = resume ? before.scannedLines : 0;
    Object state = resume ? before.state : null;
    int first = pos; // where this call starts
    int lastStart = 0;
    for (int step = 0; step < LINE_BUDGET && pos >= 0; step++) {
      int feed = text.indexOf('\n', pos);
      String line = text.substring(pos, feed < 0 ? text.length() : feed);
      int lineStart = pos;
      lastStart = lineStart;
      state = language.comments(line, state, (from, to, doc, continued) -> {
        int start = lineStart + Math.clamp(from, 0, line.length());
        int end = Math.max(start, lineStart + Math.clamp(to, 0, line.length()));
        Comment last = found.isEmpty() ? null : found.getLast();
        if (continued && last != null) {
          found.set(found.size() - 1, new Comment(last.start(), end, last.doc()));
        } else {
          found.add(new Comment(start, end, doc));
        }
      });
      scanned++;
      pos = feed < 0 ? -1 : feed + 1;
    }
    boolean complete = pos < 0;
    int boundary = complete ? Integer.MAX_VALUE : lastStart;
    // Something goes on: the last comment may, across blank lines a language does not report.
    // Only one this call found or extended, so that a long run of blank lines costs one more call.
    int end = found.isEmpty() ? -1 : found.getLast().end();
    if (!complete && state != null && end >= first) {
      int at = end;
      while (at < pos && Character.isWhitespace(text.charAt(at))) {
        at++;
      }
      if (at == pos) {
        boundary = Math.min(boundary, end);
      }
    }
    return new Comments(
        text,
        language,
        Collections.unmodifiableList(found),
        scanned,
        boundary,
        complete,
        pos,
        complete ? null : state);
  }
}
