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
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * A search field above a list of entries filtered by what is typed ({@link #filterList}), which an
 * app can embed in a {@link Popover}, a {@link Card} or a side panel, and the filtered list that a
 * {@link ComboBox} and a {@link CommandPalette} share. The entries match the typed text {@link
 * Fuzzy fuzzily}, best first, with the characters that match in a heavier weight, or the app finds
 * them for the text itself, also on another thread ({@link Entries}). An entry has a text, an
 * optional icon or element before it, an optional muted detail, shortcut and check mark at its
 * right, an optional group, under whose heading it shows, and the handler that runs when it is
 * chosen; without one it is disabled.
 *
 * <pre>{@code
 * El trigger = Button.button(SECONDARY, state.model, state.modelPopover::toggle);
 * Popover.popover(trigger, state.modelPopover, "Choose model",
 *     FilterList.filterList(state.modelFilter, "Search models", FilterList.Entries.of(entries))
 *         .w(280));
 * }</pre>
 *
 * <p>While the field has the focus, Up and Down, Page Up and Page Down, Home and End move the
 * highlight, and Enter or a press on an entry runs its handler while leaving the focus in the
 * field. Escape is left to the parent (in a {@link Popover} it closes it). The element is {@code
 * size().palette()} wide unless sized by the caller, its field {@code size().paletteField()} high
 * and its list at most {@value CommandPalette#ROWS} rows of {@code size().paletteRow()}, then it
 * scrolls.
 *
 * <p>The list is a virtual list ({@link El#rows}) of rows of one height, so that a hundred
 * thousand entries cost as much to show as twenty, and the filtering of a text that only grows
 * looks only at the entries the shorter text matched. In a tree dump the field is {@code
 * role=textbox} and the list is {@code role=listbox}, its entries {@code role=option} with their
 * text as the label and the keys {@code option-<row>}, the highlighted one {@code selected}, and
 * the heading of each group a row {@code role=group} with the group's name as its label, before
 * the group's entries.
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
   * @param key what tells the entry from others with the same text, group and detail, as the
   *     list finds the highlighted entry again when its entries change; null for none, and then
   *     the text, group and detail tell it
   * @param keepsOpen whether choosing it leaves a {@link CommandPalette} open, for an entry whose
   *     handler changes the entries the palette shows; a {@link ComboBox} closes anyway
   * @param font builds the font the entry's text is drawn in, or null for the theme's; see {@link
   *     #font(Supplier)}
   */
  public record Entry(
      String text,
      Icon symbol,
      String detail,
      String shortcut,
      String group,
      Runnable onChoose,
      Supplier<El> leading,
      boolean checked,
      String key,
      boolean keepsOpen,
      Supplier<Font> font) {
    public Entry {
      Objects.requireNonNull(text, "text");
    }

    /** An entry drawn in the theme's body font. */
    public Entry(
        String text,
        Icon symbol,
        String detail,
        String shortcut,
        String group,
        Runnable onChoose,
        Supplier<El> leading,
        boolean checked,
        String key,
        boolean keepsOpen) {
      this(text, symbol, detail, shortcut, group, onChoose, leading, checked, key, keepsOpen, null);
    }

    /** An entry without a key that closes the palette it is chosen in. */
    public Entry(
        String text,
        Icon symbol,
        String detail,
        String shortcut,
        String group,
        Runnable onChoose,
        Supplier<El> leading,
        boolean checked) {
      this(text, symbol, detail, shortcut, group, onChoose, leading, checked, null, false, null);
    }

    /** An entry with an icon or nothing before its text, and no check mark. */
    public Entry(
        String text, Icon symbol, String detail, String shortcut, String group, Runnable onChoose) {
      this(text, symbol, detail, shortcut, group, onChoose, null, false, null, false, null);
    }

    public Entry symbol(Icon symbol) {
      return new Entry(
          text, symbol, detail, shortcut, group, onChoose, leading, checked, key, keepsOpen, font);
    }

    public Entry detail(String detail) {
      return new Entry(
          text, symbol, detail, shortcut, group, onChoose, leading, checked, key, keepsOpen, font);
    }

    public Entry shortcut(String shortcut) {
      return new Entry(
          text, symbol, detail, shortcut, group, onChoose, leading, checked, key, keepsOpen, font);
    }

    public Entry group(String group) {
      return new Entry(
          text, symbol, detail, shortcut, group, onChoose, leading, checked, key, keepsOpen, font);
    }

    /**
     * What stands before the text in place of an icon, built for each row that shows: an image, an
     * {@link Avatar}, a colored mark. It should be no higher than a row, {@link
     * Theme.Sizes#control}; the column before the texts is as wide as it, at least an icon's width.
     */
    public Entry leading(Supplier<El> leading) {
      return new Entry(
          text, symbol, detail, shortcut, group, onChoose, leading, checked, key, keepsOpen, font);
    }

    /** An image before the text, fitted into an icon's square, such as a product's logo. */
    public Entry image(com.rethinkj.ui.Image image) {
      return leading(PassiveContent.image(image));
    }

    /** A check mark at the right end of the row, as the current choice of a list has. */
    public Entry checked(boolean checked) {
      return new Entry(
          text, symbol, detail, shortcut, group, onChoose, leading, checked, key, keepsOpen, font);
    }

    /** This entry with a key, which tells it from others with the same text. */
    public Entry key(String key) {
      return new Entry(
          text, symbol, detail, shortcut, group, onChoose, leading, checked, key, keepsOpen, font);
    }

    /** This entry, leaving a {@link CommandPalette} open when it is chosen, or not. */
    public Entry keepsOpen(boolean keepsOpen) {
      return new Entry(
          text, symbol, detail, shortcut, group, onChoose, leading, checked, key, keepsOpen, font);
    }

    /**
     * The font this entry's text is drawn in, built only for the rows the list builds, those in
     * view and a few beyond; null, or a supplier that returns null, uses the theme's body font. Characters the font lacks fall back to the
     * theme's body font ({@link Font#withFallback}). While filtering, matches on an entry with its
     * own font are marked behind the text with {@link Theme.Code#match} instead of switching to the
     * theme's heading font, so the text stays in its typeface.
     */
    public Entry font(Supplier<Font> font) {
      return new Entry(
          text, symbol, detail, shortcut, group, onChoose, leading, checked, key, keepsOpen, font);
    }

    public boolean isEnabled() {
      return onChoose != null;
    }

    /** Whether this is {@code other}: the same key, or with none the same text, group, detail. */
    boolean same(Entry other) {
      return key != null || other.key != null
          ? Objects.equals(key, other.key)
          : text.equals(other.text)
              && Objects.equals(group, other.group)
              && Objects.equals(detail, other.detail);
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
     * row "Loading…". A new text cancels the work for the one before, and so does closing a {@link
     * ComboBox} or {@link CommandPalette} that shows the list; a {@link #filterList} has no close
     * of its own, and its work runs to its end. {@code find} gets only the text: what it reads it
     * takes in its closure, and nothing of the app's state that the app's thread changes.
     *
     * @param name what the work is called, which {@link App#await} names and which tells one
     *     background source from another on the same {@link State} (give two sources that share a
     *     state different names, and pass the same name on every build of one source so work stays
     *     in flight)
     */
    static Entries background(App<?> app, String name, Function<String, List<Entry>> find) {
      return new Found(find, Objects.requireNonNull(app, "app"), name);
    }
  }

  record Fixed(List<Entry> all, boolean inOrder) implements Entries {}

  record Found(Function<String, List<Entry>> find, App<?> app, String name) implements Entries {}

  /**
   * What a list keeps between builds: the text typed, the highlighted row and what was filtered
   * for it. The app keeps one in its state for {@link #filterList}.
   */
  public static final class State {
    static final int BEST = -2; // highlight the first entry that can be chosen at the next build
    static final int KEEP = -3;

    String query = "";
    int highlight = -1; // the row, or -1 for none
    int want = BEST; // what to highlight at the next build: an entry's index, BEST, -1 or KEEP
    boolean none; // nothing highlighted until a key or the pointer does it (a free combo box)
    int current = -1; // the entry highlighted in place of the best while nothing is typed, or -1
    boolean picked; // the highlight is a key's, the pointer's or the app's, not the best entry
    // What was filtered: of which list, in which order, for which text, the entries matched.
    List<Entry> source;
    boolean inOrder;
    String filtered;
    int[] matched; // the indexes of the entries the text matched, ascending
    String[] lowered; // the source's texts, as a query without capitals is compared with them
    int[] groupOf; // the source's group of each entry, from 1, or 0 for none
    boolean grouped;
    Shown shown;
    // Of the app's work: which source, the text asked for, the answer and any failure.
    App<?> app;
    String workName;
    Work work;
    String asked;
    List<Entry> answer = List.of();
    String failure;

    Consumer<Entry> onHighlight; // told of an entry the keys or the pointer highlight

    /** The text typed in the field, which filters the list. */
    public String text() {
      return query;
    }

    /** Highlights a row, and tells {@link #onHighlight} of a new entry. */
    void light(int row) {
      boolean changed = row != highlight;
      highlight = row;
      want = KEEP;
      none = false;
      picked = true;
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

    /** Lets go of the entries, and of what their handlers hold, as the list closes. */
    void release() {
      reset(-1);
      clearSource();
      shown = null;
      onHighlight = null;
    }

    /** Drops what was cached or asked for the source before. */
    void clearSource() {
      cancel();
      app = null;
      workName = null;
      answer = List.of();
      failure = null;
      source = null;
      inOrder = false;
      filtered = null;
      matched = null;
      lowered = null;
      groupOf = null;
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

  /**
   * A search field above a filtered list of {@code entries}, which an app can embed in a {@link
   * Popover}, a {@link Card} or a side panel. Typing filters the list; Up and Down, Page Up and
   * Page Down, Home and End move the highlight while the field keeps the focus; Enter or a press
   * on an entry runs its {@link Entry#onChoose}. The field does not take focus on its own (inside
   * a {@link Popover} it receives focus as the first focusable element; pass {@code true} to {@link
   * #filterList(State, String, Entries, boolean)} when something focusable comes before it), and
   * Escape is left to the parent.
   *
   * <p>The element is {@code size().palette()} wide by default (override with {@code .w(...)} or
   * {@code .wFull()}), its field {@code size().paletteField()} high, and its list at most {@value
   * CommandPalette#ROWS} rows of {@code size().paletteRow()} before scrolling.
   *
   * @param state what the list keeps between builds
   * @param placeholder shown while the field is empty, and used as the label of the field and list
   * @param entries where the entries come from
   */
  public static El filterList(State state, String placeholder, Entries entries) {
    return filterList(state, placeholder, entries, false);
  }

  /**
   * Like {@link #filterList(State, String, Entries)}, and with {@code autofocus} the search field
   * takes the focus as the list appears ({@link El#autofocus}): as its {@link Popover} opens, also
   * when something focusable comes before it there.
   *
   * @param state what the list keeps between builds
   * @param placeholder shown while the field is empty, and used as the label of the field and list
   * @param entries where the entries come from
   * @param autofocus whether the search field takes the focus as the list appears
   */
  public static El filterList(State state, String placeholder, Entries entries, boolean autofocus) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(entries, "entries");
    Theme theme = Theme.CURRENT.get();
    Consumer<Entry> choose = e -> e.onChoose().run();
    El field = TextField.textField(state.query, placeholder, v -> state.typed(v, true))
        .key("query")
        .label(placeholder)
        .wFull()
        .h(theme.size().paletteField())
        // Enter without an entry to choose stays here, not a dialog's default button.
        .onKey(k -> key(k, state, CommandPalette.ROWS, choose)
            || k.modifiers() == 0 && (k.name().equals("Enter") || k.name().equals("NumpadEnter")));
    if (autofocus) {
      field.autofocus();
    }
    Shown shown = shown(state, entries);
    return col()
        .w(theme.size().palette())
        .gap(theme.space().s())
        .minSize(0, 0)
        .children(
            TextField.withIcon(field, Symbol.SEARCH).wFull().shrink(0),
            list(
                    theme,
                    state,
                    shown,
                    CommandPalette.ROWS,
                    theme.size().paletteRow(),
                    placeholder,
                    choose)
                .keepFocus());
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

    /** The row of the entry that is {@code e} ({@link Entry#same}), or -1. */
    int rowOf(Entry e) {
      for (int row = 0; row < rows.length; row++) {
        Entry at = entry(row);
        if (at != null && at.same(e)) {
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

  /** Whether the state's cached filter or background work belongs to {@code entries}. */
  private static boolean sameSource(State s, Entries entries) {
    return switch (entries) {
      case Fixed f -> s.source == f.all() && s.inOrder == f.inOrder();
      case Found f when f.app() == null -> s.source == null && s.app == null;
      case Found f -> s.app == f.app() && Objects.equals(s.workName, f.name());
    };
  }

  /** The rows to show for the state's text, filtering or asking where it has to. */
  static Shown shown(State s, Entries entries) {
    Shown before = s.shown;
    boolean changed = !sameSource(s, entries);
    if (changed) {
      s.clearSource();
    }
    Shown shown = switch (entries) {
      case Fixed f -> filter(s, f.all(), f.inOrder());
      case Found f
      when f.app() == null -> rows(f.find().apply(s.query), null, s.query, false, null);
      case Found f -> ask(s, f);
    };
    s.shown = shown;
    if (s.want == State.KEEP
        && before != null
        && (changed || before.entries() != shown.entries())) {
      // Other entries under the same highlight: the entry again by what it is, not by its row,
      // where it was picked; the best of the entries before, or one highlighted while the list
      // was loading, gives way to the best of these.
      boolean kept = s.picked && !before.loading() && before.choosable(s.highlight);
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
      s.picked = false;
    } else if (s.want >= -1) {
      s.highlight = -1;
      s.picked = true;
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
    s.app = f.app();
    s.workName = f.name();
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
    s.inOrder = inOrder;
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
   * The list's element: a virtual list of rows {@code rowHeight} high, at most {@code most} rows
   * high, then it scrolls, and lower where its panel has less room; a muted row says "No results", "Loading…" or what went wrong when there is nothing to
   * show. It has no frame of its own: the component puts it into its panel.
   *
   * @param choose runs with an entry chosen by the pointer
   */
  static El list(
      Theme theme,
      State s,
      Shown shown,
      int most,
      float rowHeight,
      String label,
      Consumer<Entry> choose) {
    int count = shown.count();
    boolean note = count == 0 || shown.loading();
    float height = Math.max(1, Math.min(count + (note && count > 0 ? 1 : 0), most)) * rowHeight;
    if (count == 0) {
      String text = shown.failure() != null
          ? shown.failure()
          : shown.loading()
              ? Texts.CURRENT.get().loading()
              : Texts.CURRENT.get().noResults();
      return col()
          .key("options")
          .role("listbox")
          .label(label)
          .h(height)
          .shrink(0)
          .child(row()
              .key("empty")
              .h(rowHeight)
              .padding(0, theme.space().m())
              .align(Style.Align.CENTER)
              .child(label(Label.Variant.MUTED, text).key("note")));
    }
    // While new entries are on their way, the old ones show with a last row that says so.
    int rows = count + (shown.loading() ? 1 : 0);
    Font strong = theme.type().heading().font().get();
    return PassiveList.view(
            theme,
            rows,
            most,
            s.highlight,
            0,
            rowHeight,
            i -> i == count
                ? PassiveList.loading(theme)
                : option(theme, s, shown, i, strong, choose))
        .key("options")
        .role("listbox")
        .label(label)
        .h(height)
        .minSize(0, rowHeight); // a short window's panel takes rows off, and the rest scroll
  }

  /**
   * The font supplied for an entry or item, falling back to the theme's body font for characters it
   * lacks, or null when no supplier is set or it returns null.
   */
  static Font font(Theme theme, Supplier<Font> supplier) {
    if (supplier == null) {
      return null;
    }
    Font own = supplier.get();
    return own == null ? null : own.withFallback(theme.type().body().font().get());
  }

  private static El option(
      Theme theme, State s, Shown shown, int i, Font strong, Consumer<Entry> choose) {
    Entry entry = shown.entry(i);
    if (entry == null) {
      String group = shown.groups()[-1 - shown.rows()[i]];
      return row()
          .key("group-" + i)
          .role("group")
          .label(group)
          .padding(theme.space().s(), theme.space().m(), 0, theme.space().m())
          .align(Style.Align.CENTER)
          .child(label(Label.Variant.MUTED, group).fontSize(theme.type().body().size()));
    }
    boolean enabled = entry.isEnabled();
    int text = PassiveContent.text(theme, enabled);
    int quiet = PassiveContent.muted(theme, enabled);
    El leading = null;
    if (entry.leading() != null) {
      leading = entry.leading().get().shrink(0);
    } else if (entry.symbol() != null) {
      leading = Icon.Picture.color(
          icon(entry.symbol(), enabled ? Icon.Variant.MUTED : Icon.Variant.DISABLED), quiet);
    }
    Font custom = font(theme, entry.font());
    // The name keeps its width as the detail gives way, and yields only where it alone overflows.
    El name = label(entry.text()).color(text).ellipsis();
    if (custom != null) {
      name.font(custom);
    }
    name.style().minYields = true;
    int[] at = shown.query().isEmpty() ? null : Fuzzy.positions(shown.query(), entry.text());
    if (at != null && at.length > 0) {
      name.highlight(shown.query(), (line, before, spans) -> {
        for (int k = 0; k < at.length; k++) {
          int end = k;
          while (end + 1 < at.length && at[end + 1] == at[end] + 1) {
            end++;
          }
          if (custom != null) {
            spans.background(at[k], at[end] + 1, theme.code().match());
          } else {
            spans.font(at[k], at[end] + 1, strong);
          }
          k = end;
        }
        return null;
      });
    }
    El content = PassiveContent.row(
        theme,
        PassiveContent.flexibleLeading(theme, leading),
        name,
        entry.detail() != null
            ? label(entry.detail()).color(quiet).minSize(0, 0).shrink(2).ellipsis()
            : null,
        entry.shortcut() != null
            ? label(entry.shortcut())
                .color(quiet)
                .shrink(0)
                .margin(0, 0, 0, theme.space().s())
            : null,
        entry.checked() ? icon(Menu.CHECKED).color(quiet) : null);
    El option = PassiveList.option(
            theme, i, s.highlight, s::light, enabled ? () -> choose.accept(entry) : null)
        .label(entry.text())
        .child(content);
    return Tooltip.whenShortened(
        option, entry.detail() == null ? entry.text() : entry.text() + " — " + entry.detail());
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
