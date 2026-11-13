package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.Checkbox.checkbox;
import static com.rethinkj.ui.kit.DataGrid.dataGrid;
import static com.rethinkj.ui.kit.Label.label;
import static com.rethinkj.ui.kit.TextField.textField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Headless;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.Button.Variant;
import com.rethinkj.ui.kit.DataGrid.Align;
import com.rethinkj.ui.kit.DataGrid.Column;
import com.rethinkj.ui.render.Pixels;
import com.rethinkj.ui.render.Png;
import com.rethinkj.ui.render.Scene;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The kit's components at fractional scales, measured from the pixels the CPU draws: borders,
 * hairlines and box edges at rest lie on whole physical pixels, as the scene snaps them, and text
 * and icons keep their place in their boxes wherever the boxes are.
 */
class PixelGridTest {

  private static final Theme T = Theme.LIGHT;
  private static final float WIDTH = 520;
  private static final float HEIGHT = 420;

  /** The kit's components on the window's colour, moved right and down by {@code shift}. */
  private static El sheet(float shift) {
    List<Column> columns = List.of(
        new Column("id", 60, Align.RIGHT, true, String::valueOf),
        new Column("customer", 110, Align.LEFT, false, i -> "Customer " + i),
        new Column("total", 70, Align.RIGHT, true, i -> i * 7 + ".50"));
    DataGrid.Selection selection = new DataGrid.Selection(DataGrid.Mode.ROWS);
    selection.select(1, 0);
    Menu.State menu = new Menu.State();
    menu.open();
    return row()
        .wFull()
        .hFull()
        .fill(T.surface().window())
        .padding(12 + shift, 0, 0, 12 + shift)
        .gap(16)
        .children(
            col()
                .gap(12)
                .w(220)
                .children(
                    textField(T, "Orders", v -> {}).key("field").w(220),
                    row()
                        .gap(8)
                        .children(
                            button(T, Variant.PRIMARY, "Run", () -> {}).key("run"),
                            button(T, Variant.SECONDARY, "Cancel", () -> {}).key("cancel"),
                            checkbox(T, true, "Wrap", () -> {}).key("wrap")),
                    Tabs.tabs(
                            T,
                            List.of(
                                new Tabs.Tab("orders", true, false),
                                new Tabs.Tab("customers", true, true)),
                            0,
                            i -> {},
                            i -> {},
                            label(T, "Rows").key("tab-content"))
                        .key("tabs")
                        .h(64),
                    // not in a card of its own: the group's shadow would tint that card's border
                    Group.group(
                            T,
                            Group.switchRow(T, "Autocommit", null, true, () -> {}),
                            Group.row(T, "Timeout", "Seconds", label(T, "30")),
                            Group.linkRow(T, "Driver", null, "PostgreSQL", () -> {}))
                        .key("card")),
            col()
                .gap(12)
                .children(
                    dataGrid(T, columns, 40, DataGrid.options().select(selection))
                        .key("grid")
                        .label("Orders")
                        .w(260)
                        .h(170),
                    Menu.menu(
                            T,
                            menu,
                            List.of(
                                Menu.item("Open", () -> {}),
                                Menu.separator(),
                                Menu.item("Copy name", () -> {})))
                        .key("menu")));
  }

  private static Headless.Frame render(float shift, float scale) {
    return Headless.render(sheet(shift), WIDTH, HEIGHT, scale, TextRendering.LINUX);
  }

