package com.rethinkj.ui.kit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A part of a value of type {@code T}, such as the port of a connection's profile: how it is read
 * and how it is written back, declared once, beside the record or class, as a constant.
 *
 * <pre>{@code
 * record Profile(String host, Integer port, Tls tls) {
 *   static final Part<Profile, String> HOST = Part.of(Profile::host, Profile::withHost);
 *   static final Part<Profile, Integer> PORT = Part.of(Profile::port, Profile::withPort);
 *   static final Part<Profile, Tls> TLS = Part.of(Profile::tls, Profile::withTls);
 *   Profile withHost(String host) { return new Profile(host, port, tls); }
 *   ...
 * }
 *
 * class Settings {                       // a POJO
 *   static final Part<Settings, Integer> FETCH = Part.ofSetter(Settings::getFetch, Settings::setFetch);
 *   ...
 * }
 * }</pre>
 *
 * <p>The constant is the part's identity: an {@link Editing} keeps the draft and the errors of a
 * field by the parts that lead to it, compared as objects, so a view that asks for {@code
 * e.at(Profile.PORT)} in every build finds the same field each time, whichever fields come and
 * go around it. A part made anew in every build ({@code e.at(Part.of(...))} in the view) is a new
 * field each time and loses its draft; declare it once. {@link #then} goes into a part of a part.
 *
 * <p>A record's part writes through a wither, which makes a new record; a POJO's through a
 * setter, which changes the object it has. The two factories have names of their own rather than
 * one overloaded name, so that a lambda works as well as a method reference: {@code (p, v) ->
 * p.withPort(v)} fits both a wither and a setter, which overloads could not tell apart.
 *
 * @param <T> the type of the whole
 * @param <V> the type of the part
 */
public final class Part<T, V> {
  private final Function<? super T, ? extends V> get;
  private final BiFunction<? super T, ? super V, ? extends T> write;
  private final List<Part<?, ?>> path; // the parts from the whole to this one
  private final Predicate<? super T> writes; // whether no part on the way is null

  private Part(
      Function<? super T, ? extends V> get,
      BiFunction<? super T, ? super V, ? extends T> write,
      List<Part<?, ?>> path,
      Predicate<? super T> writes) {
    this.get = get;
    this.write = write;
    this.path = path == null ? List.of(this) : path;
    this.writes = writes;
  }

  /** A part of a record, read by its accessor and written by a wither, which makes a new record. */
  public static <T, V> Part<T, V> of(
      Function<? super T, ? extends V> get, BiFunction<? super T, ? super V, ? extends T> with) {
    Objects.requireNonNull(get, "get");
    Objects.requireNonNull(with, "with");
    return new Part<>(get, with, null, whole -> true);
  }

  /** A part of a POJO, read by its getter and written by its setter, which changes the object. */
  public static <T, V> Part<T, V> ofSetter(
      Function<? super T, ? extends V> get, BiConsumer<? super T, ? super V> set) {
    Objects.requireNonNull(get, "get");
    Objects.requireNonNull(set, "set");
    return new Part<>(
        get,
        (whole, value) -> {
          set.accept(whole, value);
          return whole;
        },
        null,
        whole -> true);
  }

  /** The part's value in {@code whole}. */
  public V get(T whole) {
    return get.apply(whole);
  }

  /**
   * Writes a value: a new whole from a wither, or {@code whole} itself, changed by a setter.
   */
  public T write(T whole, V value) {
    return write.apply(whole, value);
  }

  /**
   * Whether a value can be written into {@code whole}: not when a part on the way to this one is
   * null there, as the {@code details} of {@code Order.DETAILS.then(Details.NOTE)} in an order
   * without details, which {@link #write} would have to write into.
   */
  public boolean writes(T whole) {
    return writes.test(whole);
  }

  /**
   * The part {@code inner} of this part, as {@code Profile.TLS.then(Tls.MODE)}: the same field as
   * {@code e.at(Profile.TLS).at(Tls.MODE)}, since both are the same parts in the same order, so a
   * rule or an error can name a field inside a field.
   */
  public <W> Part<T, W> then(Part<V, W> inner) {
    List<Part<?, ?>> joined = new ArrayList<>(path);
    joined.addAll(inner.path);
    return new Part<>(
        whole -> {
          V value = get(whole);
          return value == null ? null : inner.get(value);
        },
        (whole, value) -> write(whole, inner.write(get(whole), value)),
        List.copyOf(joined),
        whole -> writes(whole) && get(whole) != null && inner.writes(get(whole)));
  }

  /** The parts from the whole to this one, which name its field in an {@link Editing}. */
  List<Part<?, ?>> path() {
    return path;
  }
}
