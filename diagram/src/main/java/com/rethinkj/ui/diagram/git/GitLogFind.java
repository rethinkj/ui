package com.rethinkj.ui.diagram.git;

import static com.rethinkj.ui.El.row;

import com.rethinkj.ui.Colors;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.diagram.DiagramTexts;
import com.rethinkj.ui.kit.CommandPalette;
import com.rethinkj.ui.kit.FilterList;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.TextField;
import com.rethinkj.ui.kit.Theme;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Filter, find and "Go to ref" for {@link GitLog}: matches commits by subject, author or hash
 * prefix, highlights matched ranges, dims non-matching rows, jumps between matches, and opens a
 * fuzzy ref chooser on Control+G.
 */
final class GitLogFind {

  private GitLogFind() {}

  private record HighlightKey(String needle, int color) {}

  /** Normalizes the filter text; empty means no filter is active. */
  static String normalize(String filter) {
    return filter == null ? "" : filter.strip();
  }

  /** Whether {@code commit} matches {@code needle} by subject, author or commit hash prefix. */
  static boolean matches(Commit commit, String needle) {
    return containsIgnoreCase(commit.subject(), needle)
        || containsIgnoreCase(commit.author(), needle)
        || matchesHash(commit.id(), needle);
  }

  /** Counts commits matching {@code needle}. */
  static int countMatches(List<Commit> commits, String needle) {
    int matched = 0;
    for (Commit commit : commits) {
      if (matches(commit, needle)) {
        matched++;
      }
    }
    return matched;
  }

  /** Whether {@code text} contains {@code needle}, ignoring ASCII/Unicode case. */
  static boolean containsIgnoreCase(String text, String needle) {
    int len = needle.length();
    int limit = text.length() - len;
    for (int i = 0; i <= limit; i++) {
      if (text.regionMatches(true, i, needle, 0, len)) {
        return true;
      }
    }
    return false;
  }

  /** Whether {@code commitId} starts with {@code needle}, ignoring case. */
  static boolean matchesHash(String commitId, String needle) {
    return commitId.regionMatches(true, 0, needle, 0, needle.length());
  }

  /**
   * Finds the next ({@code direction > 0}) or previous ({@code direction < 0}) matching commit
   * index relative to {@code from}, wrapping at the ends; {@code -1} when none matches.
   */
  static int findNext(List<Commit> commits, String needle, int from, int direction) {
    int count = commits.size();
    if (count == 0 || needle.isEmpty()) {
      return -1;
    }
    int start;
    if (from < 0 || from >= count) {
      start = direction > 0 ? 0 : count - 1;
    } else {
      start = Math.floorMod(from + direction, count);
    }
    for (int step = 0; step < count; step++) {
      int i = Math.floorMod(start + step * direction, count);
      if (matches(commits.get(i), needle)) {
        return i;
      }
    }
    return -1;
  }

  /**
   * The next or previous match from the cursor; the first jump for a filter that looks like a hash
   * (four or more hex digits) goes to the first commit whose hash starts with it.
   */
  static int next(GitLog.State state, List<Commit> commits, String needle, int direction) {
    if (!needle.equals(state.jumped)) {
      state.jumped = needle;
      if (needle.length() >= 4 && needle.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
        for (int i = 0; i < commits.size(); i++) {
          if (matchesHash(commits.get(i).id(), needle)) {
            return i;
          }
        }
      }
    }
    return findNext(commits, needle, state.cursor(), direction);
  }

  /** Selects {@code row} and ensures the list scrolls it into view, also when already selected. */
  static void jumpTo(GitLog.State state, int row, float rowHeight) {
    El list = state.list;
    if (row < 0) {
      return;
    }
    int prev = state.cursor();
    state.select(row);
    if (row == prev && list != null) {
      double top = row * (double) rowHeight;
      double y = Math.clamp(list.scrollY(), Math.min(top, top + rowHeight - list.height()), top);
      state.scroll().scrollTo(list.scrollX(), y);
    }
  }

  /** Builds the header's filter field and, while active, its match count label. */
  static El filterControl(
      GitLog.State state,
      List<Commit> commits,
      String needle,
      int matchCount,
      float rowHeight,
      Theme theme,
      DiagramTexts texts) {
    El box = TextField.searchField(state.filter(), texts.filterCommits(), state::filter)
        .shrink(1)
        .minSize(0, 0);
    El field = box.children().getFirst();
    field
        .takeFocus(state.filterFocus == 0 ? null : state.filterFocus)
        .onKeyFirst(key -> handleFieldKey(key, state, commits, needle, rowHeight));
    El group = row().gap(theme.space().s()).align(Style.Align.CENTER).child(box);
    if (!needle.isEmpty()) {
      String text =
          matchCount == 0 ? texts.noMatches() : texts.commitMatches(matchCount, commits.size());
      group.child(Label.label(Label.Variant.SMALL, text)
          .color(theme.text().muted())
          .fontFeatures("+tnum")
          .shrink(0));
    }
    return group;
  }

  /** Handles Enter / Shift+Enter inside the filter field to jump between matches. */
  private static boolean handleFieldKey(
      Key key, GitLog.State state, List<Commit> commits, String needle, float rowHeight) {
    if (needle.isEmpty()) {
      return false;
    }
    int mods = key.modifiers();
    boolean enter = ("Enter".equals(key.name()) || "NumpadEnter".equals(key.name()))
        && (mods == 0 || mods == Key.SHIFT);
    if (!enter) {
      return false;
    }
    int target = next(state, commits, needle, mods == Key.SHIFT ? -1 : 1);
    jumpTo(state, target, rowHeight);
    return true;
  }

