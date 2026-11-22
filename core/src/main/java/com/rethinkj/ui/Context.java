package com.rethinkj.ui;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.ObjDoubleConsumer;
import java.util.function.Supplier;

/**
 * A value that the code building a view reads where it needs it instead of passing it along, as
 * the kit's components read their theme. {@link App#provide} gives it to the whole view, from the
 * state, and {@link #with} to a part of the view; {@link #get} reads the one that holds where it
 * is called, and the fallback where none does.
 *
 * <pre>{@code
 * static final Context<Locale> LOCALE = new Context<>(Locale.ROOT);
 *
 * App<Notes> app = new App<>(notes, Notes::view).provide(LOCALE, Notes::locale);
 *
 * static El preview(Note note) {
 *   return LOCALE.with(note.locale(), () -> page(note)); // this part in the note's own
 * }
 * }</pre>
 *
 * <p>A value holds while the view is built, on the app's thread, and for what the app builds from
 * the view later: the rows of virtual lists ({@link El#rows}) and the looks of the moment ({@link
 * El#whenHovered} and the like) see the values that held where they were given. Handlers run later and outside of it, so they keep what they need
 * from the view in their closure, or read it from the state.
 *
 * @param <T> the type of the value
 */
public final class Context<T> {

  // The values of the contexts that hold, by context; a map is never changed once it is bound.
  private static final ScopedValue<Map<Context<?>, Object>> VALUES = ScopedValue.newInstance();

  private final T fallback;

  /** A context whose value is {@code fallback} where none is given. */
  public Context(T fallback) {
    this.fallback = Objects.requireNonNull(fallback, "fallback");
  }

  /** The value given where this is called, or the fallback. */
  @SuppressWarnings("unchecked")
  public T get() {
    Object value = VALUES.orElse(Map.of()).get(this);
    return value == null ? fallback : (T) value;
  }

  /** Builds with {@code value} as this context's value, for what {@code build} builds. */
  public <R> R with(T value, Supplier<R> build) {
    Map<Context<?>, Object> values = new HashMap<>(VALUES.orElse(Map.of()));
    values.put(this, Objects.requireNonNull(value, "value"));
    return in(values, build);
  }

  /** The row function, made to build in the values that hold now. */
  static El.RowBuilder captured(El.RowBuilder row) {
    if (!VALUES.isBound()) {
      return row;
    }
    Map<Context<?>, Object> values = VALUES.get();
    return (index, from, to) -> in(values, () -> row.row(index, from, to));
  }

  /** The gutter, made to build its cells in the values that hold now. */
  static El.Gutter captured(El.Gutter gutter) {
    if (!VALUES.isBound()) {
      return gutter;
    }
    Map<Context<?>, Object> values = VALUES.get();
    return line -> in(values, () -> gutter.cell(line));
  }

  /** The function, or null, made to build in the values that hold now. */
  static <T, R> Function<T, R> captured(Function<T, R> function) {
    if (function == null || !VALUES.isBound()) {
      return function;
    }
    Map<Context<?>, Object> values = VALUES.get();
    return value -> in(values, () -> function.apply(value));
  }

  /** The int function, or null, made to build in the values that hold now. */
  static <R> IntFunction<R> captured(IntFunction<R> function) {
    if (function == null || !VALUES.isBound()) {
      return function;
    }
    Map<Context<?>, Object> values = VALUES.get();
    return value -> in(values, () -> function.apply(value));
  }

  /** The field's menu, or null, made to build in the values that hold now. */
  static FieldMenu captured(FieldMenu menu) {
    if (menu == null || !VALUES.isBound()) {
      return menu;
    }
    Map<Context<?>, Object> values = VALUES.get();
    return (field, open) -> in(values, () -> menu.build(field, open));
  }

  /** The look, or null, made to apply in the values that hold now. */
  static Consumer<El> captured(Consumer<El> look) {
    if (look == null || !VALUES.isBound()) {
      return look;
    }
    Map<Context<?>, Object> values = VALUES.get();
    return el -> in(values, () -> {
      look.accept(el);
      return null;
    });
  }

  /** The cycle's look, made to apply in the values that hold now. */
  static ObjDoubleConsumer<El> captured(ObjDoubleConsumer<El> look) {
    if (look == null || !VALUES.isBound()) {
      return look;
    }
    Map<Context<?>, Object> values = VALUES.get();
    return (el, phase) -> in(values, () -> {
      look.accept(el, phase);
      return null;
    });
  }

  private static <R> R in(Map<Context<?>, Object> values, Supplier<R> build) {
    return ScopedValue.where(VALUES, values).call(build::get);
  }
}
