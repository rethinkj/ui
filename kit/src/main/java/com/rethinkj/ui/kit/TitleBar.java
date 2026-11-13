package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.WindowPart;

/**
 * The title bar of a frameless window ({@code Window.options().frameless()}): a coloured mark or
 * the app's icon, the title, a menu bar if the app has one ({@link MenuBar#menuBar}), the app's
 * own controls, and minimize, maximize and close buttons, on the window's background. The system
 * moves the window by the bar, double-clicks maximize it, and acts on the buttons ({@link
 * El#windowPart}); the app's controls and the menu bar's titles take their presses, as they have
 * handlers. The buttons are lighter under the pointer and darker while pressed, the close button
 * red with a white cross under the pointer and a lighter red while pressed ({@link
 * Theme.Caption}), as the system's are. Minimize and close say what they do in a tooltip;
 * maximize has none, since Windows 11 shows its snap layouts there.
 *
 * <pre>{@code
 * col().wFull().hFull().children(TitleBar.titleBar(t, "Workbench", t.accent().color()), content)
 * }</pre>
 *
 * <p>The bar is 32 high and each button 46 wide, Windows 11's own measures. In a tree dump the
 * buttons are {@code role=button} with the labels {@code Minimize}, {@code Maximize} (or {@code
 * Restore} while maximized) and {@code Close}. On Windows the system takes the presses on
 * maximize for its snap layouts, so that button shows no pressed look there.
 */
public final class TitleBar {

  /** The height of the bar and of its buttons, as Windows 11 draws its own. */
  public static final float HEIGHT = 32;
  /** The width of a caption button, as Windows 11 draws its own. */
  public static final float BUTTON = 46;

  private TitleBar() {}

  /**
   * A title bar with a coloured mark before the title.
   *
   * @param mark the mark's colour, 0xAARRGGBB, usually the app's
   * @param controls the app's own, before the window's buttons
   */
  public static El titleBar(Theme t, String title, int mark, El... controls) {
    return titleBar(t, title, mark(mark), null, false, controls);
  }

  /**
   * A title bar with a menu bar after the title; the gaps between its titles move the window as
   * the rest of the bar does.
   */
  public static El withMenus(Theme t, String title, int mark, El menus, El... controls) {
    return titleBar(t, title, mark(mark), menus, false, controls);
  }

  /**
   * A title bar with any element before the title, such as the app's icon ({@link AppIcon}).
   *
   * @param icon before the title; null for none
   * @param menus a menu bar after the title; null for none
   * @param maximized whether the window is maximized, which the maximize button's glyph and label
   *     show as Restore
   * @param controls the app's own, before the window's buttons
   */
  public static El titleBar(Theme t, String title, El icon, El menus, boolean maximized,
      El... controls) {
    int glyph = t.text().primary();
    El bar = row().wFull().h(HEIGHT).shrink(0).align(Style.Align.CENTER)
        .fill(t.surface().window())
        .windowPart(WindowPart.DRAG);
    if (icon != null) {
      bar.child(icon.margin(0, 0, 0, t.space().l()));
    }
    bar.child(label(t, Label.Variant.STRONG, title).margin(0, 0, 0, t.space().m()));
    if (menus != null) {
      bar.child(menus.margin(0, 0, 0, t.space().m()));
    }
    bar.child(el().grow(1));
    if (controls.length > 0) {
      bar.child(row().gap(t.space().l()).margin(0, t.space().l(), 0, 0)
          .align(Style.Align.CENTER).children(controls));
    }
    El restore = maximized
        ? Icon.icon(t, Symbol.SQUARE_MULTIPLE, Icon.Variant.NORMAL, 12)
        : el().size(10, 10).border(1, glyph);
    return bar.child(Tooltip.tooltip(t,
            button(t, WindowPart.MINIMIZE, "Minimize", el().size(10, 1).fill(glyph)), "Minimize"))
        .child(button(t, WindowPart.MAXIMIZE, maximized ? "Restore" : "Maximize", restore))
        .child(Tooltip.tooltip(t, close(t), "Close"));
  }

  /** A small rounded square in the app's colour, before the title. */
  private static El mark(int argb) {
    return el().size(10, 10).radius(3).fill(argb);
  }

  /**
   * A caption button, lighter under the pointer and darker while pressed, as the system's are.
   * The system acts on it; the handler, which does nothing, makes it the press's target, so that
   * it shows the press.
   */
  private static El button(Theme t, WindowPart part, String label, El glyph) {
    return el().role("button").label(label).size(BUTTON, HEIGHT).center().windowPart(part)
        .child(glyph)
        .onPress(() -> {})
        .whenHovered(b -> b.fill(t.surface().hover()))
        .whenPressed(b -> b.fill(t.surface().pressed()));
  }

  /** The close button, red with a white cross under the pointer, as the system's is. */
  private static El close(Theme t) {
    El cross = Icon.icon(t, Symbol.DISMISS, Icon.Variant.NORMAL, 12);
    return button(t, WindowPart.CLOSE, "Close", cross).whenHovered(b -> {
      b.fill(t.caption().closeHover());
      cross.color(t.caption().onClose());
    }).whenPressed(b -> {
      b.fill(t.caption().closePressed());
      cross.color(t.caption().onClose());
    });
  }
}
