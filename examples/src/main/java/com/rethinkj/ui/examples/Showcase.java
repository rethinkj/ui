package com.rethinkj.ui.examples;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.examples.TitleBar.titleBar;
import static com.rethinkj.ui.kit.Label.Variant.MUTED;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Scrollbars;
import com.rethinkj.ui.Shadow;
import com.rethinkj.ui.kit.DataGrid.Mode;
import com.rethinkj.ui.kit.DataGrid.Selection;
import com.rethinkj.ui.kit.DataGrid.SortKey;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.Select;
import com.rethinkj.ui.kit.Split.Position;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Tabs.Tab;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Tree;
import com.rethinkj.ui.render.GpuPresenter;
import com.rethinkj.ui.text.TextRendering;
import com.rethinkj.ui.window.Window;
import com.rethinkj.ui.window.WindowLoop;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Every component of the kit in the arrangements a database tool has them in, one section at a
 * time, in the kit's two themes and in a proposed revision of them: the sections at the left, the
 * chosen one at the right, and below the sections a select that switches the theme, or follows
 * the system's light or dark setting ("System", where the window starts; the title bar's mark then
 * shows the system's accent colour). The proposal, {@link #PROPOSAL_DARK} and {@link
 * #PROPOSAL_LIGHT}, is made of the kit's themes with their {@code with} methods only, so it
 * changes no component.
 */
public final class Showcase {

  /** The themes to show. */
  enum Look {
    CURRENT_DARK("Current dark", Theme.DARK),
    CURRENT_LIGHT("Current light", Theme.LIGHT),
    PROPOSAL_DARK("Proposal dark", Showcase.PROPOSAL_DARK),
    PROPOSAL_LIGHT("Proposal light", Showcase.PROPOSAL_LIGHT),
    /** The kit's dark or light theme, as the system is set, following it while the app runs. */
    SYSTEM("System", null);

    final String title;
    final Theme theme; // null for SYSTEM

    Look(String title, Theme theme) {
      this.title = title;
      this.theme = theme;
    }

    /** The name of the folder of a script's snaps in this look, as {@code proposal-dark}. */
    String slug() {
      return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
  }

  /** The sections, in the order of the list at the left. */
  enum Section {
    BUTTONS("Buttons", "Buttons, toggles and checkboxes, at rest and in their states.",
        Symbol.CIRCLE),
    FIELDS("Text fields", "Fields in their states, and a form with selects.", Symbol.TEXT_T),
    MENUS("Menus and overlays", "A dropdown, a context menu, tooltips and a dialog.",
        Symbol.NAVIGATION),
    GRID("Data grid", "A result set: numbers, dates, NULLs, long text, two sort keys.",
        Symbol.TABLE),
    TREE("Tree", "The schema of three servers, with the columns of a table beside it.",
        Symbol.DATABASE),
    WORKBENCH("Tabs and split", "The frame of a database tool, in small.", Symbol.WINDOW),
    TYPE("Typography", "The theme's text styles, figures and letterforms.", Symbol.TEXT_T),
    COLOUR("Colour", "Every colour of the theme, by group.", Symbol.SQUARE_MULTIPLE),
    CONTRAST("Contrast", "WCAG contrast of each colour on each background.", Symbol.EYE),
    ICONS("Icons", "The named symbols of the icon font at the theme's size.", Symbol.STAR);

    final String title;
    final String description;
    final Symbol symbol;

    Section(String title, String description, Symbol symbol) {
      this.title = title;
      this.description = description;
      this.symbol = symbol;
    }
  }

  Look look;
  /** Whether the system is set to dark, as the window loop says; for {@link Look#SYSTEM}. */
  boolean systemDark;
  /** The system's accent colour, 0 where it has none: the title bar's mark in the system look. */
  int systemAccent;
  Section section = Section.BUTTONS;
  final Menu.State lookMenu = new Menu.State();
  String said = ""; // what the last action did, in the status line

