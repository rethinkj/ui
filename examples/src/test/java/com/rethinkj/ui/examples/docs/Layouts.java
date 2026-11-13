package com.rethinkj.ui.examples.docs;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;

import com.rethinkj.ui.Clipboard;
import com.rethinkj.ui.El;
import com.rethinkj.ui.kit.Breadcrumb;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.Divider;
import com.rethinkj.ui.kit.EmptyState;
import com.rethinkj.ui.kit.Form;
import com.rethinkj.ui.kit.Group;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.NavList;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Properties;
import com.rethinkj.ui.kit.Section;
import com.rethinkj.ui.kit.Split;
import com.rethinkj.ui.kit.StatusBar;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Tabs;
import com.rethinkj.ui.kit.TextField;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.TitleBar;
import com.rethinkj.ui.kit.Toggle;
import com.rethinkj.ui.kit.Toolbar;
import java.util.ArrayList;
import java.util.List;

/** The examples of the pages about cards, structure, navigation and status. */
final class Layouts {

  final Theme t = Theme.DARK;
  final Clipboard clipboard = Clipboard.inMemory();
  boolean wrap = true;
  boolean advanced;
  String host = "localhost";
  String port = "5432";
  String user = "";
  boolean autoSave = true;
  int page;
  int filter;
  Split.Position sidebar = Split.Position.first(160);
  final List<Tabs.Tab> tabs = new ArrayList<>(List.of(
      new Tabs.Tab("orders").icon(Symbol.TABLE),
      new Tabs.Tab("customers", true, true).icon(Symbol.TABLE),
      new Tabs.Tab("query 1", true, false).icon(Symbol.CODE)));
  int tab;
  final Breadcrumb.State crumbs = new Breadcrumb.State();
  final Toolbar.State tools = new Toolbar.State();
  final Menu.State runMenu = new Menu.State();
  boolean wrapped;
  boolean dark = true;
  String said = "";

  static El cards(Layouts s) {
    Theme t = s.t;
    // docs:start card
    El connection = Card.card(t,
        Card.header(t, "Connection", "Where the workbench connects at start.",
            Button.button(t, SECONDARY, "Edit", () -> s.said = "edit")),
        Label.label(t, Label.Variant.MONO, "postgres://localhost:5432/shop"));
    // docs:end card
    // docs:start card-flush
    El results = Card.flush(t,
        Card.toolbar(t, "orders", "1 000 000 rows",
            Button.button(t, PRIMARY, Symbol.PLAY, "Run", () -> s.said = "run")),
        col().padding(t.space().l()).grow(1).child(Label.label(t, "The grid goes here.")),
        Card.footer(t, Label.label(t, Label.Variant.MUTED, "23 ms")));
    // docs:end card-flush
    return Frame.of(t, connection.w(380), results.w(380).h(180));
  }

  static El groups(Layouts s) {
    Theme t = s.t;
    // docs:start group
    El editor = Group.group(t,
        Group.switchRow(t, "Wrap lines", "Long lines break at the edge.", s.wrap,
            () -> s.wrap = !s.wrap),
        Group.linkRow(t, Symbol.KEY, "Keymap", null, "Default", () -> s.said = "keymap"),
        Group.expander(t, "Advanced", null, s.advanced, () -> s.advanced = !s.advanced,
            Group.row(t, "Tab size", null, Label.label(t, "4"))));
    // docs:end group
    return Frame.of(t, editor.w(420));
  }

  static El sections(Layouts s) {
    Theme t = s.t;
    // docs:start section
    El settings = Section.page(t, "Editor",
        Section.section(t, "Text", "How the query editor shows text.",
            Group.group(t, Group.switchRow(t, "Wrap lines", null, s.wrap, () -> s.wrap = !s.wrap))),
        Section.section(t, "Saving", null,
            Group.group(t, Group.switchRow(t, "Save on close", null, s.autoSave,
                () -> s.autoSave = !s.autoSave))));
    // docs:end section
    return Frame.of(t, settings.w(480).h(360));
  }

  static El form(Layouts s) {
    Theme t = s.t;
    // docs:start form
    El form = Form.form(t, Form.Labels.BESIDE,
        Form.field("Host", TextField.textField(t, s.host, "localhost", v -> s.host = v)).required(),
        Form.field("Port", TextField.textField(t, s.port, "5432", v -> s.port = v).w(80))
            .help("The server's TCP port."),
        Form.field("User", TextField.textField(t, s.user, "Role", v -> s.user = v))
            .error(s.user.isBlank() ? "A user is required." : null));
    El buttons = Form.buttons(t,
        Button.button(t, SECONDARY, "Cancel", () -> s.said = "cancel"),
        Button.button(t, PRIMARY, "Connect", s.user.isBlank() ? null : () -> s.said = "connect"));
    // docs:end form
    return Frame.of(t, Card.card(t, form, buttons).w(440));
  }

  static El dividers(Layouts s) {
    Theme t = s.t;
    // docs:start divider
    El across = Divider.divider(t);
    El labelled = Divider.divider(t, "or");
    El upright = Divider.vertical(t);
    // docs:end divider
    return Frame.of(t, col().w(240).gap(t.space().m()).children(across, labelled),
        row().h(24).gap(t.space().m()).children(Label.label(t, "Run"), upright,
            Label.label(t, "Stop")));
  }

