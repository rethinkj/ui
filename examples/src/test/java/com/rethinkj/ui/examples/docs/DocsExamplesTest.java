package com.rethinkj.ui.examples.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.examples.Snaps;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs the examples of the documentation site (docs/), whose code the pages show: each on the
 * script {@code src/test/resources/docs/<name>.txt}, or a frame of its view where it has none,
 * comparing the trees of its snaps, and the images of those in {@link #IMAGES}, with {@code
 * src/test/resources/golden/docs/<name>}, which the pages show too. With {@code
 * -Dgolden.update=true} it stores them anew.
 */
class DocsExamplesTest {

  private static final Path SCRIPTS = Path.of("src/test/resources/docs");
  private static final Path STORED = Path.of("src/test/resources/golden/docs");

  /**
   * The snaps that the pages show, by example: these are stored as images too, in both themes, as
   * {@code <snap>-dark.png} and {@code <snap>-light.png}, since the site shows the one of its own.
   */
  static final Map<String, List<String>> IMAGES = Map.ofEntries(
      Map.entry("layout", List.of("view")),
      Map.entry("text", List.of("view")),
      Map.entry("input", List.of("pressed")),
      Map.entry("motion", List.of("opening")),
      Map.entry("overlay", List.of("open")),
      Map.entry("rows", List.of("far")),
      Map.entry("field", List.of("view")),
      Map.entry("editing", List.of("rule")),
      Map.entry("background", List.of("counting")),
      Map.entry("theme", List.of("view")),
      Map.entry("tab-groups", List.of("moved", "ideas-moved")),
      Map.entry("status", List.of("view")),
      Map.entry("bars", List.of("view")),
      Map.entry("cards-window", List.of("view")),
      Map.entry("choices", List.of("view")),
      Map.entry("hue-button", List.of("open")),
      Map.entry("dialog-stack", List.of("stacked")),
      Map.entry("details", List.of("view", "children", "hidden")),
      Map.entry("toolbar", List.of("view", "more")),
      Map.entry("tooltip", List.of("shown")),
      Map.entry("tooltip-content", List.of("shown")),
      Map.entry("tooltip-kind", List.of("shown")),
      Map.entry("dividers", List.of("view")),
      Map.entry("groups", List.of("view")),
      Map.entry("parts", List.of("view")),
      Map.entry("navigation", List.of("view")),
      Map.entry("tabs", List.of("view")),
      Map.entry("tabs-line", List.of("view")),
      Map.entry("grid", List.of("selected")),
      Map.entry("tags", List.of("added")),
      Map.entry("tags-form", List.of("view")),
      Map.entry("form-divided", List.of("view")),
      Map.entry("resize", List.of("wide", "narrow")),
      Map.entry("theming", List.of("system")),
      Map.entry("customers", List.of("loaded", "invalid", "saved")),
      Map.entry("code-editor-placeholder", List.of("view")),
      Map.entry("code-editor-json", List.of("view")),
      Map.entry("canvas", List.of("view")),
      Map.entry("canvas-arrange", List.of("view")));

  /** An example: its app in a theme and the size of its window for the default script. */
  record Example(Function<Theme, App<?>> app, int width, int height) {}

