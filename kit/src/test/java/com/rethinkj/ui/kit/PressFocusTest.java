package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.Checkbox.checkbox;
import static com.rethinkj.ui.kit.DataGrid.dataGrid;
import static com.rethinkj.ui.kit.Split.split;
import static com.rethinkj.ui.kit.TextField.textField;
import static com.rethinkj.ui.kit.Toggle.toggle;
import static com.rethinkj.ui.kit.Workbench.find;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.Button.Variant;
import com.rethinkj.ui.kit.DataGrid.Align;
import com.rethinkj.ui.kit.DataGrid.Column;
import com.rethinkj.ui.kit.Split.Direction;
import com.rethinkj.ui.kit.Split.Position;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which components take the keyboard focus when pressed: fields and the grid do; buttons,
 * toggles, checkboxes, selects and a split's divider leave it where it is, and Tab still reaches
 * each of them.
 */
class PressFocusTest {

  private static final Theme T = Theme.DARK;

  @TempDir
  Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  /** A toolbar over a split of a field and a grid. */
  static final class Tool {
    String query = "";
    boolean on;
    boolean checked;
    int runs;
    Position split = Position.first(200);
    final Menu.State select = new Menu.State();
    int choice;

    El view() {
      List<Column> columns = List.of(new Column("id", 60, Align.RIGHT, true, String::valueOf));
      El grid = dataGrid(
              T, columns, 5, DataGrid.options().select(new DataGrid.Selection(DataGrid.Mode.ROWS)))
          .key("grid")
          .label("Rows");
      return col()
          .wFull()
          .hFull()
          .fill(T.surface().window())
          .children(
              row()
                  .key("toolbar")
                  .gap(T.space().s())
                  .padding(T.space().s())
                  .children(
                      button(T, Variant.PRIMARY, "Run", () -> runs++).key("run"),
                      toggle(T, on, "Auto", () -> on = !on).key("auto"),
                      checkbox(T, checked, "Wrap", () -> checked = !checked).key("wrap"),
                      Select.select(T, List.of("one", "two"), choice, select, i -> choice = i)
                          .key("select")),
              split(
                      T,
                      Direction.ROW,
                      split,
                      null,
                      p -> split = p,
                      textField(T, query, "Query", v -> query = v).key("query"),
                      100,
                      grid,
                      100)
                  .grow(1));
    }
  }

  private void run(App<?> app, String steps) throws IOException {
    Script.run("size 480 240\n" + steps, app, out);
  }

  @Test
  void controlsAndTheDividerLeaveTheFocusWhereItIsAndTheFieldAndGridTakeIt() throws IOException {
    App<Tool> app = new App<>(new Tool(), Tool::view);
    app.textRendering(TextRendering.LINUX);
    run(app, "frame\npress #query\nframe");
    assertEquals(find(app, "query"), app.focused());

    run(app, "press #run\npress #auto\npress #wrap\nframe");
    assertEquals(1, app.state().runs);
    assertEquals(
        find(app, "query"), app.focused(), "the buttons did their work, the field kept it");

    float x = Workbench.x(app, find(app, "divider")) + 30;
    run(app, "down #divider\nmove " + x + ",120\nup " + x + ",120\nframe");
    assertNotEquals(Position.first(200), app.state().split, "dragged");
    assertEquals(find(app, "query"), app.focused(), "the divider too");

    run(app, "press #select\nframe\nkey Escape\nframe");
    assertEquals(find(app, "query"), app.focused(), "the select's menu gives it back there");

    run(app, "press #cell-1-0\nframe");
    assertEquals(find(app, "grid"), app.focused(), "the grid takes it");
    run(app, "press #query\nframe");
    assertEquals(find(app, "query"), app.focused(), "and so does the field");

    // Tab still reaches each of them, in tree order.
    run(app, "focus #run\nframe");
    StringBuilder order = new StringBuilder(app.focused().key());
    for (int i = 0; i < 5; i++) {
      run(app, "key Tab\nframe");
      order.append(' ').append(app.focused().key());
    }
    assertEquals("run auto wrap select query grid", order.toString());
  }
}
