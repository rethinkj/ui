package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
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
   */
  public record Entry(String text, Symbol symbol, String detail, String shortcut, String group,
      Runnable onChoose) {
    public Entry {
      Objects.requireNonNull(text, "text");
    }

    public Entry symbol(Symbol symbol) {
      return new Entry(text, symbol, detail, shortcut, group, onChoose);
    }

    public Entry detail(String detail) {
      return new Entry(text, symbol, detail, shortcut, group, onChoose);
    }

    public Entry shortcut(String shortcut) {
      return new Entry(text, symbol, detail, shortcut, group, onChoose);
    }

    public Entry group(String group) {
      return new Entry(text, symbol, detail, shortcut, group, onChoose);
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
      return new Fixed(all);
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

  record Fixed(List<Entry> all) implements Entries {}

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
    // What was filtered: of which list, for which text, the entries matched in no order.
    List<Entry> source;
    String filtered;
    int[] matched;
    Shown shown;
    // Of the app's work: the text asked for, the answer and the text it answered.
    Work work;
    String asked;
    List<Entry> answer = List.of();
    String failure;

    /** A new text was typed: the best entry, or none, is highlighted at the next build. */
    void typed(String text, boolean highlightBest) {
      query = text;
      want = highlightBest ? BEST : -1;
    }

    /** Starts again: no text, and this entry highlighted at the next build, -1 for none. */
    void reset(int entry) {
      query = "";
      want = entry;
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
  record Shown(List<Entry> entries, int[] rows, String[] groups, String query, boolean loading,
      String failure) {

    int count() {
      return rows.length;
    }

    /** The entry of a row, or null for a heading. */
    Entry entry(int row) {
      int at = rows[row];
      return at >= 0 ? entries.get(at) : null;
    }

    /** Whether a row is an entry that can be chosen. */
    boolean choosable(int row) {
      Entry e = row >= 0 && row < rows.length ? entry(row) : null;
      return e != null && e.isEnabled();
    }

    /** The next row that can be chosen from {@code from} in this direction, or -1. */
    int step(int from, int direction) {
      for (int at = from + direction; at >= 0 && at < rows.length; at += direction) {
        if (choosable(at)) {
          return at;
        }
      }
      return -1;
    }
  }

  /** The rows to show for the state's text, filtering or asking where it has to. */
  static Shown shown(State s, Entries entries) {
    Shown shown = switch (entries) {
      case Fixed f -> filter(s, f.all());
      case Found f when f.app() == null -> rows(f.find().apply(s.query), null, s.query, false,
          null);
      case Found f -> ask(s, f);
    };
    s.shown = shown;
    if (s.want == State.BEST) {
      s.highlight = shown.step(-1, 1);
    } else if (s.want >= -1) {
      s.highlight = -1;
      for (int row = 0; s.want >= 0 && row < shown.count(); row++) {
        if (shown.rows[row] == s.want) {
          s.highlight = row;
        }
      }
    } else if (!shown.choosable(s.highlight)) {
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
      s.work = f.app().background(f.name(), () -> find.apply(query),
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
  private static Shown rows(List<Entry> entries, int[] order, String query, boolean loading,
      String failure) {
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
    return new Shown(entries, Arrays.copyOf(rows, count), names.toArray(String[]::new), query,
        loading, failure);
  }

  /**
   * The entries that match the state's text, best first within their groups, the groups in the
   * order of their first entries; all of them, in their order, while it is empty.
   */
  private static Shown filter(State s, List<Entry> all) {
    String query = s.query;
    if (all == s.source && query.equals(s.filtered) && s.shown != null) {
      return s.shown;
    }
    if (all.size() >= 1 << INDEX_BITS) {
      throw new IllegalArgumentException("A list holds fewer than " + (1 << INDEX_BITS)
          + " entries, not " + all.size());
    }
    // The groups' order, from their first entries.
    Map<String, Integer> groups = new HashMap<>();
    boolean grouped = false;
    for (Entry e : all) {
      if (e.group() != null) {
        groups.putIfAbsent(e.group(), groups.size());
        grouped = true;
      }
    }
    Fuzzy.Matcher m = Fuzzy.matcher(query);
    int[] candidates = all == s.source && s.matched != null && !s.filtered.isEmpty()
        && query.startsWith(s.filtered) ? s.matched : null;
    int n = candidates == null ? all.size() : candidates.length;
    long[] keys = new long[n];
    int count = 0;
    for (int c = 0; c < n; c++) {
      int i = candidates == null ? c : candidates[c];
      Entry e = all.get(i);
      int score = m.score(e.text());
      if (score == Fuzzy.NO_MATCH) {
        continue;
      }
      long group = e.group() == null ? 0 : groups.get(e.group()) + 1;
      long rank = m.isEmpty() ? 0
          : (long) (SCORE_LIMIT - Math.clamp(score, -SCORE_LIMIT + 1, SCORE_LIMIT - 1)) << 10
              | Math.min(e.text().length(), 1023);
      keys[count++] = group << 47 | rank << INDEX_BITS | i;
    }
    if (!m.isEmpty() || grouped) {
      Arrays.sort(keys, 0, count);
    }
    int[] order = new int[count];
    for (int k = 0; k < count; k++) {
      order[k] = (int) (keys[k] & ((1 << INDEX_BITS) - 1));
    }
    s.source = all;
    s.filtered = query;
    s.matched = m.isEmpty() ? null : order;
    return rows(all, order, query, false, null);
  }

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
    El list = col().key("options").role("listbox").label(label).h(height).shrink(0);
    if (count == 0) {
      String text = shown.failure() != null ? shown.failure()
          : shown.loading() ? "Loading…" : "No results";
      return list.child(row().key("empty").h(rowHeight).padding(0, t.space().m())
          .align(Style.Align.CENTER)
          .child(label(t, Label.Variant.MUTED, text).key("note")));
    }
    // While new entries are on their way, the old ones show with a last row that says so.
    int rows = count + (shown.loading() ? 1 : 0);
    Font strong = t.type().heading().font().get();
    return list.scrollbars(t.scrollbars())
        .revealRow(s.highlight)
        .rows(rows, rowHeight, i -> i == count
            ? row().key("loading").padding(0, t.space().m()).align(Style.Align.CENTER)
                .child(label(t, Label.Variant.MUTED, "Loading…"))
            : option(t, s, shown, i, strong, choose));
  }

  private static El option(Theme t, State s, Shown shown, int i, Font strong,
      Consumer<Entry> choose) {
    Entry entry = shown.entry(i);
    if (entry == null) {
      String group = shown.groups()[-1 - shown.rows()[i]];
      return row().key("group-" + i).role("group").label(group)
          .padding(t.space().s(), t.space().m(), 0, t.space().m())
          .align(Style.Align.CENTER)
          .child(label(t, Label.Variant.MUTED, group).fontSize(t.type().body().size()));
    }
    boolean enabled = entry.isEnabled();
    boolean lit = i == s.highlight;
    int text = enabled ? t.text().primary() : t.text().disabled();
    int quiet = enabled ? t.text().muted() : t.text().disabled();
    El mark = el().w(t.size().icon()).shrink(0).center();
    if (entry.symbol() != null) {
      mark.child(icon(t, entry.symbol()).color(enabled ? t.text().muted() : quiet));
    }
    El name = label(t, entry.text()).color(text).grow(1).minSize(0, 0).ellipsis();
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
    El content = row().wFull().hFull().padding(0, t.space().m()).gap(t.space().m())
        .align(Style.Align.CENTER).children(mark, name);
    if (entry.detail() != null) {
      content.child(label(t, entry.detail()).color(quiet).minSize(0, 0).shrink(2).ellipsis());
    }
    if (entry.shortcut() != null) {
      content.child(label(t, entry.shortcut()).color(quiet).shrink(0)
          .margin(0, 0, 0, t.space().s()));
    }
    El row = row().key("option-" + i).role("option").label(entry.text())
        .selected(lit)
        .disabled(!enabled)
        .radius(t.radius().small())
        .fill(lit ? t.grid().selected() : 0)
        .child(content);
    if (enabled) {
      row.onHoverFor(0, () -> {
        s.highlight = i;
        s.want = State.KEEP;
      }).onPress(() -> choose.accept(entry));
    }
    return row;
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
    int next = switch (k.name()) {
      case "ArrowDown" -> at < 0 || shown.step(at, 1) < 0 ? shown.step(-1, 1) : shown.step(at, 1);
      case "ArrowUp" -> at < 0 || shown.step(at, -1) < 0 ? shown.step(n, -1) : shown.step(at, -1);
      case "PageDown" -> towards(shown, Math.min(Math.max(at, 0) + page, n - 1), 1);
      case "PageUp" -> towards(shown, Math.max(at - page, 0), -1);
      case "Home" -> shown.step(-1, 1);
      case "End" -> shown.step(n, -1);
      default -> -2;
    };
    if (next != -2) {
      if (next >= 0) {
        s.highlight = next;
        s.want = State.KEEP;
      }
      return true;
    }
    if ((k.name().equals("Enter") || k.name().equals("NumpadEnter")) && shown.choosable(at)) {
      choose.accept(shown.entry(at));
      return true;
    }
    return false;
  }

  /** The row that can be chosen at {@code row}, else the nearest in this direction, else back. */
  private static int towards(Shown shown, int row, int direction) {
    if (shown.choosable(row)) {
      return row;
    }
    int found = shown.step(row, direction);
    return found >= 0 ? found : shown.step(row, -direction);
  }
}
