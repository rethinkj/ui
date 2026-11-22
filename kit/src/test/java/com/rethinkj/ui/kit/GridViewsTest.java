package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.DataGrid.dataGrid;
import static com.rethinkj.ui.kit.Workbench.dumpLine;
import static com.rethinkj.ui.kit.Workbench.find;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.DataGrid.Align;
import com.rethinkj.ui.kit.DataGrid.Column;
import com.rethinkj.ui.kit.DataGrid.Mode;
import com.rethinkj.ui.kit.DataGrid.Selection;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Views in cells ({@link Column#view}) and the actions of a row ({@link Column#actions}): presses on
 * their controls, copying, when the actions show, and the stored image.
 */
class GridViewsTest {

  private static final Theme T = Theme.DARK;
  private static final String[] SERVICES = {
    "auth-api", "billing", "search-idx", "reports", "archive", "metrics"
  };
  private static final String[] STATUSES = {"PK", "ACTIVE", "DEGRADED", "FAILED", "idle", "ACTIVE"};
  private static final double[] LOADS = {0.24, 0.82, 0.68, 0.96, 0.12, 0.45};

  @TempDir
  Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  private static final class Fixture {
    final Selection selection;
    final boolean[] enabled = {true, true, false, true, false, true};
    final List<String> log = new ArrayList<>();
    boolean editable;
    App<Fixture> app;

    Fixture(Mode mode) {
      this.selection = new Selection(mode);
    }

    El view() {
      Theme theme = Theme.CURRENT.get();
      List<Column> columns = List.of(
          new Column("service", 180, Align.LEFT, false, row -> SERVICES[row])
              .actions(row -> row()
                  .gap(theme.space().xs())
                  .children(
                      Button.icon(
                              Button.Variant.GHOST,
                              Symbol.COPY,
                              "Copy " + SERVICES[row],
                              () -> log.add("copy " + row),
                              Button.options().size(Button.Size.SMALL))
                          .key("copy-" + row),
                      Button.icon(
                              Button.Variant.GHOST,
                              Symbol.DELETE,
                              "Delete " + SERVICES[row],
                              () -> log.add("delete " + row),
                              Button.options().size(Button.Size.SMALL).tone(Button.Tone.DANGER))
                          .key("delete-" + row))),
          new Column("status", 104, Align.LEFT, false, row -> STATUSES[row])
              .view(row -> switch (STATUSES[row]) {
                case "PK" -> Badge.badge(Badge.Kind.ACCENT, "PK");
                case "ACTIVE" -> Badge.badge(Badge.Kind.SUCCESS, "ACTIVE");
                case "DEGRADED" -> Badge.badge(Badge.Kind.WARNING, "DEGRADED");
                case "FAILED" -> Badge.badge(Badge.Kind.DANGER, "FAILED");
                default -> null; // falls back to plain text
              }),
          new Column("load", 132, Align.RIGHT, true, row -> Math.round(LOADS[row] * 100) + " %")
              .view(row -> loadView(theme, LOADS[row])),
          new Column("active", 72, Align.LEFT, false, row -> String.valueOf(enabled[row]))
              .view(row -> row()
                  .grow(1)
                  .center()
                  .child(Checkbox.checkbox(enabled[row], () -> {
                        enabled[row] = !enabled[row];
                        log.add("toggle " + row + " " + enabled[row]);
                      })
                      .key("check-" + row)
                      .label("Active " + SERVICES[row]))));
      if (editable) {
        columns = columns.stream().map(Column::editable).toList();
      }
      DataGrid.Options options = DataGrid.options().select(selection).copy(app.clipboard());
      if (editable) {
        options.onCommit((row, column, value) -> {
          log.add("commit " + row + " " + column + " " + value);
          return null;
        });
      }
      return col()
          .wFull()
          .hFull()
          .padding(theme.space().m())
          .fill(theme.surface().panel())
          .child(dataGrid(columns, SERVICES.length, options)
              .key("services")
              .label("Services")
              .grow(1));
    }

    App<Fixture> app() {
      app = new App<>(this, Fixture::view).provide(Theme.CURRENT, f -> T);
      app.textRendering(TextRendering.LINUX);
      return app;
    }
  }

  private static El loadView(Theme theme, double fraction) {
    Progress.State state = fraction >= 0.9
        ? Progress.State.ERROR
        : fraction <= 0.25 ? Progress.State.DONE : Progress.State.NORMAL;
    Theme.TextStyle mono = theme.type().mono();
    El percent = El.text(Math.round(fraction * 100) + " %")
        .font(mono.font().get())
        .fontSize(mono.size())
        .color(theme.text().primary())
        .w(36)
        .textAlign(Style.Align.END)
        .shrink(0);
    return row()
        .align(Style.Align.CENTER)
        .gap(theme.space().m())
        .children(Progress.bar(fraction, state).w(48), percent);
  }

  private void run(App<?> app, String steps) throws IOException {
    Script.run("size 520 200 scale 2\n" + steps, app, out);
  }

  @Test
  void controlsInCellsActOnTheirOwnAndCopyTakesTheText() throws IOException {
    Fixture fixture = new Fixture(Mode.CELLS);
    fixture.editable = true;
    App<Fixture> app = fixture.app();
    run(app, "frame");
    assertTrue(dumpLine(app, "cell-0-1").contains("label=\"PK\""), "a view cell says its text");
    assertEquals("idle", find(app, "cell-4-1").children().getFirst().text().string());

    // A press and a double press toggle the checkbox, and neither select nor edit the cell.
    run(app, "press #check-2\nframe\npress #check-2 clicks 2\nframe");
    assertEquals(List.of("toggle 2 true", "toggle 2 false"), fixture.log);
    assertFalse(fixture.selection.isEditing());
    assertEquals(List.of(), fixture.selection.ranges());

    // A drag from a checkbox selects nothing.
    run(app, "down #check-1\nmove #cell-4-3\nup #cell-4-3\nframe");
    assertEquals(List.of(), fixture.selection.ranges());

    run(app, """
        press #cell-1-0
        key ArrowRight shift
        key ArrowRight shift
        key ArrowRight shift
        key C control
        frame""");
    assertEquals("billing\tACTIVE\t82 %\ttrue", app.clipboard().read());
  }

  @Test
  void actionsShowInTheSelectedHoveredAndFocusedRow() throws IOException {
    Fixture fixture = new Fixture(Mode.ROWS);
    fixture.selection.select(1, 0);
    App<Fixture> app = fixture.app();
    run(app, "frame");
    assertEquals(1f, findIn(find(app, "cell-1-0"), "actions").opacity());
    assertEquals(
        DataGrid.HIDDEN_ACTIONS, findIn(find(app, "cell-2-0"), "actions").opacity());

    run(app, "move #cell-2-0\nframe");
    assertEquals(1f, findIn(find(app, "cell-2-0"), "actions").opacity());
    run(app, "move #copy-2\nframe\nadvance 1\nframe");
    assertEquals("Copy search-idx", find(app, "copy-2").overlays().getFirst().label());
    run(app, "press #copy-2\nframe");
    assertEquals(List.of("copy 2"), fixture.log);
    assertTrue(fixture.selection.isRowSelected(1), "a press on an action keeps the selection");

    run(app, "move 0,0\nframe");
    assertEquals(
        DataGrid.HIDDEN_ACTIONS, findIn(find(app, "cell-2-0"), "actions").opacity());
    run(app, "focus #check-2\nframe");
    assertEquals(1f, findIn(find(app, "cell-2-0"), "actions").opacity());
  }

  private static El findIn(El root, String key) {
    El found = findOrNull(root, key);
    assertNotNull(found, "no #" + key + " in " + root.dump());
    return found;
  }

  private static El findOrNull(El root, String key) {
    if (key.equals(root.key())) {
      return root;
    }
    for (El child : root.children()) {
      El found = findOrNull(child, key);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  // Image

  /** views.txt. */
  @Test
  void looksAsStored() throws IOException {
    Fixture fixture = new Fixture(Mode.ROWS);
    App<Fixture> app = fixture.app();
    String script = Files.readString(Path.of("src/test/resources/scripts/views.txt"));
    List<Path> files = Script.run(script, app, out);
    Golden.assertSnaps("grid-views-dark-2x-", files, "views");
    assertEquals(2, files.size());
  }
}
