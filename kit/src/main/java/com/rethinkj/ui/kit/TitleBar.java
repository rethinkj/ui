package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.WindowPart;
import com.rethinkj.ui.WindowState;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The title bar of a frameless window ({@code Window.options().frameless()}): the app's icon, the
 * title, the app's own parts at the start, such as a menu bar ({@link MenuBar#menuBar}), an
 * optional field in the middle, such as a search ({@link #search}), the app's actions at the end,
 * and minimize, maximize and close buttons, on the window's background. The system moves the
 * window by the bar, double-clicks maximize it, and acts on the buttons ({@link El#windowPart});
 * the app's parts take their presses, as they have handlers.
 *
 * <pre>{@code
 * col().wFull().hFull().children(
 *     TitleBar.titleBar(AppIcon.appIcon(16, color, Symbol.DATABASE), "Workbench"), content)
 * }</pre>
 *
 * <p>The bar reads the window's state ({@link WindowState#CURRENT}): while the window is
 * maximized the maximize button shows Restore, and while it is inactive the title and the
 * buttons' glyphs are muted, as the system's are. The buttons are lighter under the pointer and
 * darker while pressed, the close button red with a white cross under the pointer and a lighter
 * red while pressed ({@link Theme.Caption}). Minimize and close say what they do in a tooltip;
 * maximize has none, since Windows 11 shows its snap layouts there.
 *
 * <p>In a narrow window the middle gives way first, then the title shortens, and what does not fit
 * of the start is cut, then of the actions; a gap that moves the window and the buttons keep their
 * width. With a middle, the title is cut rather than shortened. The bar is {@link Theme.Caption#height} high,
 * or {@link Theme.Caption#tall} with a field in the middle, and each button {@link
 * Theme.Caption#button} wide. In a tree dump the buttons are {@code role=button} with the labels
 * {@code Minimize}, {@code Maximize} (or {@code Restore} while maximized) and {@code Close}.
 */
public final class TitleBar {

  private TitleBar() {}

  /**
   * A title bar with the app's icon, its title and its actions.
   *
   * @param icon before the title, in a square of the theme's icon size, such as {@link
   *     AppIcon#appIcon}; null for none
   * @param end the app's actions, such as icon buttons ({@link Button#icon}), before the window's
   *     buttons
   */
  public static El titleBar(El icon, String title, El... end) {
    return titleBar(icon, title, List.of(), null, Arrays.asList(end));
  }

  /**
   * A title bar with parts of the app's own at the start, in the middle and at the end.
   *
   * @param icon before the title, in a square of the theme's icon size, such as {@link
   *     AppIcon#appIcon}; null for none
   * @param start after the title, such as a menu bar, or {@code List.of()}; the gaps between its
   *     parts move the window
   * @param center in the middle of the space between the start and the end, such as {@link
   *     #search}, which makes the bar {@link Theme.Caption#tall} and gives way first in a narrow
   *     window; null for none
   * @param end the app's actions, such as icon buttons ({@link Button#icon}), before the window's
   *     buttons, or {@code List.of()}
   */
  public static El titleBar(El icon, String title, List<El> start, El center, List<El> end) {
    Theme theme = Theme.CURRENT.get();
    WindowState window = WindowState.CURRENT.get();
    Theme.Caption caption = theme.caption();
    float height = center == null ? caption.height() : caption.tall();
    int glyph = window.active() ? theme.text().primary() : theme.text().muted();
    El bar = row()
        .wFull()
        .h(height)
        .shrink(0)
        .align(Style.Align.CENTER)
        .fill(theme.surface().window())
        .windowPart(WindowPart.DRAG);
    float inset = theme.space().m();
    // In a narrow window what the app gives at the start gives way first, then its actions; a gap
    // to drag and the window's buttons keep their width. The start takes only the room left over.
    El lead =
        row().grow(1).basis(0).hFull().minSize(0, 0).align(Style.Align.CENTER).clip();
    if (icon != null) {
      float side = theme.size().icon();
      lead.child(el().size(side, side).shrink(0).margin(0, 0, 0, inset).child(icon));
    }
    El name = label(Label.Variant.STRONG, title).color(glyph).ellipsis().margin(0, 0, 0, inset);
    // With a middle, only the middle gives way; the title keeps its width and is cut last.
    lead.child(center == null ? name.minSize(0, 0) : name);
    for (El part : start) {
      lead.child(part == null ? null : part.margin(0, 0, 0, inset));
    }
    if (center != null) {
      lead.child(el().grow(1)).child(center).child(el().grow(1));
    }
    bar.child(lead).child(el().w(theme.space().xxl()).shrink(0));
    List<El> actions = end.stream().filter(Objects::nonNull).toList();
    if (!actions.isEmpty()) {
      // The room before the window's buttons is cut with the actions, not kept beside them.
      bar.child(row()
          .hFull()
          .align(Style.Align.CENTER)
          .minSize(0, 0)
          .clip()
          .child(row()
              .gap(theme.space().xs())
              .align(Style.Align.CENTER)
              .shrink(0)
              .children(actions.toArray(El[]::new)))
          .child(el().w(theme.space().xl()).shrink(0)));
    }
    El restore = glyph(theme, window.maximized() ? Symbol.SQUARE_MULTIPLE : Symbol.MAXIMIZE, glyph);
    return bar.child(Tooltip.tooltip(
            button(
                theme,
                WindowPart.MINIMIZE,
                "Minimize",
                height,
                glyph(theme, Symbol.SUBTRACT, glyph)),
            "Minimize"))
        .child(button(
            theme,
            WindowPart.MAXIMIZE,
            window.maximized() ? "Restore" : "Maximize",
            height,
            restore))
        .child(Tooltip.tooltip(close(theme, height, glyph), "Close"));
  }

  /**
   * A field for the middle of a title bar that searches or runs the app's commands, such as by
   * opening a {@link CommandPalette}: a search icon and {@code placeholder} in muted text, and the
   * shortcut that does the same at the right. In a tree dump it is {@code role=button} with the
   * placeholder as its label.
   *
   * @param shortcut the key combination that does the same, such as {@code "Ctrl+Shift+P"}; null
   *     for none
   * @param onPress runs when the field is pressed
   */
  public static El search(String placeholder, String shortcut, Runnable onPress) {
    Theme theme = Theme.CURRENT.get();
    int fill = theme.surface().raised();
    float space = theme.space().m();
    El field = row()
        .role("button")
        .label(placeholder)
        .h(theme.size().control())
        .minSize(0, 0)
        .align(Style.Align.CENTER)
        .gap(space)
        .padding(0, space, 0, space)
        .radius(theme.radius().medium())
        .fill(fill)
        .border(theme.size().stroke(), theme.border().control())
        .clip()
        .focusable()
        .keepFocus() // the focus stays where it was, for the palette to give back
        .focusRing(theme.accent().edge())
        .cursor(Cursor.POINTER)
        .onPress(onPress)
        .whenHovered(b -> b.fill(Theme.over(fill, theme.surface().hover())))
        .whenPressed(b -> b.fill(Theme.over(fill, theme.surface().pressed())))
        .child(Icon.icon(Symbol.SEARCH, Icon.Variant.MUTED))
        .child(label(Label.Variant.MUTED, placeholder));
    if (shortcut != null) {
      // Where the shortcut does not fit, it wraps onto a second line, which the field cuts away;
      // then the field cuts the placeholder.
      float line = theme.size().control();
      field.child(row()
          .wrap()
          .grow(1)
          .minSize(0, 0)
          .h(line)
          .justify(Style.Content.END)
          .align(Style.Align.CENTER)
          .clip()
          .child(el().h(line))
          .child(
              label(Label.Variant.SMALL, shortcut).margin(0, 0, 0, theme.space().xxl())));
    }
    return field;
  }

  private static El glyph(Theme theme, Symbol symbol, int color) {
    return Icon.icon(symbol, Icon.Variant.NORMAL, theme.caption().glyph()).color(color);
  }

  /**
   * A caption button, lighter under the pointer and darker while pressed, as the system's are.
   * The system acts on it; the handler, which does nothing, makes it the press's target, so that
   * it shows the press.
   */
  private static El button(Theme theme, WindowPart part, String label, float height, El glyph) {
    return el().role("button")
        .label(label)
        .size(theme.caption().button(), height)
        .shrink(0)
        .center()
        .windowPart(part)
        .child(glyph)
        .onPress(() -> {})
        .whenHovered(b -> b.fill(theme.surface().hover()))
        .whenPressed(b -> b.fill(theme.surface().pressed()));
  }

  /** The close button, red with a white cross under the pointer, as the system's is. */
  private static El close(Theme theme, float height, int color) {
    El cross = glyph(theme, Symbol.DISMISS, color);
    return button(theme, WindowPart.CLOSE, "Close", height, cross)
        .whenHovered(b -> {
          b.fill(theme.caption().closeHover());
          cross.color(theme.caption().onClose());
        })
        .whenPressed(b -> {
          b.fill(theme.caption().closePressed());
          cross.color(theme.caption().onClose());
        });
  }
}
