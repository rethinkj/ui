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
import com.rethinkj.ui.kit.DataGrid.DataType;
import com.rethinkj.ui.kit.DataGrid.Filter;
import com.rethinkj.ui.kit.DataGrid.Filter.Between;
import com.rethinkj.ui.kit.DataGrid.Filter.Kind;
import com.rethinkj.ui.kit.DataGrid.Filter.NullCheck;
import com.rethinkj.ui.kit.DataGrid.Filter.OneOf;
import com.rethinkj.ui.kit.DataGrid.Filter.RegexMatch;
import com.rethinkj.ui.kit.DataGrid.Filter.Spec;
import com.rethinkj.ui.kit.DataGrid.Filter.TextMatch;
import com.rethinkj.ui.kit.DataGrid.Selection;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;
import java.util.regex.PatternSyntaxException;

/**
 * The filters of a data grid's columns ({@link DataGrid.Options#filters}): the funnel in a
 * header cell, the popover that sets a column's filter by the column's type, and the bar of
 * badges that shows them ({@link DataGrid#filterBar}). What the popover holds is the {@link
 * Selection}'s, which the app keeps.
 */
final class GridFilter {

  private GridFilter() {}

  /** What a {@link Selection} holds of the popover that sets a filter: its column, -1 for none. */
  static final class State {
    int column = -1;
    final Popover.State popover = new Popover.State();
    Kind kind;
    String value = "";
    String to = "";
    String search = "";
    final Set<String> values = new LinkedHashSet<>();
    final Menu.State kinds = new Menu.State();
    final DatePicker.State fromCalendar = new DatePicker.State();
    final DatePicker.State toCalendar = new DatePicker.State();

    /** Puts the popover on its column's new place, or closes it where its column is gone. */
    void onColumnsChanged(IntUnaryOperator to) {
      column = to.applyAsInt(column);
      if (column < 0) {
        popover.close();
        fromCalendar.close();
        toCalendar.close();
      }
    }
  }

  /** What the popover holds: nothing, which clears the column's filter, a filter, or an error. */
  private sealed interface Draft {}

  private record Empty() implements Draft {}

  private record Valid(Filter filter) implements Draft {}

  private record Invalid(String field, String message) implements Draft {}

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

  /**
   * The funnel at the end of a header cell: under the pointer, or in the accent's edge where the
   * column has a filter, which its value says in a dump; a press opens the popover below it.
   */
  static El funnel(Build b, int column) {
    Theme theme = b.theme;
    Selection sel = b.sel;
    Column c = b.columns.get(column);
    Filter set = filterOf(b, column);
    boolean open = sel.filter.column == column && sel.filter.popover.isOpen();
    El icon = Icon.icon(Symbol.FILTER, Icon.Variant.MUTED);
    if (set != null) {
      icon.color(theme.accent().edge());
    }
    El funnel = row()
        .key("filter-" + column)
        .role("button")
        .label(b.texts.filter(c.title()))
        .size(theme.size().icon(), theme.size().icon())
        .shrink(0)
        .center()
        .radius(theme.radius().small())
        .opacity(set != null || open ? 1 : 0)
        .cursor(Cursor.POINTER)
        .keepFocus()
        .onPress(() -> open(b, column))
        .whenHovered(e -> e.fill(theme.surface().hover()))
        .child(icon);
    if (set != null) {
      funnel.value(set.describe(c.title()));
    }
    if (open) {
      funnel.overlay(panel(b, column)
          .side(
              Side.BOTTOM,
              c.align() == DataGrid.Align.RIGHT ? Style.Align.END : Style.Align.START,
              theme.space().s()));
    }
    return funnel;
  }

  /** Opens the popover of a column's filter with what the filter keeps now. */
  static void open(Build b, int column) {
    Selection sel = b.sel;
    Column c = b.columns.get(column);
    Filter set = filterOf(b, column);
    sel.filter.column = column;
    sel.filter.popover.open();
    sel.filter.kinds.close();
    sel.filter.fromCalendar.close();
    sel.filter.toCalendar.close();
    Spec spec = set != null ? set.spec() : null;
    sel.filter.kind = spec != null ? kind(spec) : kinds(c).getFirst();
    sel.filter.value = switch (spec) {
      case TextMatch text -> text.value();
      case RegexMatch regex -> regex.expression();
      case Between range -> range.from();
      case null, default -> "";
    };
    sel.filter.to = spec instanceof Between range ? range.to() : "";
    sel.filter.search = "";
    sel.filter.values.clear();
    sel.filter.values.addAll(spec instanceof OneOf oneOf ? oneOf.values() : values(c));
  }