  @ParameterizedTest
  @ValueSource(floats = {1.25f, 1.5f, 1.75f, 2.5f, 3})
  void bordersHairlinesAndEdgesLieOnWholePixels(float scale) throws IOException {
    TextLibraries.assumeLoaded();
    for (float shift : new float[] {0, 1}) {
      El root = sheet(shift);
      Pixels pixels =
          Headless.render(root, WIDTH, HEIGHT, scale, TextRendering.LINUX).pixels();
      Path out = Path.of("target/pixel-grid");
      Files.createDirectories(out);
      Files.write(out.resolve("sheet-" + scale + "x-" + (int) shift + ".png"), Png.encode(pixels));
      Edges edges = new Edges(pixels, scale);
      edges.walk(root, 0, 0, new float[] {0, 0, WIDTH, HEIGHT});
      assertTrue(edges.checked > 40, "edges checked at " + scale + ": " + edges.checked);
      assertEquals(List.of(), edges.failures, "at scale " + scale + ", shifted by " + shift);
    }
  }

  /**
   * The sheet at two places, a logical pixel apart, which is a fraction of a physical pixel apart
   * at these scales: the ink of each text element and icon, what the render with the text has
   * that the one without has not, is the same in both from the top left of the element's box.
   */
  @ParameterizedTest
  @ValueSource(floats = {1.25f, 1.5f, 1.75f, 2.5f, 3})
  void textAndIconsKeepTheirPlaceInTheirBoxes(float scale) {
    TextLibraries.assumeLoaded();
    Ink a = new Ink(0, scale);
    Ink b = new Ink(1, scale);
    assertEquals(a.boxes.size(), b.boxes.size());
    assertTrue(a.boxes.size() > 20, "text elements: " + a.boxes.size());
    List<String> moved = new ArrayList<>();
    int inked = 0;
    for (int i = 0; i < a.boxes.size(); i++) {
      int[] ra = snapped(a.boxes.get(i), scale);
      int[] rb = snapped(b.boxes.get(i), scale);
      if (ra == null || rb == null) {
        continue; // cut by a clip
      }
      int w = Math.min(ra[2], rb[2]);
      int h = Math.min(ra[3], rb[3]);
      int pixels = 0;
      search:
      for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
          int inkA = a.ink(ra[0] + x, ra[1] + y);
          int inkB = b.ink(rb[0] + x, rb[1] + y);
          if (inkA != inkB) {
            moved.add(a.names.get(i) + " at " + x + "," + y);
            break search;
          }
          pixels += inkA != 0 ? 1 : 0;
        }
      }
      inked += pixels > 0 ? 1 : 0;
    }
    assertEquals(List.of(), moved, "text drawn otherwise in its box at " + scale);
    assertTrue(inked > 20, "text elements with ink: " + inked);
  }

  /** The sheet drawn with and without its text, and its text elements. */
  private static final class Ink {

    final Pixels with;
    final Pixels without;
    final List<float[]> boxes = new ArrayList<>();
    final List<String> names = new ArrayList<>();

    Ink(float shift, float scale) {
      El root = sheet(shift);
      with = Headless.render(root, WIDTH, HEIGHT, scale, TextRendering.LINUX).pixels();
      texts(root, 0, 0, new float[] {0, 0, WIDTH, HEIGHT});
      El bare = sheet(shift);
      transparent(bare);
      without = Headless.render(bare, WIDTH, HEIGHT, scale, TextRendering.LINUX).pixels();
    }

    /** The pixel where the text draws, 0 where it does not. */
    int ink(int x, int y) {
      int pixel = with.argb(x, y);
      return pixel == without.argb(x, y) ? 0 : pixel;
    }

    /** The window rectangles of the text elements that their clips leave whole, else null. */
    private void texts(El el, float px, float py, float[] clip) {
      float x = px + el.x();
      float y = py + el.y();
      if (el.text() != null && !el.text().isField() && el.isPlaced()) {
        boolean whole =
            x >= clip[0] && y >= clip[1] && x + el.width() <= clip[2] && y + el.height() <= clip[3];
        boxes.add(whole ? new float[] {x, y, el.width(), el.height()} : null);
        names.add(el.text().string());
      }
      Style s = el.style();
      float[] inner = s.overflowX == Style.Overflow.VISIBLE && s.overflowY == Style.Overflow.VISIBLE
          ? clip
          : new float[] {
            Math.max(clip[0], x),
            Math.max(clip[1], y),
            Math.min(clip[2], x + el.width()),
            Math.min(clip[3], y + el.height())
          };
      for (El child : el.children()) {
        texts(child, x, y, inner);
      }
    }

    private static void transparent(El el) {
      if (el.text() != null && !el.text().isField()) {
        el.color(0);
      }
      for (El child : el.children()) {
        transparent(child);
      }
    }
  }

  /** A logical rectangle on the physical pixel grid, as the scene snaps it. */
  private static int[] snapped(float[] r, float scale) {
    if (r == null) {
      return null;
    }
    int left = edge(r[0], scale);
    int top = edge(r[1], scale);
    return new int[] {left, top, edge(r[0] + r[2], scale) - left, edge(r[1] + r[3], scale) - top};
  }

  private static int edge(float v, float scale) {
    return (int) Math.floor(v * (double) scale + 0.5);
  }

  /** A border, hairline or focus ring of {@code width} logical pixels, in physical pixels. */
  static int stroke(float width, float scale) {
    return (int) Math.max(1, Math.floor(width * (double) scale + 1e-3));
  }

  /** The sheet at 1.5, on whole physical pixels and moved by half of one, to look at. */
  @Test
  void theSheetAtOneAndAHalf() {
    TextLibraries.assumeLoaded();
    for (int shift = 0; shift < 2; shift++) {
      Headless.Frame frame = render(shift, 1.5f);
      Golden.assertMatches(
          "pixel-grid-1.5x-" + (shift == 0 ? "whole" : "half"), frame.pixels(), frame.tree());
    }
  }

  @Test
  void aHairlineIsAWholeNumberOfPixels() {
    float[] scales = {1, 1.25f, 1.5f, 1.75f, 2, 2.5f, 3};
    int[] widths = {1, 1, 1, 1, 2, 2, 3};
    for (int i = 0; i < scales.length; i++) {
      assertEquals(widths[i], stroke(1, scales[i]), "at " + scales[i]);
    }
  }

  /** The focus ring, from the keyboard, around a field and a button at each fractional scale. */
  @ParameterizedTest
  @ValueSource(floats = {1.25f, 1.5f, 1.75f, 2.5f, 3})
  void theFocusRingIsWholePixelsWide(float scale) {
    TextLibraries.assumeLoaded();
    App<Object> app = new App<>(new Object(), s -> sheet(1));
    app.textRendering(TextRendering.LINUX);
    app.resize(WIDTH, HEIGHT, scale);
    app.frame();
    int ring = stroke(Scene.RING, scale);
    for (int i = 0; i < 3; i++) { // the field, Run and Cancel
      app.keyDown(new Key("Tab", 0, false));
      app.frame();
      El focused = app.focused();
      float[] at = position(app.tree(), focused, 0, 0);
      Pixels pixels = app.render().pixels();
      int x = edge(at[0] + focused.width() / 2, scale);
      int top = edge(at[1], scale);
      int colour = focused.focusRing();
      // A primary button's ring lies a gap away, which shows the surface behind it.
      int gap = focused.focusRingGap() > 0 ? stroke(focused.focusRingGap(), scale) : 0;
      for (int y = top - gap; y < top; y++) {
        assertTrue(pixels.argb(x, y) != colour, "the gap of " + focused.key() + " row " + y);
      }
      top -= gap;
      for (int y = top - ring; y < top; y++) {
        assertEquals(hex(colour), hex(pixels.argb(x, y)), "ring of " + focused.key() + " row " + y);
      }
      assertTrue(pixels.argb(x, top - ring - 1) != colour, "ring no wider at " + scale);
      Edges edges = new Edges(pixels, scale);
      edges.side(focused.key() + " ring", x, top - ring, ring, false, colour, gap > 0);
      assertEquals(List.of(), edges.failures);
    }
  }

  private static float[] position(El el, El target, float parentX, float parentY) {
    float x = parentX + el.x();
    float y = parentY + el.y();
    if (el == target) {
      return new float[] {x, y};
    }
    for (El child : el.children()) {
      float[] found = position(child, target, x, y);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  private static float px(Len len) {
    return len.kind() == Len.Kind.PX ? len.value() : 0;
  }

  static String hex(int argb) {
    return String.format("%08X", argb);
  }

  /**
   * Checks the straight edges of every element whose border or fill is opaque, at the middle of
   * each side where it is straight and its clips leave it: a border or a hairline is a run of
   * exactly {@link #stroke} pixels of its colour from where {@code round(edge * scale)} puts it,
   * and the pixels next to an edge are no blend of the colours on either side, as an edge between
   * two physical pixels would draw them. A fill whose edge pixel a child covers is left out.
   */
  static final class Edges {

    final Pixels pixels;
    final float scale;
    final List<String> failures = new ArrayList<>();
    int checked;
    private boolean shadowed;

    Edges(Pixels pixels, float scale) {
      this.pixels = pixels;
      this.scale = scale;
    }

    /** @param clip the window rectangle the element's ancestors leave: x, y, right, bottom */
    void walk(El el, float px, float py, float[] clip) {
      if (!el.isPlaced() || el.opacity() < 1) {
        return;
      }
      float x = px + el.x();
      float y = py + el.y();
      float w = el.width();
      float h = el.height();
      String name = el.key() != null ? "#" + el.key() : el.role() != null ? el.role() : "el";
      Style st = el.style();
      // Per side, top, right, bottom, left: a border's run of pixels in its colour, else the fill's
      // edge.
      float[] widths = {px(st.borderTop), px(st.borderRight), px(st.borderBottom), px(st.borderLeft)
      };
      boolean coloured = el.borderColor() >>> 24 == 0xFF;
      boolean filled = el.fill() >>> 24 == 0xFF;
      int[] runs = new int[4];
      int[] colours = new int[4];
      boolean any = false;
      for (int side = 0; side < 4; side++) {
        boolean bordered = coloured && widths[side] > 0;
        runs[side] = bordered ? stroke(widths[side], scale) : 0;
        colours[side] = bordered ? el.borderColor() : filled ? el.fill() : 0;
        any |= colours[side] != 0;
      }
      shadowed = !el.shadows().isEmpty(); // a gradient outside, which is no edge's blend
      if (any) {
        boolean thinX = w > 0 && w <= Scene.HAIRLINE;
        boolean thinY = h > 0 && h <= Scene.HAIRLINE;
        float r = 0;
        for (float corner : el.radii()) {
          r = Math.max(r, corner);
        }
        float cx = x + w / 2; // the middle of each side, where it is straight
        float cy = y + h / 2;
        if (cx > clip[0] + 2 && cx < clip[2] - 2 && w > 2 * r + 2) {
          if (thinY) {
            if (y >= clip[1] + 1 && y + h <= clip[3] - 1) {
              side(
                  name + " hairline",
                  edge(cx, scale),
                  edge(y, scale),
                  stroke(h, scale),
                  false,
                  el.fill(),
                  true);
            }
          } else {
            if (y >= clip[1] + 1 && colours[0] != 0) {
              side(
                  name + " top", edge(cx, scale), edge(y, scale), runs[0], false, colours[0], true);
            }
            if (y + h <= clip[3] - 1 && colours[2] != 0) {
              side(
                  name + " bottom",
                  edge(cx, scale),
                  edge(y + h, scale) - runs[2],
                  runs[2],
                  false,
                  colours[2],
                  true);
            }
          }
        }
        if (cy > clip[1] + 2 && cy < clip[3] - 2 && h > 2 * r + 2) {
          if (thinX) {
            if (x >= clip[0] + 1 && x + w <= clip[2] - 1) {
              side(
                  name + " hairline",
                  edge(x, scale),
                  edge(cy, scale),
                  stroke(w, scale),
                  true,
                  el.fill(),
                  true);
            }
          } else {
            if (x >= clip[0] + 1 && colours[3] != 0) {
              side(
                  name + " left", edge(x, scale), edge(cy, scale), runs[3], true, colours[3], true);
            }
            if (x + w <= clip[2] - 1 && colours[1] != 0) {
              side(
                  name + " right",
                  edge(x + w, scale) - runs[1],
                  edge(cy, scale),
                  runs[1],
                  true,
                  colours[1],
                  true);
            }
          }
        }
      }
      float[] inner = clip;
      Style s = st;
      if (s.overflowX != Style.Overflow.VISIBLE || s.overflowY != Style.Overflow.VISIBLE) {
        inner = new float[] {
          Math.max(clip[0], x),
          Math.max(clip[1], y),
          Math.min(clip[2], x + w),
          Math.min(clip[3], y + h)
        };
      }
      for (El child : el.children()) {
        walk(child, x, y, inner);
      }
    }

    /**
     * The edge across {@code x, y} at {@code start}, which is {@code x} when {@code across}, else
     * {@code y}: {@code run} pixels of {@code colour} from there, or for a run of 0 a fill of that
     * colour from there, with no blend of it in the pixel before and, if {@code inside}, in the
     * pixel after the run.
     */
    void side(String name, int x, int y, int run, boolean across, int colour, boolean inside) {
      int start = across ? x : y;
      int limit = across ? pixels.width() : pixels.height();
      int other = across ? y : x;
      if (start - 2 < 0
          || start + run + 2 > limit
          || other < 0
          || other >= (across ? pixels.height() : pixels.width())) {
        return;
      }
      int[] p = new int[run + 4];
      for (int i = 0; i < p.length; i++) {
        int at = start - 2 + i;
        p[i] = across ? pixels.argb(at, y) : pixels.argb(x, at);
      }
      if (run == 0 && p[2] != colour) {
        return; // a child covers the edge
      }
      checked++;
      for (int i = 2; i < 2 + run; i++) {
        if (p[i] != colour) {
          failures.add(name + ": not " + run + " pixels of " + hex(colour).substring(2) + " from "
              + start + ":" + hexes(p));
          return;
        }
      }
      // a neighbour's hairline or an ancestor's border is a whole pixel of its colour, no blend
      if (!shadowed && !line(p[1]) && blend(p[0], p[1], colour)) {
        failures.add(name + ": the pixel before " + start + " is a blend:" + hexes(p));
      } else if (!shadowed
          && inside
          && run > 0
          && !line(p[2 + run])
          && blend(colour, p[2 + run], p[3 + run])) {
        failures.add(name + ": the pixel after the run is a blend:" + hexes(p));
      }
    }

    private static boolean line(int argb) {
      return argb == T.border().subtle()
          || argb == T.border().control()
          || argb == T.border().strong();
    }

    private static String hexes(int[] p) {
      StringBuilder out = new StringBuilder();
      for (int v : p) {
        out.append(' ').append(hex(v).substring(2));
      }
      return out.toString();
    }

    /**
     * Whether {@code m} lies between {@code a} and {@code b} in every channel, as an edge that
     * covers part of a pixel draws it, and differs from both.
     */
    static boolean blend(int a, int m, int b) {
      boolean nearA = true;
      boolean nearB = true;
      for (int shift = 0; shift < 24; shift += 8) {
        int ca = a >> shift & 0xFF;
        int cm = m >> shift & 0xFF;
        int cb = b >> shift & 0xFF;
        if (cm < Math.min(ca, cb) - 1 || cm > Math.max(ca, cb) + 1) {
          return false;
        }
        nearA &= Math.abs(cm - ca) <= 2;
        nearB &= Math.abs(cm - cb) <= 2;
      }
      return !nearA && !nearB;
    }
  }
}
