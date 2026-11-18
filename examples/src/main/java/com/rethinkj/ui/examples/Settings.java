package com.rethinkj.ui.examples;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.GHOST;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.Callout.callout;
import static com.rethinkj.ui.kit.Card.card;
import static com.rethinkj.ui.kit.ComboBox.comboBox;
import static com.rethinkj.ui.kit.EmptyState.emptyState;
import static com.rethinkj.ui.kit.Form.field;
import static com.rethinkj.ui.kit.Form.form;
import static com.rethinkj.ui.kit.Group.expander;
import static com.rethinkj.ui.kit.Group.group;
import static com.rethinkj.ui.kit.Group.linkRow;
import static com.rethinkj.ui.kit.Group.switchRow;
import static com.rethinkj.ui.kit.Label.label;
import static com.rethinkj.ui.kit.NavList.navList;
import static com.rethinkj.ui.kit.NumberField.numberField;
import static com.rethinkj.ui.kit.Radio.radioGroup;
import static com.rethinkj.ui.kit.Section.page;
import static com.rethinkj.ui.kit.Section.section;
import static com.rethinkj.ui.kit.Segmented.segmented;
import static com.rethinkj.ui.kit.Select.select;
import static com.rethinkj.ui.kit.Slider.slider;
import static com.rethinkj.ui.kit.TextField.textField;
import static com.rethinkj.ui.kit.TitleBar.titleBar;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.AppIcon;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.ComboBox;
import com.rethinkj.ui.kit.Dialog;
import com.rethinkj.ui.kit.Form;
import com.rethinkj.ui.kit.Group;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.NavList;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.ValueField;
import com.rethinkj.ui.render.GpuPresenter;
import com.rethinkj.ui.text.TextRendering;
import com.rethinkj.ui.window.SystemAppearance;
import com.rethinkj.ui.window.Window;
import com.rethinkj.ui.window.WindowLoop;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The settings of a database tool in a frameless window: its pages in a navigation list at the
 * left, the page chosen at the right, which scrolls. Built from the kit's pages, sections, groups
 * of rows, cards and forms, with its controls: the theme switches between light and dark, or
 * follows the system's setting in a window, and the accent changes the whole window; the other
 * settings are only kept. Connections are cards that select, filter and open a menu, and a dialog
 * with a form adds one. The state is this class.
 */
public final class Settings {

  /** The pages, in the order of the navigation list; About under the heading Help. */
  enum Page {
    GENERAL(Symbol.SETTINGS, "General"),
    APPEARANCE(Symbol.WINDOW, "Appearance"),
    EDITOR(Symbol.CODE, "Editor"),
    CONNECTIONS(Symbol.DATABASE, "Connections"),
    KEYMAP(Symbol.KEY_MULTIPLE, "Keymap"),
    ABOUT(Symbol.INFO, "About");

    final Symbol icon;
    final String title;

    Page(Symbol icon, String title) {
      this.icon = icon;
      this.title = title;
    }

    NavList.Item item() {
      return NavList.item(icon, title);
    }

    String group() {
      return this == ABOUT ? "Help" : null;
    }
  }

  enum Look {
    LIGHT("Light"),
    DARK("Dark"),
    SYSTEM("System");

    final String title;

    Look(String title) {
      this.title = title;
    }
  }

  enum Accent {
    BLUE("Blue", Theme.Hue.BLUE),
    PURPLE("Purple", Theme.Hue.PURPLE),
    GREEN("Green", Theme.Hue.GREEN),
    ORANGE("Orange", Theme.Hue.ORANGE);

    final String title;
    final Theme.Hue hue;

    Accent(String title, Theme.Hue hue) {
      this.title = title;
      this.hue = hue;
    }
  }

  enum EditorFont {
    JETBRAINS_MONO("JetBrains Mono"),
    INTER("Inter");

    final String title;

    EditorFont(String title) {
      this.title = title;
    }
  }

  enum Density {
    COMPACT("Compact"),
    COMFORTABLE("Comfortable");

    final String title;

    Density(String title) {
      this.title = title;
    }
  }

  enum LineEnding {
    LF("LF"),
    CRLF("CRLF"),
    AS_IN_FILE("As in the file");

    final String title;

    LineEnding(String title) {
      this.title = title;
    }
  }

  // Enough of them that typing finds one sooner than reading: a combo box.
  static final List<String> LANGUAGES = List.of(
      "English",
      "Català",
      "Čeština",
      "Dansk",
      "Deutsch",
      "Eesti",
      "Español",
      "Français",
      "Hrvatski",
      "Italiano",
      "Latviešu",
      "Lietuvių",
      "Magyar",
      "Nederlands",
      "Norsk",
      "Polski",
      "Português",
      "Română",
      "Slovenčina",
      "Slovenščina",
      "Suomi",
      "Svenska",
      "Türkçe");
  static final int FONT_SIZE = 13;

