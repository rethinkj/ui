package com.rethinkj.ui.examples;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.examples.TitleBar.titleBar;
import static com.rethinkj.ui.kit.Button.Variant.GHOST;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.Callout.callout;
import static com.rethinkj.ui.kit.Card.card;
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

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.Dialog;
import com.rethinkj.ui.kit.Divider;
import com.rethinkj.ui.kit.Form;
import com.rethinkj.ui.kit.Group;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.NavList;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.render.GpuPresenter;
import com.rethinkj.ui.text.TextRendering;
import com.rethinkj.ui.window.Window;
import com.rethinkj.ui.window.WindowLoop;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The settings of a database tool in a frameless window: its pages in a navigation list at the
 * left, the page chosen at the right, which scrolls. Built from the kit's pages, sections, groups
 * of rows, cards and forms, with its controls: the theme switches between light and dark and the
 * accent changes the whole window; the other settings are only kept. Connections are cards that
 * select, filter and open a menu, and a dialog with a form adds one. The state is this class.
 */
public final class Settings {

  static final List<NavList.Item> PAGES = List.of(
      NavList.item(Symbol.SETTINGS, "General"),
      NavList.item(Symbol.WINDOW, "Appearance"),
      NavList.item(Symbol.CODE, "Editor"),
      NavList.item(Symbol.DATABASE, "Connections"),
      NavList.item(Symbol.KEY_MULTIPLE, "Keymap"),
      NavList.heading("Help"),
      NavList.item(Symbol.INFO, "About"));
  static final List<String> THEMES = List.of("Light", "Dark", "System");
  static final List<String> ACCENTS = List.of("Blue", "Purple", "Green", "Orange");
  static final List<String> LANGUAGES = List.of("English", "Deutsch", "Français");
  static final List<String> FONTS = List.of("JetBrains Mono", "Inter");
  static final List<String> DENSITIES = List.of("Compact", "Comfortable");
  static final List<String> ENDINGS = List.of("LF", "CRLF", "As in the file");
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

  int page;
  // General
  boolean reopen = true;
  boolean updates = true;
  int language;
  final Menu.State languages = new Menu.State();
  int history = 200;
  // Appearance
  int theme = 1;
  int accent;
  final Menu.State accents = new Menu.State();
  int fontSize = FONT_SIZE;
  int density;
  // Editor
  boolean wrap;
  boolean lineNumbers = true;
  boolean highlight = true;
  int tabSize = 4;
  int font;
  final Menu.State fonts = new Menu.State();
  boolean advanced;
  boolean spaces = true;
  int undo = 100;
  int endings;
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
  String port = "5432";

  Theme theme() {
    Theme base = theme == 0 ? Theme.LIGHT : Theme.DARK; // the example cannot ask the system
    return switch (accent) {
      case 1 -> base.withAccent(new Theme.Accent(0xFF7C3AED, 0xFF8B5CF6, 0xFF6D28D9));
      case 2 -> base.withAccent(new Theme.Accent(0xFF15803D, 0xFF16A34A, 0xFF166534));
      case 3 -> base.withAccent(new Theme.Accent(0xFFC2410C, 0xFFEA580C, 0xFF9A3412));
      default -> base;
    };
  }

  List<Connection> matches() {
    String f = filter.strip().toLowerCase(Locale.ROOT);
    return connections.stream()
        .filter(c -> c.name.contains(f) || c.host.contains(f))
        .toList();
  }

  void add() {
    connections.add(new Connection(name, host, Integer.parseInt(port), false));
    selected = name;
    adding = false;
  }

  String portError() {
    if (port.isEmpty() || !port.chars().allMatch(c -> c >= '0' && c <= '9')) {
      return "Digits only";
    }
    return port.length() > 5 || Integer.parseInt(port) > 65535 ? "At most 65535" : null;
  }

  public static void main(String[] args) throws IOException {
    App<Settings> app = new App<>(new Settings(), Settings::view);
    // A script's images are stored, so they must not depend on the platform.
    app.textRendering(TextRendering.LINUX);
    if (Script.handle(args, app)) {
      return;
    }
    app.textRendering(TextRendering.platform());
    Window.Options options = Window.options().frameless().minimumSize(560, 400);
    try (Window window = Window.open("Settings", 880, 600, options);
        GpuPresenter presenter = new GpuPresenter(window)) {
      WindowLoop.run(window, app, presenter);
    }
  }

