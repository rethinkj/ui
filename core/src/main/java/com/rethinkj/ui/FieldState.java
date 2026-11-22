package com.rethinkj.ui;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * What a text field keeps of its editing while it is not in the tree, held by the app: caret and
 * anchor, the scroll of both axes, the undo history and whether Tab indents or moves the focus
 * ({@link El#tabIndents}, Control+M). {@link App} keeps that by the field's identity and forgets
 * it when the field leaves the tree, as when a tab whose content holds it is not the active one.
 * A field built with one of these ({@link El#fieldState}) takes it from there instead, so that it
 * comes back as it was:
 *
 * <pre>{@code
 * final class Query { String sql = ""; final FieldState field = new FieldState(); }
 *
 * El.text(query.sql).editable(value -> query.sql = value).multiline().fieldState(query.field)
 * }</pre>
 *
 * <p>The app keeps one per field, as it keeps a {@code Menu.State} per menu, and gives it to the
 * same field on every build; two fields of one tree cannot share one. To show one text in several
 * fields with a shared undo history, create a {@link TextDocument} and pass it to {@link
 * #FieldState(TextDocument)} for each field. A string the app changed while the field was away ends
 * the undo history when it comes back, as a string the app changes on its own always does. Its
 * composition, if an input method was composing, ends as the field leaves. The object holds the
 * field's editing state, the lines of a long text among it, until the app lets go of it.
 */
public final class FieldState {

  final TextDocument document;
  Editor editor;
  // The app whose tree has the field: another app of the same state takes it over, as a window
  // that a tab moves to.
  Object app;
  // The identity of the field that has it in the build of this number, to find two fields with one.
  Object id;
  long build = -1;
  // A selection the app asked for, which the field takes at its next build; -1 for none.
  int caret = -1;
  int anchor = -1;

  public FieldState() {
    // no field, no selection asked for: a field takes the state over at its first build
    this.document = null;
  }

  /**
   * Creates a field state backed by {@code document}, which may be shared with other {@code
   * FieldState} instances: fields sharing one document share a single undo and redo history and
   * update their carets and lines from each other's edits, while keeping their own caret,
   * selection, scroll, folds, blocks and composition.
   */
  public FieldState(TextDocument document) {
    this.document = Objects.requireNonNull(document, "document");
  }

  /**
   * Selects from {@code anchor} to {@code caret} in the field, or puts the caret there when they
   * are equal, at the field's next build, and scrolls it into view as {@link App#select} does.
   * Unlike that, it needs no field in the last frame's tree: a field that is away, as in a tab that
   * is not shown, comes back with this selection. The positions are indexes into the string as the
   * field shows it, clamped to it and moved to the grapheme boundary at or before them.
   *
   * @throws IllegalArgumentException if a position is negative
   */
  public void select(int caret, int anchor) {
    if (caret < 0 || anchor < 0) {
      throw new IllegalArgumentException(
          "caret and anchor must be 0 or more, were " + caret + " and " + anchor);
    }
    this.caret = caret;
    this.anchor = anchor;
  }

  /**
   * Replaces the field's selection by {@code text}, or puts it in at the caret when nothing is
   * selected, with the caret after it, as one step of the undo history: for a button or a menu
   * beside the field, such as one that puts in a snippet. Called in a handler, the frame edits
   * right after it, as {@link App#edit} does, in the tree built from what the handler changed;
   * the edit goes to the field's {@link El#editable onEdit} handler as typed text does. Nothing
   * happens when this field is not in that tree, as when the handler switched to another tab, or
   * when it is read-only or disabled.
   */
  public void replaceSelection(String text) {
    Objects.requireNonNull(text, "text");
    replaceSelection(text, text.length());
  }

  /**
   * Replaces the field's selection by {@code text} as {@link #replaceSelection(String)} does, with
   * the caret at {@code caret} in {@code text}, such as between the parentheses of {@code "()"}
   * with 1. The caret moves with the text when the field's limits change it, as its line breaks or
   * {@link El#maxLength}.
   *
   * @param caret the caret's position in {@code text}, from 0 to its length
   * @throws IllegalArgumentException if {@code caret} is negative or beyond {@code text}
   */
  public void replaceSelection(String text, int caret) {
    checkCaret(text, caret);
    edit(editor -> editor.request(
        editor.start(), editor.end(), text, editor.start() + caret, editor.start() + caret, false));
  }

  /**
   * Replaces characters {@code from} to {@code to} of the field's string by {@code text}, with the
   * caret at {@code caret} in {@code text}, as {@link #replaceSelection(String, int)} does with
   * the selection: for text that goes to a place the app computed, such as a line below the one
   * with the caret. The positions are taken as they are, not moved to a grapheme boundary as
   * {@link #select} moves them, and clamped to the string the field has when the edit runs. Undo
   * brings back the selection the field had before. Unlike {@link App#edit}, it needs no field of
   * the last frame's tree, and {@code caret} is a position in {@code text}, not in the new string.
   *
   * @param from the first position, an index into the string from 0
   * @param to the position after the last, at least {@code from}
   * @param caret the caret's position in {@code text}, from 0 to its length
   * @throws IllegalArgumentException if {@code from} is negative, {@code to} is before it, or
   *     {@code caret} is negative or beyond {@code text}
   */
  public void replace(int from, int to, String text, int caret) {
    if (from < 0 || to < from) {
      throw new IllegalArgumentException(
          "from must be 0 or more and to at least from, were " + from + " and " + to);
    }
    checkCaret(text, caret);
    edit(editor -> {
      int at = Math.min(from, editor.text().length()); // the caret goes into the text where it is
      editor.request(at, to, text, at + caret, at + caret, false);
    });
  }

  private static void checkCaret(String text, int caret) {
    Objects.requireNonNull(text, "text");
    if (caret < 0 || caret > text.length()) {
      throw new IllegalArgumentException(
          "caret must be from 0 to the text's length " + text.length() + ", was " + caret);
    }
  }

  private void edit(Consumer<Editor> edit) {
    if (app instanceof App<?> owner) {
      owner.edit(this, edit);
    }
  }
}
