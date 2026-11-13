package com.rethinkj.ui.kit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * The edit of a value, such as a record in a dialog: the original, the edited value and the state
 * of its fields, in one object the app keeps in its state for as long as the edit lasts.
 *
 * <pre>{@code
 * class State {
 *   Editing<Profile> editing;                        // Editing.of(profile) as the dialog opens
 * }
 *
 * Editing<Profile> e = s.editing;
 * form(t, Labels.BESIDE,
 *     field("Host", textField(t, e.at(Profile.HOST))),
 *     field("Port", valueField(t, PORT, e.at(Profile.PORT))),
 *     field("Mode", select(t, MODES, e.at(Profile.TLS).at(Tls.MODE), Mode::label, s.modeMenu)))
 * button(t, PRIMARY, "Save", e.valid() && e.changed() ? () -> save(e.value()) : null)
 * }</pre>
 *
 * <p>{@link #at} gives the {@link Prop} of a field, by a {@link Part} declared once beside the
 * record. The editing keeps by those parts what a field holds between builds: the draft of a
 * {@link ValueField}, whose text is no value yet, and an error the app set. So the view declares
 * no {@link ValueField.Draft} per field, a field that shows only under a condition finds its draft
 * again when it comes back, and the fields around it keep theirs.
 *
 * <ul>
 *   <li>{@link #changed} compares the value with the original by {@code equals}, which records
 *       have; a POJO needs an {@code equals} of its own, and a copy ({@link #of(Object,
 *       UnaryOperator)}), since its setters change the object the editing holds.
 *   <li>{@link #valid} is false while a field holds a text that is no value, an error the app set
 *       stands, or a rule fails.
 *   <li>{@link #rule} checks the whole value, such as a date not before another, and shows its
 *       message at the field it names.
 *   <li>An error the app learns later, such as the server's refusal of a port, goes on the field
 *       with {@link Prop#error(String)}: it shows there until the next edit of the field.
 *   <li>A draft stays while its field is out of the view, and counts for {@link #valid}: a text
 *       that is no value stays one until the field shows again and is edited, or {@link #reset}.
 * </ul>
 *
 * @param <T> the type of the value
 */
public final class Editing<T> {

  /** What the editing keeps of a field. */
  static final class Field {
    final ValueField.Draft draft = new ValueField.Draft();
    String error; // the app's
  }

  private record Rule<T>(List<Part<?, ?>> path, Predicate<? super T> holds, String message) {}

  private final UnaryOperator<T> copy;
  private final Map<List<Part<?, ?>>, Field> fields = new HashMap<>();
  private final List<Rule<T>> rules = new ArrayList<>();
  private T original;
  private T value;

  private Editing(T original, UnaryOperator<T> copy) {
    this.copy = copy;
    this.original = original;
    this.value = copy.apply(original);
  }

  /** The edit of a value that does not change, such as a record. */
  public static <T> Editing<T> of(T value) {
    return new Editing<>(value, UnaryOperator.identity());
  }

  /**
   * The edit of an object that its setters change, such as a POJO: the editing edits a copy, so
   * that the original stays as it was, and compares them with the object's {@code equals}.
   *
   * @param copy makes a copy of the object, such as a copy constructor {@code Settings::new}
   */
  public static <T> Editing<T> of(T value, UnaryOperator<T> copy) {
    return new Editing<>(value, Objects.requireNonNull(copy, "copy"));
  }

  /**
   * A rule over the whole value, whose message shows at the field of {@code field} while it
   * fails, as {@code rule(Order.DUE, o -> !o.due().isBefore(o.ordered()), "Not before the
   * order")}. Rules are checked in the order given.
   *
   * @return this editing, so that rules follow each other
   */
  public Editing<T> rule(Part<T, ?> field, Predicate<? super T> holds, String message) {
    rules.add(new Rule<>(field.path(), Objects.requireNonNull(holds, "holds"), message));
    return this;
  }

  /** The value as edited so far. */
  public T value() {
    return value;
  }

  /** The value as it was when the edit began or was last {@link #saved}. */
  public T original() {
    return original;
  }

  /** Whether the value differs from the original, by {@code equals}. */
  public boolean changed() {
    return !Objects.equals(value, original);
  }

  /**
   * Whether the value can be saved: no field holds a text that is no value, no error the app set
   * stands, and every rule holds.
   */
  public boolean valid() {
    for (Field f : fields.values()) {
      if (f.draft.error() != null || f.error != null) {
        return false;
      }
    }
    for (Rule<T> r : rules) {
      if (!r.holds.test(value)) {
        return false;
      }
    }
    return true;
  }

  /** Back to the original: the value, with every draft and error forgotten. */
  public void reset() {
    value = copy.apply(original);
    fields.clear();
  }

  /**
   * Makes the value the new original, after the app saved it: {@link #changed} is false until
   * the next edit. Drafts stay, as they show the saved value's texts.
   */
  public void saved() {
    original = copy.apply(value);
  }

  /** The prop of a field of the value, which also carries the field's draft and error. */
  public <V> Prop<V> at(Part<T, V> part) {
    return root().at(part);
  }

  /** The prop of the whole value. */
  public Prop<T> root() {
    return new Prop<>(() -> value, v -> value = v, this, List.of());
  }

  // What the props of the editing ask

  Field field(List<Part<?, ?>> path) {
    return fields.computeIfAbsent(path, p -> new Field());
  }

  /** The error a field shows: its draft's, the app's, then the first rule's that fails. */
  String error(List<Part<?, ?>> path) {
    Field f = fields.get(path);
    if (f != null && f.draft.error() != null) {
      return f.draft.error();
    }
    if (f != null && f.error != null) {
      return f.error;
    }
    for (Rule<T> r : rules) {
      if (r.path.equals(path) && !r.holds.test(value)) {
        return r.message;
      }
    }
    return null;
  }

  /** A field was written: the app's errors on it and on the fields around it go. */
  void edited(List<Part<?, ?>> path) {
    Field f = fields.get(path);
    if (f != null) {
      f.error = null;
    }
  }

  @Override
  public String toString() {
    return "Editing[" + value + (changed() ? ", changed" : "") + (valid() ? "" : ", invalid") + "]";
  }
}
