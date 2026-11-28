package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Icon.icon;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.RowHeights;
import com.rethinkj.ui.Side;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.text.Font;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A search field above a mixed list of action rows, separators, collapsible branches and per-row
 * submenus ({@link #filterTree}), for keyboard-driven popovers such as a branch or worktree
 * switcher. Typing filters the rows {@link Fuzzy fuzzily}, revealing matching nested rows with
 * their ancestors expanded and highlighting the matching characters in a heavier weight; clearing
 * the field restores the user's manual open and closed branches.
 *
 * <pre>{@code
 * List<FilterTree.Row> rows = List.of(
 *     FilterTree.action("Update project", state::updateProject)
 *         .icon(Symbol.ARROW_SYNC).shortcut("Ctrl+T"),
 *     FilterTree.separator(),
 *     FilterTree.branch("Local", List.of(
 *         FilterTree.action("main", () -> state.checkout("main"))
 *             .icon(Symbol.BRANCH).submenu(branchMenu))));
 * Popover.popover(trigger, state.branchPopover, "Branches",
 *     FilterTree.filterTree(state.branchTree, "Search branches", rows).w(300));
 * }</pre>
 *
 * <p>While the search field has the focus, Up, Down, Page Up, Page Down, Home and End move the
 * highlight across visible non-separator rows, keeping the focus in the field. Right opens a closed
 * branch, or opens the highlighted row's submenu, or moves to an open branch's first child; Left
 * collapses an open branch or moves to its parent branch. When the field holds text, Left and Right
 * move the caret while the caret can still move in that direction (or a selection is active), and
 * act on the tree once the caret is at the start or end. Enter runs {@link Row#onChoose}, or else
 * opens the row's submenu, or else toggles a branch. Pressing a row toggles a
 * branch, runs an action, or opens a row's submenu when it has only a submenu, leaving focus in the
 * field. Escape is left to the parent (in a {@link Popover} it closes it).
 *
 * <p>A row's submenu opens on the right of the row via {@link Menu} and takes the focus until an
 * item is chosen or Escape or Left closes it, returning focus to the search field. Optional
 * {@code fieldActions} appear as compact icon buttons inside the field's trailing end and keep the
 * focus in the field when pressed.
 *
 * <p>The element is {@code size().palette()} wide unless sized by the caller, its field {@code
 * size().paletteField()} high, and its rows {@code size().paletteRow()} high (separators are thin
 * dividers), showing up to {@value CommandPalette#ROWS} rows of height before scrolling.
 *
 * <p>In a tree dump the field is {@code #query role=textbox} and the list is {@code #tree
 * role=tree} (both labeled with {@code placeholder}). Non-separator rows are {@code role=treeitem}
 * with their text as the label, {@code selected} on the highlighted row, {@code expanded} or
 * {@code expanded=false} on branches, {@code level} and {@code pos}, {@code value="<detail>"} when
 * a detail is set, and keys {@code #row-<key>} (with branch marks {@code #mark-<key>}) when {@link
 * Row#key} is set or {@code #row-<index>} otherwise. Separators are {@code role=separator}.
 */
public final class FilterTree {

  private static final Row SEPARATOR =
      new Row(null, null, null, null, null, null, null, null, null, null, true);

  private FilterTree() {}

  /**
   * One row of the tree: an action/leaf row, a collapsible branch ({@code children != null}), or a
   * separator ({@code separator == true}).
   *
   * @param text what the row shows and what a typed query matches against; null for a separator
   * @param icon an icon before the text, or null
   * @param shortcut a key combination shown muted at the right, or null
   * @param detail muted secondary text after the label, or null
   * @param meta builds a passive, non-shrinking element right after the label (such as a {@link
   *     Badge}), or null
   * @param trailing builds a non-shrinking element aligned to the right edge, or null; when null
   *     and the row has a {@code submenu}, {@link Menu#SUBMENU} is shown instead. On a row with a
   *     submenu that also opens or runs an action, a press on it opens the submenu
   * @param children null for a leaf/action row; non-null for a collapsible branch
   * @param submenu items of the row's flyout submenu, or null for none
   * @param onChoose runs when the row is chosen, or null
   * @param key identifies the row for its open state and for {@code #row-<key>} in a dump; null
   *     uses the row's text path for open state and its index for its dump key
   * @param separator whether this row is a thin divider skipped by the highlight
   */
  public record Row(
      String text,
      Icon icon,
      String shortcut,
      String detail,
      Supplier<El> meta,
      Supplier<El> trailing,
      List<Row> children,
      List<Menu.Item> submenu,
      Runnable onChoose,
      String key,
      boolean separator) {

    public Row {
      if (!separator) {
        Objects.requireNonNull(text, "text");
      }
      children = children == null ? null : List.copyOf(children);
      submenu = submenu == null ? null : List.copyOf(submenu);
    }

    public Row icon(Icon icon) {
      return new Row(
          text, icon, shortcut, detail, meta, trailing, children, submenu, onChoose, key,
          separator);
    }

    public Row shortcut(String shortcut) {
      return new Row(
          text, icon, shortcut, detail, meta, trailing, children, submenu, onChoose, key,
          separator);
    }

    public Row detail(String detail) {
      return new Row(
          text, icon, shortcut, detail, meta, trailing, children, submenu, onChoose, key,
          separator);
    }

    public Row meta(Supplier<El> meta) {
      return new Row(
          text, icon, shortcut, detail, meta, trailing, children, submenu, onChoose, key,
          separator);
    }

    public Row trailing(Supplier<El> trailing) {
      return new Row(
          text, icon, shortcut, detail, meta, trailing, children, submenu, onChoose, key,
          separator);
    }

    public Row submenu(List<Menu.Item> submenu) {
      return new Row(
          text, icon, shortcut, detail, meta, trailing, children, submenu, onChoose, key,
          separator);
    }

    public Row key(String key) {
      return new Row(
          text, icon, shortcut, detail, meta, trailing, children, submenu, onChoose, key,
          separator);
    }

    public boolean isSeparator() {
      return separator;
    }

    public boolean isBranch() {
      return !separator && children != null;
    }

    /** Whether the row can be highlighted and interacted with. */
    public boolean isEnabled() {
      return !separator && (children != null || onChoose != null || hasSubmenu());
    }

    boolean hasSubmenu() {
      return !separator && submenu != null && !submenu.isEmpty();
    }
  }

  /** A leaf/action row that runs {@code onChoose} when chosen; null disables it unless it has a submenu. */
  public static Row action(String text, Runnable onChoose) {
    return new Row(text, null, null, null, null, null, null, null, onChoose, null, false);
  }

  /** A collapsible branch row with {@code children}. */
  public static Row branch(String text, List<Row> children) {
    return new Row(
        text,
        null,
        null,
        null,
        null,
        null,
        Objects.requireNonNull(children, "children"),
        null,
        null,
        null,
        false);
  }

  /** A thin horizontal divider skipped by the highlight and hidden while filtering. */
  public static Row separator() {
    return SEPARATOR;
  }

  /**
   * What a filter tree keeps between builds: the query, the highlighted row, the manually opened
   * branch keys, and the open submenu state.
   */
  public static final class State {
    String query = "";
    int caret;
    int anchor;
    int highlight = -1;
    String highlightedId;
    boolean pickBest = true;
    final Set<String> open = new LinkedHashSet<>();
    final Set<String> filterClosed = new HashSet<>();
    final Menu.State submenu = new Menu.State();
    Shown shown;
    private RowHeights heights;
    private float rowHeight;
    private float sepHeight;

    /** The text typed in the search field. */
    public String text() {
      return query;
    }

    /** Whether the branch identified by {@code key} is in the manual open set. */
    public boolean isOpen(String key) {
      return open.contains(key);
    }

    /** Marks one or more branch keys (or text paths) open in the manual open set. */
    public State open(String... keys) {
      for (String key : keys) {
        open.add(Objects.requireNonNull(key, "key"));
      }
      return this;
    }

    /** Marks a branch key closed in the manual open set. */
    public void close(String key) {
      open.remove(key);
    }

    /** Collapses all branches and closes any open submenu. */
    public void collapseAll() {
      open.clear();
      submenu.close();
      if (!query.isEmpty() && shown != null) {
        for (int i = 0; i < shown.count(); i++) {
          if (shown.row(i).isBranch()) {
            filterClosed.add(shown.id(i));
          }
        }
      }
    }

    /** Clears the search query, restoring the manual open state, and closes any open submenu. */
    public void clear() {
      typed("");
    }

    void typed(String text) {
      String previous = query;
      query = text;
      if (text.isEmpty()) {
        caret = 0;
        anchor = 0;
      } else if (caret == previous.length() && anchor == previous.length()) {
        caret = text.length();
        anchor = text.length();
      }
      filterClosed.clear();
      submenu.close();
      pickBest = true;
    }

    void selected(int caret, int anchor) {
      this.caret = caret;
      this.anchor = anchor;
    }

    void light(int index) {
      highlight = index;
      highlightedId = shown != null && index >= 0 && index < shown.count() ? shown.id(index) : null;
      pickBest = false;
    }

    void toggle(String id) {
      if (query.isEmpty()) {
        if (!open.add(id)) {
          open.remove(id);
        }
      } else if (!filterClosed.add(id)) {
        filterClosed.remove(id);
      }
      submenu.close();
    }

    void setOpen(String id, boolean expand) {
      if (query.isEmpty()) {
        if (expand) {
          open.add(id);
        } else {
          open.remove(id);
        }
      } else if (expand) {
        filterClosed.remove(id);
      } else {
        filterClosed.add(id);
      }
      submenu.close();
    }

    void openSubmenu(String id, boolean byKey) {
      if (byKey) {
        submenu.openByKey();
      } else {
        submenu.open();
      }
      submenu.anchor = id;
    }

    RowHeights heights(float rowHeight, float sepHeight) {
      this.rowHeight = rowHeight;
      this.sepHeight = sepHeight;
      if (heights == null) {
        heights = new RowHeights(
            i -> shown != null && i < shown.count() && shown.row(i).isSeparator()
                ? this.sepHeight
                : this.rowHeight,
            i -> shown != null && i < shown.count() ? shown.rowKey(i) : Integer.toString(i));
      }
      return heights;
    }
  }

  /**
   * A search field above a filterable tree of {@code rows}, without trailing field actions.
   *
   * @param state what the tree keeps between builds
   * @param placeholder shown while the field is empty, and used as the label of the field and tree
   * @param rows top-level actions, separators and branches
   */
  public static El filterTree(State state, String placeholder, List<Row> rows) {
    return filterTree(state, placeholder, rows, List.of());
  }

  /**
   * A search field with optional trailing icon {@code fieldActions} above a filterable tree of
   * {@code rows}.
   *
   * @param state what the tree keeps between builds
   * @param placeholder shown while the field is empty, and used as the label of the field and tree
   * @param rows top-level actions, separators and branches
   * @param fieldActions optional compact icon buttons placed inside the trailing end of the search
   *     field; each uses the item's icon, label and {@code onChoose} and keeps focus in the field
   */
  public static El filterTree(
      State state, String placeholder, List<Row> rows, List<Menu.Item> fieldActions) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(rows, "rows");
    Theme theme = Theme.CURRENT.get();
    El field = TextField.textField(state.query, placeholder, state::typed)
        .key("query")
        .label(placeholder)
        .wFull()
        .h(theme.size().paletteField())
        .onSelect(state::selected)
        // Enter without a row to act on stays here, not a dialog's default button.
        .onKey(k -> key(k, state)
            || k.modifiers() == 0 && (k.name().equals("Enter") || k.name().equals("NumpadEnter")));
    El searchBox = TextField.withIcon(field, Symbol.SEARCH).wFull().shrink(0);
    if (fieldActions != null && !fieldActions.isEmpty()) {
      float size = FieldActions.size(theme);
      float gap = theme.space().xs();
      float inset = Math.max(theme.space().xs(), (theme.size().paletteField() - size) / 2);
      El actions = row().gap(gap).keepFocus();
      int count = 0;
      for (Menu.Item item : fieldActions) {
        if (item.isSeparator() || item.icon() == null) {
          continue;
        }
        El button = FieldActions.button(theme, item.icon(), item.label(), item.onChoose());
        if (item.label() != null) {
          Tooltip.tooltip(button, item.label());
        }
        actions.child(button);
        count++;
      }
      if (count > 0) {
        float paddingRight =
            count * size + (count - 1) * gap + inset + theme.space().s();
        searchBox.child(FieldActions.place(theme, field, actions, paddingRight));
        // Centered in the field, which is taller than a control.
        actions.style().top = Len.px(inset);
        actions.style().right = Len.px(inset);
      }
    }
    Shown shown = buildShown(state, rows);
    return col()
        .w(theme.size().palette())
        .gap(theme.space().s())
        .minSize(0, 0)
        .children(searchBox, treeList(theme, state, shown, placeholder).keepFocus());
  }

  private record Item(
      Row row,
      String id,
      String rowKey,
      int depth,
      int position,
      int size,
      boolean opens, // a branch with children to show: while filtering, children that match
      boolean open,
      int score) {}

  private record Matched(Row row, String id, int score, List<Matched> children) {}

  record Shown(List<Item> items, String query) {

    int count() {
      return items.size();
    }

    Row row(int index) {
      return items.get(index).row();
    }

    String id(int index) {
      return items.get(index).id();
    }

    String rowKey(int index) {
      return items.get(index).rowKey();
    }

    int depth(int index) {
      return items.get(index).depth();
    }

    boolean isOpen(int index) {
      return items.get(index).open();
    }

    boolean opens(int index) {
      return items.get(index).opens();
    }

    boolean choosable(int index) {
      return index >= 0 && index < items.size() && items.get(index).row().isEnabled();
    }

    int step(int from, int direction) {
      return PassiveList.step(items.size(), from, direction, this::choosable);
    }

    int rowOfId(String id) {
      if (id == null) {
        return -1;
      }
      for (int i = 0; i < items.size(); i++) {
        if (id.equals(items.get(i).id())) {
          return i;
        }
      }
      return -1;
    }

    int parentRow(int index) {
      int d = depth(index);
      for (int i = index - 1; i >= 0; i--) {
        if (!row(i).isSeparator() && depth(i) < d) {
          return i;
        }
      }
      return index;
    }

    int bestMatch() {
      int best = -1;
      int bestScore = Integer.MIN_VALUE;
      for (int i = 0; i < items.size(); i++) {
        Item item = items.get(i);
        if (item.row().isEnabled() && item.score() > bestScore) {
          bestScore = item.score();
          best = i;
        }
      }
      return best >= 0 ? best : step(-1, 1);
    }

    float totalHeight(float rowHeight, float sepHeight) {
      float total = 0;
      for (Item item : items) {
        total += item.row().isSeparator() ? sepHeight : rowHeight;
      }
      return total;
    }
  }

  private static Shown buildShown(State state, List<Row> rows) {
    List<Item> items = new ArrayList<>();
    Map<String, Integer> seenKeys = new HashMap<>();
    if (state.query.isEmpty()) {
      flattenUnfiltered(rows, "", 0, state.open, items, seenKeys);
    } else {
      Fuzzy.Matcher matcher = Fuzzy.matcher(state.query);
      List<Matched> matched = matchAll(rows, "", matcher);
      flattenFiltered(matched, 0, state.filterClosed, items, seenKeys);
    }
    Shown shown = new Shown(items, state.query);
    state.shown = shown;
    if (state.pickBest) {
      int at = state.query.isEmpty() ? shown.step(-1, 1) : shown.bestMatch();
      state.highlight = at;
      state.highlightedId = at >= 0 ? shown.id(at) : null;
      state.pickBest = false;
    } else {
      int at = shown.rowOfId(state.highlightedId);
      if (!shown.choosable(at)) {
        at = shown.step(-1, 1);
      }
      state.highlight = at;
      state.highlightedId = at >= 0 ? shown.id(at) : null;
    }
    if (state.submenu.isOpen()) {
      int anchorRow =
          state.submenu.anchor instanceof String anchorId ? shown.rowOfId(anchorId) : -1;
      if (anchorRow < 0 || !shown.row(anchorRow).hasSubmenu()) {
        state.submenu.close();
      }
    }
    return shown;
  }

  private static String idOf(Row row, String parentPath) {
    if (row.key() != null) {
      return row.key();
    }
    return parentPath.isEmpty() ? row.text() : parentPath + "/" + row.text();
  }

  /**
   * The ids of {@code rows}: each row's key, or its text path, with a count after a text that a
   * sibling before it has too; null for a separator.
   */
  private static String[] ids(List<Row> rows, String parentPath) {
    String[] ids = new String[rows.size()];
    Map<String, Integer> seen = new HashMap<>();
    for (int i = 0; i < rows.size(); i++) {
      Row r = rows.get(i);
      if (!r.isSeparator()) {
        String id = idOf(r, parentPath);
        int count = seen.merge(id, 1, Integer::sum);
        ids[i] = count == 1 ? id : id + "#" + count;
      }
    }
    return ids;
  }

  private static String uniqueRowKey(String base, Map<String, Integer> seen) {
    int count = seen.merge(base, 1, Integer::sum);
    return count == 1 ? base : base + "#" + count;
  }

  private static void flattenUnfiltered(
      List<Row> rows,
      String parentPath,
      int depth,
      Set<String> openSet,
      List<Item> out,
      Map<String, Integer> seenKeys) {
    int size = 0;
    for (Row r : rows) {
      if (!r.isSeparator()) {
        size++;
      }
    }
    int pos = 0;
    String[] ids = ids(rows, parentPath);
    for (int i = 0; i < rows.size(); i++) {
      Row r = rows.get(i);
      if (r.isSeparator()) {
        out.add(new Item(r, null, "sep-" + out.size(), depth, 0, 0, false, false, Fuzzy.NO_MATCH));
        continue;
      }
      pos++;
      String id = ids[i];
      boolean open = r.isBranch() && openSet.contains(id);
      out.add(new Item(
          r, id, uniqueRowKey("row-" + id, seenKeys), depth, pos, size, r.isBranch(), open, 0));
      if (open) {
        flattenUnfiltered(r.children(), id, depth + 1, openSet, out, seenKeys);
      }
    }
  }

  private static List<Matched> matchAll(List<Row> rows, String parentPath, Fuzzy.Matcher matcher) {
    List<Matched> kept = new ArrayList<>();
    String[] ids = ids(rows, parentPath);
    for (int i = 0; i < rows.size(); i++) {
      Row r = rows.get(i);
      if (r.isSeparator()) {
        continue;
      }
      String id = ids[i];
      int score = matcher.score(r.text());
      List<Matched> kids = r.isBranch() ? matchAll(r.children(), id, matcher) : List.of();
      if (score != Fuzzy.NO_MATCH || !kids.isEmpty()) {
        kept.add(new Matched(r, id, score, kids));
      }
    }
    return kept;
  }

  private static void flattenFiltered(
      List<Matched> matched,
      int depth,
      Set<String> filterClosed,
      List<Item> out,
      Map<String, Integer> seenKeys) {
    int size = matched.size();
    for (int i = 0; i < size; i++) {
      Matched m = matched.get(i);
      boolean opens = m.row().isBranch() && !m.children().isEmpty();
      boolean open = opens && !filterClosed.contains(m.id());
      out.add(new Item(
          m.row(),
          m.id(),
          uniqueRowKey("row-" + m.id(), seenKeys),
          depth,
          i + 1,
          size,
          opens,
          open,
          m.score()));
      if (open) {
        flattenFiltered(m.children(), depth + 1, filterClosed, out, seenKeys);
      }
    }
  }

  private static El treeList(Theme theme, State state, Shown shown, String label) {
    float rowHeight = theme.size().paletteRow();
    float sepHeight = theme.size().stroke() + 2 * theme.space().s();
    int count = shown.count();
    if (count == 0) {
      return col()
          .key("tree")
          .role("tree")
          .label(label)
          .h(rowHeight)
          .shrink(0)
          .child(row()
              .key("empty")
              .h(rowHeight)
              .padding(0, theme.space().m())
              .align(Style.Align.CENTER)
              .child(label(Label.Variant.MUTED, Texts.CURRENT.get().noResults()).key("note")));
    }
    float maxHeight = CommandPalette.ROWS * rowHeight;
    float totalHeight = shown.totalHeight(rowHeight, sepHeight);
    float height = Math.max(rowHeight, Math.min(totalHeight, maxHeight));
    float gutter = totalHeight > maxHeight
        ? theme.scrollbars().thickness() + theme.scrollbars().margin()
        : 0;
    Font strong = theme.type().heading().font().get();
    El list = col()
        .key("tree")
        .role("tree")
        .label(label)
        .padding(0, gutter, 0, 0)
        .scrollbars(theme.scrollbars())
        .revealRow(state.highlight)
        .h(height)
        .minSize(0, rowHeight);
    list.rows(
        count,
        state.heights(rowHeight, sepHeight),
        i -> buildRow(theme, state, shown, list, i, rowHeight, sepHeight, strong));
    return list;
  }

  private static El buildRow(
      Theme theme,
      State state,
      Shown shown,
      El list,
      int index,
      float rowHeight,
      float sepHeight,
      Font strong) {
    Item item = shown.items().get(index);
    Row row = item.row();
    if (row.isSeparator()) {
      return row()
          .key("separator-" + index)
          .role("separator")
          .h(sepHeight)
          .padding(0, theme.space().m())
          .align(Style.Align.CENTER)
          .child(el().wFull().h(theme.size().stroke()).fill(theme.border().divider()));
    }
    boolean enabled = row.isEnabled();
    boolean lit = index == state.highlight;
    int textColor = PassiveContent.text(theme, enabled);
    int quiet = PassiveContent.muted(theme, enabled);
    String id = item.id();
    String dumpId = row.key() != null ? row.key() : Integer.toString(index);
    float indent = theme.space().l();
    float mark = theme.space().xl();
    int shownDepth = Math.min(item.depth(), Tree.deepest(theme, list.rowWidth()));

    El r = row()
        .key("row-" + dumpId)
        .role("treeitem")
        .label(row.text())
        .h(rowHeight)
        .radius(theme.radius().small())
        .padding(0, theme.space().m(), 0, theme.space().s() + shownDepth * indent)
        .gap(theme.space().s())
        .align(Style.Align.CENTER)
        .selected(lit)
        .disabled(!enabled)
        .level(item.depth() + 1)
        .position(item.position(), item.size())
        .fill(lit ? theme.surface().highlight() : 0);

    if (shownDepth > 0) {
      r.child(Tree.guides(theme, shownDepth, theme.space().s(), mark, indent, rowHeight));
    }

    if (row.isBranch()) {
      r.expanded(item.open());
      r.child(Tree.handle(theme, false, item.open(), dumpId, rowHeight, () -> {
        state.light(index);
        state.toggle(id);
      }));
      if (row.icon() != null) {
        r.child(Icon.Picture.color(
            icon(row.icon(), enabled ? Icon.Variant.MUTED : Icon.Variant.DISABLED), quiet));
      }
    } else {
      El iconEl = row.icon() == null
          ? null
          : Icon.Picture.color(
              icon(row.icon(), enabled ? Icon.Variant.MUTED : Icon.Variant.DISABLED), quiet);
      r.child(PassiveContent.leading(theme, iconEl));
    }

    El name = label(row.text()).color(textColor).ellipsis().minSize(0, 0);
    if (!shown.query().isEmpty()) {
      FilterList.highlightMatches(name, shown.query(), row.text(), strong);
    }
    El meta = row.meta() != null ? row.meta().get() : null;
    El more = null;
    if (row.detail() == null) {
      r.child(
          meta == null
              ? name.grow(1)
              : row()
                  .gap(theme.space().s())
                  .align(Style.Align.CENTER)
                  .grow(1)
                  .minSize(0, 0)
                  .clip()
                  .children(name, meta.shrink(0).inert()));
    } else {
      r.value(row.detail());
      name.style().minYields = true;
      more = label(row.detail())
          .color(quiet)
          .ellipsis()
          .minSize(0, 0)
          .grow(1)
          .shrink(2)
          .margin(0, 0, 0, theme.space().s());
      if (meta == null) {
        r.children(name, more);
      } else {
        El head = row()
            .gap(theme.space().s())
            .align(Style.Align.CENTER)
            .minSize(0, 0)
            .clip()
            .children(name, meta.shrink(0).inert());
        r.children(head, more);
      }
    }
    Tree.whole(
        theme,
        r,
        name,
        more,
        row.detail() == null ? row.text() : row.text() + " — " + row.detail(),
        rowHeight);

    if (row.shortcut() != null) {
      r.child(label(row.shortcut())
          .color(quiet)
          .shrink(0)
          .margin(0, 0, 0, theme.space().s()));
    }
    El trailing = row.trailing() != null ? row.trailing().get() : null;
    if (trailing == null && row.hasSubmenu()) {
      trailing = icon(Menu.SUBMENU, Icon.Variant.MUTED).color(quiet);
    }
    if (trailing != null && row.hasSubmenu() && (item.opens() || row.onChoose() != null)) {
      // The row's press opens the branch or runs the action; its end opens the submenu.
      trailing = el().center()
          .keepFocus()
          .onPress(() -> {
            state.light(index);
            state.openSubmenu(id, false);
          })
          .child(trailing);
    }
    if (trailing != null) {
      r.child(trailing.shrink(0));
    }

    if (enabled) {
      r.onHoverFor(0, () -> state.light(index)).onPress(() -> {
        state.light(index);
        if (item.opens()) {
          state.toggle(id);
        } else if (row.onChoose() != null) {
          state.submenu.close();
          row.onChoose().run();
        } else if (row.hasSubmenu()) {
          state.openSubmenu(id, false);
        }
      });
    }

    if (row.hasSubmenu() && state.submenu.isOpen() && id.equals(state.submenu.anchor)) {
      El menu = Menu.menu(state.submenu, row.submenu())
          .onKeyFirst(k -> {
            if (k.name().equals("ArrowLeft")
                && (k.modifiers() & ~Key.SHIFT) == 0
                && state.submenu.submenu(0) < 0) {
              state.submenu.close();
              return true;
            }
            return false;
          })
          .side(Side.RIGHT, Style.Align.START, theme.space().xs());
      r.overlay(menu);
    }
    return r;
  }

  private static boolean key(Key k, State state) {
    Shown shown = state.shown;
    if (shown == null || k.modifiers() != 0) {
      return false;
    }
    if (state.submenu.isOpen()) {
      // A key that came before the submenu took the focus is the submenu's, not the row's.
      switch (k.name()) {
        case "Escape", "ArrowLeft" -> state.submenu.close();
        case "Enter", "NumpadEnter" -> {}
        default -> {
          return false;
        }
      }
      return true;
    }
    int n = shown.count();
    int at = state.highlight;
    int next = PassiveList.move(k.name(), at, n, CommandPalette.ROWS, shown::choosable);
    if (next != PassiveList.UNHANDLED) {
      if (next >= 0) {
        state.light(next);
      }
      return true;
    }
    switch (k.name()) {
      case "ArrowRight" -> {
        if (state.caret != state.anchor || state.caret != state.query.length()) {
          return false;
        }
        if (shown.choosable(at)) {
          Row row = shown.row(at);
          if (shown.opens(at) && !shown.isOpen(at)) {
            state.setOpen(shown.id(at), true);
          } else if (row.hasSubmenu()) {
            state.openSubmenu(shown.id(at), true);
          } else if (row.isBranch()
              && shown.isOpen(at)
              && at + 1 < n
              && shown.depth(at + 1) > shown.depth(at)) {
            int child = shown.step(at, 1);
            if (child >= 0 && shown.depth(child) > shown.depth(at)) {
              state.light(child);
            }
          }
        }
        return true;
      }
      case "ArrowLeft" -> {
        if (state.caret != state.anchor || state.caret != 0) {
          return false;
        }
        if (shown.choosable(at)) {
          Row row = shown.row(at);
          if (row.isBranch() && shown.isOpen(at)) {
            state.setOpen(shown.id(at), false);
          } else {
            int parent = shown.parentRow(at);
            if (parent != at && shown.choosable(parent)) {
              state.light(parent);
            }
          }
        }
        return true;
      }
      case "Enter", "NumpadEnter" -> {
        if (shown.choosable(at)) {
          Row row = shown.row(at);
          if (row.onChoose() != null) {
            state.submenu.close();
            row.onChoose().run();
          } else if (row.hasSubmenu()) {
            state.openSubmenu(shown.id(at), true);
          } else if (shown.opens(at)) {
            state.toggle(shown.id(at));
          }
          return true;
        }
        return false;
      }
      default -> {
        return false;
      }
    }
  }
}
