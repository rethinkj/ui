package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.Clipboard;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Measure;
import com.rethinkj.ui.Style;
import java.util.List;
import java.util.Objects;

/**
 * Names and values in two columns, the details of a table, a column or a connection: the names
 * muted, the values in the text's colour or in the mono font. The names' column is as wide as the
 * widest name, measured as {@link Form} measures its labels, up to {@link Theme.Sizes#field}, and
 * a longer name ends in an ellipsis. Each value is a read-only field that a press and a drag
 * select and Control+C copies, which Tab passes by; without the focus a value too long for its
 * column ends in an ellipsis, and a long one shows whole in a tooltip. With a clipboard, a copy
 * button shows at the end of the row under the pointer.
 *
 * <pre>{@code
 * Properties.properties(t, List.of(
 *     Properties.property("Rows", "1,000,000"),
 *     Properties.property("Owner", "shop_admin"),
 *     Properties.property("Created", "2024-03-01 09:12").mono()), app.clipboard())
 * }</pre>
 *
 * <p>In a tree dump it is {@code role=list}, each row {@code role=listitem} with the name as its
 * label and the value as its {@code value}, so that a script reads it: {@code value="1,000,000"}.
 * {@link #groups} puts several under headings ({@code role=heading level=3}).
 */
public final class Properties {

  private Properties() {}

  /** A name and its value, in the body's font unless {@link #mono}. */
  public record Property(String name, String value, boolean isMono) {

    public Property {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(value, "value");
    }

    /** The value in the mono font, for identifiers, types and values that line up. */
    public Property mono() {
      return new Property(name, value, true);
    }
  }

  /** Properties under a heading. */
  public record Group(String title, List<Property> properties) {

    public Group {
      properties = List.copyOf(properties);
    }
  }

  public static Property property(String name, String value) {
    return new Property(name, value, false);
  }

  public static Group group(String title, List<Property> properties) {
    return new Group(title, properties);
  }

  /**
   * @param clipboard where the copy buttons copy to; null for none
   */
  public static El properties(Theme t, List<Property> properties, Clipboard clipboard) {
    return properties(t, properties, clipboard, null);
  }

  /**
   * @param clipboard where the copy buttons copy to; null for none
   * @param copied the app's state of the copy buttons, which confirm a copy for a moment; null
   *     for no confirmation
   */
  public static El properties(Theme t, List<Property> properties, Clipboard clipboard,
      Button.Copied copied) {
    return list(t, properties, column(t, properties), clipboard, copied);
  }

  /**
   * Groups of properties, each under its title, whose names share one column's width.
   *
   * @param clipboard where the copy buttons copy to; null for none
   */
  public static El groups(Theme t, List<Group> groups, Clipboard clipboard) {
    return groups(t, groups, clipboard, null);
  }

  /**
   * Groups of properties whose copy buttons confirm a copy for a moment.
   *
   * @param copied the app's state of the copy buttons; null for no confirmation
   */
  public static El groups(Theme t, List<Group> groups, Clipboard clipboard,
      Button.Copied copied) {
    float column = 0;
    for (Group group : groups) {
      column = Math.max(column, column(t, group.properties()));
    }
    El out = col().gap(t.space().l());
    for (Group group : groups) {
      out.child(col().gap(t.space().xs()).children(
          label(t, Label.Variant.STRONG, group.title()).role("heading").level(3)
              .padding(0, t.space().s()),
          list(t, group.properties(), column, clipboard, copied).label(group.title())));
    }
    return out;
  }

  private static El list(Theme t, List<Property> properties, float column, Clipboard clipboard,
      Button.Copied copied) {
    El list = col().role("list");
    for (Property p : properties) {
      list.child(entry(t, p, column, clipboard, copied));
    }
    return list;
  }

  private static El entry(Theme t, Property p, float column, Clipboard clipboard,
      Button.Copied copied) {
    Theme.TextStyle style = p.isMono() ? t.type().mono() : t.type().body();
    El name = label(t, Label.Variant.MUTED, p.name()).ellipsis().w(column).shrink(0)
        .minSize(0, 0);
    El value = El.text(p.value()).editable(v -> {}).readOnly().skipTab().ellipsis()
        .font(style.font().get()).fontSize(style.size()).color(t.text().primary())
        .selectionColor(t.accent().selection())
        .grow(1).minSize(0, 0).h(t.size().control());
    // A field measures 0 wide: the same text alone says how long the value is.
    if (width(El.text(p.value()).font(style.font().get()).fontSize(style.size()))
        > t.size().field()) {
      Tooltip.tooltip(t, value, p.value());
    }
    El entry = row().role("listitem").label(p.name()).value(p.value())
        .h(t.size().control()).padding(0, t.space().s()).gap(t.space().m())
        .align(Style.Align.CENTER).radius(t.radius().small())
        .children(name, value);
    if (clipboard != null) {
      boolean shown = copied != null && copied.shows("Copy " + p.name(), p.value()); // seen while it confirms
      El copy = Button.copy(t, clipboard, p.value(), "Copy " + p.name(), copied).key("copy")
          .opacity(shown ? 1 : 0);
      entry.child(copy).whenHovered(e -> {
        e.fill(t.surface().hover());
        copy.opacity(1);
      }).whenFocusWithin(e -> copy.opacity(1));
    }
    return entry;
  }

  /** The names' column: the widest name, up to a text field's width. */
  private static float column(Theme t, List<Property> properties) {
    float widest = 0;
    for (Property p : properties) {
      widest = Math.max(widest, width(label(t, Label.Variant.MUTED, p.name())));
    }
    // A pixel to spare: text shaped at another scale may come out a fraction wider.
    return (float) Math.ceil(Math.min(widest, t.size().field())) + 1;
  }

  private static float width(El text) {
    return text.text().measure(Float.NaN, Float.NaN, Measure.MAX_CONTENT, Measure.MAX_CONTENT)
        .width();
  }
}
