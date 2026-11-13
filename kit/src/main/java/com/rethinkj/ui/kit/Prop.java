package com.rethinkj.ui.kit;

import com.rethinkj.ui.El;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A value and where to write it back, which a component shows and edits in place of a value and
 * a handler: {@code textField(t, prop)} for {@code textField(t, prop.get(), null, v -> ...)}.
 *
 * <pre>{@code
 * Prop<Boolean> dark = Prop.of(() -> s.dark, v -> s.dark = v);   // over the app's state
 * toggle(t, dark, "Dark theme")
 *
 * Prop<Profile> profile = Prop.of(() -> s.profile, v -> s.profile = v);
 * textField(t, profile.at(Profile.HOST))                  // writes a new Profile back
 * select(t, MODES, profile.at(Profile.TLS).at(Tls.MODE), Mode::label, s.modeMenu)
 * }</pre>
 *
 * <p>{@link #at} goes into a {@link Part} of the value: writing the part writes the whole back
 * through this prop, with the record its wither made or the POJO its setter changed. A prop
 * without a setter, or a part of a whole that is null, cannot be written, and a component made
 * with it is disabled, as with a null handler.
 *
 * <p>A prop of an {@link Editing} ({@link Editing#at}) also carries its field's state: the draft
 * of a {@link ValueField} ({@link #draft}) and the field's {@link #error}, which the component
 * shows and {@link Form#field} puts below it. Writing the prop clears an error the app set on its
 * field ({@link #error(String)}) and on the fields around it. A prop is made anew in every build
 * and holds nothing of its own; the state is the editing's.
 *
 * @param <T> the type of the value
 */
public final class Prop<T> {
  private final Supplier<? extends T> get;
  private final Consumer<? super T> set; // null: read only
  private final Editing<?> editing; // null outside an editing
  private final List<Part<?, ?>> path; // the field in the editing

  Prop(
      Supplier<? extends T> get,
      Consumer<? super T> set,
      Editing<?> editing,
      List<Part<?, ?>> path) {
    this.get = Objects.requireNonNull(get, "get");
    this.set = set;
    this.editing = editing;
    this.path = path;
  }

  /**
   * A prop over the app's state.
   *
   * @param get reads the value
   * @param set writes it; null for a value that is only shown
   */
  public static <T> Prop<T> of(Supplier<? extends T> get, Consumer<? super T> set) {
    return new Prop<>(get, set, null, List.of());
  }

  /** The value. */
  public T get() {
    return get.get();
  }

  /** Whether the value can be written; a component made with a prop that cannot is disabled. */
  public boolean writes() {
    return set != null;
  }

  /**
   * Writes the value, and clears the errors the app set on this field and the fields around it.
   *
   * @throws IllegalStateException when the prop cannot be written
   */
  public void set(T value) {
    if (set == null) {
      throw new IllegalStateException("The prop cannot be written: give it a setter");
    }
    if (editing != null) {
      editing.edited(path);
    }
    set.accept(value);
  }

  /** {@link #set} as a handler for a component, or null when the prop cannot be written. */
  public Consumer<T> onChange() {
    return set == null ? null : this::set;
  }

  /**
   * The prop of a part of the value. It cannot be written while this prop cannot or its value is
   * null; it reads null then.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  public <V> Prop<V> at(Part<T, V> part) {
    List<Part<?, ?>> parts = part.path();
    if (parts.size() > 1) { // a joined part: one step for each, so that a null inside reads null
      Prop prop = this;
      for (Part<?, ?> p : parts) {
        prop = prop.at((Part) p);
      }
      return prop;
    }
    List<Part<?, ?>> inner = new ArrayList<>(path);
    inner.add(part);
    Supplier<V> read = () -> {
      T whole = get();
      return whole == null ? null : part.get(whole);
    };
    boolean writes = set != null && get() != null;
    return new Prop<>(
        read, writes ? value -> set(part.write(get(), value)) : null, editing, List.copyOf(inner));
  }

  /**
   * The draft of a {@link ValueField} of this prop: kept by the {@link Editing} the prop comes
   * from, for its field.
   *
   * @throws IllegalStateException for a prop that comes from no editing, which has nowhere to keep
   *     a draft: give the field a {@link ValueField.Draft} of the app's state instead
   */
  public ValueField.Draft draft() {
    if (editing == null) {
      throw new IllegalStateException(
          "A prop of Prop.of keeps no draft: make it with Editing.at, or give the field a"
              + " ValueField.Draft of the app's state");
    }
    return editing.field(path).draft;
  }

  /**
   * What is wrong with this field, or null: the message of a text that is no value (its draft's),
   * else an error the app set, else the message of the first rule of the editing that names this
   * field and fails. Always null for a prop outside an editing.
   */
  public String error() {
    return editing == null ? null : editing.error(path);
  }

  /**
   * Sets an error the app learned, such as the server's refusal of a port: the field shows it,
   * {@link Editing#valid} is false, and the next edit of the field clears it. Null clears it now.
   *
   * @throws IllegalStateException for a prop outside an editing, which has nowhere to keep it
   */
  public void error(String message) {
    if (editing == null) {
      throw new IllegalStateException("A prop of Prop.of keeps no errors: make it with Editing.at");
    }
    editing.field(path).error = message;
  }

  /**
   * Gives the component's control the prop's error: the invalid look of a text field, {@code
   * invalid} and {@link El#errorMessage} for a {@code .tree.txt} and for {@link Form#field}, which
   * shows it below the control.
   */
  static El shown(Theme t, El component, Prop<?> prop) {
    String error = prop.error();
    El target = Form.target(component);
    if (error != null && target != null) {
      if (target.isEditable()) {
        TextField.invalid(t, target);
      }
      target.invalid(true).errorMessage(error);
    }
    return component;
  }
}
