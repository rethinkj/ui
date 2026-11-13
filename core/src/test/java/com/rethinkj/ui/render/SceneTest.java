package com.rethinkj.ui.render;

import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rethinkj.ui.El;
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