  /** A server the workbench knows, with the menu of its card. */
  static final class Connection {
    final String name;
    final String host;
    final int port;
    final boolean connected;
    final Menu.State menu = new Menu.State();

    Connection(String name, String host, int port, boolean connected) {
      this.name = name;
      this.host = host;
      this.port = port;
      this.connected = connected;
    }
  }

  Page page = Page.GENERAL;
  // General
  boolean reopen = true;
  boolean updates = true;
  int language;
  final ComboBox.State languages = new ComboBox.State();
  int history = 200;
  // Appearance
  Look theme = Look.DARK;
  Accent accent = Accent.BLUE;
  final Menu.State accents = new Menu.State();
  int fontSize = FONT_SIZE;
  Density density = Density.COMPACT;
  // Editor
  boolean wrap;
  boolean lineNumbers = true;
  boolean highlight = true;
  int tabSize = 4;
  EditorFont font = EditorFont.JETBRAINS_MONO;
  final Menu.State fonts = new Menu.State();
  boolean advanced;
  boolean spaces = true;
  int undo = 100;
  LineEnding endings = LineEnding.LF;
  // Connections
  final List<Connection> connections = new ArrayList<>(List.of(
      new Connection("localhost", "localhost", 5432, true),
      new Connection("staging", "staging.db.example.com", 5432, true),
      new Connection("prod-eu", "eu.db.example.com", 6432, false)));
  String filter = "";
  String selected = "localhost";
  boolean adding;
  String name = "";
  String host = "";
  int port = 5432;
  final ValueField.Draft portDraft = new ValueField.Draft();

  /**
   * The Java and the platform the About page names: fixed under a script, whose images are stored
   * and compared on every platform; {@link #main} shows the real ones in a window.
   */
  String platform = "Java 25 · Linux x64";

  /** The system's appearance in a window, from the window loop; null under a script. */
  SystemAppearance system;

  // docs:start settings-theme
  Theme theme() {
    // System is dark unless the system says light; under a script the example cannot ask it.
    boolean light = theme == Look.LIGHT
        || theme == Look.SYSTEM
            && system != null
            && system.scheme() == SystemAppearance.Scheme.LIGHT;
    Theme base = light ? Theme.LIGHT : Theme.DARK;
    return accent == Accent.BLUE ? base : base.withAccent(accent.hue);
  }
  // docs:end settings-theme

  List<Connection> matches() {
    String query = filter.strip().toLowerCase(Locale.ROOT);
    return connections.stream()
        .filter(connection -> connection.name.contains(query) || connection.host.contains(query))
        .toList();
  }

  void add() {
    connections.add(new Connection(name, host, port, false));
    selected = name;
    adding = false;
  }

  void show(Page value) {
    page = value;
  }

  void showKeymap() {
    page = Page.KEYMAP;
  }

  void toggleReopen() {
    reopen = !reopen;
  }

  void toggleUpdates() {
    updates = !updates;
  }

  void language(int value) {
    language = value;
  }

  void history(int value) {
    history = value;
  }

  public static void main(String[] args) throws IOException {
    App<Settings> app =
        new App<>(new Settings(), Settings::view).provide(Theme.CURRENT, Settings::theme);
    // A script's images are stored, so they must not depend on the platform.
    app.textRendering(TextRendering.LINUX);
    if (Script.handle(args, app)) {
      return;
    }
    app.textRendering(TextRendering.platform());
    app.state().platform = platform();
    Window.Options options = Identity.options(
            Settings.class, "Settings", Identity.SLATE, Symbol.SETTINGS)
        .frameless()
        .minimumSize(560, 400);
    try (Window window = Window.open("Settings", 880, 600, options);
        GpuPresenter presenter = new GpuPresenter(window)) {
      new WindowLoop(window, app, presenter)
          .appearance(appearance -> app.state().system = appearance)
          .run();
    }
  }

  /** The Java and the platform this runs on, as the About page names them in a window. */
  static String platform() {
    String arch = System.getProperty("os.arch", "");
    arch = switch (arch) {
      case "amd64", "x86_64" -> "x64";
      case "aarch64" -> "arm64";
      default -> arch;
    };
    return "Java " + Runtime.version().feature() + " · " + System.getProperty("os.name", "") + " "
        + arch;
  }

