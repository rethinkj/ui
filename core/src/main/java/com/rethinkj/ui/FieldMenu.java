package com.rethinkj.ui;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The menu of a text field, such as Cut, Copy, Paste and Select all, which the kit's fields have
 * ({@link El#fieldMenu}). A secondary press on an {@link El#editable} field that takes input opens
 * it, and so do the Menu key and Shift+F10 in it, unless the field has an {@link
 * El#onSecondaryPress} handler of its own, which goes first. While it is open, the app builds it
 * after each build of the view, as it applies a look, and attaches it to the field as an overlay;
 * it closes when this returns null.
 */
@FunctionalInterface
public interface FieldMenu {

  /**
   * The menu's overlay, to place at {@link Open#x}, {@link Open#y}; or null once it has closed,
   * after which the app forgets it.
   *
   * @param field the field, of the tree the view has just built
   */
  El build(El field, Open open);

  /**
   * A field's menu while it is open: where it opened, what the field's commands can do now, and
   * the commands, which act on the field as its keys do, each a step of its undo history where it
   * edits. A command runs right after the handler that calls it, as {@link App#edit} does.
   */
  final class Open {
    private final App<?> app;
    private final Object field;
    private final Object host; // what the overlay attaches to: the field, or El#fieldMenuOf's
    private final Object on;
    private final float x;
    private final float y;
    private final boolean clipboardText; // read once, when the menu opened
    private boolean selected;
    private boolean editable;
    private boolean shown;
    private boolean filled;
    private El target;
    private Object state;

    Open(
        App<?> app, Object field, Object host, Object on, float x, float y, boolean clipboardText) {
      this.app = app;
      this.field = field;
      this.host = host;
      this.on = on;
      this.x = x;
      this.y = y;
      this.clipboardText = clipboardText;
    }

    /** The identity of the field. */
    Object field() {
      return field;
    }

    /** The identity of the element the menu attaches to: the field, or the element around it. */
    Object host() {
      return host;
    }

    /** Takes what the commands can do from the field of this build and its editor. */
    void update(El el, Editor editor, boolean scopeSelected) {
      selected = scopeSelected || editor.caret() != editor.anchor();
      editable = el.onEdit() != null && !el.isReadOnly();
      shown = !el.text().hidden();
      filled = !el.text().string().isEmpty();
      target = on == null ? null : Animator.find(el, field, on);
    }

    /** Where the menu opened, in logical pixels of the window. */
    public float x() {
      return x;
    }

    public float y() {
      return y;
    }

    /**
     * Whether the menu opened on {@code part} or inside it: the element under the pointer for a
     * secondary press, or the focused element for the Menu key and Shift+F10. {@code part} is an
     * element inside the field passed to {@link FieldMenu#build}.
     */
    public boolean openedOn(El part) {
      return part != null && target != null && contains(part, target);
    }

    private static boolean contains(El el, El wanted) {
      if (el == wanted) {
        return true;
      }
      for (El child : el.children()) {
        if (contains(child, wanted)) {
          return true;
        }
      }
      return false;
    }

    /** Whether some text is selected that the field shows and takes edits for. */
    public boolean canCut() {
      return canCopy() && editable;
    }

    /**
     * Whether some text is selected—in this field or across a {@link El#selectionScope selection
     * scope} it belongs to—that the field shows: a hidden password copies nothing.
     */
    public boolean canCopy() {
      return selected && shown;
    }

    /** Whether the field takes edits and the clipboard had text when the menu opened. */
    public boolean canPaste() {
      return editable && clipboardText;
    }

    /** Whether the field has text. */
    public boolean canSelectAll() {
      return filled;
    }

    public void cut() {
      run(editor -> editor.cut(app.clipboard()));
    }

    /**
     * Copies the field's selection, or the selection across its {@link El#selectionScope selection
     * scope} when it belongs to one.
     */
    public void copy() {
      app.copyField(field);
    }

    public void paste() {
      run(editor -> editor.paste(app.clipboard()));
    }

    public void selectAll() {
      app.selectAllField(field);
    }

    private void run(Consumer<Editor> command) {
      app.step(field, command);
    }

    /**
     * What the menu keeps while it is open, such as the item a key highlighted: made by {@code
     * initial} the first time it is asked for, then the same object until the menu closes.
     */
    @SuppressWarnings("unchecked")
    public <T> T state(Supplier<T> initial) {
      if (state == null) {
        state = initial.get();
      }
      return (T) state;
    }
  }
}
