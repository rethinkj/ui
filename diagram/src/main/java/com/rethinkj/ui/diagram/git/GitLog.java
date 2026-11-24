package com.rethinkj.ui.diagram.git;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;

import com.rethinkj.ui.Colors;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.ScrollState;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.diagram.DiagramTexts;
import com.rethinkj.ui.kit.Avatar;
import com.rethinkj.ui.kit.Badge;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Tooltip;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

/**
 * A commit history with a branch graph, framed in a {@link Card#flush flush card}: a header with
 * its title, commit count and the app's actions, a column header, a virtual list of commits ({@link
 * El#rows}), and a footer with the app's status text and ref counts. The app keeps a {@link State}
 * and passes plain {@link Commit} and {@link Ref} records:
 *
 * <pre>{@code
 * GitLog.gitLog(
 *     state.log,
 *     state.commits,
 *     state.refs,
 *     GitLog.options().onOpen(state::open).status("1 ahead of origin/main"),
 *     Button.button(Button.Variant.SECONDARY, "Fetch", state::fetch)).grow(1);
 * }</pre>
 *
 * <p>Each row shows its {@link GraphCell}, {@link RefPills}, subject (muted for merge commits with
 * two or more parents), an initials {@link Avatar} and author name, a relative date with the full
 * date in its tooltip, and the seven-character short hash in the theme's monospaced font. Hovering
 * a row emphasizes its lane and fades all others.
 *
 * <p>A press selects a row; Shift with a press or with the navigation keys selects a range between
 * {@link State#anchor()} and {@link State#cursor()} so the app can compare the two ends. While the
 * list has the focus, Up, Down, Page Up, Page Down, Home and End move the cursor and keep it
 * scrolled into view, and Enter runs {@link Options#onOpen}.
 *
 * <p>In a tree dump the virtual list is {@code role=grid} with key {@code rows}, the column header
 * is {@code role=row} with key {@code header} and {@code role=columnheader} cells, and each commit
 * row is {@code role=row} with key {@code row-<index>}, a label such as {@code commit a41f9c2, Show
 * query duration, Mara Lindqvist, 38 min ago, HEAD → main}, {@code selected} and {@code current}
 * states, and its graph cell labeled with {@link CommitGraph#text(int)}.
 */
public final class GitLog {

  /** Maximum number of ref pills shown per commit before collapsing into {@code +n}. */
  private static final int MAX_REF_PILLS = 3;

  /** Length of the short commit hash shown in each row and its label. */
  private static final int SHORT_HASH = 7;

  /** Blend share of the selection color for rows strictly between range endpoints. */
  private static final double RANGE_MIX = 0.55;

  /** So dominant a share of the shrinking that the ref pills keep their width, as in a footer. */
  private static final float SUBJECT_SHRINK = 1e9f;

  private GitLog() {}

  /**
   * What a git log keeps across frames: its laid-out {@link CommitGraph}, selection cursor and
   * range anchor, hovered row, scroll position and clock for relative dates. The app keeps one per
   * log and may read or change its selection.
   */
  public static final class State {

    private final ScrollState scroll = new ScrollState();
    private CommitGraph graph = new CommitGraph();
    private List<Commit> laidOut = new ArrayList<>();
    private int cursor = -1;
    private int anchor = -1;
    private int hoveredRow = -1;
    private final Clock clock;

    /** A state using the system default time-zone clock. */
    public State() {
      this(Clock.systemDefaultZone());
    }