  static El view(Settings s) {
    Theme t = s.theme();
    El pages = navList(t, PAGES, s.page, i -> s.page = i).key("pages").label("Settings").grow(1);
    El root = col().wFull().hFull().fill(t.surface().window())
        .child(titleBar(t, "Settings", t.accent().color()))
        .child(row().grow(1).minSize(0, 0).children(
            col().w(200).shrink(0).padding(t.space().s()).fill(t.surface().panel()).child(pages),
            Divider.vertical(t),
            switch (s.page) {
              case 0 -> general(t, s);
              case 1 -> appearance(t, s);
              case 2 -> editor(t, s);
              case 3 -> connections(t, s);
              case 4 -> keymap(t);
              default -> about(t);
            }));
    return root.overlay(s.adding ? newConnection(t, s) : null);
  }

  static El general(Theme t, Settings s) {
    return page(t, "General",
        section(t, "Startup", null, group(t,
            switchRow(t, "Reopen the last workspace",
                "Open the tabs and connections of the last session again",
                s.reopen, () -> s.reopen = !s.reopen),
            switchRow(t, "Check for updates", null, s.updates, () -> s.updates = !s.updates),
            Group.row(t, "Language", null,
                select(t, LANGUAGES, s.language, s.languages, i -> s.language = i).w(160)))),
        section(t, "History", "What the workbench remembers of your work", group(t,
            Group.row(t, "Statements kept", "Per connection, the oldest go first",
                numberField(t, s.history, 0, 10_000, 50, v -> s.history = v)),
            linkRow(t, Symbol.KEY_MULTIPLE, "Keymap", "The keys of every command", "Default",
                () -> s.page = 4))))
        .grow(1);
  }

  static El appearance(Theme t, Settings s) {
    return page(t, "Appearance",
        section(t, "Colours", null, group(t,
            Group.row(t, "Theme", "System follows the dark theme in this example",
                segmented(t, THEMES, s.theme, i -> s.theme = i)),
            Group.row(t, "Accent colour", "Buttons, switches and selections",
                select(t, ACCENTS, s.accent, s.accents, i -> s.accent = i).w(160)))),
        section(t, "Text and spacing", null,
            group(t,
                Group.row(t, "Interface font size", s.fontSize + " pixels",
                    slider(t, s.fontSize, 11, 16, 1, v -> s.fontSize = v).w(160)),
                Group.row(t, "Density", "Space between the rows of lists, trees and grids",
                    segmented(t, DENSITIES, s.density, i -> s.density = i))),
            s.fontSize == FONT_SIZE ? null : callout(t, Callout.Kind.WARNING, "Restart to apply",
                "The interface font size changes the next time the workbench starts.",
                button(t, SECONDARY, "Undo", () -> s.fontSize = FONT_SIZE))))
        .grow(1);
  }

  static El editor(Theme t, Settings s) {
    return page(t, "Editor",
        section(t, "Text", null, group(t,
            switchRow(t, "Word wrap",
                "Wrap long lines at the edge of the editor instead of scrolling sideways",
                s.wrap, () -> s.wrap = !s.wrap),
            switchRow(t, "Line numbers", null, s.lineNumbers,
                () -> s.lineNumbers = !s.lineNumbers),
            switchRow(t, "Highlight the current line", null, s.highlight,
                () -> s.highlight = !s.highlight),
            Group.row(t, "Font", null,
                select(t, FONTS, s.font, s.fonts, i -> s.font = i).w(160)))),
        section(t, "Indentation", null, group(t,
            Group.row(t, "Tab size", "Columns from one tab stop to the next",
                numberField(t, s.tabSize, 1, 16, 1, v -> s.tabSize = v)),
            expander(t, "Advanced", "Options most people never change", s.advanced,
                () -> s.advanced = !s.advanced,
                switchRow(t, "Insert spaces for tabs", null, s.spaces, () -> s.spaces = !s.spaces),
                Group.row(t, "Undo steps", null,
                    numberField(t, s.undo, 10, 1000, 10, v -> s.undo = v)),
                Group.box(t, label(t, "Line endings"),
                    radioGroup(t, ENDINGS, s.endings, i -> s.endings = i)
                        .label("Line endings"))))))
        .grow(1);
  }

