package com.rethinkj.ui.render;

import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Shadow;
import java.lang.foreign.ValueLayout;
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
  void drawsShadowsRightUnderTheirElementTheFirstOnTop() {
    El card = el().fill(0xFFFFFFFF).radius(6).opacity(0.5f).shadow(
        new Shadow(0, 2, 4, 0, 0x40000000), new Shadow(1, 8, 24, -2, 0x26000000));
    El root = el().fill(0xFF808080).child(el().fill(0xFF000000)).child(card);
    root.place(0, 0, 100, 100);
    root.children().getFirst().place(0, 0, 10, 10);
    card.place(20, 30, 40, 20);

    Scene scene = Scene.of(root);

    assertEquals(5, scene.count(), "root, its first child, two shadows, the card");
    assertEquals(Scene.RECT, scene.getInt(1, Scene.KIND));
    assertEquals(Scene.SHADOW, scene.getInt(2, Scene.KIND));
    assertEquals(Scene.SHADOW, scene.getInt(3, Scene.KIND));
    assertEquals(Scene.RECT, scene.getInt(4, Scene.KIND));
    // The second, wide shadow lies lowest: moved and shrunk by 2, with the radius shrunk too.
    assertEquals(23f, scene.get(2, Scene.X));
    assertEquals(40f, scene.get(2, Scene.Y));
    assertEquals(36f, scene.get(2, Scene.WIDTH));
    assertEquals(16f, scene.get(2, Scene.HEIGHT));
    assertEquals(4f, scene.get(2, Scene.RADIUS));
    assertEquals(12f, scene.get(2, Scene.SIGMA));
    assertEquals(0x26 / 255f * 0.5f, scene.get(2, Scene.FILL + 12), "at the card's opacity");
    assertEquals(20f, scene.get(3, Scene.X));
    assertEquals(32f, scene.get(3, Scene.Y));
    assertEquals(6f, scene.get(3, Scene.RADIUS));
    assertEquals(2f, scene.get(3, Scene.SIGMA));
    for (int slot = 48; slot < Scene.STRIDE; slot += 4) {
      assertEquals(0, scene.getInt(3, slot), "slot " + slot + " is unused");
    }
  }

  @Test
  void theSpreadGrowsTheRadiusAsInCss() {
    // A square corner stays square, a small radius grows less than the spread, and a negative
    // spread shrinks the radius down to 0.
    float[][] cases = {{0, 4, 0}, {2, 4, 5.5f}, {4, 4, 8}, {8, 4, 12}, {2, -3, 0}, {6, -3, 3}};
    for (float[] c : cases) {
      El box = el().radius(c[0]).shadow(new Shadow(0, 0, 8, c[1], 0xFF000000));
      box.place(10, 10, 40, 40);
      Scene scene = Scene.of(box);
      assertEquals(c[2], scene.get(0, Scene.RADIUS), "radius " + c[0] + ", spread " + c[1]);
    }
  }

  @Test
  void aShadowTakesTheClipOfItsElementAndIsLeftOutWhereNothingOfItShows() {
    El inside = el().fill(0xFFFFFFFF).shadow(new Shadow(0, 4, 8, 0, 0x80000000));
    El far = el().shadow(new Shadow(0, 0, 8, 0, 0x80000000));
    El empty = el().fill(0xFFFFFFFF).shadow(new Shadow(0, 0, 8, -10, 0x80000000),
        new Shadow(0, 0, 8, 0, 0x00000000));
    El panel = el().clip().radius(8).child(inside).child(far).child(empty);
    El root = el().child(panel);
    root.place(0, 0, 200, 200);
    panel.place(10, 10, 100, 100);
    inside.place(10, 10, 40, 20);
    far.place(120, 0, 10, 10); // 20 pixels right of the clip: more than its 3 deviations
    empty.place(60, 60, 20, 20); // shrunk to nothing, and transparent

    Scene scene = Scene.of(root);

    assertEquals(3, scene.count(), "inside's shadow and fill, and empty's fill");
    assertEquals(Scene.SHADOW, scene.getInt(0, Scene.KIND));
    assertEquals(1, scene.clipOf(0));
  }

  @Test
  void anUnclippedSceneHasOnlyTheEntryThatClipsNothing() {
    El root = el().fill(0xFFFFFFFF).child(el().fill(0xFF000000));
    root.place(0, 0, 10, 10);
    root.children().getFirst().place(2, 2, 4, 4);

    Scene scene = Scene.of(root);

    assertEquals(2, scene.count());
    assertEquals(1, scene.clipCount());
    assertEquals(Scene.CLIP_STRIDE, scene.clips().byteSize());
    assertEquals(-1e9f, scene.clipGet(0, Scene.X));
    assertEquals(2e9f, scene.clipGet(0, Scene.HEIGHT));
    for (int i = 0; i < scene.count(); i++) {
      assertEquals(0, scene.clipOf(i));
      assertEquals(0, scene.data().get(ValueLayout.JAVA_LONG, (long) i * Scene.STRIDE + 24));
    }
  }

  @Test
  void nestedClipsIntersectAndKeepTheInnermostRadius() {
    El dot = el().fill(0xFFFFFFFF);
    El inner = el().clip().radius(3).fill(0xFF0000FF).child(dot);
    El outside = el().fill(0xFFFF0000);
    El outer = el().clip().radius(2).child(inner).child(outside);
    El root = el().fill(0xFF000000).child(outer);
    root.place(0, 0, 100, 100);
    outer.place(10, 10, 20, 20);
    inner.place(10, 5, 30, 30); // sticks out of outer on the right and at the bottom
    dot.place(0, 0, 4, 4);
    outside.place(25, 0, 5, 5); // entirely outside outer's clip

    Scene scene = Scene.of(root);

    assertEquals(3, scene.count(), "root, inner and dot; not what lies outside the clip");
    assertEquals(3, scene.clipCount());
    assertEquals(0, scene.clipOf(0), "the root is not clipped");
    assertEquals(1, scene.clipOf(1), "inner is clipped by outer, not by itself");
    assertEquals(2, scene.clipOf(2));
    assertClip(scene, 1, 10, 10, 20, 20, 2);
    assertClip(scene, 2, 20, 15, 10, 15, 3);
  }

  @Test
  void aClipWithNothingLeftDropsEverythingBelow() {
    El child = el().fill(0xFFFFFFFF);
    El empty = el().clip().child(child);
    El root = el().child(empty);
    root.place(0, 0, 10, 10);
    empty.place(0, 0, 0, 10);
    child.place(0, 0, 10, 10);

    Scene scene = Scene.of(root);

    assertEquals(0, scene.count());
    assertEquals(1, scene.clipCount());
  }

  private static void assertClip(
      Scene scene, int clip, float x, float y, float width, float height, float radius) {
    assertEquals(x, scene.clipGet(clip, Scene.X));
    assertEquals(y, scene.clipGet(clip, Scene.Y));
    assertEquals(width, scene.clipGet(clip, Scene.WIDTH));
    assertEquals(height, scene.clipGet(clip, Scene.HEIGHT));
    assertEquals(radius, scene.clipGet(clip, Scene.RADIUS));
    assertEquals(0, scene.clips().get(ValueLayout.JAVA_FLOAT, clip * Scene.CLIP_STRIDE + 20L));
  }

  @Test
  void growsPastItsFirstAllocation() {
    Scene scene = new Scene();
    for (int i = 0; i < 200; i++) {
      scene.rect(i, 0, 1, 1, 0, 0, 0xFFFFFFFF, 0);
    }
    assertEquals(200, scene.count());
    assertEquals(199f, scene.get(199, Scene.X));

    El root = el();
    root.place(0, 0, 100, 100);
    El parent = root;
    for (int i = 0; i < 20; i++) {
      El child = el().clip();
      child.place(1, 1, 100 - 2 * (i + 1), 100 - 2 * (i + 1));
      parent.child(child);
      parent = child;
    }
    parent.child(el().fill(0xFFFFFFFF));
    parent.children().getFirst().place(0, 0, 1, 1);
    Scene clipped = Scene.of(root);
    assertEquals(21, clipped.clipCount());
    assertEquals(20, clipped.clipOf(0));
    assertEquals(20f, clipped.clipGet(20, Scene.X));
  }
}
