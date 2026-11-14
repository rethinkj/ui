package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Icon.icon;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.Work;
import com.rethinkj.ui.text.Font;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.IntStream;

/**
 * A list of entries filtered by a text that is typed, the part that a {@link ComboBox} and a
 * {@link CommandPalette} share: the entries that match the text {@link Fuzzy fuzzily}, best first,
 * with the characters that match in a heavier weight, or the entries the app finds for the text
 * itself, also on another thread ({@link Entries}). An entry has a text, an optional icon before
 * it, an optional muted detail and shortcut at its right, an optional group, under whose heading
 * it shows, and the handler that runs when it is chosen; without one it is disabled.
 *
 * <p>The list is a virtual list ({@link El#rows}) of rows {@code size().control()} high, so that a
 * hundred thousand entries cost as much to show as twenty, and the filtering of a text that only
 * grows looks only at the entries the shorter text matched. In a tree dump it is {@code
 * role=listbox}, its entries {@code role=option} with their text as the label and the keys {@code
 * option-<row>}, the highlighted one {@code selected}, and the heading of each group a row {@code
 * role=group} with the group's name as its label, before the group's entries.
 */
public final class FilterList {

  private FilterList() {}

  /**
   * An entry of the list.
   *
   * @param text what the entry shows, and what a typed text is matched against
   * @param symbol an icon before the text, or null
   * @param detail a muted text at the right, such as a table's path, or null
   * @param shortcut a key combination at the right, in muted text, or null
   * @param group the name of the group the entry belongs to, shown above the group's entries; null
   *     for none. The groups follow each other in the order their first entries have
   * @param onChoose runs when the entry is chosen; null disables it
   * @param leading builds what stands before the text in place of the icon, or null
   * @param checked whether a check mark stands at the right end
   */
  public record Entry(
      String text,
      Symbol symbol,
      String detail,
      String shortcut,
      String group,
      Runnable onChoose,
      Function<Theme, El> leading,
      boolean checked) {
    public Entry {
      Objects.requireNonNull(text, "text");
    }

    /** An entry with an icon or nothing before its text, and no check mark. */
    public Entry(
        String text,
        Symbol symbol,
        String detail,
        String shortcut,
        String group,
        Runnable onChoose) {
      this(text, symbol, detail, shortcut, group, onChoose, null, false);
    }

    public Entry symbol(Symbol symbol) {
      return new Entry(text, symbol, detail, shortcut, group, onChoose, leading, checked);
    }

    public Entry detail(String detail) {
      return new Entry(text, symbol, detail, shortcut, group, onChoose, leading, checked);
    }

    public Entry shortcut(String shortcut) {
      return new Entry(text, symbol, detail, shortcut, group, onChoose, leading, checked);
    }

    public Entry group(String group) {
      return new Entry(text, symbol, detail, shortcut, group, onChoose, leading, checked);
    }

    /**
     * What stands before the text in place of an icon, built for each row that shows: an image, an
     * {@link Avatar}, a coloured mark. It should be no higher than a row, {@link
     * Theme.Sizes#control}; the column before the texts is as wide as it, at least an icon's width.
     */
    public Entry leading(Function<Theme, El> leading) {
      return new Entry(text, symbol, detail, shortcut, group, onChoose, leading, checked);
    }

    /** An image before the text, fitted into an icon's square, such as a product's logo. */
    public Entry image(com.rethinkj.ui.Image image) {
      Objects.requireNonNull(image, "image");
      return leading(t -> El.image(image)
          .size(t.size().icon(), t.size().icon())
          .fit(com.rethinkj.ui.Image.Fit.CONTAIN));
    }

    /** A check mark at the right end of the row, as the current choice of a list has. */
    public Entry checked(boolean checked) {
      return new Entry(text, symbol, detail, shortcut, group, onChoose, leading, checked);
    }

    public boolean isEnabled() {
      return onChoose != null;
    }
  }

  /** An entry that runs {@code onChoose} when it is chosen; null disables it. */
  public static Entry entry(String text, Runnable onChoose) {
    return new Entry(text, null, null, null, null, onChoose);
  }

  /** Where the entries of a list come from for a typed text. */
  public sealed interface Entries {

    /**
     * These entries, which the kit filters by the typed text with {@link Fuzzy}: all of them in
     * their order while nothing is typed, else those that match, best first within each group,
     * the groups in their order. Pass the same list on every build while it does not change, as
     * the kit keeps what it filtered by the list's identity.
     */
    static Entries of(List<Entry> all) {
      return new Fixed(all, false);
    }

