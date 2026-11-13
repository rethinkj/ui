package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.Checkbox.checkbox;
import static com.rethinkj.ui.kit.DataGrid.dataGrid;
import static com.rethinkj.ui.kit.TextField.textField;
import static com.rethinkj.ui.kit.Toggle.toggle;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.kit.Button.Variant;
import com.rethinkj.ui.kit.DataGrid.Align;
import com.rethinkj.ui.kit.DataGrid.Column;
import com.rethinkj.ui.render.Pixels;
import com.rethinkj.ui.text.TextRendering;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Every focusable component draws its focus ring in its own theme, whatever the app's is. */
class FocusRingTest {

  /** The focusable components of the kit in one theme, on that theme's window colour. */
  private static El panel(Theme t) {
    List<Column> columns = List.of(new Column("id", 60, Align.RIGHT, true, String::valueOf));
    return col()
        .grow(1)
        .padding(t.space().xl())
        .gap(t.space().xl())
        .fill(t.surface().window())
        .children(
            button(t, Variant.SECONDARY, "Save", () -> {}),
            checkbox(t, false, "Read only", () -> {}),
            toggle(t, false, "Autocommit", () -> {}),
            textField(t, "", v -> {}).label("Name"),
            dataGrid(
                    t,
                    columns,
                    3,
                    DataGrid.options().select(new DataGrid.Selection(DataGrid.Mode.ROWS)))
                .label("Rows")
                .h(80));
  }

  @Test
  void twoThemesOnScreenEachDrawTheirOwnRing() {
    TextLibraries.assumeLoaded();
    App<Object> app = new App<>(
        new Object(), s -> row().wFull().hFull().children(panel(Theme.DARK), panel(Theme.LIGHT)));
    app.textRendering(TextRendering.LINUX);
    app.focusRing(0xFFFF00FF); // the app's colour, which no component may show
    app.resize(640, 420, 1);
    app.frame();
    for (int i = 0; i < 10; i++) {
      app.keyDown(new Key("Tab", 0, false));
      app.frame();
      Theme t = i < 5 ? Theme.DARK : Theme.LIGHT;
      El focused = app.focused();
      float[] at = position(app.tree(), focused, 0, 0);
      assertTrue(at != null, "focused: " + focused);
      // The middle of the ring's top edge, which is straight for every component.
      Pixels pixels = app.render().pixels();
      int x = Math.round(at[0] + focused.width() / 2);
      int y = (int) Math.ceil(at[1] - 2);
      assertEquals(
          hex(t.accent().edge()),
          hex(pixels.argb(x, y)),
          "the ring of " + focused.role() + " in the " + (i < 5 ? "dark" : "light") + " theme");
    }
  }

  /** The window position of {@code target}'s top left corner, or null if it is not below. */
  private static float[] position(El el, El target, float parentX, float parentY) {
    float x = parentX + el.x();
    float y = parentY + el.y();
    if (el == target) {
      return new float[] {x, y};
    }
    for (El child : el.children()) {
      float[] found = position(child, target, x, y);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  private static String hex(int argb) {
    return String.format("%08X", argb);
  }
}
