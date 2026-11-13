package com.rethinkj.ui;

import com.rethinkj.ui.motion.Spring;
import com.rethinkj.ui.motion.Track;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What {@link App} keeps of each element between rebuilds, so that elements with {@link
 * El#motion} spring from the values they showed to the ones the new tree gives them.
 *
 * <p>An element is the same as one in the previous tree when it has the same identity: its
 * parent's identity plus its key or, without a key, its index among its siblings. Every element
 * has one {@link Track} per animated number. Layout runs once, for the targets; the tracks then
 * write the values of the moment into the tree, so that everything reading the tree sees what is
 * on screen.
 */
final class Animator {

  // The animated numbers: x, y, width, height and radius in pixels, then the fill and the border
  // colour as alpha, red, green and blue.
  private static final int RADIUS = 4, FILL = 5, BORDER = 9, COUNT = 13;

  // A movement ends this near its target, then jumps onto it: a hundredth of a pixel, and a
  // quarter of a colour step, which rounds to the target.
  private static final double PIXELS = 0.01, CHANNELS = 0.25;

  /** The part is the element's key or, when it has none, its index as an Integer. */
  private record Id(Id parent, Object part) {}

  // shortcut: every element keeps thirteen tracks, also without motion, so that one that gains
  // motion moves from where it was; keep plain values for those when trees reach many thousands.
  private Map<Id, Entry> entries = new HashMap<>();
  private final List<Entry> moving = new ArrayList<>();

  /** Takes a tree that layout has just placed: its values become the targets at {@code now}. */
  void update(El root, double now) {
    Map<Id, Entry> next = new HashMap<>();
    moving.clear();
    visit(root, new Id(null, part(root, 0)), next, now);
    entries = next; // drops the elements that are gone
    present(now);
  }

  /** Writes the values at {@code now} into the elements that were moving. */
  void present(double now) {
    moving.removeIf(entry -> {
      entry.present(now);
      return entry.isAtRest(now);
    });
  }

  /** Whether the last update or present left something short of its target. */
  boolean isMoving() {
    return !moving.isEmpty();
  }

  private void visit(El el, Id id, Map<Id, Entry> next, double now) {
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
    Entry entry = entries.get(id);
    if (entry == null) {
      entry = new Entry(target);
    } else {
      Spring spring = el.motion();
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
    if (next.put(id, entry) != null) {
      throw new IllegalStateException("two siblings have the key '" + el.key() + "'");
    }
    if (!entry.isAtRest(now)) {
      moving.add(entry);
    }
    List<El> children = el.children();
    for (int i = 0; i < children.size(); i++) {
      visit(children.get(i), new Id(id, part(children.get(i), i)), next, now);
    }
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

    final Track[] tracks = new Track[COUNT];
    El el;

    Entry(double[] values) {
      for (int i = 0; i < COUNT; i++) {
        tracks[i] = new Track(values[i], i < FILL ? PIXELS : CHANNELS);
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