    /**
     * As {@link #of}, but entries that match equally well stay in their order instead of the
     * shorter text coming first: for commands, where the shortest is no likelier than the first
     * ("fi" would otherwise put {@code File: Exit} before {@code File: New query}).
     */
    static Entries inOrder(List<Entry> all) {
      return new Fixed(all, true);
    }

    /**
     * The entries the app finds for the typed text, on the app's thread on every build, shown in
     * the order given; the kit filters nothing.
     */
    static Entries by(Function<String, List<Entry>> find) {
      return new Found(find, null, null);
    }

    /**
     * The entries the app finds for the typed text on another thread ({@link App#background}),
     * shown in the order given once they arrive, meanwhile those found for the text before and a
     * row "Loading…". A new text cancels the work for the one before, and so does closing what
     * shows the list. {@code find} gets only the text: what it reads it takes in its closure, and
     * nothing of the app's state that the app's thread changes.
     *
     * @param name what the work is called, which {@link App#await} names
     */
    static Entries background(App<?> app, String name, Function<String, List<Entry>> find) {
      return new Found(find, Objects.requireNonNull(app, "app"), name);
    }
  }

  record Fixed(List<Entry> all, boolean inOrder) implements Entries {}

  record Found(Function<String, List<Entry>> find, App<?> app, String name) implements Entries {}

  /**
   * What a list keeps between builds: the text typed, the highlighted row and what was filtered
   * for it. The component that shows the list holds one in its state.
   */
  static final class State {
    static final int BEST = -2; // highlight the first entry that can be chosen at the next build
    static final int KEEP = -3;

    String query = "";
    int highlight = -1; // the row, or -1 for none
    int want = BEST; // what to highlight at the next build: an entry's index, BEST, -1 or KEEP
    boolean none; // nothing highlighted until a key or the pointer does it (a free combo box)
    int current = -1; // the entry highlighted in place of the best while nothing is typed, or -1
    // What was filtered: of which list, for which text, the entries matched in no order.
    List<Entry> source;
    String filtered;
    int[] matched; // the indexes of the entries the text matched, ascending
    String[] lowered; // the source's texts, as a query without capitals is compared with them
    int[] groupOf; // the source's group of each entry, from 1, or 0 for none
    boolean grouped;
    Shown shown;
    // Of the app's work: the text asked for, the answer and the text it answered.
    Work work;
    String asked;
    List<Entry> answer = List.of();
    String failure;

    Consumer<Entry> onHighlight; // told of an entry the keys or the pointer highlight

    /** Highlights a row, and tells {@link #onHighlight} of a new entry. */
    void light(int row) {
      boolean changed = row != highlight;
      highlight = row;
      want = KEEP;
      none = false;
      if (changed && onHighlight != null && shown != null && shown.choosable(row)) {
        onHighlight.accept(shown.entry(row));
      }
    }

    /** A new text was typed: the best entry, or none, is highlighted at the next build. */
    void typed(String text, boolean highlightBest) {
      query = text;
      want = highlightBest ? BEST : -1;
      none = !highlightBest;
    }

    /** Starts again: no text, and this entry highlighted at the next build, -1 for none. */
    void reset(int entry) {
      query = "";
      want = entry;
      none = false;
      cancel();
    }

    /** Gives up the app's work on its way, as the list closes. */
    void cancel() {
      if (work != null) {
        work.cancel();
        work = null;
      }
      asked = null;
    }
  }

  /** What the list shows: its rows, each an entry's index or a group's heading. */
  record Shown(
      List<Entry> entries,
      int[] rows,
      String[] groups,
      String query,
      boolean loading,
      String failure) {

    int count() {
      return rows.length;
    }

    /** The entry of a row, or null for a heading. */
    Entry entry(int row) {
      int at = rows[row];
      return at >= 0 ? entries.get(at) : null;
    }

    /** The row of an entry that is {@code e} by its text, group and detail, or -1. */
    int rowOf(Entry e) {
      for (int row = 0; row < rows.length; row++) {
        Entry at = entry(row);
        if (at != null
            && at.text().equals(e.text())
            && Objects.equals(at.group(), e.group())
            && Objects.equals(at.detail(), e.detail())) {
          return row;
        }
      }
      return -1;
    }

    /** Whether a row is an entry that can be chosen. */
    boolean choosable(int row) {
      Entry e = row >= 0 && row < rows.length ? entry(row) : null;
      return e != null && e.isEnabled();
    }

    /** The next row that can be chosen from {@code from} in this direction, or -1. */
    int step(int from, int direction) {
      return PassiveList.step(rows.length, from, direction, this::choosable);
    }
  }