  static El connections(Theme t, Settings s) {
    List<Connection> matches = s.matches();
    El list = col().gap(t.space().m());
    for (Connection c : matches) {
      El menu = Menu.dropdown(t,
          button(t, GHOST, "…", c.menu::toggle).key("menu").label("Actions for " + c.name),
          c.menu, List.of(
              Menu.item("Connect", c.connected ? null : () -> s.selected = c.name),
              Menu.item("Duplicate", () -> s.connections.add(
                  new Connection(c.name + "-copy", c.host, c.port, false))),
              Menu.separator(),
              Menu.item("Delete", () -> s.connections.remove(c))));
      list.child(Card.pressable(t, card(t,
              Card.header(t, c.name, c.host + ":" + c.port,
                  Icon.icon(t, c.connected ? Symbol.PLUG_CONNECTED : Symbol.PLUG_DISCONNECTED,
                      c.connected ? Icon.Variant.SUCCESS : Icon.Variant.MUTED),
                  menu))
              .key(c.name).value(c.connected ? "connected" : "not connected"),
          c.name, c.name.equals(s.selected), () -> s.selected = c.name));
    }
    return page(t, "Connections",
        section(t, "Servers", "Where the workbench connects to, and how",
            row().gap(t.space().m()).children(
                textField(t, s.filter, "Filter by name or host", v -> s.filter = v)
                    .key("filter").grow(1),
                button(t, PRIMARY, "New connection…", () -> {
                  s.name = "";
                  s.host = "";
                  s.port = "5432";
                  s.adding = true;
                }).key("add")),
            matches.isEmpty()
                ? card(t, emptyState(t, Symbol.SEARCH, "No connections match",
                    "No name or host contains \"" + s.filter.strip() + "\".",
                    button(t, SECONDARY, "Clear filter", () -> s.filter = "")))
                : list))
        .grow(1);
  }

  static El newConnection(Theme t, Settings s) {
    String portError = s.portError();
    boolean ready = !s.name.isBlank() && !s.host.isBlank() && portError == null;
    return Dialog.dialog(t, "New connection",
        form(t, Form.Labels.BESIDE,
            field("Name", textField(t, s.name, "Shown in the list", v -> s.name = v)
                .key("name").w(220)).required(),
            field("Host", textField(t, s.host, "Name or address", v -> s.host = v)
                .key("host").w(220)).required(),
            field("Port", textField(t, s.port, "5432", v -> s.port = v).key("port").w(72))
                .help("From 1 to 65535").error(portError)),
        List.of(button(t, SECONDARY, "Cancel", () -> s.adding = false).key("cancel"),
            button(t, PRIMARY, "Add", ready ? s::add : null).key("ok")),
        () -> s.adding = false);
  }

  static El keymap(Theme t) {
    return page(t, "Keymap",
        section(t, "Queries", null, group(t,
            Group.row(t, "Run the statement at the caret", null, label(t, Label.Variant.MUTED, "Ctrl+Enter")),
            Group.row(t, "Run the whole script", null, label(t, Label.Variant.MUTED, "Ctrl+Shift+Enter")),
            Group.row(t, "Cancel the running statement", null, label(t, Label.Variant.MUTED, "Escape")),
            Group.row(t, "Format", null, label(t, Label.Variant.MUTED, "Ctrl+Alt+L")))),
        section(t, "Navigation", null, group(t,
            Group.row(t, "Next tab", null, label(t, Label.Variant.MUTED, "Ctrl+Tab")),
            Group.row(t, "Close the tab", null, label(t, Label.Variant.MUTED, "Ctrl+W")),
            Group.row(t, "Find a table", null, label(t, Label.Variant.MUTED, "Ctrl+Shift+N")))))
        .grow(1);
  }

  static El about(Theme t) {
    return page(t, "About",
        card(t,
            Card.header(t, "rethinkj Workbench", "Version 0.1.0 · Java 25 · Linux x64"),
            label(t, Label.Variant.MUTED,
                "A database tool built with rethinkj UI. Free software under the MIT licence.")
                .wrapText()))
        .grow(1);
  }
}
