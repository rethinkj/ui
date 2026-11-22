package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.Clipboard;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.render.Scene;
import java.util.List;
import java.util.Objects;

/**
 * Names and values in two columns, the details of a table, a column or a connection: the names
 * muted, the values in the text's color, in the mono font, or as an {@link El} of the view's own
 * (such as a {@link Badge}, a link, or a status dot with text). The names' column is as wide as
 * the widest name, measured as {@link Form} measures its labels, up to {@link Theme.Sizes#field},
 * and a longer name ends in an ellipsis. Each text value is a read-only field that a press and a
 * drag select and Control+C copies, which Tab passes by; a value too long for its column wraps
 * onto up to three lines, the row growing with it and the name staying beside the first line, and
 * a longer one ends in an ellipsis without the focus and shows whole in a tooltip. An element
 * value ({@link #property(String, El)}, {@link #property(String, El, String)}) lines up with text
 * values in the same column: short elements sit centered in a one-line row, taller ones grow the
 * row downwards with the name beside the first line. With a clipboard, a copy button shows at the
 * end of each row that has a text to copy under the pointer; an element property without text has
 * no copy button. The rows' text lines up with what is above and below them; their hover fill
 * reaches a little past it.
 *
 * <pre>{@code
 * Properties.properties(List.of(
 *     Properties.property("Rows", "1,000,000"),
 *     Properties.property("Key", Badge.badge(Badge.Kind.ACCENT, "PK")),
 *     Properties.property("Created", "2024-03-01 09:12").mono()), app.clipboard())
 * }</pre>
 *
 * <p>In a tree dump it is {@code role=list}, each row {@code role=listitem} with the name as its
 * label and, when the property has a text value (a string property or an element property given
 * copy text), that text as its {@code value}, so that a script reads it: {@code
 * value="1,000,000"}. {@link #groups} puts several under headings ({@code role=heading level=3}).
 */
public final class Properties {

  private static final int LINES = 3; // a value's lines before the ellipsis

  private Properties() {}

  /**
   * A name and its value: a text in the body's font unless {@link #mono}, or an {@link El} in
   * place of it, optionally with the text its copy button copies and a tree dump shows as its
   * {@code value}.
   *
   * @param name the property's name, shown muted in the left column
   * @param value the text value, or for an element property the text to copy and report in a tree
   *     dump; may be null only when {@code element} is non-null
   * @param isMono whether a text value uses the mono font
   * @param element an element shown in place of a text field, such as a {@link Badge}; null for a
   *     text value
   */
  public record Property(String name, String value, boolean isMono, El element) {

    public Property {
      Objects.requireNonNull(name, "name");
      if (element == null) {
        Objects.requireNonNull(value, "value");
      }
    }

    /** A text property without a custom element. */
    public Property(String name, String value, boolean isMono) {
      this(name, value, isMono, null);
    }

    /** The value in the mono font, for identifiers, types and values that line up. */
    public Property mono() {
      return new Property(name, value, true, element);
    }
  }

  /** Properties under a heading. */
  public record Group(String title, List<Property> properties) {

    public Group {
      properties = List.copyOf(properties);
    }
  }

  public static Property property(String name, String value) {
    return new Property(name, value, false, null);
  }

  /**
   * A property whose value is an element, such as a {@link Badge}, a link, or a status indicator;
   * the row has no copy button and no {@code value} in a tree dump.
   */
  public static Property property(String name, El value) {
    return property(name, value, null);
  }

  /**
   * A property whose value is an element, with the text its copy button copies and a tree dump
   * shows as the row's {@code value}.
   *
   * @param text the text to copy and report as {@code value}; null for no copy button or tree value
   */
  public static Property property(String name, El value, String text) {
    return new Property(name, text, false, Objects.requireNonNull(value, "value"));
  }

  public static Group group(String title, List<Property> properties) {
    return new Group(title, properties);
  }

  /**
   * @param clipboard where the copy buttons copy to; null for none
   */
  public static El properties(List<Property> properties, Clipboard clipboard) {
    return properties(properties, clipboard, null);
  }

  /**
   * @param clipboard where the copy buttons copy to; null for none
   * @param copied the app's state of the copy buttons, which confirm a copy for a moment; null
   *     for no confirmation
   */
  public static El properties(
      List<Property> properties, Clipboard clipboard, Button.Copied copied) {
    Theme theme = Theme.CURRENT.get();
    return list(theme, properties, column(theme, properties), clipboard, copied);
  }