  // docs:start settings-pages
  static El view(Settings state) {
    Theme theme = Theme.CURRENT.get();
    El pages = navList(List.of(Page.values()), state.page, Page::item, Page::group, state::show)
        .key("pages")
        .label("Settings")
        .grow(1);
    El root = col()
        .wFull()
        .hFull()
        .fill(theme.surface().window())
        .child(titleBar(AppIcon.appIcon(16, Identity.SLATE, Symbol.SETTINGS), "Settings"))
        .child(row()
            .grow(1)
            .minSize(0, 0)
            .padding(0, theme.space().m(), theme.space().m(), theme.space().m())
            .gap(theme.space().m())
            .children(
                Card.flush(Card.inset(pages)).w(200).shrink(0),
                switch (state.page) {
                  case GENERAL -> general(state);
                  case APPEARANCE -> appearance(state);
                  case EDITOR -> editor(state);
                  case CONNECTIONS -> connections(state);
                  case KEYMAP -> keymap();
                  case ABOUT -> about(state.platform);
                }));
    return root.overlay(state.adding ? newConnection(state) : null);
  }
  // docs:end settings-pages

  // docs:start settings-general
  static El general(Settings state) {
    return page(
            "General",
            section(
                "Startup",
                null,
                group(
                    switchRow(
                        "Reopen the last workspace",
                        "Open the tabs and connections of the last session again",
                        state.reopen,
                        state::toggleReopen),
                    switchRow("Check for updates", null, state.updates, state::toggleUpdates),
                    Group.row(
                        "Language",
                        null,
                        comboBox(LANGUAGES, state.language, state.languages, state::language)
                            .w(160)))),
            section(
                "History",
                "What the workbench remembers of your work",
                group(
                    Group.row(
                        "Statements kept",
                        "Per connection, the oldest go first",
                        numberField(state.history, 0, 10_000, 50, state::history)),
                    linkRow(
                        Symbol.KEY_MULTIPLE,
                        "Keymap",
                        "The keys of every command",
                        "Default",
                        state::showKeymap))))
        .grow(1);
  }
  // docs:end settings-general

  static El appearance(Settings state) {
    Theme theme = Theme.CURRENT.get();
    return page(
            "Appearance",
            section(
                "Colors",
                null,
                group(
                    Group.row(
                        "Theme",
                        state.system == null
                            ? "System follows the dark theme in this example"
                            : "System follows the system's light or dark setting",
                        segmented(
                            List.of(Look.values()),
                            state.theme,
                            look -> look.title,
                            look -> state.theme = look)),
                    Group.row(
                        "Accent color",
                        "Buttons, switches and selections",
                        select(
                                List.of(Accent.values()),
                                state.accent,
                                accent -> accent.title,
                                state.accents,
                                accent -> state.accent = accent)
                            .w(160)))),
            section(
                "Text and spacing",
                null,
                group(
                    Group.row(
                        "Interface font size",
                        state.fontSize + " pixels",
                        slider(state.fontSize, 11, 16, 1, size -> state.fontSize = size)
                            .w(160)),
                    Group.row(
                        "Density",
                        "Space between the rows of lists, trees and grids",
                        segmented(
                            List.of(Density.values()),
                            state.density,
                            density -> density.title,
                            density -> state.density = density))),
                state.fontSize == FONT_SIZE
                    ? null
                    : callout(
                        Callout.Kind.WARNING,
                        "Restart to apply",
                        "The interface font size changes the next time the workbench starts.",
                        button(SECONDARY, "Undo", () -> state.fontSize = FONT_SIZE))))
        .grow(1);
  }

  static El editor(Settings state) {
    return page(
            "Editor",
            section(
                "Text",
                null,
                group(
                    switchRow(
                        "Word wrap",
                        "Wrap long lines at the edge of the editor instead of scrolling sideways",
                        state.wrap,
                        () -> state.wrap = !state.wrap),
                    switchRow(
                        "Line numbers",
                        null,
                        state.lineNumbers,
                        () -> state.lineNumbers = !state.lineNumbers),
                    switchRow(
                        "Highlight the current line",
                        null,
                        state.highlight,
                        () -> state.highlight = !state.highlight),
                    Group.row(
                        "Font",
                        null,
                        select(
                                List.of(EditorFont.values()),
                                state.font,
                                font -> font.title,
                                state.fonts,
                                font -> state.font = font)
                            .w(160)))),
            section(
                "Indentation",
                null,
                group(
                    Group.row(
                        "Tab size",
                        "Columns from one tab stop to the next",
                        numberField(state.tabSize, 1, 16, 1, size -> state.tabSize = size)),
                    expander(
                        "Advanced",
                        "Options most people never change",
                        state.advanced,
                        () -> state.advanced = !state.advanced,
                        switchRow(
                            "Insert spaces for tabs",
                            null,
                            state.spaces,
                            () -> state.spaces = !state.spaces),
                        Group.row(
                            "Undo steps",
                            null,
                            numberField(state.undo, 10, 1000, 10, steps -> state.undo = steps)),
                        Group.box(
                            label("Line endings"),
                            radioGroup(
                                    List.of(LineEnding.values()),
                                    state.endings,
                                    ending -> ending.title,
                                    ending -> state.endings = ending)
                                .label("Line endings"))))))
        .grow(1);
  }