  // Buttons
  boolean autocommit = true;
  boolean readOnly;
  boolean nullText = true;
  boolean ssl = true;
  boolean savePassword;

  // Text fields
  String search = "";
  String table = "orders_2024";
  String alias = "monthly_revenue";
  String name = "Shop (local)";
  String host = "localhost";
  String port = "5432";
  String database = "shop";
  String user = "";
  int driver = 0;
  int isolation = 1;
  final Menu.State driverMenu = new Menu.State();
  final Menu.State isolationMenu = new Menu.State();

  // Menus and overlays
  final Menu.State options = new Menu.State();
  final Menu.State context = new Menu.State();
  boolean lineNumbers = true;
  boolean wordWrap;
  final String[] queries = Sections.SAVED.clone();
  int saved = 1; // the selected one of the queries
  boolean renaming;
  String rename = "";

  // Data grid
  List<SortKey> sort = List.of(new SortKey(1, false), new SortKey(3, true));
  List<Samples.Order> rows = Samples.sorted(sort);
  final Selection selection = new Selection(Mode.CELLS);
  final Menu.State gridMenu = new Menu.State();

  // Tree
  final List<Samples.Node> catalog = Samples.catalog();
  final Set<Samples.Node> open = new HashSet<>();
  Samples.Node node;
  final Selection columns = new Selection(Mode.ROWS);

  // Tabs and split
  final Set<Samples.Node> benchOpen = new HashSet<>();
  Samples.Node benchNode;
  List<Tab> tabs = List.of(new Tab("Console", false, false), new Tab("customers.sql"),
      new Tab("orders by month.sql", true, true), new Tab("products.sql"));
  int tab = 2;
  Position sidebar = Position.first(200);
  Position editor = Position.share(0.42f);
  final Selection results = new Selection(Mode.ROWS);

  Showcase(Look look) {
    this.look = look;
    List<Samples.Node> orders = Samples.path(catalog, "localhost", "shop", "public", "orders");
    open.addAll(orders);
    open.add(catalog.get(1)); // staging, whose databases are not known yet
    node = orders.getLast();
    columns.select(2, 0);
    List<Samples.Node> shop = Samples.path(catalog, "localhost", "shop", "public");
    benchOpen.addAll(shop);
    benchNode = shop.getLast().find("orders");
    results.select(1, 0);
  }

  Theme theme() {
    return look == Look.SYSTEM ? (systemDark ? Theme.DARK : Theme.LIGHT) : look.theme;
  }

  /** The look's name, and for the system's which theme it is now. */
  String lookTitle() {
    return look == Look.SYSTEM ? "System (" + (systemDark ? "dark" : "light") + ")" : look.title;
  }

  void sort(List<SortKey> keys) {
    sort = keys;
    rows = Samples.sorted(keys);
  }

  void toggle(Set<Samples.Node> nodes, Samples.Node node, boolean open) {
    if (open) {
      nodes.add(node);
    } else {
      nodes.remove(node);
    }
  }

  void startRename() {
    rename = queries[saved];
    renaming = true;
  }

  void say(String what) {
    said = what;
  }

