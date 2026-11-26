package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.StructureTest.line;
import static com.rethinkj.ui.kit.StructureTest.rect;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Colors;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.render.Png;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Behavior, tree dump, contrast, motion and stored look of {@link Meter}. */
class MeterTest {

  @TempDir
  Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  @Test
  void rendersVariantsAndMatchesGolden() throws IOException {
    Theme theme = Theme.DARK;
    App<Object> app = new App<>(
        new Object(),
        ignored -> col()
            .wFull()
            .hFull()
            .padding(theme.space().l())
            .gap(theme.space().m())
            .fill(theme.surface().window())
            .children(
                Meter.bar(
                        List.of(new Meter.Segment("Requests", 0.54, 0)),
                        Meter.options()
                            .hatched()
                            .marker(0.7, "Target")
                            .trailing(
                                Badge.badge("54%"), Badge.badge(Badge.Kind.SUCCESS, "On track")))
                    .key("single")
                    .label("API quota"),
                Meter.bar(
                        List.of(
                            new Meter.Segment("Compute", 0.34, 0),
                            new Meter.Segment("Storage", 0.22, 1),
                            new Meter.Segment("Cache", 0.18, 2),
                            new Meter.Segment("Logs", 0.12, 3)),
                        Meter.options().hatched())
                    .key("stacked")
                    .label("Cluster usage"),
                Meter.bar(List.of(), Meter.options().hatched()).key("empty").label("Idle pool"),
                Meter.bar(
                        List.of(new Meter.Segment("Allocated", 1.0, 2)),
                        Meter.options().marker(0.9, "Alert").trailing(Badge.badge("5d 23h")))
                    .key("full")
                    .label("Disk"),
                Meter.bar(
                        List.of(new Meter.Segment("Production traffic", 0.65, 1)),
                        Meter.options()
                            .hatched()
                            .marker(0.75, "Limit")
                            .trailing(Badge.badge("Near limit")))
                    .key("narrow")
                    .label("Narrow quota")
                    .w(150)));
    app.textRendering(TextRendering.LINUX);

    String image = "meter-dark-2x";
    Script.run("size 480 216 scale 2\nframe\nkey Tab\nframe\nsnap " + image, app, out);

    String tree = Files.readString(out.resolve(image + ".tree.txt"));
    Golden.assertMatches(image, Png.decode(Files.readAllBytes(out.resolve(image + ".png"))), tree);

    // Non-focusable, role=meter with label and total percent, keyed segments and #marker.
    assertNull(app.focused(), "meter takes no focus");
    assertTrue(
        line(tree, "#single").contains("role=meter label=\"API quota\"")
            && line(tree, "#single").endsWith(" value=\"54%\""),
        tree);
    assertTrue(
        line(tree, "label=\"Requests\"").contains("#segment-0")
            && line(tree, "label=\"Requests\"").endsWith(" value=\"54%\""),
        tree);
    assertTrue(
        line(tree, "label=\"Target\"").contains("#marker")
            && line(tree, "label=\"Target\"").endsWith(" value=\"70%\""),
        tree);
    assertTrue(line(tree, "#stacked").endsWith(" value=\"86%\""), tree);
    assertTrue(line(tree, "#empty").endsWith(" value=\"0%\""), tree);
    assertTrue(line(tree, "#full").endsWith(" value=\"100%\""), tree);

    // Wide meter keeps trailing badges inside its track; narrow meter drops them below its clip.
    El single = findByKey(app.tree(), "single");
    El singleTrailing = findByKey(single, "trailing");
    assertTrue(singleTrailing.y() >= 0 && singleTrailing.y() < single.height(), tree);

    El narrow = findByKey(app.tree(), "narrow");
    El narrowText = findByKey(narrow, "segment-0").children().getFirst();
    assertTrue(narrowText.text().shortened(), "narrow segment text ellipsizes");
    El narrowTrailing = findByKey(narrow, "trailing");
    assertTrue(
        narrowTrailing.y() >= narrow.height(),
        "narrow meter hides trailing badges as a whole: y="
            + narrowTrailing.y()
            + ", track height="
            + narrow.height());
  }

