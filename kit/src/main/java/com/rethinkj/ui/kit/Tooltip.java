package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Side;
import com.rethinkj.ui.Style;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A short text, or any content, that shows below an element, centered on it, once the pointer has
 * rested on the element for {@link Theme.Motion#tooltipDelay}, and goes when the pointer leaves or
 * presses; and likewise once the keyboard has given the element the focus, with its ring, for as
 * long, until the focus leaves or a key goes down. Once one has shown, the tooltip of the next
 * element the pointer comes to shows at once, as along a toolbar ({@link El#whenHoveredFor}). It
 * needs no state: the app's clock carries the delay ({@link El#whenHoveredFor}, {@link
 * El#whenFocusVisibleFor}), and an idle window wakes for it without drawing frames meanwhile. Where
 * there is no room below, it shows above, and where it would cover the element, beside it.
 *
 * <p>It lies on the popup surface with the control border, the medium radius and the shadow of a
 * card, its text in the small type, wrapping where it would be wider than {@link
 * Theme.Sizes#tooltip}. Its {@link Options} add the key combination of the anchor's action on
 * keycaps, a muted description below the text, and a {@link Callout.Kind} with the callout's icon
 * and tint, for an error at a field. Content of the view's own, such as what an update brings, takes
 * the place of the text:
 *
 * <pre>{@code
 * Tooltip.tooltip(run, "Run statement", Tooltip.options().shortcut(Key.CONTROL, "Enter"))
 * Tooltip.tooltip(field, "Unclosed group", Tooltip.options().kind(Callout.Kind.DANGER))
 * Tooltip.tooltip(update, () -> changes(state))
 * }</pre>
 *
 * <p>In a tree dump it is an overlay of the element, {@code role=tooltip} with its text as the
 * label.
 */
public final class Tooltip {

  private Tooltip() {}

  /** What a tooltip shows beyond its text, and where; {@link #options()} is a plain one. */
  public static final class Options {
    private Key shortcut;
    private String description;
    private Callout.Kind kind;
    private Side side = Side.BOTTOM;
    private Style.Align align = Style.Align.CENTER;

    private Options() {}

    /**
     * The key combination of the anchor's action, after the text on keycaps, its keys named as a
     * menu writes them ({@link Menu#shortcutText}). It only shows: the app binds the key where it
     * binds its other keys.
     *
     * @param modifiers a combination of {@link Key#SHIFT}, {@link Key#CONTROL}, {@link Key#ALT}
     *     and {@link Key#META}
     * @param key the key's name, as {@link Key} lists them: {@code C}, {@code F5}, {@code Enter}
     */
    public Options shortcut(int modifiers, String key) {
      return shortcut(Menu.Item.combination(modifiers, key));
    }

    /** As {@link #shortcut(int, String)}; null for none. */
    Options shortcut(Key key) {
      this.shortcut = key;
      return this;
    }

    /** A text below, muted, that wraps as the text does; null, the default, for none. */
    public Options description(String text) {
      this.description = text;
      return this;
    }

    /**
     * Shows the kind's icon before the text on the tint of a {@link Callout} of that kind: {@link
     * Callout.Kind#DANGER} for an error at a field. Null, the default, for a plain tooltip.
     */
    public Options kind(Callout.Kind kind) {
      this.kind = kind;
      return this;
    }

    /** Where it shows beside the anchor, and how it lines up with it: below and centered. */
    public Options side(Side side, Style.Align align) {
      this.side = Objects.requireNonNull(side, "side");
      this.align = Objects.requireNonNull(align, "align");
      return this;
    }
  }

  public static Options options() {
    return new Options();
  }

  /**
   * Gives {@code anchor} a tooltip, in place of any other {@link El#whenHoveredFor} and {@link
   * El#whenFocusVisibleFor} look it has.
   *
   * @return the anchor
   */
  public static El tooltip(El anchor, String text) {
    return tooltip(anchor, text, options());
  }

  /** As {@link #tooltip(El, String)}, with a shortcut, a description, a kind or another side. */
  public static El tooltip(El anchor, String text, Options options) {
    Objects.requireNonNull(text, "text");
    Theme theme = Theme.CURRENT.get();
    return tooltip(theme, anchor, () -> panel(theme, text(theme, text), text, options));
  }

  /**
   * A tooltip with content of the view's own in place of a text, such as a short document of what
   * an update brings. {@code content} builds it once the tooltip is due, and again for each frame
   * it shows; it may return null for none. Give the content its width; a tooltip takes no input
   * and goes as the pointer leaves the anchor, so what is to be scrolled or pressed belongs in a
   * {@link Popover}.
   */
  public static El tooltip(El anchor, Supplier<El> content) {
    return tooltip(anchor, content, options());
  }

  /** As {@link #tooltip(El, Supplier)}, with a shortcut, a description, a kind or another side. */
  public static El tooltip(El anchor, Supplier<El> content, Options options) {
    Objects.requireNonNull(content, "content");
    Theme theme = Theme.CURRENT.get();
    return tooltip(theme, anchor, () -> {
      El shown = content.get();
      return shown == null ? null : panel(theme, shown, null, options);
    });
  }

  /**
   * A tooltip with the whole text of an anchor whose text may be cut short ({@link El#ellipsis}):
   * as {@link #tooltip}, but it shows only while a text of the anchor ends in an ellipsis.
   */
  public static El whenShortened(El anchor, String text) {
    Theme theme = Theme.CURRENT.get();
    Consumer<El> show = e -> {
      if (e.overlays().stream().noneMatch(o -> WHOLE.equals(o.key()) || KEY.equals(o.key()))) {
        e.overlay(panel(theme, text(theme, text), text, options()).key(WHOLE).whenShortened());
      }
    };
    return anchor
        .whenHoveredFor(theme.motion().tooltipDelay(), show)
        .whenFocusVisibleFor(theme.motion().tooltipDelay(), show);
  }

  private static El tooltip(Theme theme, El anchor, Supplier<El> panel) {
    Consumer<El> show = e -> show(e, panel);
    return anchor
        .whenHoveredFor(theme.motion().tooltipDelay(), show)
        .whenFocusVisibleFor(theme.motion().tooltipDelay(), show);
  }

  /**
   * A hover-only tooltip whose text is read when the delay is due; null means none. A virtual
   * row can decide from its laid-out text then, without an overlay or a focus look on every row.
   */
  static El whenHovered(Theme theme, El anchor, Supplier<String> text) {
    return anchor.whenHoveredFor(
        theme.motion().tooltipDelay(),
        e -> show(e, () -> {
          String tip = text.get();
          return tip == null ? null : panel(theme, tip);
        }));
  }

  private static void show(El anchor, Supplier<El> panel) {
    if (anchor.overlays().stream().noneMatch(o -> KEY.equals(o.key()))) { // one for both looks
      El shown = panel.get();
      if (shown != null) {
        // A tooltip the view gives stands in place of the one with the anchor's whole text.
        anchor.overlays().stream().filter(o -> WHOLE.equals(o.key())).forEach(o -> o.opacity(0));
        anchor.overlay(shown);
      }
    }
  }

  private static final String KEY = "tooltip";
  private static final String WHOLE = "whole-text"; // the key of a cut text's tooltip

  /** The tooltip's overlay, for components that decide in their own look whether it shows. */
  static El panel(Theme theme, String text) {
    return panel(theme, text(theme, text), text, options());
  }

  private static El text(Theme theme, String text) {
    return wrapping(theme, label(Label.Variant.SMALL, text).color(theme.text().primary()));
  }

  private static El wrapping(Theme theme, El text) {
    text.wrapText().style().maxWidth = Len.px(theme.size().tooltip());
    return text;
  }

  private static El panel(Theme theme, El content, String label, Options options) {
    El words = content;
    if (options.shortcut != null) {
      El keys = row()
          .label(Menu.shortcutText(options.shortcut))
          .gap(theme.space().xs())
          .shrink(0);
      for (String key : Menu.shortcutKeys(options.shortcut)) {
        keys.child(Badge.badge(key));
      }
      words =
          row().align(Style.Align.CENTER).gap(theme.space().m()).child(words).child(keys);
    }
    if (options.description != null) {
      words = col()
          .gap(theme.space().xs())
          .child(words)
          .child(wrapping(
              theme,
              label(Label.Variant.SMALL, options.description)
                  .color(
                      options.kind == null
                          ? theme.text().muted()
                          : theme.callout().text())));
    }
    El panel = row()
        .key(KEY)
        .role("tooltip")
        .label(label)
        .inert() // it only shows, also content of the view's own with links
        .keepFocus()
        .align(Style.Align.START)
        .gap(theme.space().s())
        .padding(theme.space().s(), theme.space().m())
        .radius(theme.radius().medium())
        .fill(
            options.kind == null
                ? theme.surface().popup()
                : theme.callout().of(options.kind).fill())
        .border(theme.size().stroke(), theme.border().control())
        .shadow(theme.elevation().card())
        .motion(theme.motion().fade())
        .side(options.side, options.align, theme.space().s());
    if (options.kind != null) {
      panel.child(Icon.icon(
              Callout.symbol(options.kind), Icon.Variant.MUTED, theme.size().icon())
          .color(theme.callout().of(options.kind).mark()));
    }
    return panel.child(words);
  }
}
