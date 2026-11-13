package com.rethinkj.ui;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.window.Window;
import com.rethinkj.ui.window.Windows;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Several windows without a window: an app that opens a window from a drag, which follows the
 * pointer and is dropped back, apps that share a state, and close requests, all driven by scripts.
 */
class WindowsScriptTest {

  private static final int RED = 0xFFDC2626;
  private static final int GREY = 0xFF808080;

  @TempDir Path out;

  /**
   * Three slots in the main window, 100 by 100 each; a chip in one of them, or in a window of its
   * own while it is torn off. A counter that both windows show and a press in the chip's window
   * raises.
   */
  static final class Board {
    App<Board> app;
    int slot; // the slot that holds the chip, or -1 while it is in its window
    int over = -1; // the slot a drag of the chip is over
    int count;
    boolean veto; // a close request of the chip's window changes nothing
    Windows.Handle window; // the chip's window
    float grabX; // where the pointer holds the chip
    float grabY;
    final List<String> log = new ArrayList<>();

    El main() {
      El root = row().wFull().hFull();
      for (int i = 0; i < 3; i++) {
        int index = i;
        El box = el().key("slot-" + i).size(100, 100).fill(over == i ? RED : 0xFFFFFFFF)
            .onDrop(d -> drop(index, d));
        if (slot == i) {
          box.child(chip());
        }
        root.child(box);
      }
      return root.child(el().key("count-" + count).absolute(290, 90).size(10, 10));
    }

    El chip() {
      return el().key("chip").size(40, 40).fill(GREY).dragPayload("chip").onDrag(this::drag);
    }

    El torn() {
      return row().wFull().hFull().child(el().key("count-" + count).size(40, 40)
          .onPress(() -> count++));
    }

    void drop(int index, Drop d) {
      log.add(index + " " + d.phase());
      over = d.phase() == Drop.Phase.OVER ? index : -1;
      if (d.phase() == Drop.Phase.DROP) {
        slot = index;
      }
    }

    void drag(Drag d) {
      if (d.phase() == Drag.Phase.START) {
        grabX = (float) d.x() - d.dx();
        grabY = (float) d.y() - d.dy();
      }
      boolean moving = d.phase() == Drag.Phase.START || d.phase() == Drag.Phase.MOVE;
      if (d.outside() && window == null && moving) {
        // Out of the window: the chip becomes a window of its own under the pointer.
        App<Board> chipApp = new App<>(this, Board::torn);
        float scale = app.windows().of(app).screenScale(); // as the new window's, likely
        window = app.windows().open("Chip", 60, 60, Window.options()
            .position(Math.round(d.screenX() - grabX * scale),
                Math.round(d.screenY() - grabY * scale)), chipApp);
        window.dropTarget(false); // it lies under the pointer, over what it is dropped on
        window.onCloseRequest(() -> {
          if (!veto) {
            dock(0);
          }
        });
        slot = -1;
      } else if (window != null && moving) {
        window.moveTo(Math.round(d.screenX() - grabX * window.screenScale()),
            Math.round(d.screenY() - grabY * window.screenScale()));
      } else if (window != null && d.phase() == Drag.Phase.END) {
        if (slot >= 0) { // a slot took it
          window.close();
          window = null;
        } else {
          window.dropTarget(true);
        }
      }
    }

    void dock(int index) {
      slot = index;
      window.close();
      window = null;
    }
  }

  private App<Board> board() {
    Board board = new Board();
    App<Board> app = new App<>(board, Board::main);
    board.app = app;
    return app;
  }

  private String tree(String snap) throws IOException {
    return Files.readString(out.resolve(snap + ".tree.txt"));
  }

  @Test
  void aChipDraggedOutOfTheWindowBecomesAWindowThatFollowsAndDropsBack() throws IOException {
    App<Board> app = board();
    Board b = app.state();
    List<Path> written = Script.run("""
        size 300 100 at 100,100
        frame
        down #chip
        move 20,60
        move 150,50          # over the second slot
        frame
        snap over
        move 150,-60         # above the window: the chip's own window opens there
        frame
        snap torn
        move screen 350,150  # back over the third slot, in screen pixels
        frame
        snap back
        up screen 350,150
        frame
        snap docked
        """, app, out);

    assertEquals(List.of("0 OVER", "0 LEAVE", "1 OVER", "1 LEAVE", "2 OVER", "2 DROP"),
        b.log.subList(b.log.indexOf("0 OVER"), b.log.size()));
    assertEquals(2, b.slot);
    assertNull(b.window);
    assertTrue(tree("over").contains("el #slot-2 200,0 100x100\n"), tree("over"));
    assertTrue(Files.exists(out.resolve("chip-torn.png")), "the chip's window under its name");
    assertTrue(Files.exists(out.resolve("chip-back.png")));
    assertFalse(Files.exists(out.resolve("chip-docked.png")), "closed by then");
    assertFalse(tree("torn").contains("#chip"), "out of the main window");
    assertTrue(written.contains(out.resolve("chip-torn.tree.txt").toAbsolutePath()));
  }

  @Test
  void theWindowFollowsThePointerUntilTheRelease() throws IOException {
    App<Board> app = board();
    Board b = app.state();
    Script.run("""
        size 300 100 at 100,100 scale 2
        frame
        down #chip           # at its centre, 20,20 in the chip
        move 30,60
        move 30,-80          # above: the window opens with its top left at the chip's
        frame
        move screen 700,40
        frame
        up screen 700,40
        frame
        """, app, out);

    Windows.Handle window = b.window;
    assertNotNull(window, "released outside the slots: it stays a window");
    assertEquals(700 - 20 * 2, window.x(), "the grab point stays under the pointer");
    assertEquals(40 - 20 * 2, window.y());
    assertEquals(-1, b.slot);
    assertEquals(2, app.windows().all().size(), "the script's windows stay the app's");
  }

  @Test
  void aPressInOneWindowChangesWhatTheOtherShowsInTheSameFrame() throws IOException {
    App<Board> app = board();
    Script.run("""
        size 300 100 at 0,0
        frame
        down #chip
        move 20,-60
        up 20,-60
        frame
        window Chip
        press #count-0
        frame
        snap counted
        window main
        close              # nothing happens: the request is the app's, which closes only Chip
        """, app, out);

    assertTrue(tree("counted").contains("#count-1"), tree("counted"));
    assertTrue(tree("chip-counted").contains("#count-1"));
  }

  @Test
  void aCloseRequestRunsTheHandlerWhichMayVeto() throws IOException {
    App<Board> app = board();
    Board b = app.state();
    b.veto = true;
    Script.run("""
        size 300 100
        frame
        down #chip
        move 20,-60
        up 20,-60
        frame
        window Chip
        close
        frame
        snap vetoed
        """, app, out);
    assertNotNull(b.window);
    assertTrue(Files.exists(out.resolve("chip-vetoed.png")));

    b.veto = false;
    Script.run("""
        window Chip
        close
        frame
        snap closed
        """, app, out.resolve("again"));
    assertNull(b.window);
    assertEquals(0, b.slot, "back in the first slot");
    assertFalse(Files.exists(out.resolve("again/chip-closed.png")));
  }

  @Test
  void stepsNameTheWindowsThereAre() {
    Script.Failure failure = assertThrows(Script.Failure.class, () -> Script.run("""
        size 300 100
        frame
        window Nope
        """, board(), out));
    assertEquals("line 3: window Nope\n  There is no open window Nope. The open windows are"
        + " \"main\"; `main` is the first.", failure.getMessage());
  }
}
