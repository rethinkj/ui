package com.rethinkj.ui.text;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Glyph bitmaps for drawing, rasterised once and kept in square pages of 8-bit coverage, which a
 * GPU renderer uploads as textures. A glyph is identified by everything that changes its bitmap:
 * font, size in physical pixels, hinting, glyph id and quarter-pixel offset.
 *
 * <p>Glyphs are packed into rows (shelves) whose heights are multiples of 4 texels, but for a last
 * shelf that takes the rows left where only that rounding would keep a glyph out, each glyph with
 * one empty texel to its right and below it, so that no glyph's texels touch another's. The atlas
 * is bounded in entries and in bytes. Beyond the number of entries, the least recently used ones
 * are forgotten, and their texels are reused once nothing in their page is left. When a glyph
 * fits into no page and another page would exceed the bytes, the page used least recently is
 * emptied for it. Neither touches a glyph used since the last {@link #nextFrame}: the current
 * frame may need more than the bounds, and then the atlas grows beyond them.
 *
 * <p>Each page records the region that changed since it was last {@link Page#takeChanged taken},
 * so that a renderer uploads only that.
 *
 * <p>An atlas is not thread-safe: it belongs to one {@link com.rethinkj.ui.App} or one render, and
 * the thread that draws it.
 */
public final class Atlas {

  /** The side of a page in texels when the constructor is not told otherwise. */
  public static final int PAGE_SIZE = 1024;

  /**
   * Where a glyph's bitmap lies in the atlas, in texels, and where it lies relative to the glyph's
   * origin, in physical pixels; one texel covers one physical pixel.
   *
   * @param page the index of the page in {@link #pages}, or -1 for a glyph without ink, such as a
   *     space, or one too large for a page
   * @param left how far the bitmap's left edge lies right of the origin
   * @param top how far the bitmap's top edge lies above the baseline
   */
  public record Slot(int page, int x, int y, int width, int height, int left, int top) {

    /** Whether there is nothing to draw. */
    public boolean isEmpty() {
      return page < 0;
    }
  }

  /** A rectangle of texels; empty when its width is 0. */
  public record Region(int x, int y, int width, int height) {

    public static final Region EMPTY = new Region(0, 0, 0, 0);

    public boolean isEmpty() {
      return width == 0;
    }
  }

  /**
   * One page: {@link #size} by {@link #size} texels of coverage, one unsigned byte each from 0
   * (none) to 255 (all), rows from the top, {@link #size} bytes a row, in native memory. As a
   * WebGPU texture it is {@code r8unorm}. Every texel next to a glyph's bitmap is 0, so that
   * sampling at its edge never picks up another; glyphs that were forgotten may stay elsewhere.
   */
  public static final class Page {

    private final int size;
    private final MemorySegment pixels;
    private final List<int[]> shelves = new ArrayList<>(); // y, height, next free x
    private int bottom; // where the next shelf starts
    private int live; // entries whose texels lie here
    private long used; // the frame this page was last used in
    private int changedLeft;
    private int changedTop;
    private int changedRight;
    private int changedBottom;

    private Page(int size) {
      this.size = size;
      pixels = Arena.ofAuto().allocate((long) size * size);
      clearChanged();
    }

    public int size() {
      return size;
    }

    /** The texels, read-only, {@code size * size} bytes. */
    public MemorySegment pixels() {
      return pixels.asReadOnly();
    }

    /** The coverage of one texel, 0 to 255. */
    public int coverage(int x, int y) {
      return pixels.get(JAVA_BYTE, (long) y * size + x) & 0xFF;
    }

    /** The bounding box of the texels written since the last {@link #takeChanged}. */
    public Region changed() {
      return changedRight <= changedLeft
          ? Region.EMPTY
          : new Region(
              changedLeft, changedTop, changedRight - changedLeft, changedBottom - changedTop);
    }

    /**
     * {@link #changed}, and forgets it: the next call returns only what is written after this one.
     * A new page has nothing changed; a renderer uploads the region of each page it draws from
     * before drawing.
     */
    public Region takeChanged() {
      Region region = changed();
      clearChanged();
      return region;
    }

    private void clearChanged() {
      changedLeft = Integer.MAX_VALUE;
      changedTop = Integer.MAX_VALUE;
      changedRight = 0;
      changedBottom = 0;
    }

    /**
     * Finds room for a rectangle, or returns null; the position as {x, y}. A shelf's height is
     * the rectangle's rounded up to a multiple of 4, except that where only that rounding keeps
     * the rectangle out, the last shelf takes the rows that are left, as a page whose side is no
     * multiple of 4 has.
     */
    private int[] place(int width, int height) {
      if (width > size) {
        return null;
      }
      int rounded = (height + 3) & ~3;
      int[] taller = null;
      int[] last = null; // a last shelf lower than rounded but high enough
      for (int[] shelf : shelves) {
        if (shelf[2] + width <= size) {
          if (shelf[1] == rounded) {
            return take(shelf, width);
          }
          if (taller == null && shelf[1] > rounded) {
            taller = shelf;
          }
          if (last == null && shelf[1] >= height && shelf[1] < rounded) {
            last = shelf;
          }
        }
      }
      if (bottom + rounded <= size) {
        return take(shelf(rounded), width);
      }
      if (taller != null) {
        return take(taller, width);
      }
      if (last == null && bottom + height <= size) {
        last = shelf(size - bottom);
      }
      return last == null ? null : take(last, width);
    }

    /** A new shelf of this height below the others. */
    private int[] shelf(int height) {
      int[] shelf = {bottom, height, 0};
      shelves.add(shelf);
      bottom += height;
      return shelf;
    }

    private static int[] take(int[] shelf, int width) {
      int[] at = {shelf[2], shelf[0]};
      shelf[2] += width;
      return at;
    }

    /** Writes a bitmap at {x, y}, with an empty column to its right and an empty row below. */
    private void write(int x, int y, Font.Bitmap bitmap) {
      int width = bitmap.width();
      int height = bitmap.height();
      for (int row = 0; row <= height; row++) {
        long at = (long) (y + row) * size + x;
        if (row < height) {
          MemorySegment.copy(bitmap.coverage(), row * width, pixels, JAVA_BYTE, at, width);
          pixels.set(JAVA_BYTE, at + width, (byte) 0);
        } else {
          pixels.asSlice(at, width + 1).fill((byte) 0);
        }
      }
      changedLeft = Math.min(changedLeft, x);
      changedTop = Math.min(changedTop, y);
      changedRight = Math.max(changedRight, x + width + 1);
      changedBottom = Math.max(changedBottom, y + height + 1);
    }

    /** Empties the page: all texels 0, which the next upload has to take whole. */
    private void reset() {
      shelves.clear();
      bottom = 0;
      live = 0;
      pixels.fill((byte) 0);
      changedLeft = 0;
      changedTop = 0;
      changedRight = size;
      changedBottom = size;
    }
  }

  private record Key(
      Font font, float pixelSize, TextRendering.Hinting hinting, int glyph, int quarter) {}

  private static final class Entry {
    final Slot slot;
    long used;

    Entry(Slot slot) {
      this.slot = slot;
    }
  }

  private final int pageSize;
  private final long maxBytes;
  private final int maxEntries;
  private final List<Page> pages = new ArrayList<>();
  // In access order: the least recently used first.
  private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(256, 0.75f, true);
  private long frame;

  /** Pages of {@link #PAGE_SIZE} texels, up to 16 MiB of them and 16384 glyphs. */
  public Atlas() {
    this(PAGE_SIZE, 16L << 20, 16384);
  }

  /**
   * @param pageSize the side of a page in texels
   * @param maxBytes how many bytes the pages may take, at least one page's
   * @param maxEntries how many glyphs the atlas keeps, at least 1
   */
  public Atlas(int pageSize, long maxBytes, int maxEntries) {
    if (pageSize < 16
        || pageSize > 8192
        || maxBytes < (long) pageSize * pageSize
        || maxEntries < 1) {
      throw new IllegalArgumentException("an atlas needs pages of 16 to 8192 texels, room for one"
          + " page and one entry, was " + pageSize + ", " + maxBytes + ", " + maxEntries);
    }
    this.pageSize = pageSize;
    this.maxBytes = maxBytes;
    this.maxEntries = maxEntries;
  }

  /**
   * Starts a frame: glyphs used before may be evicted from now on, those used after this call not
   * until the next one. A scene calls this when it is built.
   */
  public void nextFrame() {
    frame++;
  }

  /**
   * The slot of a glyph, rasterised and stored if it is not there yet. It stays where it is at
   * least until the next {@link #nextFrame}.
   *
   * @param pixelSize the size in physical pixels, as {@link GlyphRun#pixelSize}
   * @param quarter how many quarter pixels right of a whole pixel the glyph's origin lies, 0 to 3
   */
  public Slot glyph(
      Font font, float pixelSize, TextRendering.Hinting hinting, int glyph, int quarter) {
    if (quarter < 0 || quarter > 3) {
      throw new IllegalArgumentException("quarter must be from 0 to 3, was " + quarter);
    }
    Key key = new Key(font, pixelSize, hinting, glyph, quarter);
    Entry entry = entries.get(key);
    if (entry == null) {
      entry = new Entry(store(font.rasterize(glyph, pixelSize, hinting, quarter)));
      entries.put(key, entry);
    }
    entry.used = frame;
    if (!entry.slot.isEmpty()) {
      pages.get(entry.slot.page).used = frame;
    }
    trim();
    return entry.slot;
  }

  /** The pages, which slots and scene instances refer to by index; the list grows. */
  public List<Page> pages() {
    return Collections.unmodifiableList(pages);
  }

  /** The number of glyphs kept. */
  public int size() {
    return entries.size();
  }

  /** The bytes the pages take. */
  public long bytes() {
    return (long) pages.size() * pageSize * pageSize;
  }

  private Slot store(Font.Bitmap bitmap) {
    int width = bitmap.width() + 1;
    int height = bitmap.height() + 1;
    // shortcut: a glyph larger than a page is not drawn; give it a page of its own when text that
    // large is needed.
    if (bitmap.width() == 0 || bitmap.height() == 0 || width > pageSize || height > pageSize) {
      return new Slot(-1, 0, 0, 0, 0, bitmap.left(), bitmap.top());
    }
    int index = -1;
    int[] at = null;
    for (int i = 0; i < pages.size() && at == null; i++) {
      Page page = pages.get(i);
      if (page.live == 0 && !page.shelves.isEmpty()) {
        page.reset(); // everything in it was forgotten
      }
      at = page.place(width, height);
      index = i;
    }
    if (at == null) {
      index = bytes() + (long) pageSize * pageSize <= maxBytes ? -1 : leastRecentlyUsedPage();
      if (index < 0) {
        index = pages.size();
        pages.add(new Page(pageSize));
      } else {
        evict(index);
      }
      at = pages.get(index).place(width, height);
      if (at == null) {
        // An empty page holds any bitmap that is no larger than it; should one not fit anyway,
        // the glyph is left out as one larger than a page is.
        return new Slot(-1, 0, 0, 0, 0, bitmap.left(), bitmap.top());
      }
    }
    Page page = pages.get(index);
    page.write(at[0], at[1], bitmap);
    page.live++;
    return new Slot(
        index, at[0], at[1], bitmap.width(), bitmap.height(), bitmap.left(), bitmap.top());
  }

  /** The page used longest ago and not in this frame, or -1 if every page is in use. */
  private int leastRecentlyUsedPage() {
    int oldest = -1;
    for (int i = 0; i < pages.size(); i++) {
      if (pages.get(i).used < frame && (oldest < 0 || pages.get(i).used < pages.get(oldest).used)) {
        oldest = i;
      }
    }
    return oldest;
  }

  /** Forgets every glyph of a page and empties it. */
  private void evict(int index) {
    entries.values().removeIf(entry -> entry.slot.page == index);
    pages.get(index).reset();
  }

  /** Forgets the least recently used glyphs beyond the bound, as far as this frame allows. */
  private void trim() {
    Iterator<Map.Entry<Key, Entry>> eldest = entries.entrySet().iterator();
    while (entries.size() > maxEntries) {
      Entry entry = eldest.next().getValue();
      if (entry.used == frame) {
        return; // so is everything after it
      }
      eldest.remove();
      if (!entry.slot.isEmpty()) {
        pages.get(entry.slot.page).live--;
      }
    }
  }
}
