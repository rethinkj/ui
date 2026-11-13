package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Measure;
import com.rethinkj.ui.Style;

/**
 * Fields with their labels, help and errors, as a dialog or a page of settings asks for values:
 *
 * <pre>{@code
 * form(t, Labels.BESIDE,
 *     field("Host", textField(t, s.host, "Name or address", v -> s.host = v)).required(),
 *     field("Port", textField(t, s.port, "5432", s::port).w(72))
 *         .help("1 to 65535").error(s.portError()),               // null while it is right
 *     field("Password", revealable(t, passwordField(t, s.password, null, v -> s.password = v),
 *         s.revealed, s::flipRevealed)))
 * }</pre>
 *
 * <p>Labels stand {@link Labels#BESIDE} their fields, in a column as wide as the widest of them,
 * for wide panels, or {@link Labels#ABOVE} them, for narrow ones. A label beside its field is
 * centred on the field's first line. A required field has a {@code *} after its label in the
 * danger colour. Help text is muted and wraps below the field; an error replaces it, in the danger
 * colour, and gives a text field the invalid look ({@link TextField#invalid}). The fields are
 * {@link Theme.Spacing#m} apart beside their labels and {@link Theme.Spacing#l} apart below them.
 *
 * <p>Each label names its field: the field, or the first control inside it, gets the label as
 * its {@link El#label} when it is a text field or has no label of its own, so that {@code
 * label="Host"} finds the field and a tree dump says what it is. A select keeps its choice as its
 * label. An error makes the field {@code invalid} in the dump. A {@link ValueField} whose text is
 * no value shows its format's message as the error without the app passing one; an error the
 * app passes wins. {@link #buttons} ends a form, a
 * card or a page with its buttons at the right, as a dialog has them.
 */
public final class Form {

  /** Where the labels stand. */
  public enum Labels {
    /** In a column before the fields, as wide as the widest label: for wide panels. */
    BESIDE,
    /** Above each field: for narrow panels. */
    ABOVE
  }

  /**
   * A field of a form: its label, its control and what is said below it.
   *
   * @param label the text before or above the control, also the control's label
   * @param control a text field or any other control, with the width it should have
   * @param help muted text below the control; null for none
   * @param error text in the danger colour below the control, in place of the help, while the
   *     value is not accepted; null for none
   * @param isRequired whether the label is followed by a {@code *}
   */
  public record Field(String label, El control, String help, String error, boolean isRequired) {

    /** This field with muted help below it. */
    public Field help(String text) {
      return new Field(label, control, text, error, isRequired);
    }

    /** This field with an error below it in place of its help, or without when null. */
    public Field error(String text) {
      return new Field(label, control, help, text, isRequired);
    }

    /** This field marked as one that has to be filled in. */
    public Field required() {
      return new Field(label, control, help, error, true);
    }
  }

  private Form() {}

  /** A field with its label and no help, error or marker. */
  public static Field field(String label, El control) {
    return new Field(label, control, null, null, false);
  }

  /**
   * The fields, one below the other, with their labels where {@code labels} says; a null field is
   * left out, so that {@code active ? field(...) : null} shows a field under a condition.
   */
  public static El form(Theme t, Labels labels, Field... fields) {
    fields =
        java.util.Arrays.stream(fields).filter(java.util.Objects::nonNull).toArray(Field[]::new);
    El[] heads = new El[fields.length];
    float column = 0;
    for (int i = 0; i < fields.length; i++) {
      heads[i] = head(t, fields[i]);
      column = Math.max(column, width(heads[i]));
    }
    El form = col().gap(labels == Labels.BESIDE ? t.space().m() : t.space().l());
    for (int i = 0; i < fields.length; i++) {
      Field field = fields[i];
      El target = target(field.control());
      named(field.control(), field.label());
      // The app's error, else the message of a typed field's text ({@link ValueField}).
      String error =
          field.error() != null || target == null ? field.error() : target.errorMessage();
      if (error != null && target != null && target.isEditable()) {
        TextField.invalid(t, target);
      } else if (error != null && target != null) {
        target.invalid(true);
      }
      El body = col().gap(t.space().xs()).child(field.control().alignSelf(Style.Align.START));
      String below = error != null ? error : field.help();
      if (below != null) {
        body.child(label(t, Label.Variant.MUTED, below)
            .wrapText()
            .color(error != null ? t.status().danger() : t.text().muted()));
      }
      if (labels == Labels.ABOVE) {
        form.child(col().gap(t.space().xs()).children(heads[i], body));
        continue;
      }
      body.grow(1).style().minWidth = Len.ZERO;
      // As high as a control, so that the label lines up with the field's first line.
      El head = heads[i].h(t.size().control()).w((float) Math.ceil(column)).shrink(0);
      form.child(row().gap(t.space().m()).children(head, body));
    }
    return form;
  }

  /**
   * Buttons at the end of a form, a card or a page: a row at the right, {@link Theme.Spacing#m}
   * apart, the main action usually last, as a dialog has them.
   */
  public static El buttons(Theme t, El... buttons) {
    return row()
        .gap(t.space().m())
        .justify(Style.Content.END)
        .align(Style.Align.CENTER)
        .children(buttons);
  }

  /** The label of a field, with the marker of a required one. */
  private static El head(Theme t, Field field) {
    El head = row().gap(t.space().xs()).align(Style.Align.CENTER).child(label(t, field.label()));
    if (field.isRequired()) {
      head.child(label(t, "*").color(t.status().danger()));
    }
    return head;
  }

  /** The width of a row of text elements on one line, as layout will give it. */
  private static float width(El head) {
    float width = 0;
    for (El child : head.children()) {
      width += child
          .text()
          .measure(Float.NaN, Float.NaN, Measure.MAX_CONTENT, Measure.MAX_CONTENT)
          .width();
    }
    return width + (head.children().size() - 1) * head.style().columnGap.value();
  }

  /**
   * Names a control after its label: the control, or the first element inside it that takes
   * input or has a role, gets {@code name} as its label when it is a text field, whose label is
   * otherwise its placeholder, or has no label yet.
   *
   * @return the control
   */
  static El named(El control, String name) {
    El target = target(control);
    if (target != null && (target.isEditable() || target.label() == null)) {
      target.label(name);
    }
    return control;
  }

  /** The control itself or the first element inside it that takes input or has a role. */
  static El target(El el) {
    if (el.isEditable() || el.isFocusable() || el.role() != null) {
      return el;
    }
    for (El child : el.children()) {
      El found = target(child);
      if (found != null) {
        return found;
      }
    }
    return null;
  }
}