  /** The rows to show for the state's text, filtering or asking where it has to. */
  static Shown shown(State s, Entries entries) {
    Shown before = s.shown;
    Shown shown = switch (entries) {
      case Fixed f -> filter(s, f.all(), f.inOrder());
      case Found f
      when f.app() == null -> rows(f.find().apply(s.query), null, s.query, false, null);
      case Found f -> ask(s, f);
    };
    s.shown = shown;
    if (s.want == State.KEEP && before != null && before.entries() != shown.entries()) {
      // Other entries under the same highlight: the entry again by what it is, not by its row; one
      // highlighted while the list was loading was the best of the entries before, not a choice.
      boolean kept = !before.loading() && before.choosable(s.highlight);
      s.highlight = kept ? shown.rowOf(before.entry(s.highlight)) : -1;
      if (!shown.choosable(s.highlight) && !(s.none && !kept)) {
        s.want = State.BEST;
      }
    }
    if (s.want == State.BEST && s.query.isEmpty() && s.current >= 0) {
      s.want = s.current;
    }
    if (s.want == State.BEST) {
      s.highlight = shown.step(-1, 1);
    } else if (s.want >= -1) {
      s.highlight = -1;
      for (int row = 0; s.want >= 0 && row < shown.count(); row++) {
        if (shown.rows[row] == s.want) {
          s.highlight = row;
        }
      }
    } else if (!shown.choosable(s.highlight) && !(s.none && s.highlight < 0)) {
      s.highlight = shown.step(-1, 1);
    }
    s.want = State.KEEP;
    return shown;
  }

  private static Shown ask(State s, Found f) {
    String query = s.query;
    if (!query.equals(s.asked)) {
      if (s.work != null) {
        s.work.cancel();
      }
      s.asked = query;
      Function<String, List<Entry>> find = f.find();
      s.work = f.app()
          .background(
              f.name(),
              () -> find.apply(query),
              found -> {
                s.answer = List.copyOf(found);
                s.failure = null;
                s.work = null;
              },
              error -> {
                s.answer = List.of();
                s.failure = error.getMessage() == null ? error.toString() : error.getMessage();
                s.work = null;
              });
    }
    return rows(s.answer, null, query, s.work != null, s.failure);
  }

  /** The entries in their order, with a heading before each group. */
  private static Shown rows(
      List<Entry> entries, int[] order, String query, boolean loading, String failure) {
    Map<String, Integer> groups = new HashMap<>();
    List<String> names = new ArrayList<>();
    int n = order == null ? entries.size() : order.length;
    int[] rows = new int[n + 8];
    int count = 0;
    String last = null;
    for (int i = 0; i < n; i++) {
      int at = order == null ? i : order[i];
      String group = entries.get(at).group();
      if (group != null && !group.equals(last)) {
        Integer g = groups.get(group);
        if (g == null) {
          g = names.size();
          groups.put(group, g);
          names.add(group);
        }
        if (count + 2 > rows.length) {
          rows = Arrays.copyOf(rows, rows.length * 2);
        }
        rows[count++] = -1 - g;
      }
      last = group;
      if (count + 1 > rows.length) {
        rows = Arrays.copyOf(rows, rows.length * 2);
      }
      rows[count++] = at;
    }
    return new Shown(
        entries, Arrays.copyOf(rows, count), names.toArray(String[]::new), query, loading, failure);
  }