  /** Each example by the name of its script, or of its snap where it has no script. */
  static Map<String, Example> examples() {
    Map<String, Example> all = new LinkedHashMap<>();
    all.put("lights", new Example(t -> new App<>(new Lights(), Lights::view), 360, 240));
    controls(all, "buttons", Controls::buttons, 520, 120);
    controls(all, "button-options", Controls::buttonOptions, 420, 170);
    controls(all, "toggles", Controls::toggles, 240, 110);
    controls(all, "choices", Controls::choices, 320, 260);
    controls(all, "swatches", Controls::swatches, 460, 140);
    controls(all, "hue-button", Controls::huePickerButton, 460, 140);
    controls(all, "combos", Controls::combos, 360, 240);
    controls(all, "typed", Controls::typed, 360, 420);
    controls(all, "fields", Controls::fields, 360, 400);
    controls(all, "parts", Controls::parts, 400, 200);
    controls(all, "values", Controls::values, 360, 240);
    layouts(all, "cards", Layouts::cards, 420, 360);
    layouts(all, "groups", Layouts::groups, 460, 200);
    layouts(all, "sections", Layouts::sections, 520, 400);
    layouts(all, "form", Layouts::form, 480, 260);
    layouts(all, "form-groups", Layouts::formGroups, 480, 300);
    layouts(all, "form-cards", Layouts::formCards, 440, 260);
    layouts(all, "form-divided", Layouts::formDivided, 480, 204);
    layouts(all, "dividers", Layouts::dividers, 280, 140);
    layouts(all, "navigation", Layouts::navigation, 440, 220); // wide, for the tile's crop
    layouts(all, "navigation-typed", Layouts::typedNavigation, 260, 220);
    layouts(all, "split", Layouts::split, 520, 240);
    layouts(all, "cards-window", Layouts::cardsWindow, 520, 280);
    layouts(all, "tabs", Layouts::tabs, 480, 220);
    layouts(all, "tabs-line", Layouts::tabsLine, 480, 180);
    layouts(all, "status", Layouts::status, 460, 420);
    layouts(all, "bars", Layouts::bars, 720, 160);
    layouts(all, "details", Layouts::details, 520, 280);
    layouts(all, "toolbar", Layouts::toolbar, 600, 100);
    all.put("canvas", new Example(t -> new App<>(new Diagrams(), Diagrams::view), 560, 280));
    all.put(
        "canvas-arrange", new Example(t -> new App<>(new Arranging(), Arranging::view), 560, 300));
    overlays(all, "menu", Overlays::menus, 360, 260);
    overlays(all, "context-menu", Overlays::menus, 360, 260);
    overlays(all, "menu-bar", Overlays::menuBar, 360, 200);
    overlays(all, "popover", Overlays::popover, 420, 260);
    overlays(all, "tooltip", Overlays::tooltip, 360, 160);
    overlays(all, "tooltip-content", Overlays::tooltipContent, 360, 160);
    overlays(all, "tooltip-kind", Overlays::tooltipKind, 360, 120);
    overlays(all, "dialog", Overlays::dialog, 420, 260);
    overlays(all, "dialog-stack", Overlays::dialogStack, 440, 320);
    overlays(all, "toast", Overlays::toast, 420, 200);
    overlays(all, "command-palette", Overlays::palette, 640, 360);
    grids(all, "grid", Grids::basic, 520, 300);
    grids(all, "grid-open", Grids::opened, 360, 260);
    grids(all, "grid-sort", Grids::sorted, 520, 300);
    grids(all, "grid-fit", Grids::fitted, 520, 300);
    grids(all, "grid-edit", Grids::edited, 520, 300);
    grids(all, "grid-filter", Grids::filtered, 520, 300);
    grids(all, "grid-typed", Grids::typed, 520, 300);
    grids(all, "tree", Grids::tree, 320, 260);
    all.put(
        "grid-commit",
        new Example(
            t -> {
              Commits state = new Commits();
              return new App<>(state, Commits::view);
            },
            520,
            300));
    editors(all, "code-editor", Editors::codeEditor, 520, 260);
    editors(all, "code-editor-placeholder", Editors::placeholderAndReadOnly, 520, 180);
    editors(all, "code-editor-json", Editors::json, 520, 200);
    editors(all, "find", Editors::find, 440, 220);
    all.put(
        "highlighter",
        new Example(
            t -> {
              Highlighters state = new Highlighters();
              App<Highlighters> app = new App<>(state, Highlighters::view);
              state.app = app;
              return app;
            },
            440,
            200));
    editors(all, "markdown", Editors::markdown, 520, 320);
    editors(all, "image", Editors::image, 240, 180);
    concepts(all, "layout", Concepts::layout, 480, 200);
    all.put(
        "styles",
        new Example(
            t -> {
              Styles state = new Styles();
              return new App<>(state, Styles::view);
            },
            360,
            400));
    concepts(all, "text", Concepts::texts, 320, 200);
    concepts(all, "input", Concepts::input, 280, 140);
    concepts(all, "states", Concepts::states, 240, 80);
    concepts(all, "motion", Concepts::motion, 320, 160);
    concepts(all, "overlay", Concepts::overlay, 280, 160);
    concepts(all, "rows", Concepts::rows, 280, 200);
    all.put(
        "row-heights",
        new Example(
            t -> {
              Messages state = new Messages();
              return new App<>(state, Messages::view);
            },
            320,
            240));
    concepts(all, "field", Concepts::fields, 320, 200);
    all.put(
        "range",
        new Example(
            t -> {
              Paragraphs state = new Paragraphs();
              return new App<>(state, Paragraphs::view);
            },
            300,
            140));
    concepts(all, "background", Concepts::background, 420, 140);
    concepts(all, "progress", Concepts::progress, 280, 120);
    all.put(
        "lanes",
        new Example(
            t -> {
              Concepts state = new Concepts();
              App<Concepts> app = new App<>(state, Concepts::lanes);
              state.app = app;
              state.connection = app.lane("staging");
              state.db = Concepts.Connection.madeUp(app);
              return app;
            },
            440,
            150));
    concepts(all, "images", Concepts::images, 280, 120);
    concepts(all, "theme", Concepts::theme, 200, 80);
    all.put(
        "tab-groups",
        new Example(
            t -> {
              App<Notebook> app = Notebook.app();
              app.state().theme = t;
              return app;
            },
            420,
            260));
    editings(all, "editing", Editings::record, 380, 260);
    editings(all, "editing-pojo", Editings::pojo, 360, 160);
    all.put("tags", new Example(t -> tags(Tags::view), 320, 140));
    all.put("tags-form", new Example(t -> tags(Tags::form), 400, 160));
    all.put(
        "resize",
        new Example(
            t -> {
              Bars state = new Bars();
              return new App<>(state, Bars::view);
            },
            420,
            100));
    all.put(
        "theming",
        new Example(
            t -> {
              Themes state = new Themes();
              state.systemDark = t == Theme.DARK; // System mode: the system's choice
              return new App<>(state, Themes::view).provide(Theme.CURRENT, Themes::theme);
            },
            420,
            200));
    all.put(
        "customers",
        new Example(
            t -> {
              App<Customers> app = Customers.app();
              return app;
            },
            760,
            460));
    return all;
  }

