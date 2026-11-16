package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;
import static com.rethinkj.ui.kit.Theme.over;

import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Style;

/**
 * A surface set off from what it lies on, which holds what belongs together: a header with a title,
 * a description and actions at the right, any body, and buttons at the end ({@link Form#buttons}).
 * The children are stacked {@link Theme.Spacing#l} apart inside {@link Theme.Spacing#xl} of padding,
 * and the rounded corners ({@link Theme.Radii#large}) clip them:
 *
 * <pre>{@code
 * card(theme,
 *     Card.header(theme, "Query history", "The last 50 statements",
 *         button(theme, GHOST, "Clear", state::clear)),
 *     history,
 *     Form.buttons(theme, button(theme, SECONDARY, "Export…", state::export)))
 * }</pre>
 *
 * <p>A body that runs to the edges, such as a data grid, goes into a {@link #flush} card, whose
 * parts lie edge to edge and whose rounded corners clip them: a {@link #toolbar} at the top, the
 * body, a {@link #footer} at the bottom, with a {@link Divider#divider} where a hairline belongs. A
 * list with rounded rows, such as a {@link Tree}, goes into it through {@link #inset}:
 *
 * <pre>{@code
 * Card.flush(theme,
 *     Card.toolbar(theme, "orders", "1,000,000 rows", filter,
 *         button(theme, PRIMARY, Symbol.PLAY, "Run", state::run)),
 *     Divider.divider(theme),
 *     grid.grow(1),
 *     Card.footer(theme, label(theme, MUTED, "Row 2 of 1,000,000"), label(theme, MUTED, "18 ms")))
 * }</pre>
 *
 * <p>{@link Group} is a card whose body is rows, and {@link Tabs} a flush card whose first row is
 * the tab bar.
 *
 * <p>Cards lie on the window ({@link Theme.Surfaces#window}) on the panel's surface, lifted by
 * {@link Theme.Elevation#card}. They nest by their border: a card inside a card is {@link
 * Variant#OUTLINED}, on the same surface as the one around it, set off by its edge; its lift shows
 * little there. {@link Variant#FILLED} lies on the window, where it is a quieter card without edge
 * or lift; inside a card it would vanish. {@link Variant#ELEVATED} is for a card that lies above
 * others, as on a dashboard, and does not nest.
 *
 * <p>{@link #pressable} makes a whole card one press target, such as a connection in a list of
 * them, with an optional selected state.
 */
public final class Card {

  public enum Variant {
    /** The panel's surface with the subtle border and the card's lift: the default, which nests. */
    OUTLINED,
    /** The panel's surface without a visible edge or lift, quieter on the window. */
    FILLED,
    /** The panel's surface with the subtle border, lifted by {@link Theme.Elevation#popup}. */
    ELEVATED
  }

  private Card() {}

  /** An {@link Variant#OUTLINED} card. */
  public static El card(Theme theme, El... content) {
    return card(theme, Variant.OUTLINED, content);
  }

  public static El card(Theme theme, Variant variant, El... content) {
    return surface(theme, variant)
        .padding(theme.space().xl())
        .gap(theme.space().l())
        .children(content);
  }

  /**
   * An {@link Variant#OUTLINED} card without padding or gap, whose parts lie edge to edge and are
   * clipped by its rounded corners, such as a toolbar, a data grid and a footer. Null parts are
   * left out. It takes no minimum size from its parts, so that a list of rows in it scrolls inside
   * it; the part that fills the rest grows ({@code grid.grow(1)}).
   */
  public static El flush(Theme theme, El... parts) {
    El card = surface(theme, Variant.OUTLINED);
    for (El part : parts) {
      if (part == null) {
        continue;
      }
      // The part that grows fills the rest and gives way; the others keep their height.
      if (part.style().grow > 0) {
        part.style().minHeight = Len.ZERO;
      } else {
        part.shrink(0);
      }
      card.child(part);
    }
    card.style().minWidth = Len.ZERO;
    card.style().minHeight = Len.ZERO;
    return card;
  }

  /** A card's surface, edge, corners and lift, without padding. */
  static El surface(Theme theme, Variant variant) {
    El card = col().radius(theme.radius().large()).clip().motion(theme.motion().feedback());
    return switch (variant) {
      case OUTLINED ->
        card.fill(theme.surface().panel())
            .border(theme.size().stroke(), theme.border().subtle())
            .shadow(theme.elevation().card());
      // The edge is there but transparent, so that the variants lay out alike.
      case FILLED -> card.fill(theme.surface().panel()).border(theme.size().stroke(), 0);
      case ELEVATED ->
        card.fill(theme.surface().panel())
            .border(theme.size().stroke(), theme.border().subtle())
            .shadow(theme.elevation().popup());
    };
  }