    /** A state using {@code clock} to format relative dates and tooltips. */
    public State(Clock clock) {
      this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The laid-out commit graph from the last build. */
    public CommitGraph graph() {
      return graph;
    }

    /** The cursor row index, or {@code -1} when nothing is selected. */
    public int cursor() {
      return cursor;
    }

    /** The range anchor row index, or {@code -1} when nothing is selected. */
    public int anchor() {
      return anchor;
    }

    /** The lower (newer) row index of the selection, or {@code -1} when nothing is selected. */
    public int rangeStart() {
      if (cursor < 0) {
        return -1;
      }
      return anchor < 0 ? cursor : Math.min(anchor, cursor);
    }

    /** The upper (older) row index of the selection, or {@code -1} when nothing is selected. */
    public int rangeEnd() {
      if (cursor < 0) {
        return -1;
      }
      return anchor < 0 ? cursor : Math.max(anchor, cursor);
    }

    /** Whether more than one row is selected as a range. */
    public boolean hasRange() {
      return cursor >= 0 && anchor >= 0 && cursor != anchor;
    }

    /** Selects a single row, or clears the selection when {@code row < 0}. */
    public void select(int row) {
      if (row < 0) {
        clearSelection();
      } else {
        cursor = row;
        anchor = row;
      }
    }

    /**
     * Selects a range between {@code anchor} and {@code cursor}, or clears the selection when
     * either is negative.
     */
    public void selectRange(int anchor, int cursor) {
      if (anchor < 0 || cursor < 0) {
        clearSelection();
      } else {
        this.anchor = anchor;
        this.cursor = cursor;
      }
    }

    /** Clears the selection. */
    public void clearSelection() {
      cursor = -1;
      anchor = -1;
    }

    void extend(int row) {
      if (row < 0) {
        return;
      }
      if (anchor < 0) {
        anchor = cursor >= 0 ? cursor : row;
      }
      cursor = row;
    }

    void clamp(int count) {
      if (count <= 0) {
        clearSelection();
        hoveredRow = -1;
        return;
      }
      if (cursor >= count) {
        cursor = count - 1;
      }
      if (cursor < 0) {
        anchor = -1;
      } else if (anchor < 0) {
        anchor = cursor;
      } else if (anchor >= count) {
        anchor = count - 1;
      }
      if (hoveredRow >= count) {
        hoveredRow = -1;
      }
    }

    /**
     * Updates the cached {@link CommitGraph}: appends only new tail commits when {@code commits}
     * extends the previously laid-out prefix, and rebuilds from scratch otherwise.
     */
    CommitGraph syncGraph(List<Commit> commits) {
      int n = commits.size();
      int built = graph.size();
      if (n >= built && matchesPrefix(commits, built)) {
        if (n > built) {
          List<Commit> tail = commits.subList(built, n);
          graph.append(tail);
          laidOut.addAll(tail);
        }
        return graph;
      }
      graph = new CommitGraph();
      laidOut = new ArrayList<>(commits);
      graph.append(commits);
      return graph;
    }

    private boolean matchesPrefix(List<Commit> commits, int count) {
      Iterator<Commit> given = commits.iterator(); // not get(i): linear for any list
      for (int i = 0; i < count; i++) {
        if (!sameNode(laidOut.get(i), given.next())) {
          return false;
        }
      }
      return true;
    }

    private static boolean sameNode(Commit a, Commit b) {
      return a == b || (a.id().equals(b.id()) && a.parents().equals(b.parents()));
    }
  }

  /** Optional configuration of a {@link GitLog}; {@link #options()} starts empty. */
  public static final class Options {

    private Consumer<Commit> onOpen;
    private String status;

    private Options() {}

    /** Told the cursor's commit when the user presses Enter; null for none. */
    public Options onOpen(Consumer<Commit> onOpen) {
      this.onOpen = onOpen;
      return this;
    }

    /**
     * Status text shown at the start of the footer, such as {@code "1 ahead of origin/main"}; null
     * or empty for none.
     */
    public Options status(String status) {
      this.status = status;
      return this;
    }
  }

  /** Default options for {@link #gitLog(State, List, List, Options, El...)}. */
  public static Options options() {
    return new Options();
  }

  /**
   * Builds a git log card with default options.
   *
   * @param state the log's state, kept by the app
   * @param commits commits newest first in topological order
   * @param refs all refs pointing at commits in the history
   * @param actions optional app actions placed at the right of the header
   */
  public static El gitLog(State state, List<Commit> commits, List<Ref> refs, El... actions) {
    return gitLog(state, commits, refs, options(), actions);
  }

  /**
   * Builds a git log card.
   *
   * @param state the log's state, kept by the app
   * @param commits commits newest first in topological order
   * @param refs all refs pointing at commits in the history
   * @param options callbacks and footer status text
   * @param actions optional app actions placed at the right of the header
   */
  public static El gitLog(
      State state, List<Commit> commits, List<Ref> refs, Options options, El... actions) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(commits, "commits");
    Objects.requireNonNull(refs, "refs");
    Objects.requireNonNull(options, "options");

    Theme theme = Theme.CURRENT.get();
    DiagramTexts texts = DiagramTexts.CURRENT.get();
    GraphStyle style = GraphStyle.CURRENT.get();

    CommitGraph graph = state.syncGraph(commits);
    int count = commits.size();
    state.clamp(count);