  @Test
  void clampsFractionsCapsSumPicksReadableInkAndUsesTextsForMarker() throws IOException {
    int brightYellow = 0xFFFDD835;
    int darkNavy = 0xFF102040;
    int midGray = 0xFF7A7A7A; // neither white nor the light primary text reads 4.5:1 on it
    App<Object> app = new App<>(
            new Object(),
            ignored -> col()
                .wFull()
                .child(Meter.bar(
                        List.of(
                            new Meter.Segment("NaN", Double.NaN, 0),
                            new Meter.Segment("Negative", -0.5, 1),
                            new Meter.Segment("Bright", 0.7, 0).color(brightYellow),
                            new Meter.Segment("Capped", 0.5, 1).color(darkNavy)),
                        Meter.options().marker(1.5))
                    .key("meter")
                    .label("Clamped"))
                .child(Meter.bar(List.of(
                        new Meter.Segment("A", 0.6, 0).color(midGray),
                        new Meter.Segment("B", 0.3, 1),
                        new Meter.Segment("C", 0.1, 2)))
                    .key("whole")))
        .provide(Texts.CURRENT, ignored -> new GermanTexts());
    Script.run("size 400 80\nframe", app, out);

    String dump = app.tree().dump();
    assertTrue(line(dump, "#meter").endsWith(" value=\"100%\""), dump);
    assertTrue(line(dump, "#whole").endsWith(" value=\"100%\""), "0.6 + 0.3 + 0.1 is whole");
    assertTrue(line(dump, "#segment-0").endsWith(" value=\"0%\""), dump);
    assertTrue(line(dump, "#segment-1").endsWith(" value=\"0%\""), dump);
    assertTrue(line(dump, "#segment-2").endsWith(" value=\"70%\""), dump);
    assertTrue(line(dump, "#segment-3").endsWith(" value=\"30%\""), dump);
    assertTrue(
        line(dump, "#marker").contains("label=\"Markierung\"")
            && line(dump, "#marker").endsWith(" value=\"100%\""),
        dump);

    // Zero-fraction segments take 0 width so they paint nothing.
    assertEquals(0, rect(line(dump, "#segment-0"))[2], dump);
    assertEquals(0, rect(line(dump, "#segment-1"))[2], dump);

    // Custom segment colors pick readable ink by WCAG contrast.
    El brightText = findByKey(app.tree(), "segment-2").children().getFirst();
    El darkText = findByKey(app.tree(), "segment-3").children().getFirst();
    assertTrue(Colors.contrast(brightText.text().color(), brightYellow) >= 4.5);
    assertTrue(Colors.contrast(darkText.text().color(), darkNavy) >= 4.5);
    assertEquals(Theme.DARK.series().text(), darkText.text().color());
    El grayText =
        findByKey(findByKey(app.tree(), "whole"), "segment-0").children().getFirst();
    assertTrue(Colors.contrast(grayText.text().color(), midGray) >= 4.5);
  }

  @Test
  void segmentsAndMarkerSpringToNewValues() throws IOException {
    double[] values = {0.2, 0.3}; // segment fraction, marker fraction
    App<double[]> app = new App<>(
        values,
        state -> col()
            .wFull()
            .child(Meter.bar(
                    List.of(new Meter.Segment("Used", state[0], 0)),
                    Meter.options().marker(state[1]))
                .key("meter")));
    Script.run("size 400 80\nframe", app, out);
    float startWidth = findByKey(app.tree(), "segment-0").width();
    float startMarkerX = findByKey(app.tree(), "marker").x();
    assertFalse(app.isMoving());

    values[0] = 0.7;
    values[1] = 0.8;
    app.invalidate();
    Script.run("frame\nadvance 0.05\nframe", app, out);
    assertTrue(app.isMoving(), "segment and marker spring to new values");
    float midWidth = findByKey(app.tree(), "segment-0").width();
    float midMarkerX = findByKey(app.tree(), "marker").x();
    assertTrue(midWidth > startWidth && midWidth < 280, "width mid-spring: " + midWidth);
    assertTrue(midMarkerX > startMarkerX && midMarkerX < 318, "marker mid-spring: " + midMarkerX);

    Script.run("advance 1\nframe", app, out);
    assertFalse(app.isMoving(), "settled");
    assertEquals(280f, findByKey(app.tree(), "segment-0").width(), 1f);
  }

