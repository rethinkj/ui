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
 * El.text(q.sql).editable(v -> q.sql = v).multiline().fieldState(q.field)
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
  // The identity of the field that has it in the build of this number, to find two fields with one.
  Object id;
  long build = -1;

  public FieldState() {}
}
