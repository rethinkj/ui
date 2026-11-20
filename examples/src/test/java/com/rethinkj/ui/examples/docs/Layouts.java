package com.rethinkj.ui.examples.docs;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.GHOST;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Form.Labels.BESIDE;

import com.rethinkj.ui.Clipboard;
import com.rethinkj.ui.El;
import com.rethinkj.ui.kit.AppIcon;
import com.rethinkj.ui.kit.Badge;
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
import com.rethinkj.ui.kit.Toolbar;
import java.util.ArrayList;
import java.util.List;

/** The examples of the pages about cards, structure, navigation and status. */
final class Layouts {

  final Clipboard clipboard = Clipboard.inMemory();
  boolean wrap = true;
  boolean advanced;
  String host = "localhost";
  String port = "5432";
  String user = "";
  String password = "";
  boolean autoSave = true;
  int page = 1; // Tables, the first item under the heading Database
  int filter;
  Split.Position sidebar = Split.Position.first(160);
  final List<Tabs.Tab> tabs = new ArrayList<>(List.of(
      new Tabs.Tab("orders").icon(Symbol.TABLE),
      new Tabs.Tab("customers", true, true).icon(Symbol.TABLE),
      new Tabs.Tab("query 1", true, false).icon(Symbol.CODE)));
  int tab;
  int result;
  final Breadcrumb.State crumbs = new Breadcrumb.State();
  final Toolbar.State tools = new Toolbar.State();
  final Menu.State runMenu = new Menu.State();
  final Menu.State encodings = new Menu.State();
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

  void open(String place) {
    said = "open " + place;
  }

