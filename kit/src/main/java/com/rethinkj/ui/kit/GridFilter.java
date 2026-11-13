package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.TextField.textField;

import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Side;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.DataGrid.Build;
import com.rethinkj.ui.kit.DataGrid.Column;
import com.rethinkj.ui.kit.DataGrid.Filter;
import com.rethinkj.ui.kit.DataGrid.Filter.Kind;
import com.rethinkj.ui.kit.DataGrid.Selection;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The filters of a data grid's columns ({@link DataGrid.Options#filters}): the funnel in a
 * header cell, the popover that sets a column's filter by the column's type, and the bar of
 * badges that shows them ({@link DataGrid#filterBar}). What the popover holds is the {@link
 * Selection}'s, which the app keeps.
 */
final class GridFilter {

  private GridFilter() {}

  /** The column's filter among the grid's, or null. */
  private static Filter filterOf(Build b, int column) {
    for (Filter f : b.o.filters) {
      if (f.column() == column) {
        return f;
      }
    }
    return null;
  }

  /** Whether the column has a filter. */
  static boolean set(Build b, int column) {
    return filterOf(b, column) != null;
  }

  /** The kinds of filter a column offers, the first the one it starts with. */
  static List<Kind> kinds(Column c) {
    if (!values(c).isEmpty()) {
      return List.of(Kind.ONE_OF, Kind.IS_NULL, Kind.NOT_NULL);
    }
    return switch (c.type()) {
      case INTEGER, DECIMAL, DATE, TIME, DATE_TIME, INTERVAL ->
        List.of(Kind.BETWEEN, Kind.EQUALS, Kind.IS_NULL, Kind.NOT_NULL);
      default ->
        List.of(
            Kind.CONTAINS,
            Kind.STARTS_WITH,
            Kind.EQUALS,
            Kind.MATCHES,
            Kind.IS_NULL,
            Kind.NOT_NULL);
    };
  }

  /** The values to check: an enumeration's choices, or true and false. */
  private static List<String> values(Column c) {
    if (!c.editing().choices().isEmpty()) {
      return c.editing().choices();
    }
    return c.type() == DataGrid.DataType.BOOLEAN ? List.of("true", "false") : List.of();
  }

  private static String name(Kind kind) {
    return switch (kind) {
      case CONTAINS -> "Contains";
      case STARTS_WITH -> "Starts with";
      case EQUALS -> "Equals";
      case MATCHES -> "Matches regular expression";
      case BETWEEN -> "Between";
      case ONE_OF -> "One of";
      case IS_NULL -> "Is NULL";
      case NOT_NULL -> "Is not NULL";
    };
  }

  /**
   * The funnel at the end of a header cell: under the pointer, or in the accent's edge where the
   * column has a filter, which its value says in a dump; a press opens the popover below it.
   */
  static El funnel(Build b, int column) {
    Theme t = b.t;
    Selection sel = b.sel;
    Column c = b.columns.get(column);
    Filter set = filterOf(b, column);
    boolean open = sel.filterColumn == column && sel.filtering.isOpen();
    El icon = Icon.icon(t, Symbol.FILTER, Icon.Variant.MUTED);
    if (set != null) {
      icon.color(t.accent().edge());
    }
    El funnel = row()
        .key("filter-" + column)
        .role("button")
        .label("Filter " + c.title())
        .size(t.size().icon(), t.size().icon())
        .shrink(0)
        .center()
        .radius(t.radius().small())
        .opacity(set != null || open ? 1 : 0)
        .cursor(Cursor.POINTER)
        .keepFocus()
        .onPress(() -> open(b, column))
        .whenHovered(e -> e.fill(t.surface().hover()))
        .child(icon);
    if (set != null) {
      funnel.value(set.describe(c.title()));
    }
    if (open) {
      funnel.overlay(panel(b, column)
          .side(
              Side.BOTTOM,
              c.align() == DataGrid.Align.RIGHT ? Style.Align.END : Style.Align.START,
              t.space().s()));
    }
    return funnel;
  }

  /** Opens the popover of a column's filter with what the filter keeps now. */
  static void open(Build b, int column) {
    Selection sel = b.sel;
    Column c = b.columns.get(column);
    Filter set = filterOf(b, column);
    sel.filterColumn = column;
    sel.filtering.open();
    sel.filterKinds.close();
    sel.filterKind = set != null ? set.kind() : kinds(c).getFirst();
    sel.filterValue = set != null ? set.value() : "";
    sel.filterTo = set != null ? set.to() : "";
    sel.filterValues.clear();
    sel.filterValues.addAll(set != null && set.kind() == Kind.ONE_OF ? set.values() : values(c));
  }

  private static void close(Selection sel) {
    sel.filtering.close();
    sel.filterKinds.close();
    sel.filterColumn = -1;
  }

  /** The filter the popover holds, null for none, or the message of what is wrong with it. */
  private static Object made(Selection sel, Column c, int column) {
    Kind kind = sel.filterKind;
    if (kind == Kind.EQUALS && ordered(c) && !sel.filterValue.isBlank()) {
      String error = GridEdit.check(c, sel.filterValue.strip()); // equality needs a value
      if (error != null) {
        return error;
      }
    }
    return switch (kind) {
      case CONTAINS, STARTS_WITH, EQUALS ->
        sel.filterValue.isEmpty() ? null : Filter.of(column, kind, sel.filterValue);
      case MATCHES -> {
        try {
          Pattern.compile(sel.filterValue);
          yield sel.filterValue.isEmpty() ? null : Filter.of(column, kind, sel.filterValue);
        } catch (PatternSyntaxException e) {
          yield "Not a regular expression";
        }
      }
      case BETWEEN ->
        sel.filterValue.isBlank() && sel.filterTo.isBlank()
            ? null
            : Filter.between(column, sel.filterValue.strip(), sel.filterTo.strip());
      case ONE_OF -> {
        List<String> kept = new ArrayList<>();
        for (String value : values(c)) {
          if (sel.filterValues.contains(value)) {
            kept.add(value);
          }
        }
        yield kept.size() == values(c).size() ? null : Filter.oneOf(column, kept);
      }
      case IS_NULL, NOT_NULL -> Filter.of(column, kind, "");
    };
  }

  /** Whether the column's filters compare values, as numbers and dates do. */
  private static boolean ordered(Column c) {
    return kinds(c).getFirst() == Kind.BETWEEN;
  }

  /** Whether the popover holds equality with nothing to be equal to, which it does not apply. */
  private static boolean incomplete(Selection sel, Column c) {
    return sel.filterKind == Kind.EQUALS
        && (ordered(c) ? sel.filterValue.isBlank() : sel.filterValue.isEmpty());
  }

  /** Hands the app the filters with this column's as the popover holds it, and closes it. */
  private static void apply(Build b, int column, Filter filter) {
    List<Filter> next = new ArrayList<>();
    for (Filter f : b.o.filters) {
      if (f.column() != column) {
        next.add(f);
      }
    }
    if (filter != null) {
      next.add(filter);
    }
    close(b.sel);
    b.o.onFilter.accept(List.copyOf(next));
  }

  /**
   * The popover: how the filter matches, then a field, the two ends of a range or the values to
   * keep, and Clear and Apply; Enter applies, Escape and a press outside close it unchanged.
   */
  private static El panel(Build b, int column) {
    Theme t = b.t;
    Selection sel = b.sel;
    Column c = b.columns.get(column);
    List<Kind> kinds = kinds(c);
    Object made = made(sel, c, column);
    String error = made instanceof String message ? message : null;
    Filter filter = made instanceof Filter f ? f : null;
    float width = t.size().field();
    El content = col()
        .gap(t.space().s())
        .child(Select.select(
                t,
                kinds.stream().map(GridFilter::name).toList(),
                kinds.indexOf(sel.filterKind),
                sel.filterKinds,
                i -> sel.filterKind = kinds.get(i))
            .key("kind")
            .w(width));
    switch (sel.filterKind) {
      case CONTAINS, STARTS_WITH, EQUALS, MATCHES -> {
        El field = textField(t, sel.filterValue, "Value", v -> sel.filterValue = v)
            .key("value")
            .w(width)
            .autofocus();
        content.child(error == null ? field : TextField.invalid(t, field));
      }
      case BETWEEN ->
        content.child(row()
            .gap(t.space().s())
            .children(
                textField(t, sel.filterValue, "From", v -> sel.filterValue = v)
                    .key("from")
                    .w((width - t.space().s()) / 2)
                    .autofocus(),
                textField(t, sel.filterTo, "To", v -> sel.filterTo = v)
                    .key("to")
                    .w((width - t.space().s()) / 2)));
      case ONE_OF -> {
        El list = col().key("values").gap(t.space().xs());
        for (String value : values(c)) {
          list.child(Checkbox.checkbox(t, sel.filterValues.contains(value), value, () -> {
                if (!sel.filterValues.remove(value)) {
                  sel.filterValues.add(value);
                }
              })
              .key("value-" + value));
        }
        content.child(list);
      }
      default -> {}
    }
    if (error != null) {
      content.child(Label.label(t, error)
          .key("error")
          .role("alert")
          .label(error)
          .color(t.status().danger()));
    }
    Runnable apply = error == null && !incomplete(sel, c) ? () -> apply(b, column, filter) : null;
    content.child(row()
        .gap(t.space().s())
        .justify(Style.Content.END)
        .children(
            button(
                t,
                Button.Variant.SECONDARY,
                "Clear",
                filterOf(b, column) == null ? null : () -> apply(b, column, null)),
            button(t, Button.Variant.PRIMARY, "Apply", apply)));
    El panel = Popover.panel(
        t,
        sel.filtering,
        "Filter " + c.title(),
        col()
            .gap(t.space().m())
            .children(
                Label.label(t, Label.Variant.STRONG, "Filter " + c.title())
                    .role("heading")
                    .level(3),
                content));
    Predicate<Key> own = panel.onKey();
    return panel.key("filter").onDismiss(() -> close(sel)).onKey(k -> {
      if (k.modifiers() == 0 && k.name().equals("Enter")) {
        if (apply != null) {
          apply.run();
        }
        return true;
      }
      if (k.modifiers() == 0 && k.name().equals("Escape")) {
        close(sel);
        return true;
      }
      return own.test(k);
    });
  }

  /** {@link DataGrid#filterBar}. */
  static El bar(
      Theme t, List<Column> columns, List<Filter> filters, Consumer<List<Filter>> onFilter) {
    if (filters.isEmpty()) {
      return null;
    }
    El bar = row()
        .key("filters")
        .role("list")
        .label("Filters")
        .wrap()
        .gap(t.space().xs())
        .align(Style.Align.CENTER);
    for (Filter f : filters) {
      String title = f.column() < columns.size()
          ? columns.get(f.column()).title()
          : "column " + (f.column() + 1);
      bar.child(Badge.badge(
              t,
              f.describe(title),
              Badge.options().toolbar().icon(Symbol.FILTER).onRemove(() -> {
                List<Filter> left = new ArrayList<>(filters);
                left.remove(f);
                onFilter.accept(List.copyOf(left));
              }))
          .role("listitem"));
    }
    if (filters.size() > 1) {
      bar.child(button(t, Button.Variant.GHOST, "Clear all", () -> onFilter.accept(List.of()))
          .key("clear-filters"));
    }
    return bar;
  }
}