  private static void controls(
      Map<String, Example> all, String name, Function<Controls, El> view, int width, int height) {
    all.put(
        name,
        new Example(
            t -> {
              Controls state = new Controls();
              return new App<>(state, view);
            },
            width,
            height));
  }

  private static void layouts(
      Map<String, Example> all, String name, Function<Layouts, El> view, int width, int height) {
    all.put(
        name,
        new Example(
            t -> {
              Layouts state = new Layouts();
              return new App<>(state, view);
            },
            width,
            height));
  }

  private static void overlays(
      Map<String, Example> all, String name, Function<Overlays, El> view, int width, int height) {
    all.put(
        name,
        new Example(
            t -> {
              Overlays state = new Overlays();
              return new App<>(state, view);
            },
            width,
            height));
  }

  private static void grids(
      Map<String, Example> all, String name, Function<Grids, El> view, int width, int height) {
    all.put(
        name,
        new Example(
            t -> {
              Grids state = new Grids();
              App<Grids> app = new App<>(state, view);
              state.app = app;
              return app;
            },
            width,
            height));
  }

  private static void editors(
      Map<String, Example> all, String name, Function<Editors, El> view, int width, int height) {
    all.put(
        name,
        new Example(
            t -> {
              Editors state = new Editors();
              App<Editors> app = new App<>(state, view);
              state.app = app;
              return app;
            },
            width,
            height));
  }

