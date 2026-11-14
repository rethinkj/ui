package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Measure;
import com.rethinkj.ui.Style;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

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
 *
 * <p>A longer form groups its fields under headings and puts fields that belong together in one
 * row:
 *
 * <pre>{@code
 * form(t, Labels.BESIDE,
 *     group("Server",
 *         row(field("Host", host).required().grow(), field("Port", port.w(72))),
 *         field("Database", database)),
 *     group("Login", field("User", user), field("Password", password)).divided())
 * }</pre>
 *
 * <p>The label column is one for the whole form, as wide as the widest label in any group, so the
 * controls of all groups start at one x; forms in separate cards share one by {@link #labelWidth}.
 * A {@link Group} is {@code role=group} with its title as its label, its heading in {@link
 * Theme.Type#strong} at {@code level=4} with an optional muted description, {@link
 * Theme.Spacing#m} above its fields; groups lie two steps of spacing further apart than fields
 * ({@code xl} beside, {@code xxl} above), with a {@link Divider} in the middle where a group is
 * {@link Group#divided}. In a {@link Row} each field keeps its label, help, error and marker:
 * beside, the first label stands in the column and each further one before its control; above,
 * each above its control. Each field is as wide as its control, or takes the room left with
 * {@link Field#grow}, and what is said below it is as wide as its control; the controls stay on
 * the row's first line. Where the form is too narrow the row wraps, and a field on a further line
 * keeps its label before its control.
 */
public final class Form {

  /** Where the labels stand. */
  public enum Labels {
    /** In a column before the fields, as wide as the widest label: for wide panels. */
    BESIDE,
    /** Above each field: for narrow panels. */
    ABOVE
  }

  /** What a form holds: a {@link Field}, a {@link Row} of fields or a {@link Group} of them. */
  public sealed interface Entry permits Field, Row, Group {}

  /**
   * A field of a form: its label, its control and what is said below it.
   *
   * @param label the text before or above the control, also the control's label
   * @param control a text field or any other control, with the width it should have
   * @param help muted text below the control; null for none
   * @param error text in the danger colour below the control, in place of the help, while the
   *     value is not accepted; null for none
   * @param isRequired whether the label is followed by a {@code *}
   * @param grows whether the field takes the room a {@link Row} leaves, its control's width the
   *     least it has; a field alone in its row takes the form's width whatever this says
   */
  public record Field(
      String label, El control, String help, String error, boolean isRequired, boolean grows)
      implements Entry {

    /** A field that keeps its control's width. */
    public Field(String label, El control, String help, String error, boolean isRequired) {
      this(label, control, help, error, isRequired, false);
    }

    /** This field with muted help below it. */
    public Field help(String text) {
      return new Field(label, control, text, error, isRequired, grows);
    }

    /** This field with an error below it in place of its help, or without when null. */
    public Field error(String text) {
      return new Field(label, control, help, text, isRequired, grows);
    }

    /** This field marked as one that has to be filled in. */
    public Field required() {
      return new Field(label, control, help, error, true, grows);
    }

    /** This field taking the room its {@link Row} leaves, as a host beside its port. */
    public Field grow() {
      return new Field(label, control, help, error, isRequired, true);
    }
  }

  /**
   * Fields side by side in one row of a form, each with its own label, help, error and marker.
   * Made by {@link Form#row}.
   */
  public record Row(List<Field> fields) implements Entry {}

  /**
   * Fields and rows under a heading, which share the form's label column with the rest. Made by
   * {@link Form#group}.
   *
   * @param title the heading, also the group's label in a tree dump
   * @param description muted text below the heading that wraps; null for none
   * @param isDivided whether a {@link Divider} sets the group apart from what comes before it
   * @param level the heading's level in a tree dump
   * @param entries the fields and rows; null entries are left out
   */
  public record Group(
      String title, String description, boolean isDivided, int level, List<Entry> entries)
      implements Entry {

    /** This group with muted text below its heading. */
    public Group description(String text) {
      return new Group(title, text, isDivided, level, entries);
    }

    /** This group with a hairline between it and what comes before it in the form. */
    public Group divided() {
      return new Group(title, description, true, level, entries);
    }

    /** This group with its heading at another level, 4 unless set. */
    public Group level(int level) {
      return new Group(title, description, isDivided, level, entries);
    }
  }

  private Form() {}

  /** A field with its label and no help, error or marker. */
  public static Field field(String label, El control) {
    return new Field(label, control, null, null, false);
  }

  /**
   * Fields side by side: host and port, first and last name, a number and its unit. Each keeps
   * its own label, help, error and marker; null fields are left out, and a row of one field is
   * that field.
   */
  public static Row row(Field... fields) {
    return new Row(Collections.unmodifiableList(Arrays.asList(fields.clone())));
  }

  /**
   * Fields and rows under a heading of level 4, below a card's title ({@link Card#header}); a
   * group holds no groups.
   */
  public static Group group(String title, Entry... entries) {
    return new Group(
        title, null, false, 4, Collections.unmodifiableList(Arrays.asList(entries.clone())));
  }

  /**
   * The fields, one below the other, with their labels where {@code labels} says; a null field is
   * left out, so that {@code active ? field(...) : null} shows a field under a condition, and so
   * is a group or a row whose entries are all null.
   */
  public static El form(Theme t, Labels labels, Entry... entries) {
    return form(t, labels, 0, entries);
  }

  /**
   * The form with its label column at least {@code labelWidth} wide ({@link Labels#BESIDE}), so
   * that forms in separate cards or pages line up: give each the {@link #labelWidth} of all.
   */
  public static El form(Theme t, Labels labels, float labelWidth, Entry... entries) {
    List<Entry> present = present(entries, true);
    float column = (float) Math.ceil(Math.max(labelWidth, column(t, present)));
    boolean beside = labels == Labels.BESIDE;
    float gap = beside ? t.space().m() : t.space().l();
    // Groups lie two steps of spacing further apart than fields.
    float apart = beside ? t.space().xl() : t.space().xxl();
    El form = col().gap(gap);
    Entry before = null;
    for (Entry entry : present) {
      boolean group = entry instanceof Group;
      float above = before != null && (group || before instanceof Group) ? apart - gap : 0;
      if (group && before != null && ((Group) entry).isDivided()) {
        form.child(Divider.divider(t).margin(above, 0, 0, 0));
      }
      El el =
          group ? group(t, labels, column, gap, (Group) entry) : entry(t, labels, column, entry);
      if (above > 0) {
        el.style().marginTop = Len.px(above);
      }
      form.child(el);
      before = entry;
    }
    return form;
  }

  /**
   * The width of the label column of a form of these entries with {@link Labels#BESIDE}: of the
   * widest label of a field or of a row's first field, in any group, with its marker.
   */
  public static float labelWidth(Theme t, Entry... entries) {
    return (float) Math.ceil(column(t, present(entries, true)));
  }

  private static float column(Theme t, List<Entry> entries) {
    float column = 0;
    for (Entry entry : entries) {
      column = Math.max(
          column,
          switch (entry) {
            case Field f -> width(head(t, f));
            case Row r -> width(head(t, r.fields().getFirst()));
            case Group g -> column(t, g.entries());
          });
    }
    return column;
  }

  /** The entries without nulls, rows of one field as that field, and empty rows and groups. */
  private static List<Entry> present(Entry[] entries, boolean groups) {
    return present(Arrays.asList(entries), groups);
  }

  private static List<Entry> present(List<Entry> entries, boolean groups) {
    List<Entry> out = new ArrayList<>();
    for (Entry entry : entries) {
      switch (entry) {
        case null -> {}
        case Field f -> out.add(f);
        case Row r -> {
          List<Field> fields = r.fields().stream().filter(Objects::nonNull).toList();
          if (fields.size() == 1) {
            out.add(fields.getFirst());
          } else if (!fields.isEmpty()) {
            out.add(new Row(fields));
          }
        }
        case Group g -> {
          if (!groups) {
            throw new IllegalArgumentException(
                "The group \"" + g.title() + "\" is inside a group; a group holds fields and rows");
          }
          List<Entry> inside = present(g.entries(), false);
          if (!inside.isEmpty()) {
            out.add(new Group(g.title(), g.description(), g.isDivided(), g.level(), inside));
          }
        }
      }
    }
    return out;
  }

  /** A group: its heading and description, then its entries. */
  private static El group(Theme t, Labels labels, float column, float gap, Group group) {
    El head = col()
        .gap(t.space().xs())
        .child(label(t, Label.Variant.STRONG, group.title()).role("heading").level(group.level()));
    if (group.description() != null) {
      head.child(label(t, Label.Variant.MUTED, group.description()).wrapText());
    }
    El fields = col().gap(gap);
    for (Entry entry : group.entries()) {
      fields.child(entry(t, labels, column, entry));
    }
    return col().role("group").label(group.title()).gap(t.space().m()).children(head, fields);
  }

  /** A field or a row of fields. */
  private static El entry(Theme t, Labels labels, float column, Entry entry) {
    if (entry instanceof Row row) {
      return row(t, labels, column, row);
    }
    Field field = (Field) entry;
    El head = head(t, field);
    El body = body(t, field);
    if (labels == Labels.ABOVE) {
      return col().gap(t.space().xs()).children(head, body);
    }
    body.grow(1).style().minWidth = Len.ZERO;
    // As high as a control, so that the label lines up with the field's first line.
    return El.row()
        .gap(t.space().m())
        .children(head.h(t.size().control()).w(column).shrink(0), body);
  }

  /**
   * Fields side by side in a row that wraps where the form is too narrow, each as wide as its
   * control and its help and error below it as wide; beside, the first field's label in the
   * column and each further one's before its control.
   */
  private static El row(Theme t, Labels labels, float column, Row row) {
    boolean beside = labels == Labels.BESIDE;
    El line = El.row().wrap().align(Style.Align.START);
    line.style().columnGap = Len.px(t.space().m());
    line.style().rowGap = Len.px(beside ? t.space().m() : t.space().l());
    for (int i = 0; i < row.fields().size(); i++) {
      Field field = row.fields().get(i);
      El box = body(t, field);
      if (box.children().size() > 1) {
        // What is said below takes no width of its own: it wraps at the control's.
        El below = box.children().get(1).w(0);
        below.style().minWidth = Len.percent(1);
      }
      El item = box;
      if (!beside) {
        item = col().gap(t.space().xs()).children(head(t, field), box);
      } else if (i > 0) {
        item = El.row()
            .gap(t.space().m())
            .align(Style.Align.START)
            .children(head(t, field).h(t.size().control()).shrink(0), box);
      }
      if (field.grows()) {
        field.control().style().minWidth = Len.percent(1);
        box.grow(1);
        item.grow(1);
      } else {
        item.shrink(0);
      }
      line.child(item);
    }
    if (!beside) {
      return line;
    }
    line.grow(1).style().minWidth = Len.ZERO;
    El head = head(t, row.fields().getFirst()).h(t.size().control()).w(column).shrink(0);
    return El.row().gap(t.space().m()).children(head, line);
  }

  /**
   * A field's control with its help or error below it; names the control after the label and
   * marks it invalid while there is an error.
   */
  private static El body(Theme t, Field field) {
    El target = target(field.control());
    named(field.control(), field.label());
    // The app's error, else the message of a typed field's text ({@link ValueField}).
    String error = field.error() != null || target == null ? field.error() : target.errorMessage();
    if (error != null && target != null && target.isEditable()) {
      TextField.invalid(t, target).errorMessage(error); // error="..." in a dump, as a draft's
    } else if (error != null && target != null) {
      target.invalid(true).errorMessage(error);
    }
    El body = col().gap(t.space().xs()).child(field.control().alignSelf(Style.Align.START));
    String below = error != null ? error : field.help();
    if (below != null) {
      body.child(label(t, Label.Variant.MUTED, below)
          .wrapText()
          .color(error != null ? t.status().danger() : t.text().muted()));
    }
    return body;
  }

  /**
   * Buttons at the end of a form, a card or a page: a row at the right, {@link Theme.Spacing#m}
   * apart, the main action usually last, as a dialog has them.
   */
  public static El buttons(Theme t, El... buttons) {
    return El.row()
        .gap(t.space().m())
        .justify(Style.Content.END)
        .align(Style.Align.CENTER)
        .children(buttons);
  }

  /** The label of a field, with the marker of a required one. */
  private static El head(Theme t, Field field) {
    El head = El.row().gap(t.space().xs()).align(Style.Align.CENTER).child(label(t, field.label()));
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