  /** The kind of filter that keeps what {@code spec} does, as the popover offers it. */
  private static Kind kind(Spec spec) {
    return switch (spec) {
      case TextMatch text -> text.kind();
      case RegexMatch _ -> Kind.MATCHES;
      case Between _ -> Kind.BETWEEN;
      case OneOf _ -> Kind.ONE_OF;
      case NullCheck(boolean isNull) -> isNull ? Kind.IS_NULL : Kind.NOT_NULL;
    };
  }

  private static void close(Selection sel) {
    sel.filter.popover.close();
    sel.filter.kinds.close();
    sel.filter.fromCalendar.close();
    sel.filter.toCalendar.close();
    sel.filter.column = -1;
  }

  /**
   * What the popover holds: a regular expression is compiled here, once, and an invalid expression,
   * an invalid value on an ordered column, or a range whose start lies above its end is {@link
   * Invalid}, so the popover says so and applies nothing.
   */
  private static Draft made(Texts texts, Selection sel, Column c, int column) {
    Kind kind = sel.filter.kind;
    String value = sel.filter.value;
    if (kind == Kind.EQUALS && ordered(c) && !value.isBlank()) {
      String error = GridEdit.check(c, value.strip()); // equality needs a value
      if (error != null) {
        return new Invalid("value", error);
      }
    }
    if (kind == Kind.BETWEEN && ordered(c)) {
      String from = value.strip();
      String to = sel.filter.to.strip();
      String fromError = from.isEmpty() ? null : GridEdit.check(c, from);
      String toError = to.isEmpty() ? null : GridEdit.check(c, to);
      if (fromError != null || toError != null) { // both wrong: both marked, From's message
        String field = toError == null ? "from" : fromError == null ? "to" : null;
        return new Invalid(field, fromError != null ? fromError : toError);
      }
      if (!from.isEmpty() && !to.isEmpty() && above(c, column, from, to)) {
        return new Invalid(null, texts.fromAboveTo());
      }
    }
    Spec spec;
    try {
      spec = switch (kind) {
        case CONTAINS, STARTS_WITH, EQUALS -> value.isEmpty() ? null : new TextMatch(kind, value);
        case MATCHES -> value.isEmpty() ? null : new RegexMatch(value);
        case BETWEEN ->
          value.isBlank() && sel.filter.to.isBlank()
              ? null
              : new Between(value.strip(), sel.filter.to.strip());
        case ONE_OF -> {
          List<String> kept = new ArrayList<>();
          for (String choice : values(c)) {
            if (sel.filter.values.contains(choice)) {
              kept.add(choice);
            }
          }
          yield kept.size() == values(c).size() ? null : new OneOf(kept);
        }
        case IS_NULL, NOT_NULL -> new NullCheck(kind == Kind.IS_NULL);
      };
    } catch (PatternSyntaxException _) {
      return new Invalid("value", texts.notARegularExpression());
    }
    return spec == null ? new Empty() : new Valid(new Filter(column, spec));
  }

  /**
   * Whether {@code from} lies above {@code to} as the column's values are ordered, as SQL
   * compares them: typed cells by their format's values, numbers and dates by theirs, and times by
   * the time, which their text does not tell where offsets differ. An interval's order is the
   * app's to know, so a range of intervals is never refused.
   */
  static boolean above(Column c, int column, String from, String to) {
    if (c.cells() != null) {
      return above(c.cells().format(), column, from, to);
    }
    return switch (c.type()) {
      case INTEGER -> above(Format.BIG_INTEGER, column, from, to);
      case DECIMAL -> above(Format.DECIMAL, column, from, to);
      case DATE -> above(Format.DATE, column, from, to);
      case TIME, DATE_TIME -> timeAbove(c.type() == DataType.TIME, from, to);
      default -> false;
    };
  }

  private static boolean timeAbove(boolean time, String from, String to) {
    Format<TemporalAccessor> any = time ? Format.ANY_TIME : Format.ANY_DATE_TIME;
    Format.Parsed<TemporalAccessor> low = any.parseText(from);
    Format.Parsed<TemporalAccessor> high = any.parseText(to);
    if (!low.valid() || !high.valid()) {
      return false;
    }
    boolean offset = low.value().isSupported(ChronoField.OFFSET_SECONDS);
    if (offset != high.value().isSupported(ChronoField.OFFSET_SECONDS)) {
      return false; // a local time against one with an offset: no order to go by
    }
    try {
      return offset
          ? offsetAbove(time, low.value(), high.value())
          : localAbove(time, low.value(), high.value());
    } catch (DateTimeException _) {
      return false; // an offset java.time does not take, such as +19:00: no order to go by
    }
  }

