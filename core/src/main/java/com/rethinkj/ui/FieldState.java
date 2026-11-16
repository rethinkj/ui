package com.rethinkj.ui;

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
 * same field on every build; two fields of one tree cannot share one. A string the app changed
 * while the field was away ends the undo history when it comes back, as a string the app changes
 * on its own always does. Its composition, if an input method was composing, ends as the field
 * leaves. The object holds the field's editing state, the lines of a long text among it, until
 * the app lets go of it.
 */
public final class FieldState {

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

  public FieldState() {}

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
}