  /**
   * Groups of properties, each under its title, whose names share one column's width.
   *
   * @param clipboard where the copy buttons copy to; null for none
   */
  public static El groups(List<Group> groups, Clipboard clipboard) {
    return groups(groups, clipboard, null);
  }

  /**
   * Groups of properties whose copy buttons confirm a copy for a moment.
   *
   * @param copied the app's state of the copy buttons; null for no confirmation
   */
  public static El groups(List<Group> groups, Clipboard clipboard, Button.Copied copied) {
    Theme theme = Theme.CURRENT.get();
    float column = 0;
    for (Group group : groups) {
      column = Math.max(column, column(theme, group.properties()));
    }
    El out = col().gap(theme.space().l());
    for (Group group : groups) {
      out.child(col()
          .gap(theme.space().xs())
          .children(
              label(Label.Variant.STRONG, group.title()).role("heading").level(3),
              list(theme, group.properties(), column, clipboard, copied).label(group.title())));
    }
    return out;
  }

  private static El list(
      Theme theme,
      List<Property> properties,
      float column,
      Clipboard clipboard,
      Button.Copied copied) {
    // The rows' padding, taken back by the margin, so that their text lines up with the heading.
    El list = col().role("list").margin(0, -theme.space().s(), 0, -theme.space().s());
    for (Property p : properties) {
      list.child(entry(theme, p, column, clipboard, copied));
    }
    if (copied != null) {
      // Ends a confirmation also when its button's label changed with the texts meanwhile.
      list.overlay(Button.ending(theme, copied));
    }
    return list;
  }

  private static El entry(
      Theme theme, Property p, float column, Clipboard clipboard, Button.Copied copied) {
    // The name centered beside the value's first line, as in a row one line high.
    El name = row()
        .h(theme.size().control())
        .shrink(0)
        .align(Style.Align.CENTER)
        .child(label(Label.Variant.MUTED, p.name()).ellipsis().w(column).minSize(0, 0));
    El value;
    if (p.element() != null) {
      // Short elements center vertically against the name; taller ones grow the row downwards.
      // The clip keeps a wide element off the copy button; the padding, taken back by the margin,
      // leaves room for its focus ring.
      value = row()
          .grow(1)
          .minSize(0, theme.size().control() + 2 * Scene.RING)
          .padding(Scene.RING)
          .margin(-Scene.RING)
          .align(Style.Align.CENTER)
          .clip()
          .child(p.element());
    } else {
      Theme.TextStyle style = p.isMono() ? theme.type().mono() : theme.type().body();
      // The padding leaves a line's room in a control's height: one line sits where a field of that
      // height centers it, more lines grow the field downwards, the first beside the name.
      value = El.text(p.value())
          .editable(v -> {})
          .readOnly()
          .skipTab()
          .wrapText()
          .maxLines(LINES)
          .ellipsis()
          .font(style.font().get())
          .fontSize(style.size())
          .color(theme.text().primary())
          .selectionColor(theme.accent().selection())
          .grow(1)
          .minSize(0, theme.size().control())
          .padding(theme.space().s(), 0);
      Tooltip.whenShortened(value, p.value());
    }
    El entry = row()
        .role("listitem")
        .label(p.name())
        .padding(0, theme.space().s())
        .gap(theme.space().m())
        .align(Style.Align.START)
        .radius(theme.radius().small())
        .children(name, value);
    if (p.value() != null) {
      entry.value(p.value());
    }
    if (clipboard != null && p.value() != null) {
      String label = Texts.CURRENT.get().copy(p.name());
      boolean shown = copied != null && copied.shows(label, p.value()); // seen while it confirms
      El copy = Button.copy(clipboard, p.value(), label, copied).key("copy").opacity(shown ? 1 : 0);
      entry
          .child(copy)
          .whenHovered(e -> {
            e.fill(theme.surface().hover());
            copy.opacity(1);
          })
          .whenFocusWithin(e -> copy.opacity(1));
    }
    return entry;
  }

  /** The names' column: the widest name, up to a text field's width. */
  private static float column(Theme theme, List<Property> properties) {
    float widest = 0;
    for (Property p : properties) {
      widest = Math.max(widest, TextSizing.natural(label(Label.Variant.MUTED, p.name())));
    }
    // A pixel to spare: text shaped at another scale may come out a fraction wider.
    return (float) Math.ceil(Math.min(widest, theme.size().field())) + 1;
  }
}
