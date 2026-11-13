package com.rethinkj.ui.window;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Drop;
import com.rethinkj.ui.El;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Files and text dropped on a real window: SDL's drop events, put into its queue as SDL makes
 * them from the system's drag and drop, reach the element's {@link El#onFileDrop} through the
 * loop. Skipped like {@link WindowTest}.
 */
class FileDropWindowTest {

  private static final String TITLE = "rethinkj file drop test";

  @BeforeEach
  void needsWindows() {
    Desktop.assumeWindows();
  }

  @Test
  void filesAndTextFromAnotherApplicationReachTheHandlerUnderThem() {
    try (Window window = Window.open(TITLE, 160, 100)) {
      List<String> log = new ArrayList<>();
      App<List<String>> app = new App<>(
          log,
          l -> col()
              .wFull()
              .hFull()
              .child(el().key("target")
                  .size(160, 50)
                  .onFileDrop(d -> l.add(d.phase()
                      + (d.payload() == null ? "" : " " + d.payload())
                      + (d.phase() == Drop.Phase.LEAVE
                          ? ""
                          : " " + (int) d.x() + "," + (int) d.y())))));
      String deep = "/Ordner mit Ümlauten/" + "sehr lang/".repeat(60) + "Größe 1.sql";
      AtomicReference<WindowLoop> loop = new AtomicReference<>();
      int[] step = new int[1];
      loop.set(new WindowLoop(window, app, (scene, w, h, s) -> {}));
      loop.get().waits((millis, holdBack) -> {
        if (millis != -1) {
          return;
        }
        switch (step[0]++) {
          case 0 -> { // files: begin, moves, a file each, complete
            Desktop.drop(window, Sdl.EVENT_DROP_BEGIN, 0, 0, null);
            Desktop.drop(window, Sdl.EVENT_DROP_POSITION, 30, 20, null);
            Desktop.drop(window, Sdl.EVENT_DROP_POSITION, 40, 25, null);
            Desktop.drop(window, Sdl.EVENT_DROP_FILE, 40, 25, "/tmp/a b.sql");
            Desktop.drop(window, Sdl.EVENT_DROP_FILE, 40, 25, deep);
            Desktop.drop(window, Sdl.EVENT_DROP_COMPLETE, 40, 25, null);
          }
          case 1 -> { // a drag that leaves: complete without anything
            Desktop.drop(window, Sdl.EVENT_DROP_BEGIN, 0, 0, null);
            Desktop.drop(window, Sdl.EVENT_DROP_POSITION, 10, 10, null);
            Desktop.drop(window, Sdl.EVENT_DROP_POSITION, 10, 80, null); // below the target
            Desktop.drop(window, Sdl.EVENT_DROP_COMPLETE, 10, 80, null);
          }
          case 2 -> { // text, line by line as X11 hands it over
            Desktop.drop(window, Sdl.EVENT_DROP_POSITION, 50, 10, null);
            Desktop.drop(window, Sdl.EVENT_DROP_TEXT, 50, 10, "SELECT 1");
            Desktop.drop(window, Sdl.EVENT_DROP_TEXT, 50, 10, "FROM dual");
            Desktop.drop(window, Sdl.EVENT_DROP_COMPLETE, 50, 10, null);
          }
          default -> loop.get().stop();
        }
      });
      Desktop.run(loop.get(), () -> "the drops arrive: " + log);
      assertEquals(
          List.of(
              "OVER 30,20",
              "OVER 40,25",
              "DROP " + List.of(Path.of("/tmp/a b.sql"), Path.of(deep)) + " 40,25",
              "OVER 10,10",
              "LEAVE",
              "OVER 50,10",
              "DROP SELECT 1\nFROM dual 50,10"),
          log);
    }
  }
}