  private static boolean offsetAbove(boolean time, TemporalAccessor low, TemporalAccessor high) {
    return time
        ? OffsetTime.from(low).isAfter(OffsetTime.from(high))
        : OffsetDateTime.from(low).isAfter(OffsetDateTime.from(high));
  }

  private static boolean localAbove(boolean time, TemporalAccessor low, TemporalAccessor high) {
    return time
        ? LocalTime.from(low).isAfter(LocalTime.from(high))
        : LocalDateTime.from(low).isAfter(LocalDateTime.from(high));
  }

  private static <V> boolean above(Format<V> format, int column, String from, String to) {
    Format.Parsed<V> low = format.parseText(from);
    Format.Parsed<V> high = format.parseText(to);
    if (!low.valid() || !high.valid() || low.value() == null || high.value() == null) {
      return false;
    }
    // the same instant at two offsets is no empty range in SQL, though compareTo orders them
    if (low.value() instanceof OffsetDateTime a && high.value() instanceof OffsetDateTime b) {
      return a.isAfter(b);
    }
    if (low.value() instanceof OffsetTime a && high.value() instanceof OffsetTime b) {
      return a.isAfter(b);
    }
    return !new Filter(column, new Between("", to)).matches(low.value(), format);
  }

  /** Marks the field invalid when {@code draft} reports an error on its key or on all fields. */
  private static El field(El field, Draft draft, String key) {
    return draft instanceof Invalid invalid
            && (invalid.field() == null || invalid.field().equals(key))
        ? TextField.invalid(field)
        : field;
  }

  /** Whether the column's filters compare values, as numbers and dates do. */
  private static boolean ordered(Column c) {
    return kinds(c).getFirst() == Kind.BETWEEN;
  }

  /** Whether the column takes a date calendar in its filter fields. */
  private static boolean dateColumn(Column c) {
    return c.type() == DataType.DATE || c.type() == DataType.DATE_TIME;
  }

  /** A date or date-time filter field with an embedded calendar button and popover. */
  private static El dateField(
      Theme theme,
      Texts texts,
      Column column,
      String value,
      String placeholder,
      String key,
      float width,
      boolean autofocus,
      Draft draft,
      DatePicker.State calendar,
      DatePicker.State otherCalendar,
      Menu.State kinds,
      Consumer<String> onChange) {
    El input = field(textField(value, placeholder, onChange).key(key).wFull(), draft, key);
    if (autofocus) {
      input.autofocus();
    }
    Runnable open = () -> {
      otherCalendar.close();
      kinds.close();
      calendar.open();
    };
    Runnable toggle = () -> {
      if (calendar.isOpen()) {
        calendar.close();
      } else {
        open.run();
      }
    };
    El box = DatePicker.embedField(
            theme, texts, input, calendar, column.type() == DataType.DATE_TIME, true, open, toggle)
        .w(width);
    if (!calendar.isOpen()) {
      return box;
    }
    return box.overlay(DatePicker.calendarPanel(
        theme,
        texts,
        DatePicker.CLOCK.get(),
        calendar,
        DatePicker.parseDatePrefix(value),
        day -> {
          onChange.accept(DatePicker.replaceDate(column, value, day));
          calendar.close();
        },
        () -> {
          onChange.accept("");
          calendar.close();
        },
        null));
  }