    Map<String, List<Ref>> refsByCommit = new HashMap<>();
    Set<String> localBranches = new HashSet<>();
    int tagCount = 0;
    for (Ref ref : refs) {
      Objects.requireNonNull(ref, "ref");
      refsByCommit.computeIfAbsent(ref.commit(), ignored -> new ArrayList<>(2)).add(ref);
      switch (ref.kind()) {
        case BRANCH, HEAD -> localBranches.add(ref.name());
        case TAG -> tagCount++;
        case REMOTE -> {}
      }
    }
    localBranches.remove(RefPills.DETACHED); // a detached HEAD names no branch

    float graphCellWidth =
        graph.width() > 0 ? 2 * style.inset() + (graph.width() - 1) * style.laneWidth() : 0;
    float graphColWidth =
        Math.max(graphCellWidth, 2 * theme.size().control() + theme.space().xl());
    float authorColWidth = theme.size().field() - theme.space().xxl();
    float dateColWidth = theme.size().field() / 2;
    float commitColWidth = 3 * theme.size().control() + theme.space().xl();

    El countBadge = Badge.badge(texts.commits(count), Badge.options().toolbar())
        .radius(theme.badge().toolbar() / 2);
    El[] barItems = new El[actions.length + 2];
    barItems[0] = countBadge;
    barItems[1] = Card.spacer();
    System.arraycopy(actions, 0, barItems, 2, actions.length);
    El header = Card.toolbar(texts.history(), null, barItems);

    El columns =
        columnHeader(theme, texts, graphColWidth, authorColWidth, dateColWidth, commitColWidth);

    El body;
    if (count == 0) {
      body = col()
          .key("rows")
          .grow(1)
          .minSize(0, 0)
          .center()
          .padding(theme.space().xl())
          .child(Label.label(Label.Variant.MUTED, texts.noCommits()));
    } else {
      boolean[] focused = {false};
      float rowHeight = style.rowHeight();
      int hoveredChain =
          state.hoveredRow >= 0 && state.hoveredRow < count ? graph.color(state.hoveredRow) : -1;
      IntPredicate faded = hoveredChain >= 0 ? chain -> chain != hoveredChain : null;
      int rangeStart = state.rangeStart();
      int rangeEnd = state.rangeEnd();
      boolean hasRange = state.hasRange();

      El list = col()
          .key("rows")
          .role("grid")
          .label(texts.history())
          .grow(1)
          .minSize(0, 0)
          .focusable()
          .focusRing(theme.accent().edge())
          .scrollbars(theme.scrollbars())
          .scrollState(state.scroll)
          .revealRow(state.cursor)
          .whenFocused(e -> focused[0] = true)
          .onHover(inside -> {
            if (!inside && state.hoveredRow >= 0) {
              state.hoveredRow = -1;
            }
          })
          .onRowsBuilt((first, last) -> {
            // a row scrolled away, as by dragging the scrollbar, gets no leave of its own
            if (state.hoveredRow < first || state.hoveredRow > last) {
              state.hoveredRow = -1;
            }
          });

      list.onKey(key -> handleKey(key, state, commits, options.onOpen, list, rowHeight));
      list.rows(
          count,
          rowHeight,
          index -> commitRow(
              index,
              commits.get(index),
              refsByCommit.getOrDefault(commits.get(index).id(), List.of()),
              graph,
              state,
              focused[0],
              rangeStart,
              rangeEnd,
              hasRange,
              faded,
              theme,
              texts,
              rowHeight,
              graphColWidth,
              authorColWidth,
              dateColWidth,
              commitColWidth));
      body = list;
    }

    El statusEl = options.status != null && !options.status.isEmpty()
        ? Label.label(Label.Variant.SMALL, options.status).color(theme.text().primary())
        : null;
    String countsText = texts.branches(localBranches.size()) + " · " + texts.tags(tagCount);
    El countsEl =
        Label.label(Label.Variant.SMALL, countsText).color(theme.text().muted());
    El footer = Card.footer(statusEl, countsEl);

    return Card.flush(header, columns, body, footer);
  }

  private static El columnHeader(
      Theme theme,
      DiagramTexts texts,
      float graphColWidth,
      float authorColWidth,
      float dateColWidth,
      float commitColWidth) {
    float stroke = theme.size().stroke();
    return row()
        .key("header")
        .role("row")
        .h(theme.size().paletteRow())
        .shrink(0)
        .align(Style.Align.CENTER)
        .fill(theme.grid().header())
        .border(stroke, 0, stroke, 0, theme.border().divider())
        .child(headerCell(theme, texts.graphColumn())
            .w(graphColWidth)
            .shrink(0)
            .padding(0, theme.space().m(), 0, theme.space().xl()))
        .child(headerCell(theme, texts.messageColumn())
            .grow(1)
            .minSize(0, 0)
            .padding(0, theme.space().m()))
        .child(headerCell(theme, texts.authorColumn())
            .w(authorColWidth)
            .shrink(0)
            .padding(0, theme.space().m()))
        .child(headerCell(theme, texts.dateColumn())
            .w(dateColWidth)
            .shrink(0)
            .padding(0, theme.space().m()))
        .child(headerCell(theme, texts.commitColumn())
            .w(commitColWidth)
            .shrink(0)
            .padding(0, theme.space().xl(), 0, theme.space().m()));
  }

