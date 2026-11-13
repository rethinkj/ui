package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
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
 * The children are stacked {@link Theme.Spacing#l} apart inside {@link Theme.Spacing#l} of padding,
 * and the rounded corners ({@link Theme.Radii#large}) clip them:
 *
 * <pre>{@code
 * card(t,
 *     Card.header(t, "Query history", "The last 50 statements", button(t, GHOST, "Clear", s::clear)),
 *     history,
 *     Form.buttons(t, button(t, SECONDARY, "Export…", s::export)))
 * }</pre>
 *
 * <p>A body that runs to the edges, such as a list or a data grid, goes into a card with {@code
 * .padding(0).gap(0)}: the corners still clip it. {@link Group} is such a card whose body is rows.
 *
 * <p>Cards nest by their border: a card inside a card is {@link Variant#OUTLINED}, on the same
 * surface as the one around it, set off by its edge alone. {@link Variant#FILLED} lies on the
 * window, where it is a quieter card; inside a card it would read as a hole. {@link
 * Variant#ELEVATED} is for a card that lies above others, as on a dashboard, and does not nest.
 *
 * <p>{@link #pressable} makes a whole card one press target, such as a connection in a list of
 * them, with an optional selected state.
 */
public final class Card {

  public enum Variant {
    /** The raised surface with the subtle border: the default, and the one that nests. */
    OUTLINED,
    /** The panel's surface without a visible edge, quieter on the window. */
    FILLED,
    /** The raised surface with the subtle border, lifted by {@link Theme.Elevation#popup}. */
    ELEVATED
  }

  private Card() {}

  /** An {@link Variant#OUTLINED} card. */
  public static El card(Theme t, El... content) {
    return card(t, Variant.OUTLINED, content);
  }

  public static El card(Theme t, Variant variant, El... content) {
    El card = col().padding(t.space().l()).gap(t.space().l())
        .radius(t.radius().large())
        .clip()
        .motion(t.motion().feedback())
        .children(content);
    return switch (variant) {
      case OUTLINED -> card.fill(t.surface().raised()).border(t.size().stroke(), t.border().subtle());
      // The edge is there but transparent, so that the variants lay out alike.
      case FILLED -> card.fill(t.surface().panel()).border(t.size().stroke(), 0);
      case ELEVATED -> card.fill(t.surface().raised()).border(t.size().stroke(), t.border().subtle())
          .shadow(t.elevation().popup());
    };
  }

  /**
   * The top of a card: its title in {@link Label.Variant#STRONG} with a description in muted text
   * below that wraps, and the actions at the right, centred on the text. The title is a heading of
   * level 3 in a tree dump, below a page's title and a section's heading.
   *
   * @param description below the title; null for none
   * @param actions buttons or a menu button, in the order shown
   */
  public static El header(Theme t, String title, String description, El... actions) {
    El text = col().grow(1).gap(t.space().xs())
        .child(label(t, Label.Variant.STRONG, title).role("heading").level(3));
    text.style().minWidth = Len.ZERO;
    if (description != null) {
      text.child(label(t, Label.Variant.MUTED, description).wrapText());
    }
    El header = row().gap(t.space().m()).align(Style.Align.CENTER).child(text);
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
      Theme t, El card, String label, boolean selected, Runnable onPress) {
    card.role("button").label(label).selected(selected).disabled(onPress == null);
    if (selected) {
      card.border(t.size().stroke(), t.accent().edge());
    }
    if (onPress == null) {
      return card;
    }
    int fill = card.fill();
    boolean opaque = fill >>> 24 == 0xFF;
    int hover = opaque ? over(fill, t.surface().hover()) : t.surface().hover();
    int pressed = opaque ? over(fill, t.surface().pressed()) : t.surface().pressed();
    return card.focusable().keepFocus()
        .focusRing(t.accent().edge())
        .cursor(Cursor.POINTER)
        .onPress(onPress)
        .whenHovered(e -> e.fill(hover))
        .whenPressed(e -> e.fill(pressed));
  }
}
