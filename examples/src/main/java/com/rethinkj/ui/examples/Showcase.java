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
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.DataGrid.Mode;
import com.rethinkj.ui.kit.DataGrid.Selection;
import com.rethinkj.ui.kit.DataGrid.SortKey;
import com.rethinkj.ui.kit.CodeEditor;
import com.rethinkj.ui.kit.Completion;
import com.rethinkj.ui.kit.Breadcrumb;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.Popover;
import com.rethinkj.ui.kit.Toolbar;
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
 * time, in the kit's two themes: the sections at the left, the chosen one at the right, and below
 * the sections a select that switches the theme, or follows the system's light or dark setting
 * ("System", where the window starts; the title bar's mark then shows the system's accent colour).
 */
public final class Showcase {

  /** The themes to show. */
  enum Look {
    DARK("Dark", Theme.DARK),
    LIGHT("Light", Theme.LIGHT),
    /** The kit's dark or light theme, as the system is set, following it while the app runs. */
    SYSTEM("System", null);

    final String title;
    final Theme theme; // null for SYSTEM

    Look(String title, Theme theme) {
      this.title = title;
      this.theme = theme;
    }

    /** The name of the folder of a script's snaps in this look, as {@code dark}. */
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
    DETAILS("Table details", "A table's path, tools, properties and columns, with a filter.",
        Symbol.DOCUMENT_TABLE),
    PROGRESS("Progress", "Bars, a running bar, a line on a card's edge and spinners.",
        Symbol.ARROW_DOWNLOAD),
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
  String sql = "SELECT name, total\nFROM orders\nWHERE total > 100\nORDER BY total DESC;";
  final CodeEditor.State sqlEditor = new CodeEditor.State();
  final Completion.State sqlCompletion = new Completion.State();
  App<Showcase> app; // puts a completion in place

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

  // Table details
  List<String> path = List.of("localhost", "shop", "public", "orders");
  final Breadcrumb.State crumbs = new Breadcrumb.State();
  final Toolbar.State tools = new Toolbar.State();
  final Menu.State runMenu = new Menu.State();
  final Popover.State filter = new Popover.State();
  boolean wrapValues;
  boolean showSystem;
  String filterColumn = "status";
  String filterValue = "paid";
  List<String> filters = new java.util.ArrayList<>(List.of("total > 100"));

  // Progress
  static final double SIZE = 80; // megabytes of the made-up download
  double downloaded; // megabytes so far
  boolean downloading;
  boolean failed;
  private int download; // counts the downloads, so that a step of an old one does nothing

  /** Starts the made-up download: 4 MB each quarter of a second of the app's clock. */
  void startDownload() {
    downloaded = 0;
    failed = false;
    downloading = true;
    int run = ++download;
    for (int step = 1; step <= SIZE / 4; step++) { // all at once, so a long advance runs them all
      app.after(0.25 * step, () -> {
        if (run == download) {
          downloaded = Math.min(SIZE, downloaded + 4);
          downloading = downloaded < SIZE;
        }
      });
    }
  }

  /** Lets the download fail where it is. */
  void failDownload() {
    download++;
    downloading = false;
    failed = true;
  }

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
    app.state().app = app;
    // A script's images are stored, so they must not depend on the platform.
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  public static void main(String[] args) throws IOException {
    App<Showcase> app = app(Look.DARK);
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
    El sidebar = Card.flush(t,
        Card.inset(t, nav),
        col().padding(0, t.space().m(), t.space().m(), t.space().m()).gap(t.space().s())
            .child(label(t, MUTED, "Theme"))
            .child(Select.select(t, looks, s.look.ordinal(), s.lookMenu,
                i -> s.look = Look.values()[i]).key("look").label("Theme").wFull()))
        .w(200).shrink(0);
    El root = col().wFull().hFull().fill(t.surface().window())
        .child(titleBar(t, "Showcase · " + s.lookTitle(),
            s.look == Look.SYSTEM && s.systemAccent != 0 ? s.systemAccent : t.accent().color()))
        .child(row().grow(1).minSize(0, 0).padding(0, t.space().m(), t.space().m(), t.space().m())
            .gap(t.space().m())
            .child(sidebar)
            .child(Sections.section(s, t)));
    return root.overlay(Sections.dialog(s, t));
  }
}