  /** Handles card-wide find shortcuts: Ctrl+F, Ctrl+G, F3 and Shift+F3. */
  static boolean handleCardKey(
      Key key,
      GitLog.State state,
      List<Commit> commits,
      List<Ref> refs,
      String needle,
      float rowHeight,
      DiagramTexts texts) {
    int mods = key.modifiers();
    switch (key.name()) {
      case "F" -> {
        if (mods != Key.CONTROL) {
          return false;
        }
        if (state.palette.isOpen()) {
          state.palette.close();
        }
        state.filterFocus++;
        return true;
      }
      case "G" -> {
        if (mods != Key.CONTROL) {
          return false;
        }
        openRefPalette(state, commits, refs, rowHeight, texts);
        return true;
      }
      case "F3" -> {
        if ((mods != 0 && mods != Key.SHIFT) || needle.isEmpty()) {
          return false;
        }
        int target = next(state, commits, needle, mods == Key.SHIFT ? -1 : 1);
        jumpTo(state, target, rowHeight);
        return true;
      }
      default -> {
        return false;
      }
    }
  }

  /** Opens the fuzzy "Go to ref" command palette for refs pointing at loaded commits. */
  private static void openRefPalette(
      GitLog.State state,
      List<Commit> commits,
      List<Ref> refs,
      float rowHeight,
      DiagramTexts texts) {
    Set<String> loaded = HashSet.newHashSet(commits.size());
    for (Commit commit : commits) {
      loaded.add(commit.id());
    }

    List<Ref> heads = new ArrayList<>();
    List<Ref> branches = new ArrayList<>();
    List<Ref> remotes = new ArrayList<>();
    List<Ref> tags = new ArrayList<>();
    for (Ref ref : refs) {
      if (!loaded.contains(ref.commit())) {
        continue;
      }
      switch (ref.kind()) {
        case HEAD -> heads.add(ref);
        case BRANCH -> branches.add(ref);
        case REMOTE -> remotes.add(ref);
        case TAG -> tags.add(ref);
      }
    }

    List<FilterList.Entry> entries =
        new ArrayList<>(heads.size() + branches.size() + remotes.size() + tags.size());
    for (Ref ref : heads) {
      entries.add(refEntry(ref, state, rowHeight, texts));
    }
    for (Ref ref : branches) {
      if (!isHeadBranch(ref, heads)) {
        entries.add(refEntry(ref, state, rowHeight, texts));
      }
    }
    for (Ref ref : remotes) {
      entries.add(refEntry(ref, state, rowHeight, texts));
    }
    for (Ref ref : tags) {
      entries.add(refEntry(ref, state, rowHeight, texts));
    }
    state.palette.ask(CommandPalette.Stage.choose(texts.goToRef(), entries, -1));
  }

  private static boolean isHeadBranch(Ref branch, List<Ref> heads) {
    for (Ref head : heads) {
      if (!RefPills.DETACHED.equals(head.name())
          && head.name().equals(branch.name())
          && head.commit().equals(branch.commit())) {
        return true;
      }
    }
    return false;
  }

  private static FilterList.Entry refEntry(
      Ref ref, GitLog.State state, float rowHeight, DiagramTexts texts) {
    Symbol symbol = switch (ref.kind()) {
      case HEAD, BRANCH -> Symbol.BRANCH;
      case REMOTE -> Symbol.CLOUD;
      case TAG -> Symbol.TAG;
    };
    String text = switch (ref.kind()) {
      case HEAD ->
        RefPills.DETACHED.equals(ref.name()) ? texts.detachedHead() : texts.headBranch(ref.name());
      case BRANCH, REMOTE, TAG -> ref.name();
    };
    // the row anew on choosing, as the history may have changed while the popup was open
    return FilterList.entry(text, () -> jumpTo(state, state.row(ref.commit()), rowHeight))
        .symbol(symbol)
        .detail(texts.refKind(ref.kind()))
        .key(ref.kind() + ":" + ref.name() + ":" + ref.commit());
  }

  /** Attaches background highlight spans for every case-insensitive match of {@code needle}. */
  static void highlightSubstrings(El textEl, String text, String needle, int background) {
    if (!containsIgnoreCase(text, needle)) {
      return;
    }
    int len = needle.length();
    textEl.highlight(new HighlightKey(needle, background), (line, before, spans) -> {
      int limit = line.length() - len;
      for (int i = 0; i <= limit; ) {
        if (line.regionMatches(true, i, needle, 0, len)) {
          spans.background(i, i + len, background);
          i += len;
        } else {
          i++;
        }
      }
      return null;
    });
  }

  /** Attaches a prefix highlight span on {@code shortHash} when {@code commitId} matches. */
  static void highlightPrefix(
      El textEl, String shortHash, String commitId, String needle, int background) {
    if (!matchesHash(commitId, needle)) {
      return;
    }
    int end = Math.min(needle.length(), shortHash.length());
    if (end <= 0) {
      return;
    }
    textEl.highlight(new HighlightKey(needle, background), (line, before, spans) -> {
      spans.background(0, Math.min(end, line.length()), background);
      return null;
    });
  }

  /** Mixes an {@link com.rethinkj.ui.kit.Avatar}'s fill and initials opaquely towards background. */
  static void dimAvatar(El avatar, int background, float fading) {
    int fill = Colors.mix(avatar.fill(), background, fading);
    avatar.fill(fill);
    if (!avatar.children().isEmpty()) {
      El initials = avatar.children().getFirst();
      if (initials.text() != null) {
        initials.color(Colors.mix(initials.text().color(), fill, fading));
      }
    }
  }
}