  /** The app in a look, as {@link #main} runs it and the tests drive it. */
  static App<Showcase> app(Look look) {
    App<Showcase> app = new App<>(new Showcase(look), Showcase::view);
    // A script's images are stored, so they must not depend on the platform.
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  public static void main(String[] args) throws IOException {
    App<Showcase> app = app(Look.CURRENT_DARK);
    if (Script.handle(args, app)) {
      return;
    }
    app.textRendering(TextRendering.platform());
    Showcase s = app.state();
    s.look = Look.SYSTEM; // the window follows the system until another look is chosen
    Window.Options options = Identity.options(Showcase.class, "Showcase", 0xFF7C3AED, Symbol.SHAPES)
        .frameless().minimumSize(900, 600);
    try (Window window = Window.open("Showcase", 1100, 720, options);
        GpuPresenter presenter = new GpuPresenter(window)) {
      new WindowLoop(window, app, presenter)
          .appearance(a -> {
            s.systemDark = a.dark();
            s.systemAccent = a.accent();
          })
          .run();
    }
  }

  static El view(Showcase s) {
    Theme t = s.theme();
    El nav = Tree.tree(t, new Sections.Nav(t), section -> false, s.section,
            section -> s.section = section, null, null)
        .key("sections").label("Sections").grow(1);
    List<String> looks = Arrays.stream(Look.values()).map(l -> l.title).toList();
    El sidebar = col().w(200).shrink(0).fill(t.surface().panel())
        .padding(t.space().m()).gap(t.space().s())
        .child(nav)
        .child(label(t, MUTED, "Theme"))
        .child(Select.select(t, looks, s.look.ordinal(), s.lookMenu,
            i -> s.look = Look.values()[i]).key("look").label("Theme").wFull());
    El root = col().wFull().hFull().fill(t.surface().window())
        .child(titleBar(t, "Showcase · " + s.lookTitle(),
            s.look == Look.SYSTEM && s.systemAccent != 0 ? s.systemAccent : t.accent().color()))
        .child(row().grow(1).minSize(0, 0)
            .child(sidebar)
            .child(el().w(t.size().stroke()).shrink(0).fill(t.border().subtle()))
            .child(Sections.section(s, t)));
    return root.overlay(Sections.dialog(s, t));
  }

  // The proposal

  /*
   * The rules the proposal follows. Each value below says which one it comes from.
   *
   * Surfaces. Three levels a fixed step of CIELAB lightness apart: window (content: editors, the
   * grid's rows), panel (the chrome around content: sidebars, bars, the grid's header) and raised
   * (controls and popups). Dark: window L* 10, panel 13.5, raised 16.6, each a neutral grey with
   * blue three levels above red. Light: window and raised white, panel L* 96; a raised control on
   * white is told apart by its edge, a popup by its edge and shadow. Stripes lie halfway between
   * window and panel. Raised stays dark enough in the dark theme that the accent, as the edge of a
   * focused field or the fill of a checked box, keeps 3:1 on it.
   *
   * Hover and pressed. A neutral grey about 20 L* beyond the surfaces (lighter in the dark theme,
   * darker in the light one) at 30 % and at 50 %, not white or black at a few per cent. The kit
   * lays these over a surface in two ways: Theme.over mixes sRGB values, the renderer mixes
   * translucent fills in linear light. A colour near the surfaces makes the two agree within a few
   * L*; white at 6 % gave 5.5 L* one way and 19 the other.
   *
   * Accent. One blue. Dark: as light as white text on it allows (4.5:1), so that it has 3:1 as an
   * edge on raised; hover darker, pressed darker still, both keeping white text. Light: as it was.
   * Text selection: the accent at 40 % (dark) and 35 % (light), drawn over raised.
   *
   * Text. Primary and muted at least 4.5:1 on every surface, hover, pressed and selection they
   * meet; disabled from 2.5:1 to 3.5:1 on window, panel and raised, legible but clearly below
   * muted. Status colours 4.5:1 on every surface and on the grid's selection.
   *
   * Edges and marks. The strong border, the accent as an edge, the grid's cursor and the
   * scrollbar's thumb at rest at least 3:1 on the surfaces they lie on, the thumb no more than it
   * needs. The focus ring 3:1 on every surface and on the accent it surrounds: a pale accent in the
   * dark theme, near black in the light one, where no blue dark enough remains blue.
   *
   * Selection in lists. Focused: the accent mixed into the row, primary text at 9:1 or more.
   * Without the focus: the same lightness with little colour, apart from the hover by 4 L* or more.
   *
   * Shadows. Mixed in linear light too, so a light theme needs about twice the alpha a CSS shadow
   * has to show; a dark one shows mostly the tight shadow, which separates the edge.
   *
   * Sizes, spacing, radii and type stay: controls 24, rows 22 and text 13 are already the compact
   * end of what desktop tools use, and the scales are regular (spacing 2, 4, 8, 12, 16, 24; radii 3
   * for marks and items inside a container, 4 for controls, 8 for free-standing containers).
   */

  /** The dark half of the proposal: the kit's dark theme with the rules above. */
  static final Theme PROPOSAL_DARK = Theme.DARK
      .withSurface(new Theme.Surfaces(
          0xFF1A1B1E, 0xFF222326, 0xFF28292C, // window, panel, raised: L* 10, 13.5, 16.6
          0x4D4B4C51, 0x804B4C51, // hover 30 %, pressed 50 % of a grey at L* 32
          0x99000000))
      .withText(new Theme.TextColors(
          0xFFE4E5E9, 0xFFA1A3AA, 0xFF6E7077, 0xFFFFFFFF)) // disabled 2.9:1 on raised
      .withBorder(new Theme.Borders(
          0xFF36373C, 0xFF7A7D86, 0xFFC3D3F8)) // focus: a pale accent, 3.1:1 on it
      .withAccent(new Theme.Accent(
          0xFF3B6DE3, 0xFF3366DB, 0xFF2A5BD0, 0x663B6DE3)) // 3.1:1 on raised, white 4.7:1
      .withGrid(new Theme.Grid(22, 32, 480, 6, 12,
          0xFF222326, 0xFF1A1B1E, 0xFF1E1F22, // header = panel, row = window, stripe halfway
          0xFF1D3768, 0xFF2D3446, 0xFF7AA2F7, 2))
      .withElevation(new Theme.Elevation(
          List.of(new Shadow(0, 1, 2, 0, 0x99000000), new Shadow(0, 4, 12, 0, 0x80000000)),
          List.of(new Shadow(0, 2, 4, 0, 0x99000000), new Shadow(0, 12, 32, 0, 0x99000000))));

  /** The light half of the proposal: the kit's light theme with the rules above. */
  static final Theme PROPOSAL_LIGHT = Theme.LIGHT
      .withSurface(new Theme.Surfaces(
          0xFFFFFFFF, 0xFFF3F4F6, 0xFFFFFFFF, // window and raised white, panel L* 96
          0x4DBDBFC5, 0x80BDBFC5, // hover 30 %, pressed 50 % of a grey at L* 77
          0x59000000))
      .withText(new Theme.TextColors(
          0xFF1D1E21, 0xFF5C5F66, 0xFF97999F, 0xFFFFFFFF)) // disabled 2.6:1 on panel
      .withBorder(new Theme.Borders(0xFFD8DADF, 0xFF82858E, 0xFF1D1E21))
      .withAccent(new Theme.Accent(0xFF2E62D9, 0xFF2755C0, 0xFF2048A8, 0x592E62D9))
      .withStatus(new Theme.Status(
          0xFFB42318, 0xFF7A5300, 0xFF17693A)) // 4.9:1 on the grid's selection
      .withGrid(new Theme.Grid(22, 32, 480, 6, 12,
          0xFFF3F4F6, 0xFFFFFFFF, 0xFFF9FAFB, // header = panel, row = window, stripe halfway
          0xFFCFE0FC, 0xFFD9DFE8, 0xFF1D4ED8, 2))
      .withScrollbars(new Scrollbars(6, 2, 3, 24, 0xFF787B84, 0xFF5C5F66, 0xFF4A4D54))
      .withElevation(new Theme.Elevation(
          List.of(new Shadow(0, 1, 3, 0, 0x40000000), new Shadow(0, 6, 16, 0, 0x38000000)),
          List.of(new Shadow(0, 2, 6, 0, 0x40000000), new Shadow(0, 16, 40, 0, 0x4D000000))));
}