  private static El headerCell(Theme theme, String title) {
    Theme.TextStyle type = theme.badge().text();
    return row()
        .role("columnheader")
        .label(title)
        .align(Style.Align.CENTER)
        .child(El.text(title)
            .font(type.font().get())
            .fontSize(type.size())
            .color(theme.text().muted())
            .ellipsis()
            .minSize(0, 0));
  }

  private static El commitRow(
      int index,
      Commit commit,
      List<Ref> rowRefs,
      CommitGraph graph,
      State state,
      boolean focused,
      int rangeStart,
      int rangeEnd,
      boolean hasRange,
      IntPredicate faded,
      Theme theme,
      DiagramTexts texts,
      float rowHeight,
      float graphColWidth,
      float authorColWidth,
      float dateColWidth,
      float commitColWidth) {
    boolean isCurrent = index == state.cursor;
    boolean isSelected = rangeStart >= 0 && index >= rangeStart && index <= rangeEnd;
    boolean isEndpoint = isCurrent || (hasRange && (index == rangeStart || index == rangeEnd));
    boolean isInRange = isSelected && !isEndpoint;
    boolean isHovered = index == state.hoveredRow;
    boolean isMerge = commit.parents().size() >= 2;

    int panel = theme.surface().panel();
    int selFill = focused ? theme.grid().selected() : theme.grid().selectedInactive();
    int rowBg;
    if (isEndpoint) {
      rowBg = selFill;
    } else if (isInRange) {
      rowBg = Colors.mix(panel, selFill, RANGE_MIX);
    } else if (isHovered) {
      rowBg = Colors.over(panel, theme.surface().hover());
    } else {
      rowBg = panel;
    }

    boolean isHead = false;
    for (Ref ref : rowRefs) {
      if (ref.kind() == Ref.Kind.HEAD) {
        isHead = true;
        break;
      }
    }

    List<Integer> palette = theme.lanes().colors();
    int laneColor = palette.get(Math.floorMod(graph.color(index), palette.size()));
    String shortHash = shortHash(commit.id());
    String relativeDate = texts.relativeDate(commit.time(), state.clock);
    String fullDate = texts.fullDate(commit.time().atZone(state.clock.getZone()));
    String rowLabel =
        rowLabel(shortHash, commit.subject(), commit.author(), relativeDate, rowRefs, texts);

    El graphBox = row()
        .w(graphColWidth)
        .h(rowHeight)
        .shrink(0)
        .align(Style.Align.CENTER)
        .child(
            GraphCell.cell(graph, index, isHead, isCurrent, rowBg, faded).label(graph.text(index)));

    El messageCell = row()
        .grow(1)
        .minSize(0, 0)
        .h(rowHeight)
        .align(Style.Align.CENTER)
        .gap(theme.space().s())
        .padding(0, theme.space().m())
        .clip();
    if (!rowRefs.isEmpty()) {
      messageCell.child(RefPills.pills(
              rowRefs,
              laneColor,
              rowBg,
              theme.size().minTabGroup() + theme.space().xxl(),
              MAX_REF_PILLS)
          .shrink(1) // pills give way so the subject keeps its share of a narrow column
          .minSize(0, 0)
          .clip());
    }
    Theme.TextStyle bodyType = theme.type().body();
    El subject = El.text(commit.subject())
        .font(bodyType.font().get())
        .fontSize(bodyType.size())
        .color(isMerge ? theme.text().muted() : theme.text().primary())
        .grow(1)
        .shrink(SUBJECT_SHRINK)
        .ellipsis();
    // The subject gives way first, down to the date column's width, then the pills, then the rest.
    subject.style().minWidth = Len.px(dateColWidth);
    subject.style().minYields = true;
    messageCell.child(subject);

    El authorCell = row()
        .w(authorColWidth)
        .h(rowHeight)
        .shrink(0)
        .align(Style.Align.CENTER)
        .gap(theme.space().m())
        .padding(0, theme.space().m())
        .clip()
        .child(Avatar.avatar(commit.author(), null, theme.size().controlSmall()))
        .child(El.text(commit.author())
            .font(bodyType.font().get())
            .fontSize(bodyType.size())
            .color(theme.text().primary())
            .grow(1)
            .ellipsis()
            .minSize(0, 0));

    Theme.TextStyle smallType = theme.type().small();
    El dateCell = Tooltip.tooltip(
        row()
            .w(dateColWidth)
            .h(rowHeight)
            .shrink(0)
            .align(Style.Align.CENTER)
            .padding(0, theme.space().m())
            .clip()
            .child(El.text(relativeDate)
                .font(smallType.font().get())
                .fontSize(smallType.size())
                .color(theme.text().muted())
                .ellipsis()
                .minSize(0, 0)),
        fullDate);

    Theme.TextStyle monoType = theme.type().mono();
    El commitCell = row()
        .w(commitColWidth)
        .h(rowHeight)
        .shrink(0)
        .align(Style.Align.CENTER)
        .padding(0, theme.space().xl(), 0, theme.space().m())
        .clip()
        .child(El.text(shortHash)
            .font(monoType.font().get())
            .fontSize(monoType.size())
            .color(theme.text().muted()));

    El r = row()
        .key("row-" + index)
        .role("row")
        .label(rowLabel)
        .selected(isSelected)
        .current(isCurrent)
        .align(Style.Align.CENTER)
        .fill(rowBg)
        .child(graphBox)
        .child(messageCell)
        .child(authorCell)
        .child(dateCell)
        .child(commitCell)
        .onPointerDown((clicks, modifiers) -> {
          if ((modifiers & Key.SHIFT) != 0) {
            state.extend(index);
          } else {
            state.select(index);
          }
        })
        .onHover(inside -> {
          if (inside) {
            state.hoveredRow = index;
          } else if (state.hoveredRow == index) {
            state.hoveredRow = -1;
          }
        });

    if (isEndpoint) {
      float barWidth = theme.callout().bar();
      float inset = theme.space().s();
      r.child(el().inert()
          .absolute(0, inset)
          .size(barWidth, Math.max(0, rowHeight - 2 * inset))
          .radius(0, barWidth / 2, barWidth / 2, 0)
          .fill(theme.accent().edge()));
    }
    return r;
  }

