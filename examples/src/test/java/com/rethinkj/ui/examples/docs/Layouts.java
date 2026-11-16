package com.rethinkj.ui.examples.docs;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Form.Labels.BESIDE;

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

  Theme theme = Theme.DARK;
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

  void edit() {
    said = "edit";
  }

  void run() {
    said = "run";
  }

  void keymap() {
    said = "keymap";
  }

  void cancel() {
    said = "cancel";
  }

  void connect() {
    said = "connect";
  }

  void restart() {
    said = "restart";
  }

  void encoding() {
    said = "encoding";
  }

  void part(int index) {
    said = "part " + index;
  }

  void explain() {
    said = "explain";
  }

  void refresh() {
    said = "refresh";
  }

  void export() {
    said = "export";
  }

  void toggleWrap() {
    wrap = !wrap;
  }

  void toggleAdvanced() {
    advanced = !advanced;
  }

  void toggleAutoSave() {
    autoSave = !autoSave;
  }

  void toggleDark() {
    dark = !dark;
  }

  void host(String value) {
    host = value;
  }

  void port(String value) {
    port = value;
  }

  void user(String value) {
    user = value;
  }

  void page(int index) {
    page = index;
  }

  void clearFilter() {
    filter = 0;
  }

  void sidebar(Split.Position position) {
    sidebar = position;
  }

  void tab(int index) {
    tab = index;
  }

  void wrapped(boolean on) {
    wrapped = on;
  }

  static El cards(Layouts state) {
    Theme theme = state.theme;
    // docs:start card
    El connection = Card.card(
        theme,
        Card.header(
            theme,
            "Connection",
            "Where the workbench connects at start.",
            Button.button(theme, SECONDARY, "Edit", state::edit)),
        Label.label(theme, Label.Variant.MONO, "postgres://localhost:5432/shop"));
    // docs:end card
    // docs:start card-flush
    El results = Card.flush(
        theme,
        Card.toolbar(
            theme,
            "orders",
            "1 000 000 rows",
            Button.button(theme, PRIMARY, Symbol.PLAY, "Run", state::run)),
        col().padding(theme.space().l()).grow(1).child(Label.label(theme, "The grid goes here.")),
        Card.footer(theme, Label.label(theme, Label.Variant.MUTED, "23 ms")));
    // docs:end card-flush
    return Frame.of(theme, connection.w(380), results.w(380).h(180));
  }

  static El groups(Layouts state) {
    Theme theme = state.theme;
    // docs:start group
    El editor = Group.group(
        theme,
        Group.switchRow(
            theme, "Wrap lines", "Long lines break at the edge.", state.wrap, state::toggleWrap),
        Group.linkRow(theme, Symbol.KEY, "Keymap", null, "Default", state::keymap),
        Group.expander(
            theme,
            "Advanced",
            null,
            state.advanced,
            state::toggleAdvanced,
            Group.row(theme, "Tab size", null, Label.label(theme, "4"))));
    // docs:end group
    return Frame.of(theme, editor.w(420));
  }

  static El sections(Layouts state) {
    Theme theme = state.theme;
    // docs:start section
    El settings = Section.page(
        theme,
        "Editor",
        Section.section(
            theme,
            "Text",
            "How the query editor shows text.",
            Group.group(
                theme, Group.switchRow(theme, "Wrap lines", null, state.wrap, state::toggleWrap))),
        Section.section(
            theme,
            "Saving",
            null,
            Group.group(
                theme,
                Group.switchRow(
                    theme, "Save on close", null, state.autoSave, state::toggleAutoSave))));
    // docs:end section
    return Frame.of(theme, settings.w(480).h(360));
  }

  static El form(Layouts state) {
    Theme theme = state.theme;
    // docs:start form
    El form = Form.form(
        theme,
        Form.Labels.BESIDE,
        Form.field("Host", TextField.textField(theme, state.host, "localhost", state::host))
            .required(),
        Form.field(
                "Port",
                TextField.textField(theme, state.port, "5432", state::port).w(80))
            .help("The server's TCP port."),
        Form.field("User", TextField.textField(theme, state.user, "Role", state::user))
            .error(state.user.isBlank() ? "A user is required." : null));
    El buttons = Form.buttons(
        theme,
        Button.button(theme, SECONDARY, "Cancel", state::cancel),
        Button.button(theme, PRIMARY, "Connect", state.user.isBlank() ? null : state::connect));
    // docs:end form
    return Frame.of(theme, Card.card(theme, form, buttons).w(440));
  }

  static El formGroups(Layouts state) {
    Theme theme = state.theme;
    // docs:start form-groups
    El form = Form.form(
        theme,
        Form.Labels.BESIDE,
        Form.group(
                "Server",
                // Each field of the row keeps its label, help and error; the host grows.
                Form.row(
                    Form.field(
                            "Host",
                            TextField.textField(theme, state.host, "localhost", state::host))
                        .required()
                        .grow(),
                    Form.field(
                            "Port",
                            TextField.textField(theme, state.port, "5432", state::port)
                                .w(72))
                        .error(state.port.matches("\\d+") ? null : "Digits only")))
            .description("Where the database runs."),
        Form.group(
                "Login",
                Form.field("User", TextField.textField(theme, state.user, "Role", state::user)),
                Form.field("Password", TextField.passwordField(theme, "", null, value -> {})))
            .divided());
    // docs:end form-groups
    return Frame.of(theme, Card.card(theme, form).w(440));
  }

  static El formCards(Layouts state) {
    Theme theme = state.theme;
    // docs:start form-cards
    Form.Field host = Form.field("Host", TextField.textField(theme, state.host, null, state::host));
    Form.Field user =
        Form.field("User name", TextField.textField(theme, state.user, null, state::user));
    // One label column for forms in separate cards: the width of all their labels.
    float column = Form.labelWidth(theme, host, user);
    El server = Card.card(
        theme, Card.header(theme, "Server", null), Form.form(theme, BESIDE, column, host));
    El login =
        Card.card(theme, Card.header(theme, "Login", null), Form.form(theme, BESIDE, column, user));
    // docs:end form-cards
    return Frame.of(theme, col().w(400).gap(theme.space().m()).children(server, login));
  }

  static El dividers(Layouts state) {
    Theme theme = state.theme;
    // docs:start divider
    El across = Divider.divider(theme);
    El labelled = Divider.divider(theme, "or");
    El upright = Divider.vertical(theme);
    // docs:end divider
    return Frame.of(
        theme,
        col().w(240).gap(theme.space().m()).children(across, labelled),
        row()
            .h(24)
            .gap(theme.space().m())
            .children(Label.label(theme, "Run"), upright, Label.label(theme, "Stop")));
  }

  static El navigation(Layouts state) {
    Theme theme = state.theme;
    // docs:start nav-list
    El pages = NavList.navList(
            theme,
            List.of(
                NavList.heading("Database"),
                NavList.item(Symbol.TABLE, "Tables").count("12"),
                NavList.item(Symbol.CODE, "Views").count("3"),
                NavList.heading("Server"),
                NavList.item(Symbol.PEOPLE, "Roles")),
            state.page,
            state::page)
        .label("Sections");
    // docs:end nav-list
    return Frame.of(theme, Card.inset(theme, pages).w(220));
  }

  // docs:start nav-list-types
  enum Area {
    TABLES(Symbol.TABLE, "Tables", "Database"),
    VIEWS(Symbol.CODE, "Views", "Database"),
    ROLES(Symbol.PEOPLE, "Roles", "Server");

    final Symbol icon;
    final String title;
    final String group;

    Area(Symbol icon, String title, String group) {
      this.icon = icon;
      this.title = title;
      this.group = group;
    }
  }
  // docs:end nav-list-types

  Area area = Area.VIEWS;

  void area(Area value) {
    area = value;
  }

  static El typedNavigation(Layouts state) {
    Theme theme = state.theme;
    // docs:start nav-list-typed
    El sections = NavList.navList(
            theme,
            List.of(Area.values()),
            state.area,
            area -> NavList.item(area.icon, area.title),
            area -> area.group, // a heading wherever the group changes
            state::area)
        .label("Sections");
    // docs:end nav-list-typed
    return Frame.of(theme, Card.inset(theme, sections).w(220));
  }

  static El split(Layouts state) {
    Theme theme = state.theme;
    El tree = Card.card(theme, Label.label(theme, "Schema")).grow(1);
    El editor = Card.card(theme, Label.label(theme, "Query")).grow(1);
    // docs:start split
    El frame = Split.cards(
        theme,
        Split.Direction.ROW,
        state.sidebar,
        Split.Position.first(160),
        state::sidebar,
        tree,
        120,
        editor,
        200);
    // docs:end split
    return Frame.of(theme, frame.wFull().grow(1));
  }

  static El tabs(Layouts state) {
    Theme theme = state.theme;
    // docs:start tabs
    El content = state.tab < 0 // the user closed every tab
        ? EmptyState.emptyState(theme, Symbol.TABLE, "No open tables", null, null)
        : col()
            .padding(theme.space().l())
            .grow(1)
            .child(Label.label(theme, "The rows of " + state.tabs.get(state.tab).title()));
    El tabs =
        Tabs.tabs(theme, state.tabs, state.tab, state::tab, index -> close(state, index), content);
    // docs:end tabs
    return Frame.of(theme, tabs.wFull().grow(1));
  }

  // docs:start tabs-close
  static void close(Layouts state, int index) {
    state.tabs.remove(index);
    if (state.tab > index || state.tab == state.tabs.size()) {
      state.tab--; // the active tab keeps its place, or the last one goes
    }
  }
  // docs:end tabs-close

  static El status(Layouts state) {
    Theme theme = state.theme;
    // docs:start callout
    El restart = Callout.callout(
        theme,
        Callout.Kind.WARNING,
        "Restart needed",
        "The new font size applies after a restart.",
        Button.button(theme, SECONDARY, "Restart", state::restart));
    // docs:end callout
    // docs:start empty-state
    El empty = EmptyState.emptyState(
        theme,
        Symbol.SEARCH,
        "No tables match",
        "Try another filter.",
        Button.button(theme, SECONDARY, "Clear filter", state::clearFilter));
    // docs:end empty-state
    // docs:start progress
    El download = Progress.labeled(theme, "orders.csv", 0.42, null);
    El failed = Progress.bar(theme, 0.64, Progress.State.ERROR);
    El running = Progress.indeterminate(theme);
    El spinner = Progress.spinner(theme);
    // docs:end progress
    return Frame.of(
        theme, restart.w(420), empty, download.w(240), failed.w(240), running.w(240), spinner);
  }

  static El bars(Layouts state) {
    Theme theme = state.theme;
    // docs:start title-bar
    El title = TitleBar.titleBar(
        theme,
        "Workbench",
        theme.accent().color(),
        Toggle.toggle(theme, state.dark, state::toggleDark).label("Dark theme"));
    // docs:end title-bar
    // docs:start status-bar
    El status = StatusBar.statusBar(
        theme,
        List.of(
            StatusBar.status(theme, StatusBar.State.CONNECTED, "localhost", null),
            StatusBar.text(theme, "shop · public")),
        List.of(
            StatusBar.item(theme, null, "UTF-8", state::encoding),
            StatusBar.text(theme, "Ln 3, Col 9")));
    // docs:end status-bar
    return col()
        .wFull()
        .hFull()
        .fill(theme.surface().window())
        .child(title)
        .child(el().grow(1))
        .child(status);
  }

  static El details(Layouts state) {
    Theme theme = state.theme;
    // docs:start breadcrumb
    El path = Breadcrumb.breadcrumb(
        theme, state.crumbs, List.of("localhost", "shop", "public", "orders"), state::part);
    // docs:end breadcrumb
    // docs:start toolbar
    El tools = Toolbar.toolbar(
        theme,
        state.tools,
        "Table",
        List.of(
            Toolbar.split(
                PRIMARY,
                Symbol.PLAY,
                "Run",
                state::run,
                state.runMenu,
                List.of(Menu.item("Explain", state::explain))),
            Toolbar.separator(),
            Toolbar.toggle(Symbol.TEXT_WHOLE_WORD, "Wrap", state.wrapped, state::wrapped),
            Toolbar.button(Symbol.ARROW_CLOCKWISE, "Refresh", state::refresh),
            Toolbar.spacer(),
            Toolbar.text(Symbol.ARROW_EXPORT_LTR, "Export", state::export)));
    // docs:end toolbar
    // docs:start properties
    El table = Properties.properties(
        theme,
        List.of(
            Properties.property("Name", "orders"),
            Properties.property("Rows", "1 000 000"),
            Properties.property("OID", "16384").mono()),
        state.clipboard);
    // docs:end properties
    return Frame.of(theme, path.wFull(), tools.wFull(), table.w(320));
  }
}
