package com.rethinkj.ui.diagram.git;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;

import com.rethinkj.ui.Colors;
import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.diagram.DiagramTexts;
import com.rethinkj.ui.kit.Avatar;
import com.rethinkj.ui.kit.Badge;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.Divider;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Properties;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Tooltip;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Shows the details of the selected commit or commit range of a {@link GitLog}, framed in a {@link
 * Card#flush flush card}: either attached below the log through {@link GitLog.Options#details(El)}
 * or placed anywhere on its own.
 *
 * <pre>{@code
 * CommitDetails.details(
 *     state.log,
 *     state.commits,
 *     state.refs,
 *     CommitDetails.options()
 *         .body(state.body)
 *         .changes(state.changes)
 *         .chosen(state.chosen)
 *         .onChoose(state::choose)
 *         .diff(diff))
 * }</pre>
 *
 * <p>For a single selected commit, the panel shows its short hash in the header, its subject and
 * optional body, properties for author, date (full and relative), parents (hash chips tinted with
 * the parent's lane color that jump to that parent when pressed) and refs ({@link RefPills}), the
 * list of changed files with status badges ({@code A}/{@code M}/{@code D}/{@code R}), muted folders
 * and {@code +/−} bars, and the app's optional diff element below.
 *
 * <p>For a selected range ({@link GitLog.State#hasRange()}), the panel shows a compare summary with
 * the commit count, {@code From} and {@code To} endpoint chips, the distinct authors, the app's
 * actions, and any changes and diff the app supplies. When nothing is selected, it shows a muted
 * empty state.
 */
public final class CommitDetails {

  /** Number of segments in a file's small addition/deletion bar. */
  private static final int BAR_SEGMENTS = 5;

  /** Maximum number of author avatars shown side by side in a range summary. */
  private static final int MAX_RANGE_AVATARS = 6;

  private CommitDetails() {}

  /** Optional content of a {@link CommitDetails} panel; {@link #options()} starts empty. */
  public static final class Options {

    private String body;
    private List<Change> changes;
    private Change chosen;
    private Consumer<Change> onChoose;
    private El diff;

    private Options() {}

    /** The commit message body below the subject; null or blank for none. */
    public Options body(String body) {
      this.body = body;
      return this;
    }

    /** The changed files to list; null or empty to omit the changes section. */
    public Options changes(List<Change> changes) {
      this.changes = changes;
      return this;
    }

    /** The currently chosen changed file, highlighted as selected; null for none. */
    public Options chosen(Change chosen) {
      this.chosen = chosen;
      return this;
    }

    /** Called when the user presses a changed file row; null to make file rows non-interactive. */
    public Options onChoose(Consumer<Change> onChoose) {
      this.onChoose = onChoose;
      return this;
    }

    /** An element placed below the changed files to show the chosen file's diff; null for none. */
    public Options diff(El diff) {
      this.diff = diff;
      return this;
    }
  }

  /** Default options for {@link #details(GitLog.State, List, List, Options, El...)}. */
  public static Options options() {
    return new Options();
  }

  /**
   * Builds a commit details card with default options.
   *
   * @param state the git log's state, read for selection and updated when a parent chip is pressed
   * @param commits commits newest first in topological order
   * @param refs all refs pointing at commits in the history
   * @param actions optional app actions: placed at the right of the header for a single commit, or
   *     below the range summary properties when comparing a range
   */
  public static El details(
      GitLog.State state, List<Commit> commits, List<Ref> refs, El... actions) {
    return details(state, commits, refs, options(), actions);
  }

  /**
   * Builds a commit details card.
   *
   * @param state the git log's state, read for selection and updated when a parent chip is pressed
   * @param commits commits newest first in topological order
   * @param refs all refs pointing at commits in the history
   * @param options on-demand body, changed files, chosen file, handler and diff slot
   * @param actions optional app actions: placed at the right of the header for a single commit, or
   *     below the range summary properties when comparing a range
   */
  public static El details(
      GitLog.State state, List<Commit> commits, List<Ref> refs, Options options, El... actions) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(commits, "commits");
    Objects.requireNonNull(refs, "refs");
    Objects.requireNonNull(options, "options");

    Theme theme = Theme.CURRENT.get();
    DiagramTexts texts = DiagramTexts.CURRENT.get();

    CommitGraph graph = state.syncGraph(commits);
    state.clamp(commits.size());

    if (state.cursor() < 0 || commits.isEmpty()) {
      El empty = col()
          .key("empty")
          .grow(1)
          .minSize(0, 0)
          .center()
          .padding(theme.space().xl())
          .child(Label.label(Label.Variant.MUTED, texts.noCommitSelected()));
      return Card.flush(empty).key("details").label(texts.commit());
    }

    if (state.hasRange()) {
      return rangeDetails(state, commits, graph, options, actions, theme, texts);
    }
    return singleDetails(state, commits, refs, graph, options, actions, theme, texts);
  }

  private static El singleDetails(
      GitLog.State state,
      List<Commit> commits,
      List<Ref> refs,
      CommitGraph graph,
      Options options,
      El[] actions,
      Theme theme,
      DiagramTexts texts) {
    int cursor = state.cursor();
    Commit commit = commits.get(cursor);
    String shortHash = GitLog.shortHash(commit.id());

    Theme.TextStyle mono = theme.type().mono();
    El hashBadge = row()
        .key("hash")
        .h(theme.badge().toolbar())
        .align(Style.Align.CENTER)
        .padding(0, theme.space().m())
        .radius(theme.radius().small())
        .fill(theme.surface().band())
        .border(theme.size().stroke(), theme.border().subtle())
        .child(El.text(shortHash)
            .font(mono.font().get())
            .fontSize(mono.size())
            .color(theme.text().primary()));
    El hashEl = commit.id().length() > shortHash.length()
        ? Tooltip.tooltip(hashBadge, commit.id())
        : hashBadge;

    El[] barItems = new El[actions.length + 2];
    barItems[0] = hashEl;
    barItems[1] = Card.spacer();
    System.arraycopy(actions, 0, barItems, 2, actions.length);
    El header = Card.toolbar(texts.commit(), null, barItems);

    El body = scrollBody(commit.id(), theme);

    // Subject and optional commit message body.
    El message = col()
        .shrink(0)
        .gap(theme.space().s())
        .child(Label.label(Label.Variant.SECTION, commit.subject()).wrapText());
    if (options.body != null && !options.body.isBlank()) {
      message.child(Label.label(Label.Variant.MUTED, options.body).wrapText());
    }
    body.child(message);

    // Properties: author, date, parents, refs.
    List<Properties.Property> propList = new ArrayList<>(4);

    El authorEl = row()
        .align(Style.Align.CENTER)
        .gap(theme.space().m())
        .child(Avatar.avatar(commit.author(), null, theme.size().controlSmall()))
        .child(Label.label(commit.author()));
    propList.add(Properties.property(texts.author(), authorEl, commit.author()));

    String fullDate = texts.fullDate(commit.time().atZone(state.clock().getZone()));
    String relativeDate = texts.relativeDate(commit.time(), state.clock());
    El dateEl = row()
        .align(Style.Align.CENTER)
        .gap(theme.space().s())
        .child(Label.label(fullDate))
        .child(Label.label(Label.Variant.MUTED, "· " + relativeDate));
    propList.add(Properties.property(texts.date(), dateEl, fullDate));

    if (!commit.parents().isEmpty()) {
      El parentsRow = row().align(Style.Align.CENTER).gap(theme.space().s()).wrap();
      List<String> shortParents = new ArrayList<>(commit.parents().size());
      for (String parentId : commit.parents()) {
        String parentShort = GitLog.shortHash(parentId);
        shortParents.add(parentShort);
        int rowIdx = rowOf(commits, parentId, cursor + 1);
        if (rowIdx >= 0) {
          int dotColor = GitLog.laneColor(theme, graph.color(rowIdx));
          String tip = commits.get(rowIdx).subject();
          parentsRow.child(commitChip(
              "parent-" + parentShort,
              parentId,
              tip,
              dotColor,
              () -> state.select(rowIdx),
              theme,
              texts));
        } else {
          String tip = parentId.length() > parentShort.length() ? parentId : null;
          parentsRow.child(commitChip(
              "parent-" + parentShort, parentId, tip, theme.text().disabled(), null, theme, texts));
        }
      }
      String parentPropName = commit.parents().size() == 1 ? texts.parent() : texts.parents();
      propList.add(
          Properties.property(parentPropName, parentsRow, String.join(", ", shortParents)));
    }

    List<Ref> commitRefs = new ArrayList<>();
    for (Ref ref : refs) {
      Objects.requireNonNull(ref, "ref");
      if (commit.id().equals(ref.commit())) {
        commitRefs.add(ref);
      }
    }
    if (!commitRefs.isEmpty()) {
      int laneColor = GitLog.laneColor(theme, graph.color(cursor));
      El pills = RefPills.pills(
              commitRefs,
              laneColor,
              theme.surface().band(),
              theme.size().field(),
              commitRefs.size())
          .wrap()
          .shrink(1)
          .minSize(0, 0);
      String refText = String.join(", ", RefPills.labels(commitRefs, texts));
      propList.add(Properties.property(texts.refs(), pills, refText));
    }

    body.child(propertiesBox(propList, theme));
    appendChangesAndDiff(body, options, theme, texts);

    return Card.flush(header, Divider.divider(), body).key("details").label(texts.commit());
  }

  private static El rangeDetails(
      GitLog.State state,
      List<Commit> commits,
      CommitGraph graph,
      Options options,
      El[] actions,
      Theme theme,
      DiagramTexts texts) {
    int start = state.rangeStart();
    int end = state.rangeEnd();
    int count = end - start + 1;
    Commit fromCommit = commits.get(end);
    Commit toCommit = commits.get(start);

    El countBadge = Badge.badge(texts.commits(count), Badge.options().toolbar())
        .radius(theme.badge().toolbar() / 2);
    El header = Card.toolbar(texts.compare(), null, countBadge, Card.spacer());

    El body = scrollBody(fromCommit.id() + ".." + toCommit.id(), theme);

    List<Properties.Property> propList = new ArrayList<>(3);
    propList.add(
        rangeEndProperty(texts.from(), "from", end, fromCommit, graph, state, theme, texts));
    propList.add(rangeEndProperty(texts.to(), "to", start, toCommit, graph, state, theme, texts));

    Set<String> authors = new LinkedHashSet<>();
    for (int i = start; i <= end; i++) {
      authors.add(commits.get(i).author());
    }
    El avatars = row().align(Style.Align.CENTER).gap(theme.space().xs());
    int shown = 0;
    for (String author : authors) {
      if (shown++ >= MAX_RANGE_AVATARS) {
        break;
      }
      avatars.child(Avatar.avatar(author, null, theme.size().controlSmall()));
    }
    String authorsCountText = texts.authors(authors.size());
    El authorsEl = row()
        .align(Style.Align.CENTER)
        .gap(theme.space().m())
        .child(avatars)
        .child(Label.label(Label.Variant.MUTED, authorsCountText));
    propList.add(Properties.property(texts.authors(), authorsEl, authorsCountText));

    body.child(propertiesBox(propList, theme));

    if (actions.length > 0) {
      El actionRow = row()
          .key("actions")
          .shrink(0)
          .align(Style.Align.CENTER)
          .gap(theme.space().m());
      for (El action : actions) {
        actionRow.child(action.shrink(0));
      }
      body.child(actionRow);
    }

    appendChangesAndDiff(body, options, theme, texts);

    return Card.flush(header, Divider.divider(), body).key("details").label(texts.compare());
  }

  private static Properties.Property rangeEndProperty(
      String name,
      String key,
      int rowIdx,
      Commit commit,
      CommitGraph graph,
      GitLog.State state,
      Theme theme,
      DiagramTexts texts) {
    int dotColor = GitLog.laneColor(theme, graph.color(rowIdx));
    String shortHash = GitLog.shortHash(commit.id());
    String relativeDate = texts.relativeDate(commit.time(), state.clock());
    String fullDate = texts.fullDate(commit.time().atZone(state.clock().getZone()));
    El chip = commitChip(
        key, commit.id(), commit.subject(), dotColor, () -> state.select(rowIdx), theme, texts);
    El dateLabel = Tooltip.tooltip(Label.label(Label.Variant.MUTED, relativeDate), fullDate);
    El value =
        row().align(Style.Align.CENTER).gap(theme.space().m()).child(chip).child(dateLabel);
    return Properties.property(name, value, shortHash);
  }

  /** The scrolled body, keyed by what it shows so that another selection starts at its top. */
  private static El scrollBody(String shown, Theme theme) {
    return col()
        .key("body-" + shown)
        .grow(1)
        .minSize(0, 0)
        .scroll()
        .scrollbars(theme.scrollbars())
        .padding(theme.space().xl())
        .gap(theme.space().l());
  }

  private static El propertiesBox(List<Properties.Property> propList, Theme theme) {
    return col()
        .key("properties")
        .shrink(0)
        .padding(theme.space().s(), theme.space().l())
        .radius(theme.radius().medium())
        .fill(theme.surface().band())
        .border(theme.size().stroke(), theme.border().subtle())
        .child(Properties.properties(propList, null));
  }

  private static void appendChangesAndDiff(
      El body, Options options, Theme theme, DiagramTexts texts) {
    if (options.changes != null && !options.changes.isEmpty()) {
      List<Change> changes = options.changes;
      int totalAdded = 0;
      int totalRemoved = 0;
      for (Change change : changes) {
        Objects.requireNonNull(change, "change");
        totalAdded += change.added();
        totalRemoved += change.removed();
      }

      Theme.TextStyle mono = theme.type().mono();
      El sectionHead = row()
          .shrink(0)
          .align(Style.Align.CENTER)
          .gap(theme.space().m())
          .child(
              Label.label(Label.Variant.STRONG, texts.changes()).role("heading").level(4))
          .child(Badge.badge(texts.files(changes.size())).radius(theme.badge().row() / 2))
          .child(el().grow(1))
          .child(El.text("+" + totalAdded)
              .font(mono.font().get())
              .fontSize(mono.size())
              .color(theme.status().success())
              .shrink(0))
          .child(El.text("\u2212" + totalRemoved)
              .font(mono.font().get())
              .fontSize(mono.size())
              .color(theme.status().danger())
              .shrink(0));

      El filesSection = col().shrink(0).gap(theme.space().s()).child(sectionHead);
      El fileList = col()
          .key("files")
          .role("list")
          .label(texts.changes())
          .gap(theme.space().xs());
      for (int i = 0; i < changes.size(); i++) {
        Change change = changes.get(i);
        boolean isChosen = Objects.equals(change, options.chosen);
        fileList.child(fileRow(i, change, isChosen, options.onChoose, theme, texts));
      }
      filesSection.child(fileList);
      body.child(filesSection);
    }

    if (options.diff != null) {
      body.child(options.diff.shrink(0));
    }
  }

  private static El fileRow(
      int index,
      Change change,
      boolean chosen,
      Consumer<Change> onChoose,
      Theme theme,
      DiagramTexts texts) {
    Badge.Kind badgeKind = switch (change.status()) {
      case ADDED -> Badge.Kind.SUCCESS;
      case MODIFIED -> Badge.Kind.WARNING;
      case DELETED -> Badge.Kind.DANGER;
      case RENAMED -> Badge.Kind.ACCENT;
    };
    String badgeLetter = switch (change.status()) {
      case ADDED -> "A";
      case MODIFIED -> "M";
      case DELETED -> "D";
      case RENAMED -> "R";
    };
    String statusName = switch (change.status()) {
      case ADDED -> texts.changeAdded();
      case MODIFIED -> texts.changeModified();
      case DELETED -> texts.changeDeleted();
      case RENAMED -> texts.changeRenamed();
    };

    float badgeSize = theme.badge().row();
    El statusBadge =
        Badge.badge(badgeKind, badgeLetter).w(badgeSize).padding(0).justify(Style.Content.CENTER);

    String name = fileName(change.path());
    String dir = shortDir(change.path());
    Theme.TextStyle strong = theme.type().strong();
    Theme.TextStyle small = theme.type().small();
    Theme.TextStyle mono = theme.type().mono();

    El nameEl = El.text(name)
        .font(strong.font().get())
        .fontSize(strong.size())
        .color(theme.text().primary())
        .ellipsis()
        .shrink(1)
        .minSize(0, 0);

    El dirEl = dir.isEmpty()
        ? el().grow(1)
        : El.text(dir)
            .font(small.font().get())
            .fontSize(small.size())
            .color(theme.text().muted())
            .ellipsis()
            .grow(1)
            .shrink(GitLog.SUBJECT_SHRINK)
            .minSize(0, 0);

    El stats = row()
        .align(Style.Align.CENTER)
        .gap(theme.space().s())
        .shrink(0)
        .child(El.text("+" + change.added())
            .font(mono.font().get())
            .fontSize(mono.size())
            .color(theme.status().success())
            .shrink(0))
        .child(El.text("\u2212" + change.removed())
            .font(mono.font().get())
            .fontSize(mono.size())
            .color(theme.status().danger())
            .shrink(0))
        .child(statBar(change.added(), change.removed(), theme));

    String rowLabel =
        statusName + " " + change.path() + ", +" + change.added() + " \u2212" + change.removed();
    El file = row()
        .key("file-" + index)
        .label(rowLabel)
        .selected(chosen)
        .h(theme.size().paletteRow())
        .align(Style.Align.CENTER)
        .gap(theme.space().m())
        .padding(0, theme.space().m())
        .radius(theme.radius().small())
        .motion(theme.motion().feedback())
        .child(statusBadge)
        .child(nameEl)
        .child(dirEl)
        .child(stats);

    int selectedFill = theme.grid().selected();
    if (chosen) {
      file.fill(selectedFill);
    }
    if (onChoose != null) {
      file.role("button")
          .focusable()
          .keepFocus()
          .focusRing(theme.accent().edge())
          .cursor(Cursor.POINTER)
          .onPress(() -> onChoose.accept(change));
      if (!chosen) {
        file.whenHovered(e -> e.fill(theme.surface().hover()))
            .whenPressed(e -> e.fill(theme.surface().pressed()));
      } else {
        file.whenHovered(e -> e.fill(Colors.over(selectedFill, theme.surface().hover())))
            .whenPressed(e -> e.fill(Colors.over(selectedFill, theme.surface().pressed())));
      }
    } else {
      file.role("listitem");
    }

    String tip = change.status() == Change.Status.RENAMED
        ? change.oldPath() + " → " + change.path()
        : change.path();
    return Tooltip.tooltip(file, tip);
  }

  private static El statBar(int added, int removed, Theme theme) {
    El bar = row().gap(theme.space().xs()).align(Style.Align.CENTER).shrink(0);
    int total = added + removed;
    int green;
    if (total == 0 || added == 0) {
      green = 0;
    } else if (removed == 0) {
      green = BAR_SEGMENTS;
    } else {
      green = Math.clamp(Math.round((float) BAR_SEGMENTS * added / total), 1, BAR_SEGMENTS - 1);
    }
    float width = theme.space().s();
    float height = theme.space().m();
    float radius = theme.space().xs();
    for (int i = 0; i < BAR_SEGMENTS; i++) {
      int fill = total == 0
          ? theme.border().control()
          : (i < green ? theme.status().success() : theme.status().danger());
      bar.child(el().size(width, height).shrink(0).radius(radius).fill(fill));
    }
    return bar;
  }

  private static El commitChip(
      String key,
      String id,
      String tooltip,
      int dotColor,
      Runnable onPress,
      Theme theme,
      DiagramTexts texts) {
    String shortHash = GitLog.shortHash(id);
    Theme.TextStyle mono = theme.type().mono();
    float dotSize = theme.space().s() + theme.space().xs();
    El chip = row()
        .key(key)
        .label(texts.commitLabel(shortHash))
        .h(theme.size().controlSmall())
        .shrink(0)
        .align(Style.Align.CENTER)
        .gap(theme.space().s() + theme.space().xs())
        .padding(0, theme.space().m())
        .radius(theme.radius().medium())
        .fill(theme.surface().raised())
        .border(theme.size().stroke(), theme.border().control())
        .motion(theme.motion().feedback())
        .child(el().size(dotSize, dotSize).shrink(0).radius(dotSize / 2).fill(dotColor))
        .child(El.text(shortHash)
            .font(mono.font().get())
            .fontSize(mono.size())
            .color(theme.text().primary()));
    if (onPress != null) {
      chip.role("button")
          .focusable()
          .keepFocus()
          .focusRing(theme.accent().edge())
          .cursor(Cursor.POINTER)
          .onPress(onPress)
          .whenHovered(e -> e.border(theme.size().stroke(), theme.accent().edge()))
          .whenPressed(
              e -> e.fill(Colors.over(theme.surface().raised(), theme.surface().pressed()))
                  .border(theme.size().stroke(), theme.accent().edge()));
    }
    return tooltip != null ? Tooltip.tooltip(chip, tooltip) : chip;
  }

  /** The row of {@code id} at or after {@code from}, where topological order puts a parent. */
  private static int rowOf(List<Commit> commits, String id, int from) {
    for (int i = from; i < commits.size(); i++) {
      if (commits.get(i).id().equals(id)) {
        return i;
      }
    }
    return -1;
  }

  private static String fileName(String path) {
    int slash = path.lastIndexOf('/');
    return slash >= 0 ? path.substring(slash + 1) : path;
  }

  private static String shortDir(String path) {
    int slash = path.lastIndexOf('/');
    if (slash <= 0) {
      return "";
    }
    String dir = path.substring(0, slash);
    String[] parts = dir.split("/");
    if (parts.length > 3) {
      return parts[0] + "/…/" + parts[parts.length - 2] + "/" + parts[parts.length - 1];
    }
    return dir;
  }
}
