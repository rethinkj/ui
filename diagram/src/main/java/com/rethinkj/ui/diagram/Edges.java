package com.rethinkj.ui.diagram;

import static com.rethinkj.ui.El.el;

import com.rethinkj.ui.Colors;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Segment;
import com.rethinkj.ui.diagram.Canvas.Edge;
import com.rethinkj.ui.diagram.Canvas.Emphasis;
import com.rethinkj.ui.diagram.Canvas.Marker;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Tooltip;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws a canvas's edges as its children, under its nodes. Each run of an edge is a band from the
 * middle of one bend to the middle of the next, as wide as {@link EdgeStyle#hit} or up to halfway
 * to the next run beside it, that shows the edge's tooltip, also over its bends, and holds a thin
 * rectangle, its line; a box as tall as a marker over it shows the tooltip too. Each bend is a
 * quarter of a ring, and a marker's bars, crow's foot and arrowhead are line segments of the band
 * at its end, its circle a ring filled with the canvas's color. All are children of the canvas
 * itself: a parent as large as an edge's bounds would take the pointer from the edges beneath it.
 *
 * <p>Limits: the pointer finds an edge by these rectangles, not by its distance from the lines, so
 * where two edges bend close together, the one drawn later can take the pointer from the other
 * near their bends.
 */
final class Edges {

  private Edges() {}

  /**
   * What the canvas shows: the diagram point at its top left, the zoom, and its size in logical
   * pixels.
   */
  record View(double left, double top, float zoom, float width, float height) {

    /** A diagram x in the canvas, rounded as the canvas rounds a node's edges. */
    float edge(double x) {
      return Math.round((x - left) * zoom);
    }

    /** A diagram coordinate in the canvas, where a line this wide is sharpest. */
    float line(double coordinate, double origin, float width) {
      return Math.round((coordinate - origin) * zoom - width / 2) + width / 2;
    }
  }

  // Where the parts of a marker lie from the node, in markers' lengths.
  private static final float NEAR = 0.75f;
  private static final float FAR = 1.25f;
  private static final float BEYOND_FOOT = 1.5f;
  private static final float CIRCLE = 1.75f;
  private static final float CIRCLE_RADIUS = 0.35f;
  private static final float SPREAD = 0.5f; // half a bar's or crow's foot's height
  private static final float REACH = CIRCLE + CIRCLE_RADIUS; // the farthest a marker reaches
  // How far from its corner the middle of a bend lies along either run, in its radii.
  private static final float HALF_BEND = (float) (1 - Math.sqrt(0.5));

  /** How far a marker reaches from the node along its line, in markers' lengths. */
  static float reach(Marker marker) {
    return switch (marker) {
      case NONE -> 0;
      case ONE -> FAR;
      case ONE_OR_MANY -> BEYOND_FOOT;
      case ZERO_OR_ONE, ZERO_OR_MANY -> REACH;
      case ARROW -> 1;
    };
  }

  /** Adds the edges with these routes, one for each, null for one that is not drawn. */
  static void add(
      El canvas, List<Edge> edges, List<Routing.Route> routes, View view, EdgeStyle style) {
    Theme theme = Theme.CURRENT.get();
    DiagramTexts texts = DiagramTexts.CURRENT.get();
    List<Drawn> drawn = new ArrayList<>();
    for (Emphasis emphasis :
        new Emphasis[] {Emphasis.DIMMED, Emphasis.NORMAL, Emphasis.HIGHLIGHTED}) {
      for (int i = 0; i < edges.size(); i++) {
        Edge edge = edges.get(i);
        if (edge.emphasis() == emphasis && routes.get(i) != null) {
          Drawn shown = drawn(edge, routes.get(i), view, style, theme);
          if (shown != null) {
            drawn.add(shown);
          }
        }
      }
    }
    bands(drawn, style.hit());
    for (Drawn shown : drawn) {
      add(canvas, shown, view, style, theme, texts);
    }
  }

  /**
   * An edge as the canvas shows it: its points, the radius of each bend, its line, and how far the
   * band along each run reaches across it, towards lower and higher coordinates.
   */
  private record Drawn(
      Edge edge, float[][] points, float[] radii, float width, int color, float[][] bands) {}

  /** An edge in the canvas, or null where it lies outside. */
  private static Drawn drawn(
      Edge edge, Routing.Route route, View view, EdgeStyle style, Theme theme) {
    boolean highlighted = edge.emphasis() == Emphasis.HIGHLIGHTED;
    float width = highlighted ? style.highlightedWidth() : style.width();
    int strong = theme.border().strong();
    int color = switch (edge.emphasis()) {
      case NORMAL -> strong;
      case HIGHLIGHTED -> theme.accent().color();
      case DIMMED -> Colors.mix(strong, theme.surface().window(), style.dimming());
    };
    float[][] points = points(route, view, width);
    int n = points.length;
    float reach = REACH * style.marker() * view.zoom() + style.hit() + width / 2;
    float minX = Float.POSITIVE_INFINITY;
    float minY = Float.POSITIVE_INFINITY;
    float maxX = Float.NEGATIVE_INFINITY;
    float maxY = Float.NEGATIVE_INFINITY;
    for (float[] point : points) {
      minX = Math.min(minX, point[0]);
      minY = Math.min(minY, point[1]);
      maxX = Math.max(maxX, point[0]);
      maxY = Math.max(maxY, point[1]);
    }
    if (maxX + reach < 0
        || maxY + reach < 0
        || minX - reach > view.width()
        || minY - reach > view.height()) {
      return null;
    }
    // Each bend's radius: the style's, but at most half of either run, and none where it turns
    // back.
    float[] radii = new float[n];
    for (int k = 1; k + 1 < n; k++) {
      if (turns(points[k - 1], points[k], points[k + 1])) {
        radii[k] = (float) Math.floor(Math.min(
            style.radius() * view.zoom(),
            Math.min(length(points[k - 1], points[k]), length(points[k], points[k + 1])) / 2));
      }
    }
    float half = Math.max(width, style.hit()) / 2;
    float[][] bands = new float[n - 1][];
    for (int k = 0; k + 1 < n; k++) {
      bands[k] = new float[] {half, half};
    }
    return new Drawn(edge, points, radii, width, color, bands);
  }

  /**
   * Narrows the bands of runs that lie side by side to meet halfway between their lines, so that
   * each shows the tooltip of its own edge; runs on one line keep theirs.
   */
  private static void bands(List<Drawn> drawn, float hit) {
    for (boolean vertical : new boolean[] {true, false}) {
      List<float[]> runs = new ArrayList<>(); // across, low, high, edge, run
      for (int e = 0; e < drawn.size(); e++) {
        float[][] points = drawn.get(e).points();
        for (int k = 0; k + 1 < points.length; k++) {
          float[] a = points[k];
          float[] b = points[k + 1];
          int along = vertical ? 1 : 0;
          if (a[1 - along] == b[1 - along] && a[along] != b[along]) {
            runs.add(new float[] {
              a[1 - along], Math.min(a[along], b[along]), Math.max(a[along], b[along]), e, k
            });
          }
        }
      }
      runs.sort((r, s) -> Float.compare(r[0], s[0]));
      for (int i = 0; i < runs.size(); i++) {
        float[] r = runs.get(i);
        for (int j = i + 1; j < runs.size() && runs.get(j)[0] - r[0] < hit; j++) {
          float[] s = runs.get(j);
          float apart = s[0] - r[0];
          if (apart > 0 && s[1] < r[2] && s[2] > r[1]) {
            Drawn lower = drawn.get((int) r[3]);
            Drawn upper = drawn.get((int) s[3]);
            float[] below = lower.bands()[(int) r[4]];
            float[] above = upper.bands()[(int) s[4]];
            below[1] = Math.max(lower.width() / 2, Math.min(below[1], apart / 2));
            above[0] = Math.max(upper.width() / 2, Math.min(above[0], apart / 2));
          }
        }
      }
    }
  }

  private static void add(
      El canvas, Drawn shown, View view, EdgeStyle style, Theme theme, DiagramTexts texts) {
    Edge edge = shown.edge();
    float[][] points = shown.points();
    float[] radii = shown.radii();
    float width = shown.width();
    int color = shown.color();
    int n = points.length;
    float marker = style.marker() * view.zoom();
    // A marker's strokes no wider than a fifth of it, so that its bars keep a gap and its circle a
    // hole when zoomed out, also where the line is highlighted and wider.
    float stroke = Math.min(width, Math.max(style.width(), marker / 5));
    // None where they would be too small to tell apart.
    boolean legible = marker >= style.smallestMarker();
    Marker fromMarker = legible ? edge.fromMarker() : Marker.NONE;
    Marker toMarker = legible ? edge.toMarker() : Marker.NONE;
    List<El> circles = new ArrayList<>();
    for (int k = 0; k + 1 < n; k++) {
      float[] a = points[k];
      float[] b = points[k + 1];
      boolean vertical = a[0] == b[0];
      int along = vertical ? 1 : 0;
      float direction = Math.signum(b[along] - a[along]);
      // The band reaches over each bend to its middle, where the next run's band takes over; the
      // line in it reaches a pixel under its bends, or the smoothed ends of a bend leave a seam.
      float from = a[along] + direction * HALF_BEND * radii[k];
      float to = b[along] - direction * HALF_BEND * radii[k + 1];
      float low = Math.min(from, to);
      float length = Math.abs(to - from);
      float start = a[along] + direction * tucked(radii[k]);
      float end = b[along] - direction * tucked(radii[k + 1]);
      float across = a[1 - along];
      float before = shown.bands()[k][0];
      float after = shown.bands()[k][1];
      float originX = vertical ? across - before : low;
      float originY = vertical ? low : across - before;
      float lineAt = Math.min(start, end) - low;
      float lineLength = Math.abs(end - start);
      El band = vertical
          ? el().absolute(originX, originY).size(before + after, length)
          : el().absolute(originX, originY).size(length, before + after);
      band.child(
          vertical
              ? el().absolute(before - width / 2, lineAt)
                  .size(width, lineLength)
                  .fill(color)
              : el().absolute(lineAt, before - width / 2)
                  .size(lineLength, width)
                  .fill(color));
      List<Segment> marks = new ArrayList<>();
      if (k == 0) {
        band.key(edge.key()).role("graphics-symbol").label(edge.label());
        switch (edge.emphasis()) {
          case HIGHLIGHTED -> band.value(texts.highlighted());
          case DIMMED -> band.value(texts.dimmed());
          case NORMAL -> {}
        }
        marker(fromMarker, a, b, marker, stroke, color, theme, marks, circles, originX, originY);
      }
      if (k == n - 2) {
        marker(toMarker, b, a, marker, stroke, color, theme, marks, circles, originX, originY);
      }
      if (!marks.isEmpty()) {
        band.segments(marks);
      }
      if (edge.label() != null) {
        Tooltip.tooltip(band, edge.label(), Tooltip.options().description(edge.description()));
      }
      canvas.child(band);
      if (k == 0) {
        hover(canvas, edge, fromMarker, a, b, marker, stroke);
      }
      if (k == n - 2) {
        hover(canvas, edge, toMarker, b, a, marker, stroke);
      }
    }
    for (int k = 1; k + 1 < n; k++) {
      if (radii[k] > 0) {
        canvas.child(bend(points[k - 1], points[k], points[k + 1], radii[k], width, color));
      }
    }
    circles.forEach(canvas::child);
  }

  /**
   * The route's points in the canvas, its ends on the nodes' edges and every line where it is
   * sharpest, without points that do not bend it.
   */
  private static float[][] points(Routing.Route route, View view, float width) {
    List<Routing.Point> corners = route.points();
    List<float[]> points = new ArrayList<>();
    for (int i = 0; i < corners.size(); i++) {
      Routing.Point corner = corners.get(i);
      boolean end = i == 0 || i == corners.size() - 1;
      float x = end ? view.edge(corner.x()) : view.line(corner.x(), view.left(), width);
      float y = view.line(corner.y(), view.top(), width);
      if (!points.isEmpty()) {
        float[] last = points.getLast();
        if (last[0] == x && last[1] == y) {
          continue;
        }
      }
      points.add(new float[] {x, y});
    }
    // A point between runs in one direction does not bend the line.
    for (int k = 1; k + 1 < points.size(); ) {
      float[] a = points.get(k - 1);
      float[] b = points.get(k);
      float[] c = points.get(k + 1);
      boolean straight = (a[0] == b[0] && b[0] == c[0] || a[1] == b[1] && b[1] == c[1])
          && Math.signum(b[0] - a[0]) == Math.signum(c[0] - b[0])
          && Math.signum(b[1] - a[1]) == Math.signum(c[1] - b[1]);
      if (straight) {
        points.remove(k);
      } else {
        k++;
      }
    }
    if (points.size() == 1) {
      points.add(points.getFirst().clone()); // both ends in one place: a run of no length
    }
    return points.toArray(float[][]::new);
  }

  /**
   * Over a marker at {@code end}, where the line from {@code next} meets the node, a box as tall
   * as the marker that shows the edge's tooltip too: the band along its run is no taller than
   * the others.
   */
  private static void hover(
      El canvas, Edge edge, Marker marker, float[] end, float[] next, float size, float width) {
    if (marker == Marker.NONE || edge.label() == null) {
      return;
    }
    float direction = Math.signum(next[0] - end[0]);
    float length = reach(marker) * size + width / 2 + 1; // and the pixel a bar may round to
    float half = SPREAD * size + width / 2;
    float left = direction < 0 ? end[0] - length : end[0];
    canvas.child(Tooltip.tooltip(
        el().absolute(left, end[1] - half).size(length, 2 * half),
        edge.label(),
        Tooltip.options().description(edge.description())));
  }

  /** How far a run stops short of a bend with this radius: a pixel under it. */
  private static float tucked(float radius) {
    return Math.max(0, radius - 1);
  }

  /** Whether the line turns by a right angle at {@code b}, not back on itself. */
  private static boolean turns(float[] a, float[] b, float[] c) {
    return (a[0] == b[0]) != (b[0] == c[0]);
  }

  private static float length(float[] a, float[] b) {
    return Math.abs(b[0] - a[0]) + Math.abs(b[1] - a[1]);
  }

  /** A quarter of a ring whose middle runs from one run's end to the next run's start. */
  private static El bend(float[] a, float[] b, float[] c, float radius, float width, int color) {
    float inX = Math.signum(b[0] - a[0]);
    float inY = Math.signum(b[1] - a[1]);
    float outX = Math.signum(c[0] - b[0]);
    float outY = Math.signum(c[1] - b[1]);
    float centerX = b[0] - inX * radius + outX * radius;
    float centerY = b[1] - inY * radius + outY * radius;
    // From the center, the arc runs between the directions back along the outgoing run and along
    // the incoming one.
    float first = turn(-outX, -outY);
    float second = turn(inX, inY);
    float start = (first + 0.25f) % 1 == second ? first : second;
    float outer = radius + width / 2;
    return el().inert()
        .absolute(centerX - outer, centerY - outer)
        .size(2 * outer, 2 * outer)
        .radius(outer)
        .border(width, color)
        .arc(start, 0.25f);
  }

  /** A direction in turns clockwise from the top. */
  private static float turn(float x, float y) {
    if (y < 0) {
      return 0;
    }
    if (x > 0) {
      return 0.25f;
    }
    return y > 0 ? 0.5f : 0.75f;
  }

  /**
   * Adds the marker at {@code end}, where the line from {@code next} meets the node, as segments
   * of the band whose top left is at {@code originX}, {@code originY}, and its circle as a ring.
   */
  private static void marker(
      Marker marker,
      float[] end,
      float[] next,
      float size,
      float width,
      int color,
      Theme theme,
      List<Segment> marks,
      List<El> circles,
      float originX,
      float originY) {
    float direction = Math.signum(next[0] - end[0]);
    if (direction == 0) {
      direction = 1; // a run of no length: the line leaves to the right
    }
    float x = end[0] - originX;
    float y = end[1] - originY;
    float spread = SPREAD * size;
    switch (marker) {
      case NONE -> {}
      case ONE -> {
        bar(marks, x + direction * NEAR * size, y, spread, width, color);
        bar(marks, x + direction * FAR * size, y, spread, width, color);
      }
      case ZERO_OR_ONE -> {
        bar(marks, x + direction * NEAR * size, y, spread, width, color);
        circles.add(circle(end[0] + direction * CIRCLE * size, end[1], size, width, color, theme));
      }
      case ONE_OR_MANY -> {
        foot(marks, x, y, direction * size, spread, width, color);
        bar(marks, x + direction * BEYOND_FOOT * size, y, spread, width, color);
      }
      case ZERO_OR_MANY -> {
        foot(marks, x, y, direction * size, spread, width, color);
        circles.add(circle(end[0] + direction * CIRCLE * size, end[1], size, width, color, theme));
      }
      case ARROW -> {
        marks.add(new Segment(x, y, x + direction * size, y - spread, width, color));
        marks.add(new Segment(x, y, x + direction * size, y + spread, width, color));
      }
    }
  }

  private static void bar(
      List<Segment> marks, float x, float y, float spread, float width, int color) {
    float sharp = Math.round(x - width / 2) + width / 2;
    marks.add(new Segment(sharp, y - spread, sharp, y + spread, width, color));
  }

  /** A crow's foot: from a point {@code length} out on the line to the node, spread wide. */
  private static void foot(
      List<Segment> marks, float x, float y, float length, float spread, float width, int color) {
    marks.add(new Segment(x + length, y, x, y - spread, width, color));
    marks.add(new Segment(x + length, y, x, y + spread, width, color));
  }

  private static El circle(float x, float y, float size, float width, int color, Theme theme) {
    float radius = CIRCLE_RADIUS * size + width / 2;
    return el().inert()
        .absolute(x - radius, y - radius)
        .size(2 * radius, 2 * radius)
        .radius(radius)
        .fill(theme.surface().window())
        .border(width, color);
  }
}