  List<Menu.Item> tables() {
    return List.of(
        Menu.item("customers", () -> open("customers")).icon(Symbol.TABLE),
        Menu.item("orders", () -> open("orders")).icon(Symbol.TABLE));
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

  void search() {
    said = "search";
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

  void password(String value) {
    password = value;
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

  void result(int index) {
    result = index;
  }

  void wrapped(boolean on) {
    wrapped = on;
  }

  void format() {
    said = "format";
  }

  void save() {
    said = "save";
  }

  static El cards(Layouts state) {
    Theme theme = Theme.CURRENT.get();
    // docs:start card
    El connection = Card.card(
        Card.header(
            "Connection",
            "Where the workbench connects at start.",
            Button.button(SECONDARY, "Edit", state::edit)),
        Label.label(Label.Variant.MONO, "postgres://localhost:5432/shop"));
    // docs:end card
    // docs:start card-flush
    El results = Card.flush(
        Card.toolbar(
            "orders", "1 000 000 rows", Button.button(PRIMARY, Symbol.PLAY, "Run", state::run)),
        col().padding(theme.space().l()).grow(1).child(Label.label("The grid goes here.")),
        Card.footer(Label.label(Label.Variant.MUTED, "23 ms")));
    // docs:end card-flush
    return Frame.of(connection.w(380), results.w(380).h(180));
  }

  static El groups(Layouts state) {
    // docs:start group
    El editor = Group.group(
        Group.switchRow(
            "Wrap lines", "Long lines break at the edge.", state.wrap, state::toggleWrap),
        Group.linkRow(Symbol.KEY, "Keymap", null, "Default", state::keymap),
        Group.expander(
            "Advanced",
            null,
            state.advanced,
            state::toggleAdvanced,
            Group.row("Tab size", null, Label.label("4"))));
    // docs:end group
    return Frame.of(editor.w(420));
  }

  static El sections(Layouts state) {
    // docs:start section
    El settings = Section.page(
        "Editor",
        Section.section(
            "Text",
            "How the query editor shows text.",
            Group.group(Group.switchRow("Wrap lines", null, state.wrap, state::toggleWrap))),
        Section.section(
            "Saving",
            null,
            Group.group(
                Group.switchRow("Save on close", null, state.autoSave, state::toggleAutoSave))));
    // docs:end section
    return Frame.of(settings.w(480).h(360));
  }

  static El form(Layouts state) {
    // docs:start form
    El form = Form.form(
        Form.Labels.BESIDE,
        Form.field("Host", TextField.textField(state.host, "localhost", state::host))
            .required(),
        Form.field("Port", TextField.textField(state.port, "5432", state::port).w(80))
            .help("The server's TCP port."),
        Form.field("User", TextField.textField(state.user, "Role", state::user))
            .error(state.user.isBlank() ? "A user is required." : null));
    El buttons = Form.buttons(
        Button.button(SECONDARY, "Cancel", state::cancel),
        Button.button(PRIMARY, "Connect", state.user.isBlank() ? null : state::connect));
    // docs:end form
    return Frame.of(Card.card(form, buttons).w(440));
  }

  static El formGroups(Layouts state) {
    // docs:start form-groups
    El form = Form.form(
        Form.Labels.BESIDE,
        Form.group(
                "Server",
                // Each field of the row keeps its label, help and error; the host grows.
                Form.row(
                    Form.field("Host", TextField.textField(state.host, "localhost", state::host))
                        .required()
                        .grow(),
                    Form.field(
                            "Port",
                            TextField.textField(state.port, "5432", state::port).w(72))
                        .error(state.port.matches("\\d+") ? null : "Digits only")))
            .description("Where the database runs."),
        Form.group(
                "Login",
                Form.field("User", TextField.textField(state.user, "Role", state::user)),
                Form.field("Password", TextField.passwordField("", null, value -> {})))
            .divided());
    // docs:end form-groups
    return Frame.of(Card.card(form).w(440));
  }

  static El formDivided(Layouts state) {
    // docs:start form-divided
    El form = Form.form(
        Form.Labels.BESIDE,
        Form.field("Host", TextField.textField(state.host, "localhost", state::host)),
        Form.field("Port", TextField.textField(state.port, "5432", state::port).w(72)),
        // No title: no heading, only a hairline above, and the form's label column.
        Form.group(
                null,
                Form.field("User", TextField.textField(state.user, "Role", state::user)),
                Form.field(
                    "Password", TextField.passwordField(state.password, null, state::password)))
            .divided());
    // docs:end form-divided
    return Frame.of(Card.card(form).w(440));
  }

  static El formCards(Layouts state) {
    Theme theme = Theme.CURRENT.get();
    // docs:start form-cards
    Form.Field host = Form.field("Host", TextField.textField(state.host, null, state::host));
    Form.Field user = Form.field("User name", TextField.textField(state.user, null, state::user));
    // One label column for forms in separate cards: the width of all their labels.
    float column = Form.labelWidth(host, user);
    El server = Card.card(Card.header("Server", null), Form.form(BESIDE, column, host));
    El login = Card.card(Card.header("Login", null), Form.form(BESIDE, column, user));
    // docs:end form-cards
    return Frame.of(col().w(400).gap(theme.space().m()).children(server, login));
  }

  static El dividers(Layouts state) {
    Theme theme = Theme.CURRENT.get();
    // docs:start divider
    El across = Divider.divider();
    El labeled = Divider.divider("or");
    El upright = Divider.vertical();
    // docs:end divider
    return Frame.of(
        col().w(240).gap(theme.space().m()).children(across, labeled),
        row()
            .h(24)
            .gap(theme.space().m())
            .children(Label.label("Run"), upright, Label.label("Stop")));
  }

  static El navigation(Layouts state) {
    // docs:start nav-list
    El pages = NavList.navList(
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
    return Frame.of(Card.flush(Card.inset(pages)).w(220).grow(1));
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
    // docs:start nav-list-typed
    El sections = NavList.navList(
            List.of(Area.values()),
            state.area,
            area -> NavList.item(area.icon, area.title),
            area -> area.group, // a heading wherever the group changes
            state::area)
        .label("Sections");
    // docs:end nav-list-typed
    return Frame.of(Card.flush(Card.inset(sections)).w(220).grow(1));
  }

  static El split(Layouts state) {
    El tree = Card.card(Label.label("Schema")).grow(1);
    El editor = Card.card(Label.label("Query")).grow(1);
    // docs:start split
    El frame = Split.cards(
        Split.Direction.ROW,
        state.sidebar,
        Split.Position.first(160),
        state::sidebar,
        tree,
        120,
        editor,
        200);
    // docs:end split
    return Frame.of(frame.wFull().grow(1));
  }

  static El cardsWindow(Layouts state) {
    Theme theme = Theme.CURRENT.get();
    El icon = AppIcon.appIcon(16, theme.accent().color(), Symbol.DATABASE);
    El tree = Card.card(Label.label("Schema")).grow(1);
    El editor = Card.card(Label.label("Query")).grow(1);
    // docs:start cards-window
    float gap = theme.space().m();
    El cards = Split.cards(
            Split.Direction.ROW,
            state.sidebar,
            Split.Position.first(160),
            state::sidebar,
            tree,
            120,
            editor,
            200)
        .grow(1)
        // Below the cards the same gap as beside and between them, so they clear the status bar.
        .margin(0, gap, gap, gap);
    El window = col()
        .wFull()
        .hFull()
        .fill(theme.surface().window())
        .child(TitleBar.titleBar(icon, "Workbench"))
        .child(cards)
        .child(StatusBar.statusBar(
            List.of(StatusBar.status(StatusBar.State.OK, "localhost", null)),
            List.of(StatusBar.text("Ln 3, Col 9"))));
    // docs:end cards-window
    return window;
  }

  static El tabs(Layouts state) {
    Theme theme = Theme.CURRENT.get();
    // docs:start tabs
    El content = state.tab < 0 // the user closed every tab
        ? EmptyState.emptyState(Symbol.TABLE, "No open tables", null, null)
        : col()
            .padding(theme.space().l())
            .grow(1)
            .child(Label.label("The rows of " + state.tabs.get(state.tab).title()));
    El tabs = Tabs.tabs(state.tabs, state.tab, state::tab, index -> close(state, index), content);
    // docs:end tabs
    return Frame.of(tabs.wFull().grow(1));
  }

  static El tabsLine(Layouts state) {
    Theme theme = Theme.CURRENT.get();
    // docs:start tabs-line
    El results = Tabs.tabs(
        List.of(new Tabs.Tab("Result", false, false), new Tabs.Tab("Messages", false, false)),
        state.result,
        state::result,
        null,
        col()
            .padding(theme.space().l())
            .child(Label.label(state.result == 0 ? "6 rows in 18 ms" : "No messages")),
        Tabs.options().variant(Tabs.Variant.LINE));
    El card = Card.flush(Card.toolbar("orders by month", null), results.grow(1));
    // docs:end tabs-line
    return Frame.of(card.wFull().grow(1));
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
    // docs:start callout
    El restart = Callout.callout(
        Callout.Kind.WARNING,
        "Restart needed",
        "The new font size applies after a restart.",
        Button.button(SECONDARY, "Restart", state::restart));
    // docs:end callout
    // docs:start empty-state
    El empty = EmptyState.emptyState(
        Symbol.SEARCH,
        "No tables match",
        "Try another filter.",
        Button.button(SECONDARY, "Clear filter", state::clearFilter));
    // docs:end empty-state
    // docs:start progress
    El download = Progress.labeled("orders.csv", 0.42, null);
    El failed = Progress.bar(0.64, Progress.State.ERROR);
    El running = Progress.indeterminate();
    El spinner = Progress.spinner();
    El ring = Progress.ring(0.42); // where a bar does not fit, as in a button
    // docs:end progress
    return Frame.of(
        restart.w(420),
        empty,
        download.w(240),
        failed.w(240),
        running.w(240),
        row().gap(Theme.CURRENT.get().space().m()).children(spinner, ring));
  }

  static El bars(Layouts state) {
    Theme theme = Theme.CURRENT.get();
    // docs:start title-bar
    El title = TitleBar.titleBar(
        AppIcon.appIcon(16, theme.accent().color(), Symbol.DATABASE),
        "Workbench",
        List.of(),
        TitleBar.search("Search commands", "Ctrl+Shift+P", state::search),
        List.of(Button.icon(GHOST, Symbol.WEATHER_MOON, "Dark theme", state::toggleDark)
            .checked(state.dark)));
    // docs:end title-bar
    // docs:start status-bar
    El status = StatusBar.statusBar(
        List.of(
            StatusBar.group(
                "Connection",
                StatusBar.status(StatusBar.State.OK, "localhost", null),
                StatusBar.text("shop · public")),
            StatusBar.status(StatusBar.State.ERROR, "prod-eu", null)),
        StatusBar.item(Symbol.CHECKMARK_CIRCLE, "20 rows in 48 ms", null),
        List.of(
            StatusBar.item(Badge.Kind.ACCENT, Symbol.EDIT, "3 changes", null),
            StatusBar.group(
                "Caret",
                StatusBar.text("Ln 3, Col 9"),
                StatusBar.menu(
                    StatusBar.item(null, "UTF-8", state.encodings::toggle),
                    state.encodings,
                    List.of(
                        Menu.item("UTF-8", state::encoding),
                        Menu.item("ISO-8859-1", state::encoding))))));
    // docs:end status-bar
    return col()
        .wFull()
        .hFull()
        .fill(theme.surface().window())
        .child(title)
        .child(el().grow(1))
        .child(status);
  }

  static El toolbar(Layouts state) {
    Theme theme = Theme.CURRENT.get();
    // docs:start toolbar
    El tools = Toolbar.toolbar(
        state.tools,
        "Query",
        List.of(
            Toolbar.split(
                PRIMARY,
                Symbol.PLAY,
                "Run",
                state::run,
                state.runMenu,
                List.of(Menu.item("Explain", state::explain))),
            Toolbar.group(
                Toolbar.toggle(Symbol.TEXT_WHOLE_WORD, "Wrap", state.wrapped, state::wrapped),
                Toolbar.button(Symbol.BRACES, "Format", state::format),
                Toolbar.button(Symbol.ARROW_CLOCKWISE, "Refresh", state::refresh)),
            Toolbar.spacer(),
            Toolbar.text(Symbol.ARROW_EXPORT_LTR, "Export", state::export),
            Toolbar.text(SECONDARY, Symbol.SAVE, "Save", state::save)));
    // docs:end toolbar
    El query = Card.flush(
        col().padding(theme.space().m()).child(tools.wFull()),
        Divider.divider(),
        col()
            .padding(theme.space().m())
            .child(Label.label(Label.Variant.MONO, "select * from orders where total > 100")));
    return Frame.of(query.wFull());
  }

  static El details(Layouts state) {
    // docs:start breadcrumb
    El path = Breadcrumb.breadcrumb(
        state.crumbs,
        List.of(
            Breadcrumb.crumb("localhost", () -> state.open("localhost")).icon(Symbol.SERVER),
            Breadcrumb.crumb("shop", () -> state.open("shop")).icon(Symbol.DATABASE),
            Breadcrumb.crumb("public", () -> state.open("public"))
                .icon(Symbol.FOLDER)
                .children(state::tables),
            Breadcrumb.crumb("orders", null).icon(Symbol.TABLE)));
    // docs:end breadcrumb
    // docs:start properties
    El table = Properties.properties(
        List.of(
            Properties.property("Name", "orders"),
            Properties.property("Rows", "1 000 000"),
            Properties.property("OID", "16384").mono()),
        state.clipboard);
    // docs:end properties
    return Frame.of(path.wFull(), table.w(320));
  }
}