  /**
   * The top row of a {@link #flush} card: its title as a heading in {@link Theme.Type#heading}
   * (level 3 in a tree dump), a secondary text beside it in muted text, such as a count of rows,
   * and the actions at the right, such as a filter field and buttons, inside the card's padding.
   *
   * @param secondary beside the title; null for none
   * @param actions in the order shown
   */
  public static El toolbar(Theme theme, String title, String secondary, El... actions) {
    Theme.TextStyle style = theme.type().heading();
    El bar = row()
        .padding(theme.space().m(), theme.space().xl())
        .gap(theme.space().m())
        .align(Style.Align.CENTER)
        .child(El.text(title)
            .font(style.font().get())
            .fontSize(style.size())
            .color(theme.text().primary())
            .role("heading")
            .level(3)
            .ellipsis()
            .minSize(0, 0));
    if (secondary != null) {
      bar.child(label(theme, Label.Variant.MUTED, secondary).shrink(0));
    }
    bar.child(el().grow(1));
    for (El action : actions) {
      bar.child(action.shrink(0));
    }
    return bar;
  }

  /**
   * An optional filter bar below a toolbar, inside the card's side padding and apart from the
   * content below it. Returns null for no filters, so a card leaves the whole row out.
   */
  public static El filters(Theme theme, El filters) {
    return filters == null
        ? null
        : row()
            .padding(0, theme.space().xl(), theme.space().m(), theme.space().xl())
            .child(filters);
  }

  /** The height of a {@link #footer}, for what has to stay clear of it, as a {@link Toast}. */
  public static float footerHeight(Theme theme) {
    return theme.size().control() + theme.space().s() + theme.size().stroke();
  }

  /**
   * The bottom row of a {@link #flush} card: a quiet band ({@link Theme.Surfaces#band}) with a
   * hairline along its top, {@code start} at the left and the rest at the right, as status texts
   * in muted labels.
   *
   * @param start at the left; null for none
   * @param end at the right, in the order shown
   */
  public static El footer(Theme theme, El start, El... end) {
    El row = row()
        .h(footerHeight(theme))
        .shrink(0)
        .padding(0, theme.space().xl())
        .gap(theme.space().l())
        .align(Style.Align.CENTER)
        .fill(theme.surface().band())
        .border(theme.size().stroke(), 0, 0, 0, theme.border().divider());
    if (start != null) {
      row.child(start.shrink(1).minSize(0, 0));
    }
    row.child(el().grow(1));
    for (El part : end) {
      row.child(part.shrink(0));
    }
    return row;
  }

  /**
   * The body of a {@link #flush} card that is a list with rounded rows, such as a {@link Tree} or a
   * {@link NavList}: inset by {@link Theme.Spacing#s}, so that the rows' corners lie inside the
   * card's. It grows to fill the card.
   */
  public static El inset(Theme theme, El list) {
    El body = col().grow(1).padding(theme.space().s()).child(list.grow(1));
    body.style().minHeight = Len.ZERO;
    return body;
  }

  /**
   * The top of a card: its title in {@link Theme.Type#heading} with a description in muted text
   * below that wraps, and the actions at the right, centred on the text. The title is a heading of
   * level 3 in a tree dump, below a page's title and a section's heading.
   *
   * @param description below the title; null for none
   * @param actions buttons or a menu button, in the order shown
   */
  public static El header(Theme theme, String title, String description, El... actions) {
    El text = col()
        .grow(1)
        .gap(theme.space().xs())
        .child(label(theme, Label.Variant.HEADING, title).role("heading").level(3));
    text.style().minWidth = Len.ZERO;
    if (description != null) {
      text.child(label(theme, Label.Variant.MUTED, description).wrapText());
    }
    El header = row().gap(theme.space().m()).align(Style.Align.CENTER).child(text);
    for (El action : actions) {
      header.child(action.shrink(0));
    }
    return header;
  }

  /**
   * Makes a card one press target: the pointer and Enter or Space while it has the focus run
   * {@code onPress}, and the pointer shows its hover and pressed looks, the hover and pressed
   * overlays laid over its fill. Controls inside it, such as a menu button, keep their own
   * presses. A selected card has the {@link Theme.Accent#edge} edge. In a tree dump the card
   * is {@code role=button} with the label, and {@code selected} and {@code disabled} as they are.
   *
   * @param card a card of this class
   * @param label what the card stands for, such as a connection's name
   * @param selected whether the card is the selected one of its kind
   * @param onPress runs when the card is pressed; null disables it, which takes it out of the
   *     focus order and leaves its content to the app to draw as disabled
   * @return the card
   */
  public static El pressable(
      Theme theme, El card, String label, boolean selected, Runnable onPress) {
    card.role("button").label(label).selected(selected).disabled(onPress == null);
    if (selected) {
      card.border(theme.size().stroke(), theme.accent().edge());
    }
    if (onPress == null) {
      return card;
    }
    int fill = card.fill();
    boolean opaque = fill >>> 24 == 0xFF;
    int hover = opaque ? over(fill, theme.surface().hover()) : theme.surface().hover();
    int pressed =
        opaque ? over(fill, theme.surface().pressed()) : theme.surface().pressed();
    return card.focusable()
        .keepFocus()
        .focusRing(theme.accent().edge())
        .cursor(Cursor.POINTER)
        .onPress(onPress)
        .whenHovered(e -> e.fill(hover))
        .whenPressed(e -> e.fill(pressed));
  }
}
