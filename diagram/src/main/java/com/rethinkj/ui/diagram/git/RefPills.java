package com.rethinkj.ui.diagram.git;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;

import com.rethinkj.ui.Colors;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.diagram.DiagramTexts;
import com.rethinkj.ui.kit.Badge;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Tooltip;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Builds the row of ref pills for a single commit in a git log.
 *
 * <p>Refs appear in order: {@code HEAD}'s branch (or detached {@code HEAD}), local branches,
 * remote-tracking branches, then tags. A remote branch whose name after the first {@code /} matches
 * a local or {@code HEAD} branch on the same commit folds into that branch's pill with a trailing
 * cloud glyph. Names wider than the given limit end in an ellipsis and show in full in the pill's
 * tooltip; refs beyond the given maximum collapse into a neutral {@code +n} pill whose tooltip
 * lists the hidden names.
 */
public final class RefPills {

  /** The branch name of a detached {@link Ref.Kind#HEAD}. */
  static final String DETACHED = "HEAD";

  /** Opacity of a local branch pill's tinted fill in linear light. */
  private static final float TINT_ALPHA = 0.14f;

  /** Minimum WCAG contrast ratio for text and icons on a pill's background. */
  private static final double MIN_CONTRAST = 4.5;

  /** Handler notified when the user secondary-presses a ref pill. */
  @FunctionalInterface
  interface RefPress {
    void press(Ref ref, float x, float y);
  }

  private RefPills() {}

  /**
   * Builds a horizontal row of pills for {@code refs} on one commit.
   *
   * @param refs the commit's refs, in any order
   * @param lane the ARGB color of the commit's lane, used for branch, HEAD and remote pills
   * @param background the row's opaque background color (normal, hovered or selected), used so that
   *     branch and remote pill text meets 4.5:1 contrast on it
   * @param nameWidth maximum width in logical pixels of each pill's name text before it ends in an
   *     ellipsis
   * @param most maximum number of ref pills shown before remaining refs collapse into a {@code +n}
   *     pill
   */
  public static El pills(List<Ref> refs, int lane, int background, float nameWidth, int most) {
    return pills(refs, lane, background, nameWidth, most, null);
  }

  /** Like {@link #pills(List, int, int, float, int)}, with an optional secondary-press handler. */
  static El pills(
      List<Ref> refs,
      int lane,
      int background,
      float nameWidth,
      int most,
      RefPress onSecondaryPress) {
    Objects.requireNonNull(refs, "refs");
    if (!(nameWidth > 0 && Float.isFinite(nameWidth))) {
      throw new IllegalArgumentException("nameWidth must be positive and finite, was " + nameWidth);
    }
    if (most < 0) {
      throw new IllegalArgumentException("most must be 0 or more, was " + most);
    }
    Theme theme = Theme.CURRENT.get();
    DiagramTexts texts = DiagramTexts.CURRENT.get();
    El strip = row().gap(theme.space().s()).align(Style.Align.CENTER).shrink(0);
    List<Pill> items = order(refs);
    if (items.isEmpty()) {
      return strip;
    }
    int shown = Math.min(items.size(), most);
    for (int i = 0; i < shown; i++) {
      strip.child(pill(items.get(i), lane, background, nameWidth, theme, texts, onSecondaryPress));
    }
    if (items.size() > most) {
      List<Pill> hidden = items.subList(most, items.size());
      String names = hidden.stream()
          .flatMap(pill -> Stream.concat(Stream.of(pill.ref().name()), pill.remotes().stream()))
          .collect(Collectors.joining(", "));
      String tip = texts.moreRefs(hidden.size(), names);
      El overflow =
          Badge.badge("+" + hidden.size()).radius(theme.badge().row() / 2).label(tip);
      strip.child(Tooltip.tooltip(overflow, tip));
    }
    return strip;
  }

  /** Ordered, folded ref labels for a commit row's accessible label in a tree dump. */
  static List<String> labels(List<Ref> refs, DiagramTexts texts) {
    List<Pill> items = order(refs);
    if (items.isEmpty()) {
      return List.of();
    }
    List<String> out = new ArrayList<>(items.size());
    for (Pill item : items) {
      out.add(label(item, texts));
    }
    return out;
  }

  /** One pill to draw after ordering and folding synced remotes into their local branches. */
  private record Pill(Ref ref, boolean detached, List<String> remotes) {}

  private static List<Pill> order(List<Ref> refs) {
    List<Ref> heads = new ArrayList<>();
    List<Ref> branches = new ArrayList<>();
    List<Ref> remotes = new ArrayList<>();
    List<Ref> tags = new ArrayList<>();
    for (Ref ref : refs) {
      Objects.requireNonNull(ref, "ref");
      switch (ref.kind()) {
        case HEAD -> heads.add(ref);
        case BRANCH -> branches.add(ref);
        case REMOTE -> remotes.add(ref);
        case TAG -> tags.add(ref);
      }
    }
    boolean[] consumed = new boolean[remotes.size()];
    List<Pill> pills = new ArrayList<>(refs.size());
    for (Ref head : heads) {
      boolean detached = DETACHED.equals(head.name());
      List<String> synced = detached ? List.of() : takeRemotes(head, remotes, consumed);
      pills.add(new Pill(head, detached, synced));
    }
    for (Ref branch : branches) {
      if (isHeadBranch(branch, heads)) {
        continue;
      }
      List<String> synced = takeRemotes(branch, remotes, consumed);
      pills.add(new Pill(branch, false, synced));
    }
    for (int i = 0; i < remotes.size(); i++) {
      if (!consumed[i]) {
        pills.add(new Pill(remotes.get(i), false, List.of()));
      }
    }
    for (Ref tag : tags) {
      pills.add(new Pill(tag, false, List.of()));
    }
    return pills;
  }

