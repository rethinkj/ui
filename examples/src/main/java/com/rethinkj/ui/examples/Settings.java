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
  App<Settings> app; // whose window says whether it is maximized
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
    App<Settings> app = new App<>(new Settings(), Settings::view);
    app.state().app = app;
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
    Theme theme = state.theme();
    El pages = navList(
            theme, List.of(Page.values()), state.page, Page::item, Page::group, state::show)
        .key("pages")
        .label("Settings")
        .grow(1);
    El root = col()
        .wFull()
        .hFull()
        .fill(theme.surface().window())
        .child(titleBar(theme, "Settings", theme.accent().color(), Identity.maximized(state.app)))
        .child(row()
            .grow(1)
            .minSize(0, 0)
            .padding(0, theme.space().m(), theme.space().m(), theme.space().m())
            .gap(theme.space().m())
            .children(
                Card.flush(theme, Card.inset(theme, pages)).w(200).shrink(0),
                switch (state.page) {
                  case GENERAL -> general(theme, state);
                  case APPEARANCE -> appearance(theme, state);
                  case EDITOR -> editor(theme, state);
                  case CONNECTIONS -> connections(theme, state);
                  case KEYMAP -> keymap(theme);
                  case ABOUT -> about(theme, state.platform);
                }));
    return root.overlay(state.adding ? newConnection(theme, state) : null);
  }
  // docs:end settings-pages

  // docs:start settings-general
  static El general(Theme theme, Settings state) {
    return page(
            theme,
            "General",
            section(
                theme,
                "Startup",
                null,
                group(
                    theme,
                    switchRow(
                        theme,
                        "Reopen the last workspace",
                        "Open the tabs and connections of the last session again",
                        state.reopen,
                        state::toggleReopen),
                    switchRow(
                        theme, "Check for updates", null, state.updates, state::toggleUpdates),
                    Group.row(
                        theme,
                        "Language",
                        null,
                        comboBox(theme, LANGUAGES, state.language, state.languages, state::language)
                            .w(160)))),
            section(
                theme,
                "History",
                "What the workbench remembers of your work",
                group(
                    theme,
                    Group.row(
                        theme,
                        "Statements kept",
                        "Per connection, the oldest go first",
                        numberField(theme, state.history, 0, 10_000, 50, state::history)),
                    linkRow(
                        theme,
                        Symbol.KEY_MULTIPLE,
                        "Keymap",
                        "The keys of every command",
                        "Default",
                        state::showKeymap))))
        .grow(1);
  }
  // docs:end settings-general

  static El appearance(Theme theme, Settings state) {
    return page(
            theme,
            "Appearance",
            section(
                theme,
                "Colors",
                null,
                group(
                    theme,
                    Group.row(
                        theme,
                        "Theme",
                        state.system == null
                            ? "System follows the dark theme in this example"
                            : "System follows the system's light or dark setting",
                        segmented(
                            theme,
                            List.of(Look.values()),
                            state.theme,
                            look -> look.title,
                            look -> state.theme = look)),
                    Group.row(
                        theme,
                        "Accent color",
                        "Buttons, switches and selections",
                        select(
                                theme,
                                List.of(Accent.values()),
                                state.accent,
                                accent -> accent.title,
                                state.accents,
                                accent -> state.accent = accent)
                            .w(160)))),
            section(
                theme,
                "Text and spacing",
                null,
                group(
                    theme,
                    Group.row(
                        theme,
                        "Interface font size",
                        state.fontSize + " pixels",
                        slider(theme, state.fontSize, 11, 16, 1, size -> state.fontSize = size)
                            .w(160)),
                    Group.row(
                        theme,
                        "Density",
                        "Space between the rows of lists, trees and grids",
                        segmented(
                            theme,
                            List.of(Density.values()),
                            state.density,
                            density -> density.title,
                            density -> state.density = density))),
                state.fontSize == FONT_SIZE
                    ? null
                    : callout(
                        theme,
                        Callout.Kind.WARNING,
                        "Restart to apply",
                        "The interface font size changes the next time the workbench starts.",
                        button(theme, SECONDARY, "Undo", () -> state.fontSize = FONT_SIZE))))
        .grow(1);
  }

  static El editor(Theme theme, Settings state) {
    return page(
            theme,
            "Editor",
            section(
                theme,
                "Text",
                null,
                group(
                    theme,
                    switchRow(
                        theme,
                        "Word wrap",
                        "Wrap long lines at the edge of the editor instead of scrolling sideways",
                        state.wrap,
                        () -> state.wrap = !state.wrap),
                    switchRow(
                        theme,
                        "Line numbers",
                        null,
                        state.lineNumbers,
                        () -> state.lineNumbers = !state.lineNumbers),
                    switchRow(
                        theme,
                        "Highlight the current line",
                        null,
                        state.highlight,
                        () -> state.highlight = !state.highlight),
                    Group.row(
                        theme,
                        "Font",
                        null,
                        select(
                                theme,
                                List.of(EditorFont.values()),
                                state.font,
                                font -> font.title,
                                state.fonts,
                                font -> state.font = font)
                            .w(160)))),
            section(
                theme,
                "Indentation",
                null,
                group(
                    theme,
                    Group.row(
                        theme,
                        "Tab size",
                        "Columns from one tab stop to the next",
                        numberField(theme, state.tabSize, 1, 16, 1, size -> state.tabSize = size)),
                    expander(
                        theme,
                        "Advanced",
                        "Options most people never change",
                        state.advanced,
                        () -> state.advanced = !state.advanced,
                        switchRow(
                            theme,
                            "Insert spaces for tabs",
                            null,
                            state.spaces,
                            () -> state.spaces = !state.spaces),
                        Group.row(
                            theme,
                            "Undo steps",
                            null,
                            numberField(
                                theme, state.undo, 10, 1000, 10, steps -> state.undo = steps)),
                        Group.box(
                            theme,
                            label(theme, "Line endings"),
                            radioGroup(
                                    theme,
                                    List.of(LineEnding.values()),
                                    state.endings,
                                    ending -> ending.title,
                                    ending -> state.endings = ending)
                                .label("Line endings"))))))
        .grow(1);
  }

  static El connections(Theme theme, Settings state) {
    List<Connection> matches = state.matches();
    El list = col().gap(theme.space().m());
    for (Connection connection : matches) {
      El menu = Menu.dropdown(
          theme,
          button(theme, GHOST, "…", connection.menu::toggle)
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
          theme,
          card(
                  theme,
                  Card.header(
                      theme,
                      connection.name,
                      connection.host + ":" + connection.port,
                      Icon.icon(
                          theme,
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
            theme,
            "Connections",
            section(
                theme,
                "Servers",
                "Where the workbench connects to, and how",
                row()
                    .gap(theme.space().m())
                    .children(
                        textField(
                                theme,
                                state.filter,
                                "Filter by name or host",
                                filter -> state.filter = filter)
                            .key("filter")
                            .grow(1),
                        button(theme, PRIMARY, "New connection…", () -> {
                              state.name = "";
                              state.host = "";
                              state.port = 5432;
                              state.portDraft.clear();
                              state.adding = true;
                            })
                            .key("add")),
                matches.isEmpty()
                    ? card(
                        theme,
                        emptyState(
                            theme,
                            Symbol.SEARCH,
                            "No connections match",
                            "No name or host contains \"" + state.filter.strip() + "\".",
                            button(theme, SECONDARY, "Clear filter", () -> state.filter = "")))
                    : list))
        .grow(1);
  }

  static El newConnection(Theme theme, Settings state) {
    El port = ValueField.valueField(
            theme,
            ConnectionFormats.PORT,
            state.port,
            "5432",
            state.portDraft,
            value -> state.port = value)
        .key("port")
        .w(72);
    boolean ready =
        !state.name.isBlank() && !state.host.isBlank() && state.portDraft.error() == null;
    El add = button(theme, PRIMARY, "Add", ready ? state::add : null).key("ok");
    return Dialog.dialog(
        theme,
        "New connection",
        form(
            theme,
            Form.Labels.BESIDE,
            field(
                    "Name",
                    textField(theme, state.name, "Shown in the list", name -> state.name = name)
                        .key("name")
                        .w(220))
                .required(),
            field(
                    "Host",
                    textField(theme, state.host, "Name or address", host -> state.host = host)
                        .key("host")
                        .w(220))
                .required(),
            field("Port", port).help("From 1 to 65535")),
        List.of(button(theme, SECONDARY, "Cancel", () -> state.adding = false).key("cancel"), add),
        add, // Enter in a field adds, as Add does; on a button it presses that instead
        () -> state.adding = false);
  }

  static El keymap(Theme theme) {
    return page(
            theme,
            "Keymap",
            section(
                theme,
                "Queries",
                null,
                group(
                    theme,
                    Group.row(
                        theme,
                        "Run the statement at the caret",
                        null,
                        label(theme, Label.Variant.MUTED, "Ctrl+Enter")),
                    Group.row(
                        theme,
                        "Run the whole script",
                        null,
                        label(theme, Label.Variant.MUTED, "Ctrl+Shift+Enter")),
                    Group.row(
                        theme,
                        "Cancel the running statement",
                        null,
                        label(theme, Label.Variant.MUTED, "Escape")),
                    Group.row(
                        theme, "Format", null, label(theme, Label.Variant.MUTED, "Ctrl+Alt+L")))),
            section(
                theme,
                "Navigation",
                null,
                group(
                    theme,
                    Group.row(
                        theme, "Next tab", null, label(theme, Label.Variant.MUTED, "Ctrl+Tab")),
                    Group.row(
                        theme, "Close the tab", null, label(theme, Label.Variant.MUTED, "Ctrl+W")),
                    Group.row(
                        theme,
                        "Find a table",
                        null,
                        label(theme, Label.Variant.MUTED, "Ctrl+Shift+N")))))
        .grow(1);
  }

  static El about(Theme theme, String platform) {
    return page(
            theme,
            "About",
            card(
                theme,
                Card.header(theme, "rethinkj Workbench", "Version 0.1.0 · " + platform),
                label(
                        theme,
                        Label.Variant.MUTED,
                        "A database tool built with rethinkj UI. Free software under the MIT licence.")
                    .wrapText()))
        .grow(1);
  }
}