  /** Whether the popover holds equality with nothing to be equal to, which it does not apply. */
  private static boolean incomplete(Selection sel, Column c) {
    return sel.filter.kind == Kind.EQUALS
        && (ordered(c) ? sel.filter.value.isBlank() : sel.filter.value.isEmpty());
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
    Theme theme = b.theme;
    Selection sel = b.sel;
    Column c = b.columns.get(column);
    List<Kind> kinds = kinds(c);
    Texts texts = b.texts;
    Draft draft = made(texts, sel, c, column);
    String error = draft instanceof Invalid invalid ? invalid.message() : null;
    Filter filter = draft instanceof Valid(Filter made) ? made : null;
    float width = theme.size().field();
    El content = col()
        .minSize(0, 0) // a long list of values gives way where the window is low
        .gap(theme.space().s())
        .child(Select.select(
                kinds.stream().map(texts::filterKind).toList(),
                kinds.indexOf(sel.filter.kind),
                sel.filter.kinds,
                i -> {
                  sel.filter.kind = kinds.get(i);
                  sel.filter.fromCalendar.close();
                  sel.filter.toCalendar.close();
                })
            .key("kind")
            .w(width));
    switch (sel.filter.kind) {
      case CONTAINS, STARTS_WITH, EQUALS, MATCHES -> {
        if (sel.filter.kind == Kind.EQUALS && dateColumn(c)) {
          content.child(dateField(
              theme,
              texts,
              c,
              sel.filter.value,
              texts.value(),
              "value",
              width,
              true,
              draft,
              sel.filter.fromCalendar,
              sel.filter.toCalendar,
              sel.filter.kinds,
              v -> sel.filter.value = v));
        } else {
          content.child(field(
              textField(sel.filter.value, texts.value(), v -> sel.filter.value = v)
                  .key("value")
                  .w(width)
                  .autofocus(),
              draft,
              "value"));
        }
      }
      case BETWEEN -> {
        if (dateColumn(c)) {
          content.child(dateField(
              theme,
              texts,
              c,
              sel.filter.value,
              texts.from(),
              "from",
              width,
              true,
              draft,
              sel.filter.fromCalendar,
              sel.filter.toCalendar,
              sel.filter.kinds,
              v -> sel.filter.value = v));
          content.child(dateField(
              theme,
              texts,
              c,
              sel.filter.to,
              texts.to(),
              "to",
              width,
              false,
              draft,
              sel.filter.toCalendar,
              sel.filter.fromCalendar,
              sel.filter.kinds,
              v -> sel.filter.to = v));
        } else {
          content.child(row()
              .gap(theme.space().s())
              .children(
                  field(
                      textField(sel.filter.value, texts.from(), v -> sel.filter.value = v)
                          .key("from")
                          .w((width - theme.space().s()) / 2)
                          .autofocus(),
                      draft,
                      "from"),
                  field(
                      textField(sel.filter.to, texts.to(), v -> sel.filter.to = v)
                          .key("to")
                          .w((width - theme.space().s()) / 2),
                      draft,
                      "to")));
        }
      }
      case ONE_OF -> {
        List<String> choices = values(c);
        boolean many = choices.size() > GridColumns.SEARCH_THRESHOLD;
        if (many) {
          content.child(GridColumns.searchField(
              theme, sel.filter.search, texts.searchValues(), v -> sel.filter.search = v));
        }
        String search = many ? GridColumns.searchQuery(sel.filter.search) : "";
        El list = col().key("values").gap(theme.space().xs());
        if (many) {
          GridColumns.scrollList(theme, list).minSize(0, 0);
        }
        for (String value : choices) {
          if (!GridColumns.matchesSearch(value, search)) {
            continue;
          }
          list.child(Checkbox.checkbox(sel.filter.values.contains(value), value, () -> {
                if (!sel.filter.values.remove(value)) {
                  sel.filter.values.add(value);
                }
              })
              .key("value-" + value));
        }
        content.child(list);
      }
      default -> {}
    }
    if (error != null) {
      content.child(Label.label(error)
          .key("error")
          .role("alert")
          .label(error)
          .color(theme.status().danger()));
    }
    Runnable apply = error == null && !incomplete(sel, c) ? () -> apply(b, column, filter) : null;
    content.child(row()
        .gap(theme.space().s())
        .justify(Style.Content.END)
        .children(
            button(
                Button.Variant.SECONDARY,
                texts.clear(),
                filterOf(b, column) == null ? null : () -> apply(b, column, null)),
            button(Button.Variant.PRIMARY, texts.apply(), apply)));
    El panel = Popover.panel(
        sel.filter.popover,
        texts.filter(c.title()),
        col()
            .minSize(0, 0)
            .gap(theme.space().m())
            .children(
                Label.label(Label.Variant.STRONG, texts.filter(c.title()))
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
      Theme theme, List<Column> columns, List<Filter> filters, Consumer<List<Filter>> onFilter) {
    if (filters.isEmpty()) {
      return null;
    }
    Texts texts = Texts.CURRENT.get();
    El bar = row()
        .key("filters")
        .role("list")
        .label(texts.filters())
        .wrap()
        .gap(theme.space().xs())
        .align(Style.Align.CENTER);
    for (Filter f : filters) {
      String title = f.column() < columns.size()
          ? columns.get(f.column()).title()
          : texts.column(f.column() + 1);
      bar.child(Badge.badge(
              f.describe(title), Badge.options().toolbar().icon(Symbol.FILTER).onRemove(() -> {
                List<Filter> left = new ArrayList<>(filters);
                left.remove(f);
                onFilter.accept(List.copyOf(left));
              }))
          .role("listitem"));
    }
    if (filters.size() > 1) {
      bar.child(button(Button.Variant.GHOST, texts.clearAll(), () -> onFilter.accept(List.of()))
          .key("clear-filters"));
    }
    return bar;
  }
}
