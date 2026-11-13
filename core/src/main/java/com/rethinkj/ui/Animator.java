package com.rethinkj.ui;

import com.rethinkj.ui.motion.Spring;
import com.rethinkj.ui.motion.Track;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What {@link App} keeps of each element between rebuilds, so that elements with {@link
 * El#motion} spring from the values they showed to the ones the new tree gives them, and scroll
 * areas keep their offset.
 *
 * <p>An element is the same as one in the previous tree when it has the same identity: its
 * parent's identity plus its key or, without a key, its index among its siblings. Every element
 * has one {@link Track} per animated number. Layout runs once, for the targets; the tracks then
 * write the values of the moment into the tree, so that everything reading the tree sees what is
 * on screen.
 *
 * <p>An element with a key and motion that a rebuild leaves out is put back among its parent's
 * new children after layout, marked {@link El#isExiting}, with its subtree as it was, and stays
 * there until its opacity has come to rest at 0.
 *
 * <p>The rows of a virtual list ({@link El#rows}) are identified by their index, and they come
 * after layout: {@link #rows} takes them each time the list builds them, and until then a
 * rebuilt list keeps the entries of its previous rows for them.
 */
final class Animator {

  // The animated numbers: x, y, width, height and radius in pixels, then the fill, the border and
  // the text colour as alpha, red, green and blue, then the opacity.
  private static final int RADIUS = 4, FILL = 5, BORDER = 9, TEXT = 13, OPACITY = 17, COUNT = 18;

  // A movement ends this near its target, then jumps onto it: a hundredth of a pixel, and a
  // quarter of a colour step, which rounds to the target; for opacity, a quarter of 1/255.
  private static final double PIXELS = 0.01, CHANNELS = 0.25;

  /**
   * The part is the element's key or, when it has none, its index as an Integer; for a row of a
   * virtual list, the row's index.
   */
  private record Id(Id parent, Object part) {}

  /** The part of an overlay's identity: its key or index among its anchor's overlays. */
  private record Layer(Object part) {}

  /** Where an element is in a virtual list: the list, its identity and the index of the row. */
  record InRow(El list, Object listId, int row) {}

  // shortcut: every element keeps eighteen tracks, also without motion, so that one that gains
  // motion moves from where it was; keep plain values for those when trees reach many thousands.
  private Map<Id, Entry> entries = new HashMap<>();
  private final Set<Entry> moving = new LinkedHashSet<>();
  private boolean started;
  private long created; // entries made so far, which orders overlays by when they opened

  /**
   * Takes a tree that layout has just placed: its values become the targets at {@code now}, and
   * the elements that are leaving are put back into it.
   */
  void update(El root, double now) {
    Map<Id, Entry> next = new HashMap<>();
    moving.clear();
    visit(root, new Id(null, part(root, 0)), started, next, now);
    entries = next; // drops the elements that are gone
    started = true;
    present(now);
  }

  /**
   * Writes the values at {@code now} into the elements that were moving, and takes those that
   * have faded out out of the tree.
   */
  void present(double now) {
    moving.removeIf(entry -> {
      if (entries.get(entry.id) != entry) {
        return true; // forgotten with an element above it that has faded out
      }
      entry.present(now);
      if (entry.el.isExiting() && entry.tracks[OPACITY].isAtRest(now)) {
        El parent = entries.get(entry.id.parent()).el;
        if (entry.id.part() instanceof Layer) {
          parent.removeOverlay(entry.el);
        } else {
          parent.children().remove(entry.el);
        }
        forget(entry.el, entry.id);
        return true;
      }
      return entry.isAtRest(now);
    });
  }

  /** The identity of an element of the current tree, or null if it is not there. */
  Object identity(El el) {
    for (Entry entry : entries.values()) {
      if (entry.el == el) {
        return entry.id;
      }
    }
    return null;
  }

  /**
   * The element of the current tree that has this {@link #identity}, or null. It may be one that
   * is leaving, or inside one.
   */
  El element(Object identity) {
    Entry entry = entries.get(identity);
    return entry == null ? null : entry.el;
  }

  /**
   * The identities of a path of elements of the current tree, from the root down, such as hit
   * testing finds; none of them is leaving.
   */
  static List<Object> identities(List<El> path) {
    List<Object> ids = new ArrayList<>(path.size());
    Id id = null;
    for (int i = 0; i < path.size(); i++) {
      El el = path.get(i);
      if (i == 0 && el.placement() != null && el.placement().id != null) {
        id = (Id) el.placement().id; // a path from an overlay down
        ids.add(id);
        continue;
      }
      if (i > 0 && path.get(i - 1).hasOverlay(el)) {
        int index = 0;
        for (El overlay : path.get(i - 1).overlays()) {
          if (overlay == el) {
            break;
          }
          if (!overlay.isExiting()) {
            index++;
          }
        }
        id = new Id(id, new Layer(part(el, index)));
        ids.add(id);
        continue;
      }
      int index = 0;
      if (i > 0) {
        for (El sibling : path.get(i - 1).children()) {
          if (sibling == el) {
            break;
          }
          if (!sibling.isExiting()) {
            index++;
          }
        }
      }
      El parent = i > 0 ? path.get(i - 1) : null;
      id = new Id(id, parent != null && parent.isVirtualList()
          ? Integer.valueOf(parent.firstRow() + index) : part(el, index));
      ids.add(id);
    }
    return ids;
  }

  /** The identities of an element's ancestors from the root down, and its own last. */
  static List<Object> lineage(Object identity) {
    List<Object> ids = new ArrayList<>();
    for (Id id = (Id) identity; id != null; id = id.parent()) {
      ids.add(id);
    }
    return ids.reversed();
  }

  /** The identity of the row with this index of the virtual list with identity {@code list}. */
  static Object row(Object list, int index) {
    return new Id((Id) list, index);
  }

  /** Whether {@code ancestor} is the identity of an element above the one of {@code identity}. */
  static boolean isBelow(Object identity, Object ancestor) {
    for (Id id = identity == null ? null : ((Id) identity).parent(); id != null; id = id.parent()) {
      if (id.equals(ancestor)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The innermost virtual list of the current tree that the element with this identity is in, or
   * would be in if its row existed, with the index of that row; null if it is in none.
   */
  InRow inRow(Object identity) {
    for (Id id = (Id) identity; id != null && id.parent() != null; id = id.parent()) {
      Entry parent = entries.get(id.parent());
      if (parent != null && parent.el.isVirtualList() && id.part() instanceof Integer row) {
        return new InRow(parent.el, id.parent(), row);
      }
    }
    return null;
  }

  /**
   * The element of a tree that the view has just built that will have this identity once the next
   * {@link #update} takes the tree, or null.
   */
  static El find(El root, Object identity) {
    return find(root, new Id(null, part(root, 0)), identity);
  }

  /**
   * Like {@link #find(El, Object)}, below {@code top}, whose identity is {@code topIdentity}: the
   * element there that has this identity, or null if it is not below {@code top}.
   */
  static El find(El top, Object topIdentity, Object identity) {
    Id id = (Id) identity;
    if (id.equals(topIdentity)) {
      return top;
    }
    if (id.parent() == null) {
      return null;
    }
    El parent = find(top, topIdentity, id.parent());
    if (parent == null) {
      return null;
    }
    if (id.part() instanceof Layer layer) {
      List<El> overlays = parent.overlays();
      for (int i = 0; i < overlays.size(); i++) {
        if (layer.part().equals(part(overlays.get(i), i))) {
          return overlays.get(i);
        }
      }
      return null;
    }
    List<El> children = parent.children(); // nothing is leaving yet
    if (parent.isVirtualList()) {
      int i = id.part() instanceof Integer row ? row - parent.firstRow() : -1;
      return i >= 0 && i < children.size() ? children.get(i) : null;
    }
    for (int i = 0; i < children.size(); i++) {
      if (id.part().equals(part(children.get(i), i))) {
        return children.get(i);
      }
    }
    return null;
  }

  /** Whether the last update or present left something short of its target. */
  boolean isMoving() {
    return !moving.isEmpty();
  }

  /**
   * Takes the rows a virtual list of the current tree has just built ({@link El#makeRows}): each
   * gets the entries of the row with the same index that the list had, so that what moves goes on
   * moving, and the entries of the other rows it had are forgotten. A row moves with its list at
   * once, without springing, since its place among the rows that exist changes as they do.
   */
  void rows(El list, Object identity, double now) {
    Id id = (Id) identity;
    Entry entry = entries.get(id);
    if (entry == null) {
      return; // not in the tree the last update took
    }
    Map<Id, Entry> before = new HashMap<>();
    for (int row = entry.rowsFrom; row < entry.rowsTo; row++) {
      Id rowId = new Id(id, row);
      Entry old = entries.get(rowId);
      if (old != null) {
        move(old.el, rowId, before);
      }
    }
    Map<Id, Entry> current = entries;
    entries = before; // where visit finds the previous version of each row
    Map<Id, Entry> next = new HashMap<>();
    Id[] ids = ids(list, id);
    for (int i = 0; i < ids.length; i++) {
      visit(list.children().get(i), ids[i], false, true, next, now);
    }
    entries = current;
    entries.putAll(next);
    entry.rowsFrom = list.firstRow();
    entry.rowsTo = list.firstRow() + ids.length;
    present(now);
  }

  /** Moves the entries of an element and of everything below it out of {@link #entries}. */
  private void move(El el, Id id, Map<Id, Entry> into) {
    Entry entry = entries.remove(id);
    if (entry == null) {
      return;
    }
    into.put(id, entry);
    Id[] ids = ids(el, id);
    for (int i = 0; i < ids.length; i++) {
      move(el.children().get(i), ids[i], into);
    }
    Id[] layers = layerIds(el, id);
    for (int i = 0; i < layers.length; i++) {
      move(el.overlays().get(i), layers[i], into);
    }
  }

  private void visit(El el, Id id, boolean enters, Map<Id, Entry> next, double now) {
    visit(el, id, enters, false, next, now);
  }

  /**
   * @param enters whether {@code el} fades in if it is new: its parent was already there
   * @param row whether {@code el} is a row of a virtual list, whose rectangle changes at once
   */
  private void visit(El el, Id id, boolean enters, boolean row, Map<Id, Entry> next, double now) {
    if (el.style().display == Style.Display.NONE) {
      return; // forgotten, so that it appears again without moving, like a new element
    }
    double[] target = new double[COUNT];
    target[0] = el.x();
    target[1] = el.y();
    target[2] = el.width();
    target[3] = el.height();
    target[RADIUS] = el.radius();
    channels(el.fill(), target, FILL);
    channels(el.borderColor(), target, BORDER);
    channels(el.text() == null ? 0 : el.text().color(), target, TEXT);
    target[OPACITY] = el.opacity();
    Entry entry = entries.get(id);
    El before = entry == null ? null : entry.el;
    Spring spring = el.motion();
    if (entry == null) {
      entry = new Entry(id, target, created++);
      if (spring != null && enters) {
        Track opacity = entry.tracks[OPACITY];
        opacity.set(0);
        opacity.retarget(target[OPACITY], spring, now);
      }
    } else {
      for (int i = 0; i < COUNT; i++) {
        Track track = entry.tracks[i];
        if (spring == null || row && i < RADIUS) {
          track.set(target[i]);
        } else if (target[i] != track.target()) {
          track.retarget(target[i], spring, now);
        }
      }
    }
    entry.el = el;
    el.measureScroll(); // from the layout, before the leaving children come back
    if (next.put(id, entry) != null) {
      throw new IllegalStateException("two siblings have the key '" + el.key() + "'");
    }
    if (!entry.isAtRest(now)) {
      moving.add(entry);
    }
    List<El> children = el.children();
    Id[] ids = ids(el, id);
    for (int i = 0; i < ids.length; i++) {
      visit(children.get(i), ids[i], before != null, next, now);
    }
    // Overlays are placed after layout, at once, like rows.
    Id[] layers = layerIds(el, id);
    for (int i = 0; i < layers.length; i++) {
      El overlay = el.overlays().get(i);
      visit(overlay, layers[i], before != null, true, next, now);
      Entry visited = next.get(layers[i]);
      overlay.placement().id = layers[i];
      overlay.placement().opened = visited == null ? 0 : visited.opened;
    }
    if (before != null) {
      keepLeavingOverlays(before, el, id, next, now);
    }
    if (el.isVirtualList()) {
      // Its rows come later; until then the rows it had keep their entries for them.
      for (int i = entry.rowsFrom; i < entry.rowsTo; i++) {
        Id rowId = new Id(id, i);
        Entry old = entries.get(rowId);
        if (old != null) {
          keep(old.el, rowId, next, now);
        }
      }
    } else {
      entry.rowsFrom = entry.rowsTo = 0;
      if (before != null) {
        keepLeaving(before, el, id, next, now);
      }
    }
    // Clamped to the new content; also shifts the leaving children, which are the old ones.
    el.scrollTo(before == null ? 0 : before.scrollX(), before == null ? 0 : before.scrollY());
    int reveal = el.revealRow();
    if (reveal >= 0 && (before == null || before.revealRow() != reveal)) {
      if (el.isVirtualList()) {
        if (reveal < el.rowCount()) {
          el.scrollTo(el.scrollX(), el.offsetShowing(reveal));
        }
      } else if (el.isScrollArea()) {
        el.revealChild(reveal);
      }
    }
  }

  /**
   * Puts the children of the previous version of {@code el} that leave now or are still leaving
   * among its new children: each after the sibling it followed, as far as that is still there.
   */
  private void keepLeaving(El before, El el, Id id, Map<Id, Entry> next, double now) {
    Map<El, List<El>> after = new IdentityHashMap<>(); // the key null stands for the start
    El anchor = null;
    Id[] ids = ids(before, id);
    for (int i = 0; i < ids.length; i++) {
      El child = before.children().get(i);
      Entry entry = next.get(ids[i]);
      if (entry != null) {
        anchor = entry.el;
        continue;
      }
      entry = entries.get(ids[i]);
      if (entry == null || child.key() == null || child.motion() == null) {
        continue; // gone at once
      }
      Track opacity = entry.tracks[OPACITY];
      if (!child.isExiting()) {
        child.exit();
        if (opacity.target() != 0) {
          opacity.retarget(0, child.motion(), now);
        }
      }
      if (!opacity.isAtRest(now)) {
        after.computeIfAbsent(anchor, k -> new ArrayList<>()).add(child);
        keep(child, ids[i], next, now);
      }
    }
    if (after.isEmpty()) {
      return;
    }
    List<El> children = el.children();
    List<El> merged = new ArrayList<>(after.getOrDefault(null, List.of()));
    for (El child : children) {
      merged.add(child);
      merged.addAll(after.getOrDefault(child, List.of()));
    }
    children.clear();
    children.addAll(merged);
  }

  /**
   * Puts the overlays of the previous version of {@code el} that leave now or are still leaving
   * back among its new overlays, after them.
   */
  private void keepLeavingOverlays(El before, El el, Id id, Map<Id, Entry> next, double now) {
    Id[] ids = layerIds(before, id);
    for (int i = 0; i < ids.length; i++) {
      El overlay = before.overlays().get(i);
      Entry entry = next.containsKey(ids[i]) ? null : entries.get(ids[i]);
      if (entry == null || overlay.key() == null || overlay.motion() == null) {
        continue; // still there, or gone at once
      }
      Track opacity = entry.tracks[OPACITY];
      if (!overlay.isExiting()) {
        overlay.exit();
        if (opacity.target() != 0) {
          opacity.retarget(0, overlay.motion(), now);
        }
      }
      if (!opacity.isAtRest(now)) {
        el.addOverlay(overlay);
        keep(overlay, ids[i], next, now);
      }
    }
  }

  /** Carries the entries of a leaving element and of everything below it into the new tree. */
  private void keep(El el, Id id, Map<Id, Entry> next, double now) {
    Entry entry = entries.get(id);
    if (entry == null) {
      return; // display: none
    }
    next.put(id, entry);
    if (!entry.isAtRest(now)) {
      moving.add(entry);
    }
    Id[] ids = ids(el, id);
    for (int i = 0; i < ids.length; i++) {
      keep(el.children().get(i), ids[i], next, now);
    }
    Id[] layers = layerIds(el, id);
    for (int i = 0; i < layers.length; i++) {
      keep(el.overlays().get(i), layers[i], next, now);
    }
  }

  /** Forgets an element that has faded out and everything below it. */
  private void forget(El el, Id id) {
    entries.remove(id);
    Id[] ids = ids(el, id);
    for (int i = 0; i < ids.length; i++) {
      forget(el.children().get(i), ids[i]);
    }
    Id[] layers = layerIds(el, id);
    for (int i = 0; i < layers.length; i++) {
      forget(el.overlays().get(i), layers[i]);
    }
  }

  /**
   * The identities of the children of {@code el}. Leaving children were put back into the list the
   * view built, so they do not count in the positions of the others.
   */
  private static Id[] ids(El el, Id id) {
    List<El> children = el.children();
    Id[] ids = new Id[children.size()];
    int index = 0;
    for (int i = 0; i < ids.length; i++) {
      El child = children.get(i);
      ids[i] = new Id(id, el.isVirtualList() ? Integer.valueOf(el.firstRow() + i)
          : child.isExiting() ? child.key() : part(child, index++));
    }
    return ids;
  }

  /** The identities of the overlays of {@code el}, counted apart from its children. */
  private static Id[] layerIds(El el, Id id) {
    List<El> overlays = el.overlays();
    Id[] ids = new Id[overlays.size()];
    int index = 0;
    for (int i = 0; i < ids.length; i++) {
      El overlay = overlays.get(i);
      ids[i] = new Id(id, new Layer(overlay.isExiting() ? overlay.key() : part(overlay, index++)));
    }
    return ids;
  }

  /**
   * The overlays of a tree the view has just built, with their identities, in tree order: those
   * attached to its elements and to elements inside overlays, but not those in the rows of virtual
   * lists, which are built later.
   */
  static Map<Object, El> overlays(El root) {
    Map<Object, El> found = new LinkedHashMap<>();
    overlays(root, new Id(null, part(root, 0)), found);
    return found;
  }

  private static void overlays(El el, Id id, Map<Object, El> found) {
    Id[] ids = ids(el, id);
    for (int i = 0; i < ids.length; i++) {
      overlays(el.children().get(i), ids[i], found);
    }
    Id[] layers = layerIds(el, id);
    for (int i = 0; i < layers.length; i++) {
      found.put(layers[i], el.overlays().get(i));
      overlays(el.overlays().get(i), layers[i], found);
    }
  }

  /**
   * The identity of the first element at or below {@code top} of a tree the view has just built
   * that takes the focus, in tree order, without its overlays; null if none does. Elements at
   * opacity 0 and with {@code display: none} hide what is below them.
   */
  static Object firstFocusable(El top, Object topIdentity) {
    if (top.opacity() == 0 || top.style().display == Style.Display.NONE) {
      return null;
    }
    if (top.isFocusable()) {
      return topIdentity;
    }
    Id[] ids = ids(top, (Id) topIdentity);
    for (int i = 0; i < ids.length; i++) {
      Object found = firstFocusable(top.children().get(i), ids[i]);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  private static Object part(El el, int index) {
    return el.key() != null ? el.key() : Integer.valueOf(index);
  }

  private static void channels(int argb, double[] into, int at) {
    for (int i = 0; i < 4; i++) {
      into[at + i] = (argb >>> (24 - 8 * i)) & 0xFF;
    }
  }

  /** The tracks of one element, and the element of the current tree that shows them. */
  private static final class Entry {

    final Id id;
    final long opened; // how many entries were made before this one
    final Track[] tracks = new Track[COUNT];
    El el;
    // For a virtual list: the rows that have entries, from rowsFrom to rowsTo, exclusive.
    int rowsFrom;
    int rowsTo;

    Entry(Id id, double[] values, long opened) {
      this.id = id;
      this.opened = opened;
      for (int i = 0; i < COUNT; i++) {
        double tolerance = i < FILL ? PIXELS : i < OPACITY ? CHANNELS : CHANNELS / 255;
        tracks[i] = new Track(values[i], tolerance);
      }
    }

    boolean isAtRest(double now) {
      for (Track track : tracks) {
        if (!track.isAtRest(now)) {
          return false;
        }
      }
      return true;
    }

    void present(double now) {
      el.place(at(0, now), at(1, now), at(2, now), at(3, now));
      el.radius(at(RADIUS, now));
      el.fill(colour(FILL, now));
      el.borderColor(colour(BORDER, now));
      if (el.text() != null) {
        el.text().color(colour(TEXT, now));
      }
      el.shownOpacity((float) Math.clamp(tracks[OPACITY].value(now), 0.0, 1.0)); // a bounce overshoots
    }

    private float at(int index, double now) {
      return (float) tracks[index].value(now);
    }

    // shortcut: each channel of 0xAARRGGBB springs on its own and is clamped to 0..255, so a
    // bounce cannot leave the colour range, but it still overshoots inside it and tints the colour,
    // and a fade from or to a transparent colour passes through a darker one. Spring premultiplied
    // colours in a perceptual space such as Oklab when that shows.
    private int colour(int first, double now) {
      int argb = 0;
      for (int i = first; i < first + 4; i++) {
        argb = argb << 8 | Math.clamp(Math.round(tracks[i].value(now)), 0, 255);
      }
      return argb;
    }
  }
}