  /**
   * The entries that match the state's text, best first within their groups, the groups in the
   * order of their first entries; all of them, in their order, while it is empty.
   */
  private static Shown filter(State s, List<Entry> all, boolean inOrder) {
    String query = s.query;
    if (all == s.source && query.equals(s.filtered) && s.shown != null) {
      return s.shown;
    }
    if (all.size() >= 1 << INDEX_BITS) {
      throw new IllegalArgumentException(
          "A list holds fewer than " + (1 << INDEX_BITS) + " entries, not " + all.size());
    }
    if (all != s.source) {
      // What the list's texts are compared as, and the index of each entry's group from 1, in
      // the order of the groups' first entries: kept while the list is the same.
      s.lowered = new String[all.size()];
      s.groupOf = new int[all.size()];
      Map<String, Integer> groups = new HashMap<>();
      for (int i = 0; i < s.lowered.length; i++) {
        Entry e = all.get(i);
        s.lowered[i] = Fuzzy.lowered(e.text());
        if (e.group() != null) {
          s.groupOf[i] = groups.computeIfAbsent(e.group(), g -> groups.size() + 1);
        }
      }
      s.grouped = !groups.isEmpty();
      s.matched = null;
    }
    Fuzzy.Matcher m = Fuzzy.matcher(query);
    // A text that only grows matches among what the shorter one matched.
    int[] candidates = all == s.source
            && s.matched != null
            && !s.filtered.isEmpty()
            && query.startsWith(s.filtered)
        ? s.matched
        : null;
    int n = candidates == null ? all.size() : candidates.length;
    long[] keys = new long[n];
    int[] matched = new int[n];
    int count;
    if (n < PARALLEL) {
      count = score(s, all, query, candidates, 0, n, keys, matched, inOrder);
    } else {
      // Many entries: in parts on the common pool's threads, each into its own range.
      int parts = Math.min(PARTS, n / (PARALLEL / 4));
      int[] counts = new int[parts];
      IntStream.range(0, parts)
          .parallel()
          .forEach(p -> counts[p] = score(
              s,
              all,
              query,
              candidates,
              (int) ((long) n * p / parts),
              (int) ((long) n * (p + 1) / parts),
              keys,
              matched,
              inOrder));
      count = 0;
      for (int p = 0; p < parts; p++) { // closes the gaps between the parts
        int from = (int) ((long) n * p / parts);
        System.arraycopy(keys, from, keys, count, counts[p]);
        System.arraycopy(matched, from, matched, count, counts[p]);
        count += counts[p];
      }
    }
    if ((!m.isEmpty() || s.grouped) && count > 1) {
      sort(keys, count);
    }
    int[] order = new int[count];
    for (int k = 0; k < count; k++) {
      order[k] = (int) (keys[k] & ((1 << INDEX_BITS) - 1));
    }
    s.source = all;
    s.filtered = query;
    s.matched = m.isEmpty() ? null : Arrays.copyOf(matched, count);
    return rows(all, order, query, false, null);
  }

  /** Entries filtered at once on the app's thread; from this many, in parts on others too. */
  static final int PARALLEL = 4_000;

  private static final int PARTS = 16;

  /**
   * Scores the entries from {@code from} to {@code to} of the candidates, or of all for null, and
   * writes the sort key and index of each that matches from {@code from} on; returns how many.
   */
  private static int score(
      State s,
      List<Entry> all,
      String query,
      int[] candidates,
      int from,
      int to,
      long[] keys,
      int[] matched,
      boolean inOrder) {
    Fuzzy.Matcher m = Fuzzy.matcher(query); // one per thread
    int count = from;
    for (int c = from; c < to; c++) {
      int i = candidates == null ? c : candidates[c];
      Entry e = all.get(i);
      int score = m.score(e.text(), s.lowered[i]);
      if (score == Fuzzy.NO_MATCH) {
        continue;
      }
      long rank = m.isEmpty()
          ? 0
          : (long) (SCORE_LIMIT - Math.clamp(score, -SCORE_LIMIT + 1, SCORE_LIMIT - 1)) << 10
              | (inOrder ? 0 : Math.min(e.text().length(), 1023));
      keys[count] = (long) s.groupOf[i] << 47 | rank << INDEX_BITS | i;
      matched[count++] = i;
    }
    return count - from;
  }

  /**
   * Sorts the keys by their bits above the index, keeping the order of keys that are equal there,
   * which is that of their indexes: a radix sort, three passes of counting, which takes a fraction
   * of {@link Arrays#sort}'s time for a hundred thousand keys.
   */
  private static void sort(long[] keys, int count) {
    long[] from = keys;
    long[] to = new long[count];
    int[] counts = new int[1 << DIGIT];
    for (int shift = INDEX_BITS; shift < 64; shift += DIGIT) {
      Arrays.fill(counts, 0);
      for (int k = 0; k < count; k++) {
        counts[(int) (from[k] >>> shift) & (1 << DIGIT) - 1]++;
      }
      if (counts[(int) (from[0] >>> shift) & (1 << DIGIT) - 1] == count) {
        continue; // all alike in this digit
      }
      for (int d = 0, sum = 0; d < counts.length; d++) {
        int c = counts[d];
        counts[d] = sum;
        sum += c;
      }
      for (int k = 0; k < count; k++) {
        to[counts[(int) (from[k] >>> shift) & (1 << DIGIT) - 1]++] = from[k];
      }
      long[] swap = from;
      from = to;
      to = swap;
    }
    if (from != keys) {
      System.arraycopy(from, 0, keys, 0, count);
    }
  }

  private static final int DIGIT = 15; // 21 + 3 * 15 > 63: three passes

  private static final int INDEX_BITS = 21; // up to two million entries
  private static final int SCORE_LIMIT = 1 << 15; // sixteen bits of score, ten of length

