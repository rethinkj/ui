package com.rethinkj.ui;

import com.rethinkj.ui.motion.Spring;
import com.rethinkj.ui.motion.Track;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What {@link App} keeps of each element between rebuilds, so that elements with {@link
 * El#motion} spring from the values they showed to the ones the new tree gives them, and scroll
 * areas keep their offset.
 *
 * <p>An element is the same as one in the previous tree when it has the same identity: its
 * parent's identity plus its key or, without a key, its index among its siblings. An element with
 * {@link El#motion} has one {@link Track} per animated number; one without keeps none, and gets
 * tracks starting from what it last showed if a rebuild gives it motion. Layout runs once, for the
 * targets; the tracks then write the values of the moment into the tree, so that everything
 * reading the tree sees what is on screen.
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

  // The animated numbers: x, y, width, height and the four corners' radii in pixels, then the
  // fill, the border and the text color as alpha and premultiplied red, green and blue, then the
  // opacity and the sweep of an arc.
  private static final int RADIUS = 4;
  private static final int FILL = 8;
  private static final int BORDER = 12;
  private static final int TEXT = 16;
  private static final int OPACITY = 20;
  private static final int SWEEP = 21;
  private static final int COUNT = 22;

  // A movement ends this near its target, then jumps onto it: a hundredth of a pixel, and a
  // quarter of a color step, which rounds to the target; for opacity, a quarter of 1/255; for a
  // sweep, a ten-thousandth of a turn, a thirtieth of a pixel on a ring a hundred pixels across.
  private static final double PIXELS = 0.01;
  private static final double CHANNELS = 0.25;
  private static final double TURNS = 1e-4;

  /**
   * The part is the element's key or, when it has none, its index as an Integer; for a row of a
   * virtual list, the row's index.
   */
  private record Id(Id parent, Object part, int hash) {
    Id(Id parent, Object part) { // the hash once: a record's would walk all the parents each time
      this(parent, part, 31 * (parent == null ? 0 : parent.hash) + part.hashCode());
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Id id
          && hash == id.hash
          && part.equals(id.part)
          && Objects.equals(parent, id.parent);
    }

    @Override
    public int hashCode() {
      return hash;
    }
  }

  /** The part of an overlay's identity: its key or index among its anchor's overlays. */
  private record Layer(Object part) {}

  /** Where an element is in a virtual list: the list, its identity and the index of the row. */
  record InRow(El list, Object listId, int row) {}

  private Map<Id, Entry> entries = new HashMap<>();
  private final Set<Entry> moving = new LinkedHashSet<>();
  private final List<ScrollState> pendingFollows = new ArrayList<>();
  private List<Object> revealed; // while tracking key-driven reveals of existing scroll areas
  private boolean visiting; // in visit, where a coupled area's partner may not have its offset yet
  private boolean started;
  private long created; // entries made so far, which orders overlays by when they opened
  private long passes; // updates so far, to find two scroll areas with one ScrollState

  /**
   * Takes a tree that layout has just placed: its values become the targets at {@code now}, and
   * the elements that are leaving are put back into it. When {@code revealed} is not null, the
   * identity of every scroll area that was in the tree before and whose offset changed by {@link
   * El#revealRow} or {@link El#reveal} is added to it.
   */
  void update(El root, double now, List<Object> revealed) {
    El.measureScrolls(root); // before the offsets are clamped to what it finds
    Map<Id, Entry> next = new HashMap<>();
    moving.clear();
    pendingFollows.clear();
    passes++;
    visiting = true;
    this.revealed = revealed;
    try {
      visit(root, new Id(null, part(root, 0)), started, next, now);
    } finally {
      this.revealed = null;
    }
    entries = next; // drops the elements that are gone
    runPendingFollows();
    started = true;
    present(now);
  }

  /**
   * Whether a coupled area that scrolled while the tree is visited, as by {@link El#reveal}, moves
   * its partner after the visit, when the partner has taken its own kept offset.
   */
  boolean defer(ScrollState state) {
    if (visiting && !pendingFollows.contains(state)) {
      pendingFollows.add(state);
    }
    return visiting;
  }

  private void runPendingFollows() {
    visiting = false;
    for (ScrollState state : pendingFollows) {
      state.follow(state.el, false, false); // along the axes it moved
    }
    pendingFollows.clear();
  }

  /**
   * Writes the values at {@code now} into the elements that were moving, and takes those that
   * have faded out out of the tree.
   */
  void present(double now) {
    moving.removeIf(entry -> {
      if (entries.get(entry.id) != entry || entry.tracks == null) {
        return true; // forgotten with an element above it that has faded out, or dropped motion
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

  /** Whether {@code el} was in the tree before the last update. */
  boolean wasSeen(El el) {
    Object id = identity(el);
    Entry entry = id == null ? null : entries.get(id);
    return entry != null && entry.seen;
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
   * The element of the current tree that has this {@link #identity}, or null if it is not there or
   * it or an ancestor is leaving.
   */
  El liveElement(Object identity) {
    Entry leaf = entries.get(identity);
    if (leaf == null || leaf.el.isExiting()) {
      return null;
    }
    for (Id id = leaf.id.parent(); id != null; id = id.parent()) {
      Entry entry = entries.get(id);
      if (entry == null || entry.el.isExiting()) {
        return null;
      }
    }
    return leaf.el;
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
      El parent = i > 0 ? path.get(i - 1) : null;
      if (el.fieldPartKey() != null) {
        id = new Id(id, el.fieldPartKey());
        ids.add(id);
        continue;
      }
      int index = 0;
      if (parent != null) {
        for (El sibling : parent.children()) {
          if (sibling == el) {
            break;
          }
          if (!sibling.isExiting() && sibling.fieldPartKey() == null) {
            index++;
          }
        }
      }
      id = new Id(
          id,
          parent != null && parent.isVirtualList()
              ? Integer.valueOf(parent.firstRow() + index)
              : part(el, index));
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

  /**
   * The index of the row of the virtual list with identity {@code list} that the element with this
   * identity is or is in, or -1 if it is in none of its rows.
   */
  static int rowIn(Object identity, Object list) {
    for (Id id = (Id) identity; id != null; id = id.parent()) {
      if (id.part() instanceof Integer row && list.equals(id.parent())) {
        return row;
      }
    }
    return -1;
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

  /** Whether {@code identity} is a post-layout part of {@code field} or inside one. */
  static boolean isInFieldPart(Object identity, Object field) {
    for (Id id = (Id) identity; id != null && id.parent() != null; id = id.parent()) {
      if (id.part() instanceof El.FieldPart && field.equals(id.parent())) {
        return true;
      }
    }
    return false;
  }

  /** Whether {@code identity} is a post-layout part of any field or inside one. */
  static boolean inFieldPart(Object identity) {
    for (Id id = (Id) identity; id != null; id = id.parent()) {
      if (id.part() instanceof El.FieldPart) {
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
    if (id.part() instanceof El.FieldPart) {
      for (El child : children) {
        if (id.part().equals(child.fieldPartKey())) {
          return child;
        }
      }
      return null;
    }
    int index = 0;
    for (El child : children) {
      if (child.fieldPartKey() != null) {
        continue;
      }
      // As ids numbers them: in a field part of the current tree, one may be leaving.
      if (id.part().equals(child.isExiting() ? child.key() : part(child, index++))) {
        return child;
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
   * once, without springing, since its place among the rows that exist changes as they do. A row
   * the list kept keeps its entries, and only its place changes.
   */
  void rows(El list, Object identity, double now) {
    Id id = (Id) identity;
    Entry entry = entries.get(id);
    if (entry == null) {
      return; // not in the tree the last update took
    }
    Map<Id, Entry> before = new HashMap<>();
    for (int row = entry.rowsFrom; row < entry.rowsTo; row++) {
      if (list.hasRow(row) && list.children().get(row - list.firstRow()).isKept()) {
        continue;
      }
      Id rowId = new Id(id, row);
      Entry old = entries.get(rowId);
      if (old != null) {
        move(old.el, rowId, before);
      }
    }
    Map<Id, Entry> current = entries;
    entries = before; // where visit finds the previous version of each row
    visiting = true;
    Map<Id, Entry> next = new HashMap<>();
    Id[] ids = ids(list, id);
    for (int i = 0; i < ids.length; i++) {
      El row = list.children().get(i);
      Entry kept = row.isKept() ? current.get(ids[i]) : null;
      if (kept == null) {
        visit(row, ids[i], false, true, next, now);
      } else { // at once, as visit moves a row; what is inside it stays where it was
        kept.x = row.placedX();
        kept.y = row.placedY();
        kept.width = row.width();
        kept.height = row.height();
        if (kept.tracks != null) {
          kept.tracks[0].set(row.placedX());
          kept.tracks[1].set(row.placedY());
          kept.tracks[2].set(row.width());
          kept.tracks[3].set(row.height());
        }
      }
    }
    entries = current;
    entries.putAll(next);
    runPendingFollows();
    entry.rowsFrom = list.firstRow();
    entry.rowsTo = list.firstRow() + ids.length;
    present(now);
  }

  /**
   * Takes the post-layout parts a multi-line field of the current tree has built (gutter, fold
   * marks, line-end elements, line blocks and inline hints): each gets the entries of the part
   * with the same key that the field had, and entries of parts that are gone are forgotten. A part
   * moves with its field at once, without springing. Parts kept unchanged from an earlier pass
   * keep their entries, and only their placed rectangle updates.
   */
  void fieldParts(El field, Object identity, Set<El> relooked, double now) {
    Id id = (Id) identity;
    Entry entry = entries.get(id);
    if (entry == null) {
      return; // not in the tree the last update took
    }
    Set<El> keptParts = Collections.newSetFromMap(new IdentityHashMap<>());
    for (El child : field.children()) {
      El.FieldPart key = child.fieldPartKey();
      if (key == null || relooked.contains(child)) {
        continue;
      }
      Entry old = entries.get(new Id(id, key));
      if (old != null && old.el == child) {
        keptParts.add(child);
      }
    }
    Map<Id, Entry> before = new HashMap<>();
    for (Object key : entry.partKeys) {
      Id partId = new Id(id, key);
      Entry old = entries.get(partId);
      if (old != null && !keptParts.contains(old.el)) {
        move(old.el, partId, before);
      }
    }
    Map<Id, Entry> current = entries;
    entries = before; // where visit finds the previous version of each part
    visiting = true;
    Map<Id, Entry> next = new HashMap<>();
    List<Object> keys = new ArrayList<>();
    for (El child : field.children()) {
      El.FieldPart key = child.fieldPartKey();
      if (key == null) {
        continue;
      }
      Id partId = new Id(id, key);
      keys.add(key);
      if (keptParts.contains(child)) {
        Entry kept = current.get(partId);
        kept.x = child.placedX();
        kept.y = child.placedY();
        kept.width = child.width();
        kept.height = child.height();
        if (kept.tracks != null) {
          kept.tracks[0].set(child.placedX());
          kept.tracks[1].set(child.placedY());
          kept.tracks[2].set(child.width());
          kept.tracks[3].set(child.height());
        }
      } else {
        visit(child, partId, false, true, next, now);
      }
    }
    entries = current;
    entries.putAll(next);
    runPendingFollows();
    entry.partKeys = keys.isEmpty() ? List.of() : List.copyOf(keys);
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
    if (el.isExiting()) {
      keep(el, id, next, now);
      return;
    }
    if (el.style().display == Style.Display.NONE) {
      return; // forgotten, so that it appears again without moving, like a new element
    }
    Entry entry = entries.get(id);
    El before = entry == null ? null : entry.el;
    Spring spring = el.motion();
    if (el.cycle() != null) { // its phase goes on while it keeps its cycle
      el.cycleSince = before != null && before.cycle() != null ? before.cycleSince : now;
    }
    if (entry == null) {
      entry = new Entry(id, created++);
      if (spring != null) {
        entry.resetTracks(el, false);
        if (enters) {
          Track opacity = entry.tracks[OPACITY];
          opacity.set(0);
          opacity.retarget(el.opacity(), spring, now);
        }
      }
    } else if (spring == null) {
      entry.tracks = null;
    } else {
      if (before.cycle() != null && el.cycle() == null) {
        entry.resetTracks(before, false); // what the cycle's look showed last, to spring from
      } else if (entry.tracks == null) {
        entry.resetTracks(before, true); // what it showed without motion, to spring from
      }
      for (int i = 0; i < COUNT; i++) {
        Track track = entry.tracks[i];
        double target = value(el, i);
        if (row && i < RADIUS) {
          track.set(target);
        } else if (target != track.target()) {
          track.retarget(target, spring, now);
        }
      }
    }
    entry.el = el;
    entry.take(el);
    el.measureScroll(); // from the layout, before the leaving children come back
    if (next.put(id, entry) != null) {
      throw new IllegalStateException("two siblings have the key '" + el.key()
          + "'; give one of them another with key(...), as the second of two dialogs open at once");
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
      if (el.hasFieldParts()) {
        // Its parts come later; until then the parts it had keep their entries for them.
        for (Object key : entry.partKeys) {
          Id partId = new Id(id, key);
          Entry old = entries.get(partId);
          if (old != null) {
            keep(old.el, partId, next, now);
          }
        }
      } else {
        entry.partKeys = List.of();
      }
    }
    // Clamped to the new content; also shifts the leaving children, which are the old ones.
    ScrollState kept = el.scrollState();
    boolean seen = before != null;
    int revealed = before == null ? -1 : before.revealRow();
    Object revealedKey = before == null ? null : before.revealKey();
    if (kept == null) {
      el.setScroll(
          before == null ? 0 : before.scrollX(),
          before == null ? 0 : el.movedScrollY(before, before.scrollY()));
    } else {
      if (kept.owner == this && kept.pass == passes && !kept.id.equals(id)) {
        throw new IllegalStateException("Two scroll areas have the same ScrollState. Give each"
            + " area a ScrollState of its own, kept in the app's state with what it shows.");
      }
      if (before == null && kept.seen) { // back in the tree, or in another app's
        seen = true;
        revealed = kept.revealRow;
        revealedKey = kept.revealKey;
      }
      boolean carried = before != null && kept.el == before; // not a new state's own offset
      kept.owner = this;
      kept.pass = passes;
      kept.id = id;
      kept.el = el;
      kept.seen = true;
      boolean end = kept.toEnd || kept.followEnd && kept.atEnd;
      double y = end
          ? Double.POSITIVE_INFINITY
          : kept.placed || !carried ? kept.y : el.movedScrollY(before, kept.y);
      kept.placed = false;
      el.setScroll(kept.x, y);
      if (kept.followPending) { // the app's ScrollState.scrollTo moves the partner after the visit
        kept.followPending = false;
        kept.follow(el, false, false);
      }
    }
    entry.seen = before != null; // not a ScrollState's area back in the tree
    double beforeX = el.scrollX();
    double beforeY = el.scrollY();
    int reveal = el.revealRow();
    if (reveal >= 0 && (!seen || revealed != reveal)) {
      if (el.isVirtualList()) {
        if (reveal < el.rowCount()) {
          el.scrollTo(el.scrollX(), el.offsetShowing(reveal));
        }
      } else if (el.isScrollArea()) {
        el.revealChild(reveal);
      }
    }
    Object key = el.revealKey();
    if (key != null && el.isScrollArea() && (!seen || !key.equals(revealedKey))) {
      el.revealArea();
    }
    if (this.revealed != null
        && before != null
        && (el.scrollX() != beforeX || el.scrollY() != beforeY)) {
      this.revealed.add(id);
    }
    if (kept != null) {
      kept.revealRow = reveal;
      kept.revealKey = key;
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
      if (child.fieldPartKey() != null) {
        continue; // field parts are tracked by fieldParts, not as leaving view children
      }
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
      ids[i] = new Id(
          id,
          child.fieldPartKey() != null
              ? child.fieldPartKey()
              : el.isVirtualList()
                  ? Integer.valueOf(el.firstRow() + i)
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
   * lists, which are built later, nor those the view makes invisible, with opacity 0 or {@code
   * display: none} on them or above them; and the elements that {@link El#holdFocus hold the
   * focus}, each before the overlays inside it.
   */
  static Map<Object, El> overlays(El root) {
    Map<Object, El> found = new LinkedHashMap<>();
    overlays(root, new Id(null, part(root, 0)), found);
    return found;
  }

  private static void overlays(El el, Id id, Map<Object, El> found) {
    if (el.isExiting() || el.targetOpacity() == 0 || el.style().display == Style.Display.NONE) {
      return; // what cannot show opens nothing and takes no focus
    }
    if (el.holdsFocus()) {
      found.put(id, el); // holds the focus as an open overlay does, before those inside it
    }
    Id[] ids = ids(el, id);
    for (int i = 0; i < ids.length; i++) {
      overlays(el.children().get(i), ids[i], found);
    }
    Id[] layers = layerIds(el, id);
    for (int i = 0; i < layers.length; i++) {
      El overlay = el.overlays().get(i);
      if (!overlay.isExiting()
          && overlay.targetOpacity() != 0
          && overlay.style().display != Style.Display.NONE) {
        found.put(layers[i], overlay);
      }
      overlays(overlay, layers[i], found);
    }
  }

  /**
   * Like {@link #overlays(El)}, for overlays and {@link El#holdFocus focus holders} inside the
   * rows of virtual lists and the post-layout parts of multi-line fields, built after layout.
   */
  static Map<Object, El> partOverlays(El root) {
    Map<Object, El> found = new LinkedHashMap<>();
    partOverlays(root, new ArrayList<>(), false, found);
    return found;
  }

  private static void partOverlays(El el, List<El> path, boolean inPart, Map<Object, El> found) {
    if (el.isExiting() || el.targetOpacity() == 0 || el.style().display == Style.Display.NONE) {
      return;
    }
    boolean inside = inPart || el.fieldPartKey() != null;
    path.add(el);
    if (inside && el.holdsFocus()) {
      found.put(identities(path).getLast(), el);
    }
    boolean childInPart = inside || el.isVirtualList();
    for (El child : el.children()) {
      partOverlays(child, path, childInPart, found);
    }
    for (El overlay : el.overlays()) {
      if (!overlay.isExiting()
          && overlay.targetOpacity() != 0
          && overlay.style().display != Style.Display.NONE) {
        if (inside) {
          path.add(overlay);
          found.put(identities(path).getLast(), overlay);
          path.removeLast();
        }
        partOverlays(overlay, path, inside, found);
      }
    }
    path.removeLast();
  }

  /**
   * The identity of the element at or below {@code top} of a tree the view has just built that an
   * opening there focuses, the first in tree order that takes the focus and, of these, the first
   * that is {@link El#autofocus}, else {@link El#defaultFocus}, else one that Tab reaches (not
   * {@link El#skipTab}), else any; without its overlays; null if none takes the focus. Elements
   * at opacity 0 and with {@code display: none} hide what is below them.
   */
  static Object firstFocusable(El top, Object topIdentity) {
    for (Predicate<El> wanted : FIRST) {
      Id found = first(top, (Id) topIdentity, wanted);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  private static final List<Predicate<El>> FIRST = List.of(
      el -> el.autofocusKey() != null, El::isDefaultFocus, el -> !el.skipsTab(), el -> true);

  private static Id first(El top, Id id, Predicate<El> wanted) {
    // By the opacity the view set: an overlay of a row or part opens after motion began its fade.
    if (top.isInert()
        || top.isExiting()
        || top.targetOpacity() == 0
        || top.style().display == Style.Display.NONE) {
      return null;
    }
    if (top.isFocusable() && wanted.test(top)) {
      return id;
    }
    Id[] ids = ids(top, id);
    for (int i = 0; i < ids.length; i++) {
      Id found = first(top.children().get(i), ids[i], wanted);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  /**
   * The identities of the elements of a tree the view has just built, before {@link #update}
   * takes it, whose {@link El#autofocus} asks for the focus now, in tree order: each is new, or
   * its key differs from that of the element with its identity in the current tree. The first
   * that can take the focus once the tree is laid out gets it. Overlays that are new are left out,
   * as their opening decides, and so are elements at opacity 0 and with {@code display: none}.
   */
  List<Object> autofocus(El root) {
    List<Object> found = new ArrayList<>();
    autofocus(root, new Id(null, part(root, 0)), found);
    return found;
  }

  /**
   * The elements in the rows of the list {@code list}, with the identity {@code listId}, that ask
   * for the focus with {@link El#autofocus} as {@link #autofocus(El)} finds them: the rows are
   * built after layout, when that has looked at the rest of the tree.
   */
  List<Object> autofocusInRows(El list, Object listId) {
    List<Object> found = new ArrayList<>();
    Id[] ids = ids(list, (Id) listId);
    for (int i = 0; i < ids.length; i++) {
      autofocus(list.children().get(i), ids[i], found);
    }
    return found;
  }

  /**
   * The elements in the post-layout parts of {@code field}, with identity {@code fieldId}, that
   * ask for the focus with {@link El#autofocus}.
   */
  List<Object> autofocusInFieldParts(El field, Object fieldId) {
    List<Object> found = new ArrayList<>();
    Id id = (Id) fieldId;
    for (El child : field.children()) {
      El.FieldPart key = child.fieldPartKey();
      if (key != null) {
        autofocus(child, new Id(id, key), found);
      }
    }
    return found;
  }

  private void autofocus(El el, Id id, List<Object> found) {
    if (el.hidesFocus()) {
      return;
    }
    Object key = el.autofocusKey();
    if (key != null && el.isFocusable()) {
      Entry entry = entries.get(id);
      if (entry == null || entry.el.isExiting() || !key.equals(entry.el.autofocusKey())) {
        found.add(id);
      }
    }
    Id[] ids = ids(el, id);
    for (int i = 0; i < ids.length; i++) {
      autofocus(el.children().get(i), ids[i], found);
    }
    Id[] layers = layerIds(el, id);
    for (int i = 0; i < layers.length; i++) {
      if (entries.containsKey(layers[i])) {
        autofocus(el.overlays().get(i), layers[i], found);
      }
    }
  }

  private static Object part(El el, int index) {
    return el.key() != null ? el.key() : Integer.valueOf(index);
  }

  /** One animated number of an element as it is now. */
  private static double value(El el, int index) {
    return switch (index) {
      case 0 -> el.placedX();
      case 1 -> el.placedY();
      case 2 -> el.width();
      case 3 -> el.height();
      case RADIUS, RADIUS + 1, RADIUS + 2, RADIUS + 3 -> el.cornerRadius(index - RADIUS);
      case FILL, FILL + 1, FILL + 2, FILL + 3 -> channel(el.fill(), index - FILL);
      case BORDER, BORDER + 1, BORDER + 2, BORDER + 3 -> channel(el.borderColor(), index - BORDER);
      case TEXT, TEXT + 1, TEXT + 2, TEXT + 3 ->
        channel(el.text() == null ? 0 : el.text().color(), index - TEXT);
      case OPACITY -> el.opacity();
      case SWEEP -> el.arcSweep();
      default -> throw new IndexOutOfBoundsException(index);
    };
  }

  /** Channel {@code index} of {@code argb}: alpha, then premultiplied red, green and blue. */
  private static double channel(int argb, int index) {
    int alpha = argb >>> 24;
    return index == 0 ? alpha : ((argb >>> (24 - 8 * index)) & 0xFF) * alpha / 255.0;
  }

  /** The tracks of one element, and the element of the current tree that shows them. */
  private static final class Entry {

    final Id id;
    final long opened; // how many entries were made before this one
    Track[] tracks; // null while el has no motion
    El el;
    // What the last visit found of el, which the scroll offset of its parent, the placing of
    // overlays and the look of a cycle change later; an element that gains motion starts here.
    float x;
    float y;
    float width;
    float height;
    float opacity;
    // The view's target colors, kept intact when their tracks rest.
    int fill;
    int border;
    int text;
    // For a virtual list: the rows that have entries, from rowsFrom to rowsTo, exclusive.
    int rowsFrom;
    int rowsTo;
    // For a multi-line field: the post-layout field part keys that have entries.
    List<Object> partKeys = List.of();
    boolean seen; // whether this element was in the tree before the last update

    Entry(Id id, long opened) {
      this.id = id;
      this.opened = opened;
    }

    /**
     * Puts the tracks at rest on the values of {@code from}, as it is now or, with {@code
     * laidOut}, as the last visit found it.
     */
    void resetTracks(El from, boolean laidOut) {
      if (tracks == null) {
        tracks = new Track[COUNT];
      }
      for (int i = 0; i < COUNT; i++) {
        double value = laidOut ? laidOut(from, i) : value(from, i);
        if (tracks[i] == null) {
          double tolerance =
              i < FILL ? PIXELS : i < OPACITY ? CHANNELS : i == OPACITY ? CHANNELS / 255 : TURNS;
          tracks[i] = new Track(value, tolerance);
        } else {
          tracks[i].set(value);
        }
      }
    }

    /** Keeps what a visit finds of {@code el}. */
    void take(El el) {
      x = el.placedX();
      y = el.placedY();
      width = el.width();
      height = el.height();
      opacity = el.opacity();
      fill = el.fill();
      border = el.borderColor();
      text = el.text() == null ? 0 : el.text().color();
    }

    /** Like {@link #value}, as the last visit found {@code el}. */
    private double laidOut(El el, int index) {
      return switch (index) {
        case 0 -> x;
        case 1 -> y;
        case 2 -> width;
        case 3 -> height;
        case FILL, FILL + 1, FILL + 2, FILL + 3 -> channel(fill, index - FILL);
        case BORDER, BORDER + 1, BORDER + 2, BORDER + 3 -> channel(border, index - BORDER);
        case TEXT, TEXT + 1, TEXT + 2, TEXT + 3 -> channel(text, index - TEXT);
        case OPACITY -> opacity;
        default -> value(el, index);
      };
    }

    boolean isAtRest(double now) {
      if (tracks == null) {
        return true;
      }
      for (Track track : tracks) {
        if (!track.isAtRest(now)) {
          return false;
        }
      }
      return true;
    }

    void present(double now) {
      el.place(at(0, now), at(1, now), at(2, now), at(3, now));
      el.moving(!(tracks[0].isAtRest(now)
          && tracks[1].isAtRest(now)
          && tracks[2].isAtRest(now)
          && tracks[3].isAtRest(now)));
      el.radius(at(RADIUS, now), at(RADIUS + 1, now), at(RADIUS + 2, now), at(RADIUS + 3, now));
      el.fill(color(FILL, fill, now));
      el.borderColor(color(BORDER, border, now));
      if (el.text() != null) {
        el.text().shownColor(color(TEXT, text, now));
      }
      el.shownOpacity(
          (float) Math.clamp(tracks[OPACITY].value(now), 0.0, 1.0)); // a bounce overshoots
      el.arc(el.arcStart(), (float) Math.clamp(tracks[SWEEP].value(now), 0.0, 1.0));
    }

    private float at(int index, double now) {
      return (float) tracks[index].value(now);
    }

    // shortcut: premultiplied sRGB channels spring on their own and are clamped to 0..255, so a
    // bounce cannot leave the color range, but it still overshoots inside it and tints the color.
    // Spring in a perceptual space such as Oklab when that shows.
    private int color(int first, int target, double now) {
      if (tracks[first].isAtRest(now)
          && tracks[first + 1].isAtRest(now)
          && tracks[first + 2].isAtRest(now)
          && tracks[first + 3].isAtRest(now)) {
        return target;
      }
      double alpha = tracks[first].value(now); // not clamped: a bounce past 255 must not lighten
      int a = Math.clamp(Math.round(alpha), 0, 255);
      if (a == 0) {
        return 0;
      }
      int argb = a;
      for (int i = first + 1; i < first + 4; i++) {
        double v = tracks[i].value(now);
        argb = argb << 8 | Math.clamp(Math.round(alpha == 255 ? v : v * 255 / alpha), 0, 255);
      }
      return argb;
    }
  }
}
