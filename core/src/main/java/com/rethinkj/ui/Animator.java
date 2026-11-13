package com.rethinkj.ui;

import com.rethinkj.ui.motion.Spring;
import com.rethinkj.ui.motion.Track;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

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
 */
final class Animator {

  // The animated numbers: x, y, width, height and radius in pixels, then the fill and the border
  // colour as alpha, red, green and blue, then the opacity.
  private static final int RADIUS = 4, FILL = 5, BORDER = 9, OPACITY = 13, COUNT = 14;

  // A movement ends this near its target, then jumps onto it: a hundredth of a pixel, and a
  // quarter of a colour step, which rounds to the target; for opacity, a quarter of 1/255.
  private static final double PIXELS = 0.01, CHANNELS = 0.25;

  /** The part is the element's key or, when it has none, its index as an Integer. */
  private record Id(Id parent, Object part) {}

  // shortcut: every element keeps fourteen tracks, also without motion, so that one that gains
  // motion moves from where it was; keep plain values for those when trees reach many thousands.
  private Map<Id, Entry> entries = new HashMap<>();
  private final List<Entry> moving = new ArrayList<>();
  private boolean started;

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
        entries.get(entry.id.parent()).el.children().remove(entry.el);
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

  /** Whether the last update or present left something short of its target. */
  boolean isMoving() {
    return !moving.isEmpty();
  }

  /** @param enters whether {@code el} fades in if it is new: its parent was already there */
  private void visit(El el, Id id, boolean enters, Map<Id, Entry> next, double now) {
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
    target[OPACITY] = el.opacity();
    Entry entry = entries.get(id);
    El before = entry == null ? null : entry.el;
    Spring spring = el.motion();
    if (entry == null) {
      entry = new Entry(id, target);
      if (spring != null && enters) {
        Track opacity = entry.tracks[OPACITY];
        opacity.set(0);
        opacity.retarget(target[OPACITY], spring, now);
      }
    } else {
      for (int i = 0; i < COUNT; i++) {
        Track track = entry.tracks[i];
        if (spring == null) {
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
    if (before != null) {
      keepLeaving(before, el, id, next, now);
    }
    // Clamped to the new content; also shifts the leaving children, which are the old ones.
    el.scrollTo(before == null ? 0 : before.scrollX(), before == null ? 0 : before.scrollY());
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
  }

  /** Forgets an element that has faded out and everything below it. */
  private void forget(El el, Id id) {
    entries.remove(id);
    Id[] ids = ids(el, id);
    for (int i = 0; i < ids.length; i++) {
      forget(el.children().get(i), ids[i]);
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
      ids[i] = new Id(id, child.isExiting() ? child.key() : part(child, index++));
    }
    return ids;
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
    final Track[] tracks = new Track[COUNT];
    El el;

    Entry(Id id, double[] values) {
      this.id = id;
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
      el.opacity((float) Math.clamp(tracks[OPACITY].value(now), 0.0, 1.0)); // a bounce overshoots
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