  /**
   * The list's element: a virtual list of the rows, at most {@code most} rows high, then it
   * scrolls; a muted row says "No results", "Loading…" or what went wrong when there is nothing to
   * show. It has no frame of its own: the component puts it into its panel.
   *
   * @param choose runs with an entry chosen by the pointer
   */
  static El list(Theme t, State s, Shown shown, int most, String label, Consumer<Entry> choose) {
    float rowHeight = t.size().control();
    int count = shown.count();
    boolean note = count == 0 || shown.loading();
    float height = Math.max(1, Math.min(count + (note && count > 0 ? 1 : 0), most)) * rowHeight;
    if (count == 0) {
      String text =
          shown.failure() != null ? shown.failure() : shown.loading() ? "Loading…" : "No results";
      return col()
          .key("options")
          .role("listbox")
          .label(label)
          .h(height)
          .shrink(0)
          .child(row()
              .key("empty")
              .h(rowHeight)
              .padding(0, t.space().m())
              .align(Style.Align.CENTER)
              .child(label(t, Label.Variant.MUTED, text).key("note")));
    }
    // While new entries are on their way, the old ones show with a last row that says so.
    int rows = count + (shown.loading() ? 1 : 0);
    Font strong = t.type().heading().font().get();
    return PassiveList.view(
            t,
            rows,
            most,
            s.highlight,
            0,
            i -> i == count
                ? row()
                    .key("loading")
                    .padding(0, t.space().m())
                    .align(Style.Align.CENTER)
                    .child(label(t, Label.Variant.MUTED, "Loading…"))
                : option(t, s, shown, i, strong, choose))
        .key("options")
        .role("listbox")
        .label(label)
        .h(height)
        .shrink(0);
  }

  private static El option(
      Theme t, State s, Shown shown, int i, Font strong, Consumer<Entry> choose) {
    Entry entry = shown.entry(i);
    if (entry == null) {
      String group = shown.groups()[-1 - shown.rows()[i]];
      return row()
          .key("group-" + i)
          .role("group")
          .label(group)
          .padding(t.space().s(), t.space().m(), 0, t.space().m())
          .align(Style.Align.CENTER)
          .child(label(t, Label.Variant.MUTED, group).fontSize(t.type().body().size()));
    }
    boolean enabled = entry.isEnabled();
    int text = PassiveContent.text(t, enabled);
    int quiet = PassiveContent.muted(t, enabled);
    El leading = null;
    if (entry.leading() != null) {
      leading = entry.leading().apply(t).shrink(0);
    } else if (entry.symbol() != null) {
      leading = icon(t, entry.symbol()).color(quiet);
    }
    El name = label(t, entry.text()).color(text).minSize(0, 0).ellipsis();
    int[] at = shown.query().isEmpty() ? null : Fuzzy.positions(shown.query(), entry.text());
    if (at != null && at.length > 0) {
      name.highlight(shown.query(), (line, before, spans) -> {
        for (int k = 0; k < at.length; k++) {
          int end = k;
          while (end + 1 < at.length && at[end + 1] == at[end] + 1) {
            end++;
          }
          spans.font(at[k], at[end] + 1, strong);
          k = end;
        }
        return null;
      });
    }
    El content = PassiveContent.row(
        t,
        PassiveContent.flexibleLeading(t, leading),
        name,
        entry.detail() != null
            ? label(t, entry.detail()).color(quiet).minSize(0, 0).shrink(2).ellipsis()
            : null,
        entry.shortcut() != null
            ? label(t, entry.shortcut())
                .color(quiet)
                .shrink(0)
                .margin(0, 0, 0, t.space().s())
            : null,
        entry.checked() ? icon(t, Menu.CHECKED).color(quiet) : null);
    return PassiveList.option(
            t, i, s.highlight, s::light, enabled ? () -> choose.accept(entry) : null)
        .label(entry.text())
        .child(content);
  }

  /**
   * What a key does to the list: Up and Down move the highlight over the entries that can be
   * chosen, around at the ends, Page Up and Page Down by {@code page} rows, Home and End to the
   * first and the last, and Enter chooses. Whether the list took the key.
   */
  static boolean key(Key k, State s, int page, Consumer<Entry> choose) {
    Shown shown = s.shown;
    if (shown == null || k.modifiers() != 0) {
      return false;
    }
    int n = shown.count();
    int at = s.highlight;
    int next = PassiveList.move(k.name(), at, n, page, shown::choosable);
    if (next != PassiveList.UNHANDLED) {
      if (next >= 0) {
        s.light(next);
      }
      return true;
    }
    if ((k.name().equals("Enter") || k.name().equals("NumpadEnter")) && shown.choosable(at)) {
      choose.accept(shown.entry(at));
      return true;
    }
    return false;
  }
}
