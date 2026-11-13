package com.rethinkj.ui;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Drops within one app, without a runner: the steps of drop handlers and what a drag reports. */
class DropTest {

  /** Three slots in a row, 100 wide each; the first holds a chip that carries "chip". */
  static final class Slots {
    int chip; // the slot that holds the chip
    final List<String> log = new ArrayList<>();
    final List<Drag> drags = new ArrayList<>();
  }

  private static App<Slots> slots() {
    App<Slots> app = new App<>(new Slots(), s -> {
      El root = row().wFull().hFull();
      for (int i = 0; i < 3; i++) {
        int slot = i;
        El box = el().key("slot-" + i).size(100, 100).onDrop(d -> {
          s.log.add(slot + " " + d.phase() + (Double.isNaN(d.x()) ? "" : " " + (int) d.x()));
          if (d.phase() == Drop.Phase.DROP && d.payload().equals("chip")) {
            s.chip = slot;
          }
        });
        if (i == s.chip) {
          box.child(el().key("chip").size(20, 20).dragPayload("chip").onDrag(s.drags::add));
        }
        root.child(box);
      }
      return root;
    });
    app.resize(300, 100, 1);
    app.frame();
    return app;
  }

  @Test
  void aDragWithAPayloadIsOverLeavesAndDropsInTheTargetsItCrosses() {
    App<Slots> app = slots();
    Slots s = app.state();
    app.pointerDown(10, 10);
    app.pointerMove(20, 10); // a drag, over its own slot
    app.pointerMove(150, 10); // over the second
    app.pointerMove(250, 10); // the third
    app.pointerUp(250, 10);
    app.frame();

    assertEquals(
        List.of("0 OVER 20", "0 LEAVE", "1 OVER 50", "1 LEAVE", "2 OVER 50", "2 DROP 50"), s.log);
    assertEquals(2, s.chip);
    assertEquals("slot-2", app.tree().children().get(2).key());
    assertEquals(1, app.tree().children().get(2).children().size(), "the chip moved there");
    Drag end = s.drags.getLast();
    assertEquals(Drag.Phase.END, end.phase());
    assertEquals(250, end.screenX(), "without a runner, the place in the window");
    assertFalse(end.outside());
    assertNull(end.window(), "no runner, no window");
  }

  @Test
  void outsideTheWindowTheTargetIsLeftAndNothingIsDropped() {
    App<Slots> app = slots();
    Slots s = app.state();
    app.pointerDown(10, 10);
    app.pointerMove(150, 10);
    app.pointerMove(150, -40); // above the window
    app.frame();
    assertTrue(s.drags.getLast().outside());
    app.pointerUp(150, -40);
    app.frame();
    assertEquals(List.of("1 OVER 50", "1 LEAVE"), s.log);
    assertEquals(0, s.chip);
  }

  @Test
  void aCancelledDragLeavesItsTarget() {
    App<Slots> app = slots();
    Slots s = app.state();
    app.pointerDown(10, 10);
    app.pointerMove(150, 10);
    app.pointerDown(150, 10); // the release got lost
    app.frame();
    assertEquals(List.of("1 OVER 50", "1 LEAVE"), s.log);
    assertEquals(Drag.Phase.CANCEL, s.drags.getLast().phase());
  }

  @Test
  void aDragWithoutPayloadReachesNoDropHandler() {
    List<String> log = new ArrayList<>();
    App<Object> app = new App<>(
        new Object(),
        x -> row()
            .wFull()
            .hFull()
            .onDrop(d -> log.add(d.phase().name()))
            .child(el().size(20, 20).onDrag(d -> {})));
    app.resize(200, 100, 1);
    app.frame();
    app.pointerDown(10, 10);
    app.pointerMove(100, 10);
    app.pointerUp(100, 10);
    app.frame();
    assertEquals(List.of(), log);
  }
}