  private static boolean isHeadBranch(Ref branch, List<Ref> heads) {
    for (Ref head : heads) {
      if (!DETACHED.equals(head.name())
          && head.name().equals(branch.name())
          && head.commit().equals(branch.commit())) {
        return true;
      }
    }
    return false;
  }

  private static List<String> takeRemotes(Ref local, List<Ref> remotes, boolean[] consumed) {
    List<String> matched = null;
    for (int i = 0; i < remotes.size(); i++) {
      if (consumed[i]) {
        continue;
      }
      Ref remote = remotes.get(i);
      if (local.commit().equals(remote.commit())
          && local.name().equals(remoteBranch(remote.name()))) {
        consumed[i] = true;
        if (matched == null) {
          matched = new ArrayList<>(1);
        }
        matched.add(remote.name());
      }
    }
    return matched == null ? List.of() : matched;
  }

  /** The branch name after the first {@code /} in {@code remoteName}, or null if none. */
  private static String remoteBranch(String remoteName) {
    int slash = remoteName.indexOf('/');
    return slash > 0 && slash < remoteName.length() - 1 ? remoteName.substring(slash + 1) : null;
  }

  private static El pill(
      Pill item,
      int lane,
      int background,
      float nameWidth,
      Theme theme,
      DiagramTexts texts,
      RefPress onSecondaryPress) {
    Theme.Badges badges = theme.badge();
    float height = badges.row();
    float iconSize = badges.text().size();
    float side = theme.space().s() + theme.space().xs();
    int solid = Colors.alpha(lane, 1);
    int opaqueBg = Colors.alpha(background, 1);
    int panel = theme.surface().panel();
    String tip = label(item, texts);

    El pill = row()
        .label(tip)
        .h(height)
        .minSize(0, 0) // shrinks to its name's ellipsis where the strip has less room
        .clip() // and below its glyphs, without spilling onto the next pill
        .align(Style.Align.CENTER)
        .gap(theme.space().xs())
        .padding(0, side);

    int ink = switch (item.ref().kind()) {
      case HEAD -> {
        // Of the theme's inks for a colored fill, the one that stands out most on the lane's color.
        int headInk = theme.text().onAccent();
        for (int candidate : new int[] {theme.text().primary(), panel}) {
          if (Colors.contrast(Colors.over(solid, candidate), solid)
              > Colors.contrast(Colors.over(solid, headInk), solid)) {
            headInk = candidate;
          }
        }
        float dot = theme.size().dot();
        pill.radius(height / 2)
            .fill(solid)
            .child(el().size(dot, dot)
                .shrink(0)
                .margin(0, theme.space().xs(), 0, 0)
                .radius(dot / 2)
                .fill(headInk));
        yield headInk;
      }
      case BRANCH -> {
        int fill = Colors.alpha(solid, TINT_ALPHA);
        int branchInk =
            readable(solid, Colors.over(opaqueBg, fill), theme.text().primary());
        pill.radius(height / 2)
            .fill(fill)
            .child(Icon.icon(Symbol.BRANCH, Icon.Variant.MUTED, iconSize).color(branchInk));
        yield branchInk;
      }
      case REMOTE -> {
        int remoteInk = readable(solid, opaqueBg, theme.text().primary());
        pill.radius(height / 2)
            .border(theme.size().stroke(), solid)
            .child(Icon.icon(Symbol.CLOUD, Icon.Variant.MUTED, iconSize).color(remoteInk));
        yield remoteInk;
      }
      case TAG -> {
        Theme.Tone neutral = badges.neutral();
        int tagInk = neutral.softText();
        pill.radius(theme.radius().small())
            .fill(neutral.soft())
            .child(Icon.icon(Symbol.TAG, Icon.Variant.MUTED, iconSize).color(tagInk));
        yield tagInk;
      }
    };

    El text = El.text(item.ref().name())
        .font(badges.text().font().get())
        .fontSize(badges.text().size())
        .color(ink)
        .ellipsis()
        .minSize(0, 0);
    text.style().maxWidth = Len.px(nameWidth);
    pill.child(text);

    if (!item.remotes().isEmpty()) {
      pill.child(Icon.icon(Symbol.CLOUD, Icon.Variant.MUTED, iconSize).color(ink));
    }
    if (onSecondaryPress != null) {
      pill.onSecondaryPress((x, y) -> onSecondaryPress.press(item.ref(), x, y));
    }
    return Tooltip.tooltip(pill, tip);
  }

  private static String label(Pill item, DiagramTexts texts) {
    String name = item.ref().name();
    String base = switch (item.ref().kind()) {
      case HEAD -> item.detached() ? texts.detachedHead() : texts.headBranch(name);
      case BRANCH -> texts.branch(name);
      case REMOTE -> texts.remoteBranch(name);
      case TAG -> texts.tag(name);
    };
    return item.remotes().isEmpty() ? base : texts.inSync(base, String.join(", ", item.remotes()));
  }

  /**
   * Returns {@code color} if it already meets {@link #MIN_CONTRAST} on {@code background}, else
   * mixes it toward {@code target} just enough to reach that ratio while keeping its hue.
   */
  private static int readable(int color, int background, int target) {
    if (Colors.contrast(color, background) >= MIN_CONTRAST) {
      return color;
    }
    double low = 0;
    double high = 1;
    for (int i = 0; i < 12; i++) {
      double mid = (low + high) / 2;
      int trial = Colors.over(background, Colors.mix(color, target, mid));
      if (Colors.contrast(trial, background) >= MIN_CONTRAST) {
        high = mid;
      } else {
        low = mid;
      }
    }
    return Colors.mix(color, target, high);
  }
}
