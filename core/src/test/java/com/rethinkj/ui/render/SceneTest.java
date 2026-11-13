package com.rethinkj.ui.render;

import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rethinkj.ui.El;
import org.junit.jupiter.api.Test;

class SceneTest {

  @Test
  void listsPlacedElementsInDrawOrderWithWindowCoordinates() {
    El knob = el().fill(0xFFFFFFFF).radius(9);
    El toggle = el().fill(0xFF0000FF).radius(11).border(1, 0x80000000).child(knob);
    El unplaced = el().fill(0xFFFF0000);
    El root = el().child(toggle).child(unplaced);
    root.place(0, 0, 100, 60);
    toggle.place(31, 19, 38, 22);
    knob.place(18, 2, 18, 18);

    Scene scene = Scene.of(root);

    // The root has no fill and no border, the last child was never placed.
    assertEquals(2, scene.count());
    assertEquals(2L * Scene.STRIDE, scene.data().byteSize());

    assertEquals(31f, scene.get(0, Scene.X));
    assertEquals(19f, scene.get(0, Scene.Y));
    assertEquals(38f, scene.get(0, Scene.WIDTH));
    assertEquals(22f, scene.get(0, Scene.HEIGHT));
    assertEquals(11f, scene.get(0, Scene.RADIUS));
    assertEquals(1f, scene.get(0, Scene.BORDER_WIDTH));
    assertEquals(1f, scene.get(0, Scene.FILL + 8));
    assertEquals(1f, scene.get(0, Scene.FILL + 12));
    assertEquals(0f, scene.get(0, Scene.BORDER));
    assertEquals(128 / 255f, scene.get(0, Scene.BORDER + 12));

    assertEquals(49f, scene.get(1, Scene.X));
    assertEquals(21f, scene.get(1, Scene.Y));
    assertEquals(1f, scene.get(1, Scene.FILL));
  }

  @Test
  void multipliesTheOpacityOfAnElementAndItsAncestorsIntoItsColours() {
    El inner = el().fill(0x80FFFFFF).border(1, 0xFF000000).opacity(0.5f);
    El hidden = el().opacity(0).child(el().fill(0xFFFFFFFF));
    El outer = el().fill(0xFFFFFFFF).opacity(0.5f).child(inner).child(hidden);
    for (El el : new El[] {outer, inner, hidden, hidden.children().getFirst()}) {
      el.place(0, 0, 10, 10);
    }

    Scene scene = Scene.of(outer);

    assertEquals(2, scene.count(), "nothing of the element with opacity 0");
    assertEquals(0.5f, scene.get(0, Scene.FILL));
    assertEquals(0.5f, scene.get(0, Scene.FILL + 12));
    assertEquals(128 / 255f * 0.25f, scene.get(1, Scene.FILL), "premultiplied");
    assertEquals(128 / 255f * 0.25f, scene.get(1, Scene.FILL + 12));
    assertEquals(0.25f, scene.get(1, Scene.BORDER + 12));
  }

  @Test
  void growsPastItsFirstAllocation() {
    Scene scene = new Scene();
    for (int i = 0; i < 200; i++) {
      scene.rect(i, 0, 1, 1, 0, 0, 0xFFFFFFFF, 0);
    }
    assertEquals(200, scene.count());
    assertEquals(199f, scene.get(199, Scene.X));
  }
}