  static El navigation(Layouts s) {
    Theme t = s.t;
    // docs:start nav-list
    El pages = NavList.navList(t, List.of(
        NavList.heading("Database"),
        NavList.item(Symbol.TABLE, "Tables").count("12"),
        NavList.item(Symbol.CODE, "Views").count("3"),
        NavList.heading("Server"),
        NavList.item(Symbol.PEOPLE, "Roles")), s.page, i -> s.page = i).label("Sections");
    // docs:end nav-list
    return Frame.of(t, Card.inset(t, pages).w(220));
  }

  static El split(Layouts s) {
    Theme t = s.t;
    El tree = Card.card(t, Label.label(t, "Schema")).grow(1);
    El editor = Card.card(t, Label.label(t, "Query")).grow(1);
    // docs:start split
    El frame = Split.cards(t, Split.Direction.ROW, s.sidebar, Split.Position.first(160),
        p -> s.sidebar = p, tree, 120, editor, 200);
    // docs:end split
    return Frame.of(t, frame.wFull().grow(1));
  }

  static El tabs(Layouts s) {
    Theme t = s.t;
    // docs:start tabs
    El content = col().padding(t.space().l()).grow(1)
        .child(Label.label(t, "The rows of " + s.tabs.get(s.tab).title()));
    El tabs = Tabs.tabs(t, s.tabs, s.tab, i -> s.tab = i, i -> close(s, i), content);
    // docs:end tabs
    return Frame.of(t, tabs.wFull().grow(1));
  }

  // docs:start tabs-close
  static void close(Layouts s, int index) {
    s.tabs.remove(index);
    if (s.tab > index || s.tab == s.tabs.size()) {
      s.tab--; // the active tab keeps its place, or the last one goes
    }
  }
  // docs:end tabs-close

  static El status(Layouts s) {
    Theme t = s.t;
    // docs:start callout
    El restart = Callout.callout(t, Callout.Kind.WARNING, "Restart needed",
        "The new font size applies after a restart.",
        Button.button(t, SECONDARY, "Restart", () -> s.said = "restart"));
    // docs:end callout
    // docs:start empty-state
    El empty = EmptyState.emptyState(t, Symbol.SEARCH, "No tables match",
        "Try another filter.", Button.button(t, SECONDARY, "Clear filter", () -> s.filter = 0));
    // docs:end empty-state
    // docs:start progress
    El download = Progress.labeled(t, "orders.csv", 0.42, null);
    El failed = Progress.bar(t, 0.64, Progress.State.ERROR);
    El running = Progress.indeterminate(t);
    El spinner = Progress.spinner(t);
    // docs:end progress
    return Frame.of(t, restart.w(420), empty, download.w(240), failed.w(240), running.w(240),
        spinner);
  }

  static El bars(Layouts s) {
    Theme t = s.t;
    // docs:start title-bar
    El title = TitleBar.titleBar(t, "Workbench", t.accent().color(),
        Toggle.toggle(t, s.dark, () -> s.dark = !s.dark).label("Dark theme"));
    // docs:end title-bar
    // docs:start status-bar
    El status = StatusBar.statusBar(t,
        List.of(StatusBar.status(t, StatusBar.State.CONNECTED, "localhost", null),
            StatusBar.text(t, "shop · public")),
        List.of(StatusBar.item(t, null, "UTF-8", () -> s.said = "encoding"),
            StatusBar.text(t, "Ln 3, Col 9")));
    // docs:end status-bar
    return col().wFull().hFull().fill(t.surface().window())
        .child(title).child(el().grow(1)).child(status);
  }

  static El details(Layouts s) {
    Theme t = s.t;
    // docs:start breadcrumb
    El path = Breadcrumb.breadcrumb(t, s.crumbs, List.of("localhost", "shop", "public", "orders"),
        i -> s.said = "part " + i);
    // docs:end breadcrumb
    // docs:start toolbar
    El tools = Toolbar.toolbar(t, s.tools, "Table", List.of(
        Toolbar.split(PRIMARY, Symbol.PLAY, "Run", () -> s.said = "run", s.runMenu,
            List.of(Menu.item("Explain", () -> s.said = "explain"))),
        Toolbar.separator(),
        Toolbar.toggle(Symbol.TEXT_WHOLE_WORD, "Wrap", s.wrapped, on -> s.wrapped = on),
        Toolbar.button(Symbol.ARROW_CLOCKWISE, "Refresh", () -> s.said = "refresh"),
        Toolbar.spacer(),
        Toolbar.text(Symbol.ARROW_EXPORT_LTR, "Export", () -> s.said = "export")));
    // docs:end toolbar
    // docs:start properties
    El table = Properties.properties(t, List.of(
        Properties.property("Name", "orders"),
        Properties.property("Rows", "1 000 000"),
        Properties.property("OID", "16384").mono()), s.clipboard);
    // docs:end properties
    return Frame.of(t, path, tools.wFull(), table.w(320));
  }
}