  private static boolean handleKey(
      Key key,
      State state,
      List<Commit> commits,
      Consumer<Commit> onOpen,
      El list,
      float rowHeight) {
    int count = commits.size();
    if (count == 0 || (key.modifiers() & ~Key.SHIFT) != 0) {
      return false;
    }
    boolean shift = (key.modifiers() & Key.SHIFT) != 0;
    if ("Enter".equals(key.name()) && !shift) {
      if (state.cursor >= 0 && state.cursor < count && onOpen != null) {
        onOpen.accept(commits.get(state.cursor));
        return true;
      }
      return false;
    }
    int page = Math.max(1, (int) (list.height() / rowHeight));
    int from = state.cursor;
    int next = switch (key.name()) {
      case "ArrowUp" -> from < 0 ? count - 1 : Math.max(0, from - 1);
      case "ArrowDown" -> from < 0 ? 0 : Math.min(count - 1, from + 1);
      case "PageUp" -> from < 0 ? 0 : Math.max(0, from - page);
      case "PageDown" -> from < 0 ? Math.min(count - 1, page) : Math.min(count - 1, from + page);
      case "Home" -> 0;
      case "End" -> count - 1;
      default -> -1;
    };
    if (next < 0) {
      return false;
    }
    if (shift) {
      state.extend(next);
    } else {
      state.select(next);
    }
    if (next == from) { // revealRow shows only another row; bring this one back if scrolled away
      double top = next * (double) rowHeight;
      double y = Math.clamp(list.scrollY(), Math.min(top, top + rowHeight - list.height()), top);
      state.scroll.scrollTo(list.scrollX(), y);
    }
    return true;
  }

  private static String shortHash(String id) {
    return id.length() > SHORT_HASH ? id.substring(0, SHORT_HASH) : id;
  }

  private static String rowLabel(
      String shortHash,
      String subject,
      String author,
      String relativeDate,
      List<Ref> rowRefs,
      DiagramTexts texts) {
    StringBuilder out = new StringBuilder(texts.commitLabel(shortHash))
        .append(", ")
        .append(subject)
        .append(", ")
        .append(author)
        .append(", ")
        .append(relativeDate);
    for (String refLabel : RefPills.labels(rowRefs, texts)) {
      out.append(", ").append(refLabel);
    }
    return out.toString();
  }
}