  private static void concepts(
      Map<String, Example> all, String name, Function<Concepts, El> view, int width, int height) {
    all.put(
        name,
        new Example(
            t -> {
              Concepts state = new Concepts();
              App<Concepts> app = new App<>(state, view);
              state.app = app;
              return app;
            },
            width,
            height));
  }

  private static App<Tags> tags(Function<Tags, El> view) {
    Tags state = new Tags();
    return new App<>(state, view);
  }

  private static void editings(
      Map<String, Example> all, String name, Function<Editings, El> view, int width, int height) {
    all.put(
        name,
        new Example(
            t -> {
              Editings state = new Editings();
              return new App<>(state, view);
            },
            width,
            height));
  }

  @TestFactory
  Stream<DynamicTest> eachExampleRunsAndShowsTheStoredTrees() throws IOException {
    try (var files = Files.list(SCRIPTS)) {
      for (Path script : files.toList()) {
        assertTrue(
            examples().containsKey(script.getFileName().toString().replace(".txt", "")),
            script + " has no example");
      }
    }
    assertTrue(examples().keySet().containsAll(IMAGES.keySet()), "an image of no example");
    return examples().entrySet().stream()
        .map(e -> DynamicTest.dynamicTest(e.getKey(), () -> {
          assumeText();
          Example example = e.getValue();
          Path out = Path.of("target/docs", e.getKey());
          Path file = SCRIPTS.resolve(e.getKey() + ".txt");
          String script = Files.exists(file)
              ? Files.readString(file)
              : "size " + example.width() + " " + example.height() + "\nframe\nsnap view\n";
          App<?> app = example.app().apply(Theme.DARK).provide(Theme.CURRENT, state -> Theme.DARK);
          app.textRendering(TextRendering.LINUX); // the same trees on every platform
          Script.run(script, app, out.resolve("trees"));
          compare(out.resolve("trees"), STORED.resolve(e.getKey()));

          // The images at scale 2, as the site shows them, in each theme.
          String scaled = script.replaceAll("(?m)^(size \\d+ \\d+)(?=\\s*(#|$))", "$1 scale 2");
          for (String theme :
              IMAGES.containsKey(e.getKey()) ? List.of("dark", "light") : List.<String>of()) {
            Theme look = theme.equals("dark") ? Theme.DARK : Theme.LIGHT;
            App<?> themed = example.app().apply(look).provide(Theme.CURRENT, state -> look);
            themed.textRendering(TextRendering.LINUX); // the same pixels on every platform
            Snaps.clear(out.resolve(theme));
            Script.run(scaled, themed, out.resolve(theme));
            for (String snap : IMAGES.get(e.getKey())) {
              Path written = out.resolve(theme).resolve(snap + ".png");
              assertTrue(Files.exists(written), e.getKey() + " has no snap " + snap);
              Snaps.image(written, STORED.resolve(e.getKey()).resolve(snap + "-" + theme + ".png"));
            }
          }
        }));
  }

  private static void compare(Path out, Path stored) throws IOException {
    try (var files = Files.list(out)) {
      for (Path written :
          files.filter(p -> p.toString().endsWith(".tree.txt")).sorted().toList()) {
        Path kept = stored.resolve(written.getFileName());
        if (Boolean.getBoolean("golden.update")) {
          Files.createDirectories(stored);
          Files.copy(written, kept, StandardCopyOption.REPLACE_EXISTING);
          continue;
        }
        assertTrue(Files.exists(kept), "No stored " + kept + "; run with -Dgolden.update=true");
        assertEquals(
            Files.readString(kept),
            Files.readString(written),
            written.toAbsolutePath() + " differs from " + kept);
      }
    }
  }

  private static void assumeText() {
    try {
      Font.sans();
    } catch (UnsatisfiedLinkError error) {
      assumeTrue(false, "Skipped: FreeType and HarfBuzz cannot be loaded. " + error.getMessage());
    }
  }
}
