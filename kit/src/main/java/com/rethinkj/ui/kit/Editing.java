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
 * Editing<Profile> editing = state.editing;
 * form(Labels.BESIDE,
 *     field("Host", textField(editing.at(Profile.HOST))),
 *     field("Port", valueField(PORT, editing.at(Profile.PORT))),
 *     field("Mode", select(MODES, editing.at(Profile.TLS).at(Tls.MODE), Mode::label,
 *         state.modeMenu)))
 * button(PRIMARY, "Save",
 *     editing.valid() && editing.changed() ? () -> save(editing.value()) : null)
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
 *   <li>{@link #valid} is false while a field holds a text that is no value, also one the app's
 *       value gave it (null in a required field), an error the app set stands, a {@link #required}
 *       field is missing (also before its component is built, as on a tab never shown), or a rule
 *       fails. A field shows its draft's or required error once it was edited, or for all fields
 *       after {@link #showErrors}, which an app calls when Save is pressed.
 *   <li>{@link #rule} checks the whole value, such as a date not before another, and shows its
 *       message at the field it names.
 *   <li>An error the app learns later, such as the server's refusal of a port, goes on the field
 *       with {@link Prop#error(String)}: it shows there until the field, one of its ancestors, or
 *       one of its descendants is edited.
 *   <li>A draft stays while its field is out of the view, and counts for {@link #valid}: a text
 *       that is no value stays one until the field shows again and is edited, {@link #forget}
 *       drops it, or {@link #reset}. Call {@link #forget} when a hidden field's input no longer
 *       counts:
 *       <pre>{@code
 * toggle(state.tls, "TLS", state::switchTls)        // the TLS fields show while it is on
 *
 * void switchTls() {                                 // in State
 *   tls = !tls;
 *   if (!tls) {
 *     editing.forget(Profile.TLS);                   // and every field inside it
 *   }
 * }
 * }</pre>
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
  private final List<Part<T, ?>> required = new ArrayList<>();
  private final List<Rule<T>> rules = new ArrayList<>();
  private T original;
  private T value;
  private boolean showErrors; // every draft's error shows, edited or not

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
   * Marks {@code field} as required: {@link #valid} is false while the field's value is null or
   * the empty string, also before any component has built the field, such as on a tab never shown.
   * Its error ({@link Texts#required}) shows once the field was edited or {@link #showErrors} was
   * called. It does not count while a part on the way to the field is null, such as the {@code
   * details} of {@code Order.DETAILS.then(Details.NOTE)}. {@link #forget} leaves this
   * declaration alone: a requirement that holds only under a condition is a {@link #rule}, or
   * lives inside a part that is null while it does not apply.
   *
   * @return this editing, so that declarations follow each other
   */
  public Editing<T> required(Part<T, ?> field) {
    required.add(Objects.requireNonNull(field, "field"));
    return this;
  }

  /**
   * A rule over the whole value, whose message shows at the field of {@code field} while it
   * fails, as {@code rule(Order.DUE, order -> !order.due().isBefore(order.ordered()), "Not
   * before the order")}. Rules are checked in the order given.
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
   * stands, no {@link #required} field is missing (built or not), and every rule holds.
   */
  public boolean valid() {
    for (Field f : fields.values()) {
      if (f.draft.error() != null || f.error != null) {
        return false;
      }
    }
    for (Part<T, ?> part : required) {
      if (missing(part)) {
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
    showErrors = false;
  }

  /**
   * Drops the draft and the app's error of {@code field} and of every field below it, leaving the
   * value, {@link #required} declarations, rules, and {@link #showErrors} alone. An app calls this
   * when it hides a field whose input no longer counts, so that an invalid draft there does not
   * keep {@link #valid} false; when the field shows again, it starts from the value. A forgotten
   * required field still counts while its value is missing: a requirement that holds only under a
   * condition is a {@link #rule}, or lives inside a part that is null while it does not apply.
   */
  public void forget(Part<T, ?> field) {
    List<Part<?, ?>> path = Objects.requireNonNull(field, "field").path();
    fields
        .keySet()
        .removeIf(p -> p.size() >= path.size() && p.subList(0, path.size()).equals(path));
  }

  /**
   * Makes every field show its draft's or {@link #required} error, also one the user has not
   * edited, such as a required field left empty: what an app calls when Save is pressed while the
   * edit is not {@link #valid}. Until then a draft's or required error shows once its field is
   * edited; {@link #reset} ends it.
   */
  public void showErrors() {
    showErrors = true;
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

  /** Whether a required field exists in the current value and holds null or the empty string. */
  private boolean missing(Part<T, ?> part) {
    if (value == null || !part.writes(value)) {
      return false;
    }
    Object v = part.get(value);
    return v == null || (v instanceof String s && s.isEmpty());
  }

  /**
   * What is wrong with a field: its draft's error, the app's, a missing {@link #required} field,
   * then the first rule's that fails. With {@code shown}, a draft's or required error only once
   * the field was edited or {@link #showErrors} was called, as the field shows it.
   */
  String error(List<Part<?, ?>> path, boolean shown) {
    Field f = fields.get(path);
    boolean shows = !shown || showErrors || (f != null && f.draft.showsError());
    if (f != null && f.draft.error() != null && shows) {
      return f.draft.error();
    }
    if (f != null && f.error != null) {
      return f.error;
    }
    if (shows) {
      for (Part<T, ?> part : required) {
        if (part.path().equals(path) && missing(part)) {
          return Texts.CURRENT.get().required();
        }
      }
    }
    for (Rule<T> r : rules) {
      if (r.path.equals(path) && !r.holds.test(value)) {
        return r.message;
      }
    }
    return null;
  }

  /**
   * A field was written: the app's errors on it, its ancestors, and its descendants go; siblings
   * keep theirs. A {@link #required} field shows its error from now on, also a text field's.
   */
  void edited(List<Part<?, ?>> path) {
    for (Map.Entry<List<Part<?, ?>>, Field> entry : fields.entrySet()) {
      List<Part<?, ?>> other = entry.getKey();
      int n = Math.min(path.size(), other.size());
      if (path.subList(0, n).equals(other.subList(0, n))) {
        entry.getValue().error = null;
      }
    }
    for (Part<T, ?> part : required) {
      if (part.path().equals(path)) {
        field(path).draft.showError();
      }
    }
  }

  @Override
  public String toString() {
    return "Editing[" + value + (changed() ? ", changed" : "") + (valid() ? "" : ", invalid") + "]";
  }
}