  @Test
  void trailingBadgeDoesNotCoverMovingLabelWhileSegmentsSpring() throws IOException {
    double[] shares = {0.78, 0.21};
    App<double[]> app = new App<>(
        shares,
        state -> col()
            .wFull()
            .child(Meter.bar(
                    List.of(
                        new Meter.Segment("Compute", state[0], 0),
                        new Meter.Segment("Storage", state[1], 1)),
                    Meter.options().trailing(Badge.badge("5d 23h")))
                .key("meter")
                .w(400)));
    Script.run("size 400 80\nframe", app, out);
    assertTrue(
        findByKey(app.tree(), "trailing").y() >= findByKey(app.tree(), "meter").height(),
        "initially hidden");

    shares[0] = 0.30;
    shares[1] = 0.69;
    app.invalidate();
    Script.run("frame", app, out);
    assertTrue(app.isMoving(), "segments and trailing badges spring");

    for (int step = 0; step < 100; step++) {
      El meter = findByKey(app.tree(), "meter");
      El layer = findByKey(meter, "trailing-layer");
      El row = layer.children().get(1);
      El trailing = findByKey(row, "trailing");
      float badgeLeft = layer.x() + row.x() + trailing.x();
      float badgeTop = layer.y() + row.y() + trailing.y();
      El storage = findByKey(meter, "segment-1");
      El label = storage.children().getFirst();
      float labelRight = Math.min(
          storage.x() + storage.width(), storage.x() + label.x() + TextSizing.measured(label));
      assertTrue(
          badgeLeft >= meter.width() || badgeTop >= meter.height() || badgeLeft >= labelRight,
          "step " + step + ": badge left " + badgeLeft + " covers Storage label ending at "
              + labelRight);
      if (!app.isMoving()) {
        break;
      }
      Script.run("advance 0.015\nframe", app, out);
    }

    assertFalse(app.isMoving(), "settled");
    El meter = findByKey(app.tree(), "meter");
    El layer = findByKey(meter, "trailing-layer");
    El row = layer.children().get(1);
    El trailing = findByKey(row, "trailing");
    float badgeLeft = layer.x() + row.x() + trailing.x();
    float badgeTop = layer.y() + row.y() + trailing.y();
    assertTrue(
        badgeLeft >= 0 && badgeLeft < meter.width() && badgeTop >= 0 && badgeTop < meter.height(),
        "settled badge inside track: x=" + badgeLeft + ", y=" + badgeTop);
  }

  @Test
  void tinyLastSegmentDoesNotHideFittingTrailingBadge() throws IOException {
    App<Object> app = new App<>(
        new Object(),
        ignored -> col()
            .wFull()
            .children(
                Meter.bar(
                        List.of(
                            new Meter.Segment("Requests", 0.99, 0),
                            new Meter.Segment("Errors", 0.01, 1)),
                        Meter.options().trailing(Badge.badge("5d 23h")))
                    .key("tiny-400")
                    .w(400),
                Meter.bar(
                        List.of(
                            new Meter.Segment("Requests", 0.80, 0),
                            new Meter.Segment("Errors", 0.05, 1)),
                        Meter.options().trailing(Badge.badge("Near limit")))
                    .key("tiny-320")
                    .w(320),
                Meter.bar(
                        List.of(
                            new Meter.Segment("Compute", 0.25, 0),
                            new Meter.Segment("Storage", 0.74, 1),
                            new Meter.Segment("Errors", 0.01, 2)),
                        Meter.options().trailing(Badge.badge("5d 23h")))
                    .key("three-segments")
                    .w(400),
                Meter.bar(
                        List.of(
                            new Meter.Segment("Compute", 0.70, 0),
                            new Meter.Segment("Storage", 0.25, 1),
                            new Meter.Segment("Errors", 0.01, 2)),
                        Meter.options().trailing(Badge.badge("Near limit")))
                    .key("drawing-middle-hides")
                    .w(240)));
    Script.run("size 420 200\nframe", app, out);

    // Tiny last segments draw no glyphs, so their trailing badges stay inside the track.
    for (String key : List.of("tiny-400", "tiny-320", "three-segments")) {
      El meter = findByKey(app.tree(), key);
      El trailing = findByKey(meter, "trailing");
      assertTrue(
          trailing.y() >= 0 && trailing.y() < meter.height(),
          key + " keeps trailing badge inside track: y=" + trailing.y() + ", h=" + meter.height());
    }

    // When the rightmost drawing segment leaves too little room, the badge hides.
    El crowded = findByKey(app.tree(), "drawing-middle-hides");
    El drawingText = findByKey(crowded, "segment-1").children().getFirst();
    assertTrue(
        drawingText.text().lines().stream().anyMatch(line -> line.glyphs().count() > 0),
        "segment-1 draws glyphs");
    El crowdedTrailing = findByKey(crowded, "trailing");
    assertTrue(
        crowdedTrailing.y() >= crowded.height(),
        "hides trailing badge: y=" + crowdedTrailing.y() + ", h=" + crowded.height());
  }

  private static El findByKey(El root, String key) {
    if (key.equals(root.key())) {
      return root;
    }
    for (El child : root.children()) {
      El found = findByKey(child, key);
      if (found != null) {
        return found;
      }
    }
    return null;
  }
}
