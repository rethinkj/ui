package com.rethinkj.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * The keyboard focus of an {@link App}: the focused element and its identity, whether it shows a
 * ring and is to be scrolled into view, since when it is there, the text typed ahead into it, the
 * elements that asked for it with {@link El#autofocus}, where it was last in each virtual list and
 * container, and where it was before each open overlay took it. It finds the elements of a tree by
 * their identity through the app's {@link Animator}; {@code App} hands it the tree's focusable
 * elements and the app's clock where a rule needs them.
 */
final class Focus {

  /** Where the focus was, by identity, and whether it had a ring. */
  record Place(Object id, boolean ring) {}

  private final Animator animator;
  private El element; // in the current tree
  private Object id; // its identity, to find it in the next tree
  // El#onTypeAhead's text so far, typed into the focused element: a move of the focus empties it.
  private final StringBuilder typeAhead = new StringBuilder();
  private double typeAheadAt; // when its last character came
  private boolean ring; // the keyboard or the app moved the focus: draw the ring
  private double since; // when the focus moved there, for El#whenFocusVisibleFor
  private boolean keyed; // a key went down since then that did not move the focus
  private boolean reveal; // scroll the focused element into view after the next rebuild
  private Place beforeAsked; // where an El#autofocus took the focus from, until refocus finds it
  private List<Object> askedNext = List.of(); // the others that asked with it, in tree order
  // The identity of the element that last had the focus in a virtual list, by the list's identity.
  // shortcut: lists that are gone stay in it, one small entry each; drop them in update() when
  // apps create lists by the thousand.
  private final Map<Object, Object> remembered = new HashMap<>();
  // The identities of the elements that had the focus last, the latest first, for focus(container).
  // shortcut: the last 16 only; a container whose last focus is older gets its first element.
  private final ArrayDeque<Object> recent = new ArrayDeque<>();
  // The identity of a container that focus(container) or recovery found no default focus in; until
  // the focus moves, it goes to the default focus that appears there later, and the place it holds
  // meanwhile does not count in recent.
  private Object awaiting;
  // The overlays of the last tree, in the order they opened, with where the focus was before each
  // took it; null for one that took none. Those inside virtual-list rows and field parts are also
  // in partOverlays, so openAndClose(tree) leaves them to openAndCloseParts(root) after layout.
  private final Map<Object, Place> opened = new LinkedHashMap<>();
  private final Set<Object> partOverlays = new HashSet<>();
  // For the open overlays that took no focus, such as a toast: where the focus was last outside
  // each, for when the keyboard moved it inside and the overlay closes.
  private final Map<Object, Place> outside = new HashMap<>();
  private int ringColor = App.FOCUS_RING;

  Focus(Animator animator) {
    this.animator = animator;
  }

  /** The focused element of the current tree, or null. */
  El element() {
    return element;
  }

  /** The identity of the focused element, or null; kept while its row of a list is out of range. */
  Object id() {
    return id;
  }

  /** Whether the keyboard or the app moved the focus, so that the scene draws its ring. */
  boolean ring() {
    return ring;
  }

  int ringColor() {
    return ringColor;
  }

  void ringColor(int argb) {
    ringColor = argb;
  }

  /** The identities of the overlays of the last tree, in the order they opened. */
  List<Object> opened() {
    return List.copyOf(opened.keySet());
  }

  /**
   * Focuses the element of the current tree that {@code App.focusables} has, or none.
   *
   * @param ring whether the keyboard or the app moved the focus, not the pointer
   * @return whether a rebuild is due even if the focus ends where it was: to scroll it into view
   */
  boolean set(El el, boolean ring, double time) {
    noteOutside();
    awaiting = null;
    if (element != null) {
      element.focused(false);
    }
    element = el;
    id = el == null ? null : animator.identity(el);
    this.ring = ring && el != null;
    reveal = this.ring;
    since = time;
    keyed = false;
    typeAhead.setLength(0);
    if (el != null) {
      el.focused(true);
      noteRecent(); // here too: another request of the same frame may move it on before a rebuild
      Animator.InRow in = animator.inRow(id);
      if (in != null) {
        remembered.put(in.listId(), id);
      }
    }
    return ring;
  }

  /** Moves the focus to the element with this identity, which the next {@link #refocus} finds. */
  void move(Object id, boolean ring, double time) {
    noteOutside();
    awaiting = null;
    if (element != null) {
      element.focused(false);
    }
    element = null;
    this.id = id;
    this.ring = ring && id != null;
    reveal = this.ring;
    since = time;
    keyed = false;
    typeAhead.setLength(0);
  }

  /**
   * The focus went to a fallback inside the element with identity {@code scope}, which has no
   * default focus: until the focus moves, it goes to the default focus that appears there later,
   * and its place meanwhile does not count among the recent ones.
   */
  void awaitInside(Object scope) {
    awaiting = scope;
    recent.remove(id);
  }

  /**
   * The element of {@code order} inside the element with identity {@code scope} that had the focus
   * last, or null.
   */
  El lastInside(Object scope, List<El> order) {
    for (Object last : recent) {
      El el = Animator.isBelow(last, scope) ? animator.element(last) : null;
      if (el != null && order.contains(el)) {
        return el;
      }
    }
    return null;
  }

  /** Puts the focus's identity first in {@link #recent}. */
  private void noteRecent() {
    if (!id.equals(recent.peekFirst())) {
      recent.remove(id);
      recent.addFirst(id);
      if (recent.size() > 16) {
        recent.removeLast();
      }
    }
  }

  /** The identity of the element that last had the focus in the list with this identity, or null. */
  Object lastInList(Object listId) {
    return remembered.get(listId);
  }

  /**
   * Whether the focus is on a row of a virtual list that the list could build but does not have
   * now, because it is out of the range that exists, or on an element inside such a row.
   */
  boolean waits() {
    return id != null && waits(id);
  }

  /** Whether the element of this identity is in a row of a virtual list that it lacks now. */
  boolean waits(Object id) {
    Animator.InRow in = animator.inRow(id);
    return in != null && in.row() < in.list().rowCount() && !in.list().hasRow(in.row());
  }

  /**
   * A key went down, which the focus was on before as after it: the {@link
   * El#whenFocusVisibleFor} look of the focused element goes.
   *
   * @return whether that look was due to show, so that the view is built again
   */
  boolean keyed(Object before) {
    if (Objects.equals(before, id)
        && !keyed
        && element != null
        && element.focusRestLook() != null) {
      keyed = true; // the look of a stay goes
      return true;
    }
    return false;
  }

  /**
   * When the {@link El#whenFocusVisibleFor} look of {@code el}, the focused element, is due; NaN
   * when it has none or shows none now, as without a ring or after a key.
   */
  double visibleAt(El el) {
    if (el == null || el.focusRestLook() == null || !ring || keyed) {
      return Double.NaN;
    }
    return since + el.focusRestDelay();
  }

  /** Forgets the text typed ahead. */
  void clearTypeAhead() {
    typeAhead.setLength(0);
  }

  /**
   * Adds {@code text} to the text typed ahead into the focused element, which starts anew after
   * {@link App#TYPE_AHEAD}.
   *
   * @return the text so far, or null for blank text that would start it
   */
  String typeAhead(String text, double time) {
    if (time - typeAheadAt >= App.TYPE_AHEAD) {
      typeAhead.setLength(0);
    }
    if (typeAhead.isEmpty() && text.isBlank()) {
      return null;
    }
    typeAhead.append(text);
    typeAheadAt = time;
    return typeAhead.toString();
  }

  /**
   * Finds the focused element in a new tree by its identity, or where the focus goes when it is
   * gone or can no longer take it ({@link #recover}).
   *
   * @param focusables the elements of the tree that can take the focus, in tree order
   * @return the focused element if it is due to be scrolled into view now, else null
   */
  El refocus(Supplier<List<El>> focusables, double time) {
    if (id == null) {
      return null;
    }
    if (awaiting != null) {
      El scope = animator.element(awaiting);
      El now = animator.element(id);
      List<El> order = focusables.get();
      El preferred = scope == null ? null : firstInside(scope, order);
      if (scope == null || now == null || !order.contains(now)) {
        awaiting = null; // its place is gone: the focus goes where recovery puts it
      } else if (preferred != null && preferred.isDefaultFocus()) {
        if (!animator.identity(preferred).equals(id)) {
          set(preferred, ring, time); // as a move: noted in the memories, ends the wait
        }
        awaiting = null;
      }
    }
    if (element != null) {
      element.focused(false);
    }
    El el = animator.element(id);
    if (beforeAsked != null) {
      Place before = beforeAsked;
      List<Object> next = askedNext;
      beforeAsked = null;
      askedNext = List.of();
      List<El> order = focusables.get();
      for (int i = 0; (el == null || !order.contains(el)) && i < next.size(); i++) {
        id = next.get(i); // the element asked for cannot take it: the next one that asked
        el = animator.element(id);
      }
      if (el == null || !order.contains(el)) { // none can
        id = before.id();
        ring = before.ring();
        reveal = false;
        return refocus(focusables, time);
      }
    }
    if (el == null && waits()) {
      element = null; // kept by its identity until the row exists again
      reveal = false;
      return null;
    }
    List<El> order = focusables.get();
    if (el == null || !order.contains(el)) {
      el = recover(id, order);
      if (el == null) {
        element = null;
        id = null;
        ring = false;
        reveal = false;
        return null;
      }
      id = animator.identity(el);
      reveal = ring; // the ring stays as it was: after a key, the new place shows it
      since = time;
      keyed = false;
      typeAhead.setLength(0);
      Animator.InRow in = animator.inRow(id);
      if (in != null) {
        remembered.put(in.listId(), id);
      }
    }
    element = el;
    el.focused(true);
    if (awaiting == null) {
      noteRecent();
    }
    if (reveal) {
      reveal = false;
      return el;
    }
    return null;
  }

  /**
   * Where the focus goes when the element with identity {@code lost} is gone or can no longer take
   * it: to the nearest of it and its ancestors below the root that is still there and can take the
   * focus, or else has an element inside it that can, the one that had the focus last, else the
   * one {@link #firstInside} picks, which waits for a default focus as after {@link App#focus(El)};
   * null if none has.
   */
  private El recover(Object lost, List<El> order) {
    List<Object> lineage = Animator.lineage(lost);
    for (int i = lineage.size() - 1; i >= 1; i--) {
      El scope = animator.element(lineage.get(i));
      if (scope == null || scope.isExiting()) {
        continue;
      }
      if (order.contains(scope)) {
        return scope;
      }
      El last = lastInside(lineage.get(i), order);
      if (last != null) {
        return last;
      }
      El first = firstInside(scope, order);
      if (first != null) {
        awaiting = first.isDefaultFocus() ? null : lineage.get(i);
        return first;
      }
    }
    return null;
  }

  /**
   * The element of {@code order} inside {@code scope} that is {@link El#defaultFocus}, the one the
   * fewest levels below it and the first in tree order among those, else the first that is {@link
   * El#autofocus}, else the first; null if none is inside.
   */
  static El firstInside(El scope, List<El> order) {
    Map<El, Integer> inside = new IdentityHashMap<>();
    depths(scope, 0, inside);
    El preferred = null;
    El asking = null;
    El first = null;
    for (El el : order) {
      Integer depth = inside.get(el);
      if (depth == null) {
        continue;
      }
      if (el.isDefaultFocus() && (preferred == null || depth < inside.get(preferred))) {
        preferred = el;
      }
      asking = asking == null && el.autofocusKey() != null ? el : asking;
      first = first == null ? el : first;
    }
    return preferred != null ? preferred : asking != null ? asking : first;
  }

  private static void depths(El el, int depth, Map<El, Integer> into) {
    into.put(el, depth);
    for (El child : el.children()) {
      depths(child, depth + 1, into);
    }
  }

  /**
   * Before layout of a tree the view has just built: each overlay it no longer has gives the
   * focus back to where it was before the overlay took it, the newest first, if the focus is
   * still inside the overlay or nowhere; then each overlay that opens moves the focus to its first
   * {@link El#autofocus} element or else its first focusable one, without a ring, unless it {@link
   * El#keepFocus keeps the focus} where it is. Unless one did, the first element that asks for the
   * focus with {@link El#autofocus} takes it.
   * A modal overlay without one takes the focus away until it closes. An element that {@link
   * El#holdFocus holds the focus} counts as an overlay that is open while it is marked so. {@link
   * #refocus} finds the element after layout.
   */
  void openAndClose(El tree, double time) {
    if (!openAndClose(Animator.overlays(tree), false, time)) {
      ask(animator.autofocus(tree), time);
    }
  }

  /**
   * Like {@link #openAndClose}, once the rows of virtual lists and the post-layout parts of
   * multi-line fields are built: closes and opens the overlays inside them.
   */
  void openAndCloseParts(El root, double time) {
    openAndClose(Animator.partOverlays(root), true, time);
  }

  private boolean openAndClose(Map<Object, El> now, boolean parts, double time) {
    boolean took = false;
    List<Object> before = new ArrayList<>(opened.keySet());
    for (Object overlay : before.reversed()) {
      if (partOverlays.contains(overlay) != parts || now.containsKey(overlay)) {
        continue;
      }
      partOverlays.remove(overlay);
      Place back = opened.remove(overlay);
      Place left = outside.remove(overlay);
      boolean inside = id != null && (id.equals(overlay) || Animator.isBelow(id, overlay));
      if (back != null && (id == null || inside)) {
        move(back.id(), back.ring(), time);
      } else if (left != null && inside) {
        move(left.id(), left.ring(), time);
      } else if (back != null || left != null) { // one opened from inside it: a dialog from a menu
        Place giveBack = back != null ? back : left;
        BiFunction<Object, Place, Place> past = (other, place) ->
            place != null && within(place.id(), overlay) && !within(giveBack.id(), other)
                ? giveBack
                : place;
        opened.replaceAll(past);
        outside.replaceAll(past);
      }
    }
    for (Map.Entry<Object, El> overlay : now.entrySet()) {
      Object overlayId = overlay.getKey();
      if (opened.containsKey(overlayId)
          && opened.get(overlayId) == null
          && id != null
          && !id.equals(overlayId)
          && !Animator.isBelow(id, overlayId)) {
        outside.put(overlayId, new Place(id, ring));
      }
      if (opened.containsKey(overlayId)) {
        continue;
      }
      El el = overlay.getValue();
      Object first = Animator.firstFocusable(el, overlayId);
      boolean takes =
          (el.holdsFocus() || !el.keepsFocus()) && (first != null || el.isModal() && !el.isInert());
      opened.put(overlayId, takes ? new Place(id, ring) : null);
      if (parts) {
        partOverlays.add(overlayId);
      }
      if (!takes && id != null) {
        outside.put(overlayId, new Place(id, ring));
      }
      if (takes) {
        beforeAsked = null;
        askedNext = List.of();
        move(first, false, time);
        took = true;
      }
    }
    return took;
  }

  /**
   * Moves the focus to the first of these elements that ask for it with {@link El#autofocus}, in
   * tree order; where that one cannot take it once laid out, {@link #refocus} takes the next that
   * can, and gives the focus back to where it was when none can.
   */
  void ask(List<Object> asked, double time) {
    if (asked.isEmpty()) {
      return;
    }
    List<Object> next = new ArrayList<>(asked.subList(1, asked.size()));
    if (beforeAsked == null) {
      beforeAsked = new Place(id, ring);
    } else {
      next.add(id); // asked before, by the rest of the tree
      next.addAll(askedNext);
    }
    askedNext = next;
    if (!asked.getFirst().equals(id)) { // one that has it keeps it, if it still can
      move(asked.getFirst(), ring, time);
      reveal = true;
    }
  }

  /** Whether the element with identity {@code id} is {@code overlay} or inside it. */
  private static boolean within(Object id, Object overlay) {
    return overlay.equals(id) || Animator.isBelow(id, overlay);
  }

  /**
   * Before the focus moves: notes where it is for each open overlay that {@link El#keepFocus keeps
   * the focus} outside it while it is outside, as {@link #openAndClose} does in a rebuild, so that
   * the overlay gives back the last place, also after several moves between two rebuilds.
   */
  private void noteOutside() {
    if (id == null) {
      return;
    }
    for (Map.Entry<Object, Place> overlay : opened.entrySet()) {
      Object overlayId = overlay.getKey();
      if (overlay.getValue() == null && !id.equals(overlayId) && !Animator.isBelow(id, overlayId)) {
        outside.put(overlayId, new Place(id, ring));
      }
    }
  }
}
