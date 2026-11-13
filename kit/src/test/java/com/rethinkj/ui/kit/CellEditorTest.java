package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.DataGrid.dataGrid;
import static com.rethinkj.ui.kit.TextField.textField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.DataGrid.Align;
import com.rethinkj.ui.kit.DataGrid.Column;
import com.rethinkj.ui.kit.DataGrid.Mode;
import com.rethinkj.ui.kit.DataGrid.Selection;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The grid and the text field together, as an app combines them: a field laid over a cell edits
 * it, and a field beside the grid keeps its state while a column is resized. The editor is the
 * view's, not the grid's: Enter, F2 or a double press on the grid asks the app to edit the
 * cursor's cell ({@link DataGrid.Options#onEdit}), which opens the field over it. The field takes
 * the focus as it appears ({@link El#autofocus}); its own key handler commits with Enter and
 * cancels with Escape, and when it goes the focus goes back to the grid, the first focusable
 * element around it.
 */
class CellEditorTest {

  private static final Theme T = Theme.DARK;

  @TempDir Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  /** Customers with an id and a name, a filter field above them, and the editor's state. */
  static final class Sheet {
    final String[] names = new String[40];
    final float[] widths = {64, 160};
    final Selection selection = new Selection(Mode.CELLS);
    int editing = -1; // the row whose name the editor is open over
    String draft;
    String filter = "";
    final List<String> log = new ArrayList<>();

    Sheet() {
      for (int i = 0; i < names.length; i++) {
        names[i] = Orders.customer(i);
      }
    }

    El view() {
      List<Column> columns = List.of(
          new Column("id", widths[0], Align.RIGHT, true, i -> String.valueOf(i + 1)),
          new Column("name", widths[1], Align.LEFT, false, i -> names[i]));
      El grid = dataGrid(T, columns, names.length, DataGrid.options()
          .select(selection)
          .onResize((c, w) -> {
            log.add("resize " + c + " " + w);
            widths[c] = w;
          })
          .onEdit((row, column) -> {
            log.add("edit " + row + "," + column);
            if (column == 1) { // only names
              editing = row;
              draft = names[row];
            }
          }))
          .key("sheet").label("Customers").grow(1);
      El area = col().key("area").grow(1).child(grid);
      if (editing >= 0) {
        // Over the name's cell: the rows begin below the header and its line; no scrolling here.
        float rowHeight = T.grid().rowHeight();
        area.child(textField(T, draft, v -> draft = v).key("editor").label("Name")
            .absolute(widths[0], rowHeight + T.size().stroke() + editing * rowHeight)
            .w(widths[1] - T.size().stroke()).h(rowHeight)
            .autofocus()
            .onKey(this::editorKey));
      }
      return col().wFull().hFull().padding(T.space().m()).gap(T.space().m())
          .fill(T.surface().panel())
          .children(textField(T, filter, "Filter", v -> filter = v).key("filter"), area);
    }

    /** The field's own handler sees its keys first: Enter commits, Escape cancels. */
    private boolean editorKey(Key k) {
      if (k.modifiers() != 0) {
        return false;
      }
      switch (k.name()) {
        case "Enter" -> {
          names[editing] = draft;
          log.add("commit " + editing + " " + draft);
        }
        case "Escape" -> log.add("cancel " + editing);
        default -> {
          return false;
        }
      }
      editing = -1;
      return true;
    }
  }

  private static App<Sheet> start() {
    App<Sheet> app = new App<>(new Sheet(), Sheet::view);
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  /** Runs the steps in a window of 400 by 300. */
  private void run(App<?> app, String steps) throws IOException {
    Script.run("size 400 300\n" + steps, app, out);
  }

  @Test
  void aFieldOverACellEditsItEnterCommitsEscapeCancelsAndTheGridGetsTheFocusBack()
      throws IOException {
    App<Sheet> app = start();
    Sheet sheet = app.state();
    String before = sheet.names[3];
    run(app, "frame\npress #cell-3-1\nframe\nkey Enter\nframe");
    El editor = find(app, "editor");
    El cell = find(app, "row-3").children().get(2);
    assertEquals(before, text(cell), "the name's cell");
    assertEquals(x(app, cell), x(app, editor), "over the cell");
    assertEquals(y(app, cell), y(app, editor));
    assertEquals(cell.width(), editor.width());
    assertEquals(cell.height(), editor.height());

    // The editor has the focus from the frame that adds it, while it fades in.
    assertEquals("editor", app.focused().key());
    run(app, "type \" Jr.\"\nframe");
    assertEquals("editor", app.focused().key());
    assertEquals(before + " Jr.", sheet.draft);
    assertEquals(before, sheet.names[3], "not yet committed");
    assertTrue(line(app, "editor").endsWith("focused caret " + (before.length() + 4)),
        line(app, "editor"));

    run(app, "advance 1\nframe\nkey Enter\nframe"); // once it has faded in
    assertTrue(find(app, "editor").isExiting(), "the editor fades out");
    assertEquals(before + " Jr.", sheet.names[3]);
    assertEquals(before + " Jr.", text(find(app, "row-3").children().get(2)), "the grid shows it");
    assertEquals("sheet", app.focused().key(), "the grid has the focus again");
    run(app, "advance 1\nframe");
    assertNull(find(app, "editor"));
    run(app, "key ArrowDown\nframe");
    assertEquals(4, sheet.selection.row(), "and the keys");

    String fourth = sheet.names[4];
    run(app, "key F2\ntype \"xyz\"\nframe"); // the text goes to the editor that F2 opened
    // Once it shows, a press goes to the field, not to the cell under it.
    run(app, "advance 1\nframe\npress #editor\nframe");
    assertEquals("editor", app.focused().key());
    assertEquals(4, sheet.selection.row());
    run(app, "key Escape\nframe\nadvance 1\nframe");
    assertEquals(fourth, sheet.names[4], "cancelled");
    assertNull(find(app, "editor"));
    assertEquals("sheet", app.focused().key());

    // A double press on a cell puts the cursor there and asks to edit it; the id is not edited.
    run(app, "press #cell-6-0\npress #cell-6-0 clicks 2\nframe");
    assertEquals(-1, sheet.editing, "the app edits names only");
    run(app, "press #cell-7-1\npress #cell-7-1 clicks 2\nframe");
    assertEquals(7, sheet.editing);
    assertTrue(line(app, "cell-7-1").contains(" selected current "), line(app, "cell-7-1"));
    assertEquals(List.of("edit 3,1", "commit 3 " + before + " Jr.", "edit 4,1", "cancel 4",
        "edit 6,0", "edit 7,1"), sheet.log);
  }

  @Test
  void resizingAColumnLeavesTheCaretAndSelectionOfAFieldElsewhereAlone() throws IOException {
    App<Sheet> app = start();
    Sheet sheet = app.state();
    run(app, "frame\npress #filter\ntype \"SELECT name\"\nkey ArrowLeft shift\n"
        + "key ArrowLeft shift\nframe");
    assertTrue(line(app, "filter").endsWith("focused caret 9 anchor 11"), line(app, "filter"));

    El edge = find(app, "resize-1");
    float x = x(app, edge) + edge.width() / 2;
    float y = y(app, edge) + edge.height() / 2;
    run(app, "down #resize-1\nmove " + (x + 40) + "," + y + "\nframe");
    assertEquals(200, sheet.widths[1]);
    run(app, "move #filter\nframe"); // over the field: the drag goes on and selects nothing
    assertSame(find(app, "resize-1"), app.hovered(), "the edge stays under the pointer");
    assertEquals(36, sheet.widths[1], "the edge follows the pointer over the field");
    run(app, "move " + (x + 20) + "," + (y + 100) + "\nup " + (x + 20) + "," + (y + 100)
        + "\nframe");
    assertEquals(180, sheet.widths[1]);
    assertEquals("resize 1 180.0", sheet.log.getLast(), "the end of the drag");
    assertEquals("sheet", app.focused().key(), "the press on the edge focused the grid");
    assertEquals("SELECT name", sheet.filter);

    run(app, "focus #filter\nframe"); // back in the field, its selection is as it was
    assertTrue(line(app, "filter").endsWith("focused caret 9 anchor 11"), line(app, "filter"));
  }

  private static String text(El cell) {
    return cell.children().getFirst().text().string();
  }

  /** The line of the element with this key in the dump of the tree. */
  private static String line(App<?> app, String key) {
    return app.tree().dump().lines().filter(l -> l.strip().contains(" #" + key + " "))
        .findFirst().orElseThrow().strip();
  }

  private static float x(App<?> app, El el) {
    return at(app.tree(), el, 0, 0)[0];
  }

  private static float y(App<?> app, El el) {
    return at(app.tree(), el, 0, 0)[1];
  }

  /** The window position of {@code target} below {@code el}, which is at this position. */
  private static float[] at(El el, El target, float left, float top) {
    float x = left + el.x();
    float y = top + el.y();
    if (el == target) {
      return new float[] {x, y};
    }
    for (El child : el.children()) {
      float[] found = at(child, target, x, y);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  static El find(App<?> app, String key) {
    return find(app.tree(), key);
  }

  static El find(El el, String key) {
    if (key.equals(el.key())) {
      return el;
    }
    for (El child : el.children()) {
      El found = find(child, key);
      if (found != null) {
        return found;
      }
    }
    return null;
  }
}