  static El connections(Settings state) {
    Theme theme = Theme.CURRENT.get();
    List<Connection> matches = state.matches();
    El list = col().gap(theme.space().m());
    for (Connection connection : matches) {
      El menu = Menu.dropdown(
          button(GHOST, "…", connection.menu::toggle)
              .key("menu")
              .label("Actions for " + connection.name),
          connection.menu,
          List.of(
              Menu.item(
                  "Connect", connection.connected ? null : () -> state.selected = connection.name),
              Menu.item(
                  "Duplicate",
                  () -> state.connections.add(new Connection(
                      connection.name + "-copy", connection.host, connection.port, false))),
              Menu.separator(),
              Menu.item("Delete", () -> state.connections.remove(connection))));
      list.child(Card.pressable(
          card(Card.header(
                  connection.name,
                  connection.host + ":" + connection.port,
                  Icon.icon(
                      connection.connected ? Symbol.PLUG_CONNECTED : Symbol.PLUG_DISCONNECTED,
                      connection.connected ? Icon.Variant.SUCCESS : Icon.Variant.MUTED),
                  menu))
              .key(connection.name)
              .value(connection.connected ? "connected" : "not connected"),
          connection.name,
          connection.name.equals(state.selected),
          () -> state.selected = connection.name));
    }
    return page(
            "Connections",
            section(
                "Servers",
                "Where the workbench connects to, and how",
                row()
                    .gap(theme.space().m())
                    .children(
                        textField(
                                state.filter,
                                "Filter by name or host",
                                filter -> state.filter = filter)
                            .key("filter")
                            .grow(1),
                        button(PRIMARY, "New connection…", () -> {
                              state.name = "";
                              state.host = "";
                              state.port = 5432;
                              state.portDraft.clear();
                              state.adding = true;
                            })
                            .key("add")),
                matches.isEmpty()
                    ? card(emptyState(
                        Symbol.SEARCH,
                        "No connections match",
                        "No name or host contains \"" + state.filter.strip() + "\".",
                        button(SECONDARY, "Clear filter", () -> state.filter = "")))
                    : list))
        .grow(1);
  }

  static El newConnection(Settings state) {
    El port = ValueField.valueField(
            ConnectionFormats.PORT,
            state.port,
            "5432",
            state.portDraft,
            value -> state.port = value)
        .key("port")
        .w(72);
    boolean ready =
        !state.name.isBlank() && !state.host.isBlank() && state.portDraft.error() == null;
    El add = button(PRIMARY, "Add", ready ? state::add : null).key("ok");
    return Dialog.dialog(
        "New connection",
        form(
            Form.Labels.BESIDE,
            field(
                    "Name",
                    textField(state.name, "Shown in the list", name -> state.name = name)
                        .key("name")
                        .w(220))
                .required(),
            field(
                    "Host",
                    textField(state.host, "Name or address", host -> state.host = host)
                        .key("host")
                        .w(220))
                .required(),
            field("Port", port).help("From 1 to 65535")),
        List.of(button(SECONDARY, "Cancel", () -> state.adding = false).key("cancel"), add),
        add, // Enter in a field adds, as Add does; on a button it presses that instead
        () -> state.adding = false);
  }

  static El keymap() {
    return page(
            "Keymap",
            section(
                "Queries",
                null,
                group(
                    Group.row(
                        "Run the statement at the caret",
                        null,
                        label(Label.Variant.MUTED, "Ctrl+Enter")),
                    Group.row(
                        "Run the whole script",
                        null,
                        label(Label.Variant.MUTED, "Ctrl+Shift+Enter")),
                    Group.row(
                        "Cancel the running statement", null, label(Label.Variant.MUTED, "Escape")),
                    Group.row("Format", null, label(Label.Variant.MUTED, "Ctrl+Alt+L")))),
            section(
                "Navigation",
                null,
                group(
                    Group.row("Next tab", null, label(Label.Variant.MUTED, "Ctrl+Tab")),
                    Group.row("Close the tab", null, label(Label.Variant.MUTED, "Ctrl+W")),
                    Group.row("Find a table", null, label(Label.Variant.MUTED, "Ctrl+Shift+N")))))
        .grow(1);
  }

  static El about(String platform) {
    return page(
            "About",
            card(
                Card.header("rethinkj Workbench", "Version 0.1.0 · " + platform),
                label(
                        Label.Variant.MUTED,
                        "A database tool built with rethinkj UI. Free software under the MIT license.")
                    .wrapText()))
        .grow(1);
  }
}
