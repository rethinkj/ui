package com.rethinkj.ui.examples;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.GHOST;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.Checkbox.checkbox;
import static com.rethinkj.ui.kit.Label.Variant.MONO;
import static com.rethinkj.ui.kit.Label.Variant.MUTED;
import static com.rethinkj.ui.kit.Label.Variant.TITLE;
import static com.rethinkj.ui.kit.Label.label;
import static com.rethinkj.ui.kit.TextField.textField;
import static com.rethinkj.ui.kit.Toggle.toggle;

import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.examples.Samples.Node;
import com.rethinkj.ui.examples.Showcase.Section;
import com.rethinkj.ui.json.Json;
import com.rethinkj.ui.kit.Avatar;
import com.rethinkj.ui.kit.Badge;
import com.rethinkj.ui.kit.Breadcrumb;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.CodeEditor;
import com.rethinkj.ui.kit.ComboBox;
import com.rethinkj.ui.kit.CommandPalette;
import com.rethinkj.ui.kit.Completion;
import com.rethinkj.ui.kit.DataGrid;
import com.rethinkj.ui.kit.DataGrid.Align;
import com.rethinkj.ui.kit.DataGrid.Column;
import com.rethinkj.ui.kit.Dialog;
import com.rethinkj.ui.kit.Divider;
import com.rethinkj.ui.kit.FilterList;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.Popover;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Properties;
import com.rethinkj.ui.kit.Select;
import com.rethinkj.ui.kit.Split;
import com.rethinkj.ui.kit.Split.Direction;
import com.rethinkj.ui.kit.Split.Position;
import com.rethinkj.ui.kit.StatusBar;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Tabs;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Toolbar;
import com.rethinkj.ui.kit.Tree;
import com.rethinkj.ui.sql.Sql;
import com.rethinkj.ui.text.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The sections of the {@link Showcase}, each a view of the app's state in one theme. */
final class Sections {

  private Sections() {}

  /** The section the state chooses, under its title, in an area that scrolls. */
  // docs:start showcase-section
  static El section(Showcase state) {
    Theme theme = Theme.CURRENT.get();
    Section section = state.section;
    El body = switch (section) {
      case BUTTONS -> buttons(state);
      case FIELDS -> fields(state);
      case MENUS -> menus(state);
      case GRID -> grid(state);
      case TREE -> tree(state);
      case WORKBENCH -> workbench(state);
      case DETAILS -> details(state);
      case PROGRESS -> progress(state);
      case TYPE -> type();
      case COLOR -> color();
      case CONTRAST -> contrast();
      case ICONS -> icons();
    };
    // A key per section, so that what one section leaves does not fade out in the next.
    return col()
        .key(section.name().toLowerCase(Locale.ROOT))
        .label(section.title)
        .grow(1)
        .minSize(0, 0)
        .scroll()
        .scrollbars(theme.scrollbars())
        .padding(theme.space().xxl())
        .gap(theme.space().l())
        .child(col()
            .gap(theme.space().xs())
            .child(label(TITLE, section.title))
            .child(label(MUTED, section.description)))
        .child(body);
  }
  // docs:end showcase-section

  // Parts the sections share

  /** The least height of a section that fills the page, below which the page scrolls. */
  private static final float FILL_MIN = 400;

  /** A card: an area set apart on the window, with a title above its content. */
  private static El card(String title, El... content) {
    Theme theme = Theme.CURRENT.get();
    return Card.card(Card.header(title, null)).gap(theme.space().m()).children(content);
  }

  /** A row of a form: the name in a column of its own, then the control, which it labels. */
  private static El field(String name, El control) {
    return field(name, 96, control);
  }

  /** A row of a form whose names take {@code width}. */
  private static El field(String name, float width, El control) {
    Theme theme = Theme.CURRENT.get();
    return row()
        .gap(theme.space().m())
        .align(Style.Align.CENTER)
        .child(label(name).w(width).shrink(0))
        .child(control.label(name));
  }

  /** A button with an icon before its text, or with only an icon when the text is null. */
  static El toolButton(
      Button.Variant variant, Symbol symbol, String text, String label, Runnable onPress) {
    return text == null
        ? Button.icon(variant, symbol, label, onPress)
        : Button.button(variant, symbol, text, onPress);
  }

  private static El iconButton(Symbol symbol, String label, Runnable onPress) {
    return Button.icon(GHOST, symbol, label, onPress);
  }

  /** A vertical line between groups of a toolbar. */
  private static El divider() {
    Theme theme = Theme.CURRENT.get();
    return el().size(
            theme.size().stroke(), theme.size().control() - 2 * theme.space().s())
        .shrink(0)
        .margin(0, theme.space().xs(), 0, theme.space().xs())
        .fill(theme.border().subtle());
  }

  /** A strip of tools on the page, its actions apart and its icon buttons in {@link #icons}. */
  private static El toolbar() {
    Theme theme = Theme.CURRENT.get();
    return row().gap(theme.space().m()).align(Style.Align.CENTER);
  }

  /** Icon buttons that belong together, close to each other in a toolbar. */
  private static El icons(El... buttons) {
    Theme theme = Theme.CURRENT.get();
    return row().gap(theme.space().xs()).align(Style.Align.CENTER).children(buttons);
  }

  // Buttons

  private static El buttons(Showcase state) {
    Theme theme = Theme.CURRENT.get();
    El tools = toolbar()
        .child(toolButton(PRIMARY, Symbol.PLAY, "Run", "Run", () -> state.say("run"))
            .key("run"))
        .child(toolButton(SECONDARY, Symbol.STOP, "Stop", "Stop", null))
        .child(divider())
        .child(icons(
            iconButton(Symbol.ARROW_CLOCKWISE, "Refresh", () -> state.say("refresh")),
            iconButton(Symbol.ARROW_DOWNLOAD, "Export", () -> state.say("export")),
            iconButton(Symbol.FILTER, "Filter", () -> state.say("filter")),
            iconButton(Symbol.HISTORY, "History", null)))
        .child(el().grow(1))
        .child(toolButton(
            GHOST, Symbol.SETTINGS, "Settings", "Settings", () -> state.say("settings")));
    El actions = card(
        "Actions",
        row()
            .gap(theme.space().m())
            .align(Style.Align.CENTER)
            .child(button(PRIMARY, "Run query", () -> state.say("run")).key("primary"))
            .child(button(SECONDARY, "Cancel", () -> state.say("cancel")).key("cancel"))
            .child(button(GHOST, "Format", () -> state.say("format")).key("format")),
        label(MUTED, "Disabled"),
        row()
            .gap(theme.space().m())
            .align(Style.Align.CENTER)
            .child(button(PRIMARY, "Run query", null))
            .child(button(SECONDARY, "Cancel", null))
            .child(button(GHOST, "Format", null)),
        label(MUTED, "With icons"),
        row()
            .gap(theme.space().m())
            .align(Style.Align.CENTER)
            .child(toolButton(
                PRIMARY, Symbol.ADD, "New connection", "New connection", () -> state.say("new")))
            .child(toolButton(
                SECONDARY, Symbol.ARROW_IMPORT, "Import", "Import", () -> state.say("import")))
            .child(toolButton(GHOST, Symbol.COPY, "Copy", "Copy", () -> state.say("copy"))),
        label(MUTED, "Destructive"),
        row()
            .gap(theme.space().m())
            .align(Style.Align.CENTER)
            .child(button(PRIMARY, "Drop table", () -> state.say("drop"), danger()))
            .child(button(SECONDARY, "Discard", () -> state.say("discard"), danger()))
            .child(button(GHOST, "Delete", () -> state.say("delete"), danger().icon(Symbol.DELETE)))
            .child(Button.icon(
                GHOST, Symbol.DELETE, "Delete row", () -> state.say("delete"), danger())));
    El options = card(
        "Connection options",
        toggle(state.autocommit, "Auto-commit", () -> state.autocommit = !state.autocommit)
            .key("autocommit"),
        toggle(state.readOnly, "Read-only connection", () -> state.readOnly = !state.readOnly),
        toggle(state.nullText, "Show NULL as text", () -> state.nullText = !state.nullText),
        toggle(true, "Keep alive (set by the server)", null),
        toggle(false, "Single session (set by the server)", null),
        el().h(theme.space().xs()),
        checkbox(state.ssl, "Require SSL", () -> state.ssl = !state.ssl).key("ssl"),
        checkbox(
            state.savePassword, "Save password", () -> state.savePassword = !state.savePassword),
        checkbox(true, "Trust server certificate (managed)", null),
        checkbox(false, "Use SSH tunnel (not installed)", null));
    El footer = row()
        .gap(theme.space().m())
        .justify(Style.Content.END)
        .align(Style.Align.CENTER)
        .padding(theme.space().m())
        .fill(theme.surface().panel())
        .border(theme.size().stroke(), theme.border().subtle())
        .radius(theme.radius().large())
        .child(label(MUTED, "A dialog's footer").grow(1))
        .child(button(SECONDARY, "Cancel", () -> state.say("cancel")))
        .child(button(SECONDARY, "Apply", () -> state.say("apply")))
        .child(button(PRIMARY, "OK", () -> state.say("ok")).key("ok"));
    return col()
        .gap(theme.space().l())
        .child(tools)
        .child(row()
            .gap(theme.space().l())
            .align(Style.Align.START)
            .child(actions.grow(1).basis(0))
            .child(options.grow(1).basis(0)))
        .child(footer);
  }

  // Text fields

  static final List<String> DRIVERS = List.of("PostgreSQL", "MySQL", "SQLite", "SQL Server");
  /** The isolation levels of a transaction, as a connection's options offer them. */
  enum Isolation {
    READ_UNCOMMITTED("Read uncommitted"),
    READ_COMMITTED("Read committed"),
    REPEATABLE_READ("Repeatable read"),
    SERIALIZABLE("Serializable");

    final String title;

    Isolation(String title) {
      this.title = title;
    }
  }

  private static El fields(Showcase state) {
    Theme theme = Theme.CURRENT.get();
    El states = card(
        "States",
        field(
            "Empty",
            textField(state.search, "Search tables", value -> state.search = value)
                .key("empty")),
        field(
            "Filled",
            textField(state.table, "Table", value -> state.table = value).key("filled")),
        field(
            "Selected",
            textField(state.alias, "Alias", value -> state.alias = value).key("selecting")),
        field(
            "Read-only",
            textField("jdbc:postgresql://localhost:5432/shop", "URL", value -> {})
                .readOnly()
                .key("readonly")),
        field("Disabled", textField("postgres", "User", null).key("disabled")),
        field("Placeholder", textField("", "Optional", null).key("empty-disabled")));
    El form = card(
        "New connection",
        field(
            "Name",
            textField(state.name, "Name", value -> state.name = value)
                .key("name")
                .w(280)),
        row()
            .gap(theme.space().m())
            .align(Style.Align.CENTER)
            .child(field(
                "Host",
                textField(state.host, "Name or address", value -> state.host = value)
                    .key("host")
                    .w(176)))
            .child(label("Port"))
            .child(textField(state.port, "5432", value -> state.port = value)
                .key("port")
                .label("Port")
                .maxLength(5)
                .w(64)),
        field(
            "Database",
            textField(state.database, "Name", value -> state.database = value)
                .key("database")
                .w(280)),
        field(
            "User",
            textField(state.user, "Role", value -> state.user = value)
                .key("user")
                .w(280)),
        field(
            "Driver",
            Select.select(DRIVERS, state.driver, state.driverMenu, index -> state.driver = index)
                .key("driver")
                .w(280)),
        field(
            "Isolation",
            Select.select(
                    List.of(Isolation.values()),
                    state.isolation,
                    isolation -> isolation.title,
                    state.isolationMenu,
                    isolation -> state.isolation = isolation)
                .key("isolation")
                .w(280)),
        row()
            .padding(0, 0, 0, 96 + theme.space().m())
            .child(checkbox(state.ssl, "Require SSL", () -> state.ssl = !state.ssl)),
        row()
            .gap(theme.space().m())
            .justify(Style.Content.END)
            .child(button(SECONDARY, "Test connection", () -> state.say("test")))
            .child(button(PRIMARY, "Save", state.user.isBlank() ? null : () -> state.say("save"))));
    El editor = CodeEditor.codeEditor(
            state.app, state.sql, state.sqlEditor, Sql.of(), value -> state.sql = value)
        .key("sql")
        .label("Query")
        .lines(5);
    El code = Card.flush(
        Card.header(
                "Code editor",
                "Line numbers, colors and brackets; a word's second letter, or"
                    + " Control+Space, completes keywords and names; Control+F finds, Control+H replaces.")
            .padding(theme.space().l()),
        CodeEditor.embedded(Completion.complete(
            state.app, editor, state.sqlEditor.caret(), state.sqlCompletion, Sections::complete)));
    El json = Card.flush(
        Card.header("JSON", "Control+Alt+L formats the document, Control+Z takes it back.")
            .padding(theme.space().l()),
        CodeEditor.embedded(CodeEditor.codeEditor(
                state.app, state.json, state.jsonEditor, Json.of(), value -> state.json = value)
            .key("json")
            .label("Document")
            .lines(5)));
    return col()
        .gap(theme.space().l())
        .child(row()
            .gap(theme.space().l())
            .align(Style.Align.START)
            .child(states.w(360).shrink(0))
            .child(form.grow(1)))
        .child(code)
        .child(json);
  }

  private static final List<String> NAMES =
      List.of("orders", "customers", "products", "name", "total", "created_at");

  /** The names and keywords that start with the word, names first. */
  private static List<Completion.Entry> complete(String word) {
    String start = word.toLowerCase(Locale.ROOT);
    List<Completion.Entry> entries = new java.util.ArrayList<>();
    NAMES.stream()
        .filter(name -> name.startsWith(start))
        .forEach(name -> entries.add(new Completion.Entry(name, Symbol.TABLE, "name")));
    Sql.keywords().stream()
        .sorted()
        .filter(keyword ->
            keyword.toLowerCase(Locale.ROOT).startsWith(start) && !keyword.equalsIgnoreCase(word))
        .forEach(keyword -> entries.add(new Completion.Entry(keyword, null, "keyword")));
    return entries;
  }

  // Menus and overlays

  static final String[] SAVED = {
    "monthly revenue",
    "orders without invoice",
    "top customers 2026",
    "slow queries",
    "refunds by country",
    "inventory below threshold"
  };
  private static final String[] EDITED = {
    "today 09:12", "today 08:47", "yesterday", "3 days ago", "last week", "March 2"
  };

  /** The schemas of a database, more than a select should list: a combo box finds them. */
  static final List<String> SCHEMAS = List.of(
      "public",
      "accounting",
      "analytics",
      "archive",
      "audit",
      "billing",
      "catalog",
      "crm",
      "customer_events",
      "customer_notes",
      "geo",
      "inventory",
      "logistics",
      "marketing",
      "orders_archive",
      "partners",
      "payments",
      "pricing",
      "reporting",
      "sales",
      "shipping",
      "staging",
      "support",
      "warehouse");

  static final List<String> HOSTS =
      List.of("localhost", "db.staging.internal", "db-eu-1.example.com", "db-us-1.example.com");

  /** The options of the console, which its dropdown and the command palette both show. */
  static List<Menu.Item> options(Showcase state) {
    return List.of(
        Menu.item("Run selection", () -> state.say("run selection")).shortcut("Ctrl+Enter"),
        Menu.item("Explain plan", () -> state.say("explain")).shortcut("Ctrl+Shift+E"),
        Menu.separator(),
        Menu.item("Auto-commit", () -> state.autocommit = !state.autocommit)
            .checked(state.autocommit),
        Menu.item("Line numbers", () -> state.lineNumbers = !state.lineNumbers)
            .checked(state.lineNumbers),
        Menu.item("Word wrap", () -> state.wordWrap = !state.wordWrap).checked(state.wordWrap),
        Menu.separator(),
        Menu.submenu(
            "Export results",
            List.of(
                Menu.item("CSV", () -> state.say("csv")),
                Menu.item("JSON", () -> state.say("json")),
                Menu.item("SQL inserts", () -> state.say("sql")),
                Menu.item("Markdown table", null))),
        Menu.item("Close console", null).shortcut("Ctrl+W"));
  }

  /** The command palette, with the console's options as its commands, while it is open. */
  static El palette(Showcase state) {
    List<FilterList.Entry> commands = new java.util.ArrayList<>(
        CommandPalette.commands(List.of(Menu.submenu("Console", options(state)))));
    commands.add(FilterList.entry("Rename query…", state::startRename).symbol(Symbol.EDIT));
    List<FilterList.Entry> schemas = new java.util.ArrayList<>();
    for (int index = 0; index < SCHEMAS.size(); index++) {
      int at = index;
      schemas.add(FilterList.entry(SCHEMAS.get(index), () -> state.schema = at)
          .symbol(Symbol.FOLDER)
          .detail("shop"));
    }
    return CommandPalette.palette(
        state.palette,
        commands,
        List.of(new CommandPalette.Source(
            "@", "to choose a schema", FilterList.Entries.of(List.copyOf(schemas)))));
  }

  private static El menus(Showcase state) {
    Theme theme = Theme.CURRENT.get();
    List<Menu.Item> items = options(state);
    El tools = toolbar()
        .child(toolButton(PRIMARY, Symbol.PLAY, "Run", "Run", () -> state.say("run")))
        .child(Menu.dropdown(
            button(SECONDARY, "Options", state.options::toggle).key("options"),
            state.options,
            items))
        .child(el().grow(1))
        .child(button(SECONDARY, "Rename…", state::startRename).key("rename"))
        .child(divider())
        .child(icons(
            iconButton(Symbol.ARROW_CLOCKWISE, "Refresh (F5)", () -> state.say("refresh"))
                .key("refresh"),
            iconButton(Symbol.COPY, "Copy the results as CSV", () -> state.say("copy"))
                .key("copy")));

    El list = col().key("saved").role("list").label("Saved queries");
    for (int index = 0; index < state.queries.length; index++) {
      int chosen = index;
      boolean selected = index == state.saved;
      El item = row()
          .key("saved-" + index)
          .role("listitem")
          .label(state.queries[index])
          .selected(selected)
          .h(theme.grid().rowHeight())
          .padding(0, theme.space().s())
          .gap(theme.space().s())
          .align(Style.Align.CENTER)
          .radius(theme.radius().small())
          .fill(selected ? theme.grid().selectedInactive() : 0)
          .onPress(() -> state.saved = chosen)
          .onSecondaryPress((x, y) -> {
            state.saved = chosen;
            state.context.openAt(x, y);
          })
          .child(Icon.icon(Symbol.DOCUMENT, Icon.Variant.MUTED))
          .child(label(state.queries[index]).grow(1))
          .child(label(MUTED, EDITED[index]));
      if (!selected) {
        item.whenHovered(element -> element.fill(theme.surface().hover()));
      }
      list.child(item);
    }
    List<Menu.Item> actions = List.of(
        Menu.item("Open", () -> state.say("open")).shortcut("Enter"),
        Menu.item("Rename…", state::startRename).shortcut("F2"),
        Menu.item("Duplicate", () -> state.say("duplicate")).shortcut("Ctrl+D"),
        Menu.separator(),
        Menu.item("Copy name", () -> state.say("copy name")).shortcut("Ctrl+C"),
        Menu.item("Share…", null),
        Menu.separator(),
        Menu.item("Delete", () -> state.say("delete")).shortcut("Delete").danger());
    El saved =
        card("Saved queries", Menu.contextMenu(list, state.context, actions)).w(420);
    El hints = col()
        .gap(theme.space().s())
        .grow(1)
        .child(label(MUTED, "The secondary button on a query opens its menu."))
        .child(label(MUTED, "Refresh and Copy show a tooltip after half a second."))
        .child(label(MUTED, "Rename… opens a modal dialog over a scrim."))
        .child(label(state.said.isEmpty() ? "" : "Last action: " + state.said).key("said"));
    El typing = card(
            "Choosing by typing",
            label(
                    MUTED,
                    "A combo box filters its choices as one types; the free kind takes any"
                        + " text and suggests. Control+Shift+P or F1 opens the command palette.")
                .wrapText(),
            field(
                "Schema",
                ComboBox.comboBox(
                        SCHEMAS, state.schema, state.schemaBox, index -> state.schema = index)
                    .key("schema-box")),
            field(
                "Host",
                ComboBox.free(
                        state.recentHost, HOSTS, state.hostBox, value -> state.recentHost = value)
                    .key("host-box")
                    .w(280)),
            field(
                "Database",
                ComboBox.items(
                        PRODUCTS,
                        state.product,
                        Product::item,
                        state.productBox,
                        product -> state.product = product)
                    .key("provider-box")
                    .w(280)),
            row()
                .padding(0, 0, 0, 96 + theme.space().m())
                .child(button(SECONDARY, Symbol.SEARCH, "Command palette…", state.palette::open)
                    .key("open-palette")))
        .w(420);
    return col()
        .gap(theme.space().l())
        .child(tools)
        .child(
            row().gap(theme.space().l()).align(Style.Align.START).child(saved).child(hints))
        .child(typing);
  }

  /** The rename dialog while the state asks for it, else null. */
  static El dialog(Showcase state) {
    Theme theme = Theme.CURRENT.get();
    if (!state.renaming) {
      return null;
    }
    Runnable cancel = () -> state.renaming = false;
    Runnable rename = () -> {
      state.queries[state.saved] = state.rename;
      state.renaming = false;
    };
    El content = col()
        .gap(theme.space().m())
        .child(label(MUTED, "The query keeps its text and its history."))
        .child(textField(state.rename, "Name", value -> state.rename = value)
            .key("new-name")
            .label("Name")
            .w(320));
    return Dialog.dialog(
        "Rename query",
        content,
        List.of(
            button(SECONDARY, "Cancel", cancel),
            button(PRIMARY, "Rename", state.rename.isBlank() ? null : rename)),
        cancel);
  }

  private static Button.Options danger() {
    return Button.options().tone(Button.Tone.DANGER);
  }

  // Data grid

  private static El grid(Showcase state) {
    Theme theme = Theme.CURRENT.get();
    List<Column> base = Samples.orderColumns(state.kept);
    List<Column> columns = new ArrayList<>();
    for (int index = 0; index < base.size(); index++) {
      Column original = base.get(index);
      Column column = new Column(
          original.title(),
          original.width(),
          original.align(),
          original.mono(),
          original.sortable(),
          state.changes.cell(index));
      columns.add(
          switch (original.title()) {
            case "id" -> column;
            case "status" ->
              column.editable().choices(List.of("paid", "pending", "shipped", "refunded"));
            case "shipped_at", "note" -> column.editable().nullable();
            default -> column.editable();
          });
    }
    DataGrid.Options options = DataGrid.options()
        .select(state.selection)
        .sort(state.sort, state::sort)
        .rowNumbers()
        .frozen(1)
        .contextMenu(state.gridMenu, List.of())
        .changes(state.changes)
        .filters(state.gridFilters, state::gridFilters);
    El grid = DataGrid.dataGrid(columns, state.changes.rowCount(), options)
        .key("orders")
        .label("Orders")
        .h(432);
    long cells = DataGrid.cellCount(state.selection.ranges(), columns.size());
    int changed = state.changes.count();
    String status = state.changes.rowCount() + " rows in 23 ms"
        + (cells > 1 ? " · " + cells + " cells selected" : "")
        + (changed > 0 ? " · " + changed + " changes" : "")
        + " · sorted by customer, then amount descending";
    El filters = DataGrid.filterBar(columns, state.gridFilters, state::gridFilters);
    return Card.flush(
        Card.toolbar(
            "Result",
            state.changes.rowCount() + " rows",
            toolButton(
                SECONDARY,
                Symbol.ARROW_UNDO,
                "Revert",
                "Revert",
                changed == 0 ? null : state.changes::clear),
            toolButton(
                PRIMARY,
                Symbol.SAVE,
                changed == 0 ? "Commit" : "Commit (" + changed + ")",
                "Commit",
                changed == 0
                    ? null
                    : () -> {
                      state.changes.clear();
                      state.say("commit");
                    })),
        row()
            .padding(0, theme.space().l(), theme.space().m(), theme.space().l())
            .child(label(
                    MONO,
                    "SELECT o.id, c.name AS customer, c.email, o.amount,"
                        + " o.created_at, o.shipped_at, o.status, o.note FROM orders o JOIN customers c"
                        + " ON …")
                .color(theme.text().muted())
                .ellipsis()
                .minSize(0, 0)),
        Card.filters(filters),
        Divider.divider(),
        grid,
        Card.footer(label(MUTED, status).key("grid-status").ellipsis()));
  }

  // Tree

  /** The schema as the tree asks for it, with an icon per level. */
  record Schema(Theme theme, List<Node> roots) implements Tree.Model<Node> {

    @Override
    public Tree.Children<Node> children(Node node) {
      return node.children == null ? Tree.loading() : Tree.ready(node.children);
    }

    @Override
    public boolean isLeaf(Node node) {
      return node.level == 4;
    }

    @Override
    public String label(Node node) {
      return node.name;
    }

    @Override
    public El icon(Node node) {
      return switch (node.level) {
        case 0 ->
          Icon.icon(
              Symbol.SERVER,
              node.name.equals("localhost")
                  ? Icon.Variant.SUCCESS
                  : node.name.equals("prod-eu") ? Icon.Variant.DANGER : Icon.Variant.MUTED);
        case 1 -> Icon.icon(Symbol.DATABASE, Icon.Variant.MUTED);
        case 2 -> Icon.icon(Symbol.FOLDER, Icon.Variant.MUTED);
        case 3 -> Icon.icon(Symbol.TABLE, Icon.Variant.MUTED);
        default ->
          Icon.icon(node.name.equals("id") ? Symbol.KEY : Symbol.COLUMN_SINGLE, Icon.Variant.MUTED);
      };
    }
  }

  private static El schema(
      Showcase state, Set<Node> open, Node selected, java.util.function.Consumer<Node> onSelect) {
    Theme theme = Theme.CURRENT.get();
    return Tree.tree(
        new Schema(theme, state.catalog),
        new Tree.Options<Node>()
            .open(open::contains, (node, opening) -> state.toggle(open, node, opening))
            .select(selected, onSelect));
  }

  private static El tree(Showcase state) {
    Theme theme = Theme.CURRENT.get();
    El tree = schema(state, state.open, state.node, node -> state.node = node)
        .key("schema")
        .label("Schema")
        .grow(1);
    El explorer = Card.flush(
            Card.toolbar(
                "Database",
                null,
                iconButton(Symbol.ADD, "Add a connection", () -> state.say("add")),
                iconButton(Symbol.ARROW_SYNC, "Synchronize", () -> state.say("sync")),
                iconButton(Symbol.FILTER, "Filter", () -> state.say("filter"))),
            Card.inset(tree))
        .w(300)
        .alignSelf(Style.Align.STRETCH)
        .shrink(0);
    Node table = state.node != null && state.node.level == 3 ? state.node : null;
    El details = col().grow(1).gap(theme.space().m());
    if (table == null) {
      details.child(label(MUTED, "Select a table to see its columns."));
    } else {
      List<Node> tableColumns = table.children;
      List<Column> columns = List.of(
          new Column("column", 140, Align.LEFT, false, row -> tableColumns.get(row).name),
          new Column("type", 140, Align.LEFT, true, row -> tableColumns.get(row).type),
          new Column(
              "nullable",
              80,
              Align.LEFT,
              false,
              row -> tableColumns.get(row).name.endsWith("id") ? "no" : "yes"),
          new Column(
              "default",
              120,
              Align.LEFT,
              true,
              row -> tableColumns.get(row).name.equals("id") ? "nextval(…)" : null));
      details
          .child(Card.flush(
              Card.toolbar(
                  "public." + table.name, tableColumns.size() + " columns · 1,204,311 rows"),
              Divider.divider(),
              DataGrid.dataGrid(
                      columns, tableColumns.size(), DataGrid.options().select(state.columns))
                  .key("columns")
                  .label("Columns")
                  .h(200)))
          .child(label(MUTED, "The grid's selection without the focus: " + "selectedInactive."));
    }
    return row()
        .grow(1)
        .minSize(0, FILL_MIN)
        .gap(theme.space().l())
        .align(Style.Align.START)
        .child(explorer)
        .child(details);
  }

  /** A product a connection can be to, and the versions it supports; a file has no server. */
  record Product(String name, String versions, boolean file) {

    /** The product in the combo box, with a mark of its own before its name. */
    ComboBox.Item item() {
      ComboBox.Item item = ComboBox.item(name).detail(versions);
      return file
          ? item.icon(Symbol.DATABASE)
          : item.leading(
              () -> Avatar.avatar(name, null, Theme.CURRENT.get().size().icon()));
    }
  }

  static final List<Product> PRODUCTS = List.of(
      new Product("PostgreSQL", "9.6 or later", false),
      new Product("MySQL", "8.0", false),
      new Product("MariaDB", "10.5", false),
      new Product("SQL Server", "2019", false),
      new Product("Oracle", "19c", false),
      new Product("SQLite", "a file", true));

  // Tabs and split

  private static final String[][] MONTHS = {
    {"2026-08-01", "1204", "98211.40"}, {"2026-07-01", "1388", "112874.10"},
    {"2026-06-01", "1102", "87300.00"}, {"2026-05-01", "998", "79112.95"},
    {"2026-04-01", "1250", "101446.20"}, {"2026-03-01", "731", "58009.15"}
  };

  /**
   * A database tool's main window in small: one card with the tools along its top, the schema
   * beside the open queries, each an editor over its results, and the status bar along its bottom,
   * the panes divided by hairlines.
   */
  private static El workbench(Showcase state) {
    Theme theme = Theme.CURRENT.get();
    float stroke = theme.size().stroke();
    int line = theme.border().divider();
    El tree = schema(state, state.benchOpen, state.benchNode, node -> state.benchNode = node)
        .key("bench-schema")
        .label("Schema")
        .grow(1);
    // Its title in a band as high as the tab bar beside it, so that the two read as one row.
    El side = col()
        .label("Database")
        .child(Card.toolbar("Database", null)
            .h(theme.size().control() + theme.space().m() + stroke)
            .shrink(0)
            .fill(theme.surface().band())
            .border(0, 0, stroke, 0, line))
        .child(Card.inset(tree));

    El editor = CodeEditor.embedded(CodeEditor.codeEditor(
            state.app, state.query, state.queryEditor, Sql.of(), value -> state.query = value)
        .key("editor")
        .label("Query"));
    // The tab bar's hairline is the editor's top edge already; the accent's still marks focus.
    editor.border(stroke, 0, 0, 0, 0);
    List<Column> columns = List.of(
        new Column("month", 120, Align.LEFT, true, row -> MONTHS[row][0]),
        new Column("orders", 80, Align.RIGHT, true, row -> MONTHS[row][1]),
        new Column("total", 110, Align.RIGHT, true, row -> MONTHS[row][2]));
    El results = col()
        .child(DataGrid.dataGrid(
                columns, MONTHS.length, DataGrid.options().select(state.results).rowNumbers())
            .key("results")
            .label("Results")
            .grow(1)
            .minSize(0, 0))
        .child(Card.footer(label(MUTED, "6 rows in 18 ms")));
    El content = Split.split(
        Direction.COLUMN,
        state.editor,
        Position.share(0.42f),
        position -> state.editor = position,
        editor,
        60,
        results,
        80);
    // The tabs make a card of their own; inside the window's card they are a pane without a frame.
    El queries = Tabs.tabs(
            state.tabs,
            state.tab,
            tab -> state.tab = tab,
            tab -> state.say("close " + tab),
            content)
        .key("queries")
        .radius(0)
        .border(0, 0)
        .shadow();

    El top = row()
        .padding(theme.space().xs(), theme.space().m())
        .gap(theme.space().xs())
        .align(Style.Align.CENTER)
        .border(0, 0, stroke, 0, line)
        .child(iconButton(Symbol.PLAY, "Run", () -> state.say("run")))
        .child(iconButton(Symbol.STOP, "Stop", null))
        .child(divider())
        .child(iconButton(Symbol.ARROW_UNDO, "Undo", () -> state.say("undo")))
        .child(iconButton(Symbol.ARROW_REDO, "Redo", () -> state.say("redo")))
        .child(el().grow(1));
    El status = StatusBar.statusBar(
        List.of(
            StatusBar.group(
                "Connection",
                StatusBar.status(
                    StatusBar.State.OK, "localhost / shop", () -> state.say("connection")),
                StatusBar.text("PostgreSQL 17.2")),
            StatusBar.group(
                "Servers",
                StatusBar.status(StatusBar.State.BUSY, "staging", null),
                StatusBar.status(StatusBar.State.ERROR, "prod-eu", null))),
        List.of(
            StatusBar.group(
                "Caret",
                StatusBar.text("Ln 4, Col 12"),
                StatusBar.item(null, "UTF-8", () -> state.say("encoding"))),
            StatusBar.item(Symbol.HISTORY, "Tx: auto", () -> state.say("transaction"))));
    return Card.flush(
            top,
            Split.split(
                    Direction.ROW,
                    state.sidebar,
                    Position.first(200),
                    position -> state.sidebar = position,
                    side,
                    140,
                    queries,
                    240)
                .key("bench")
                .grow(1),
            status)
        .grow(1)
        .minSize(0, FILL_MIN);
  }

  // Table details

  /** The columns of orders: name, type, and what else a database tool says of each. */
  private static final String[][] COLUMNS = {
    {"id", "bigint", "PK", "NOT NULL", "identity"},
    {"customer_id", "bigint", "FK", "NOT NULL"},
    {"amount", "numeric(12,2)", "NOT NULL"},
    {"status", "order_status", "NOT NULL", "default 'new'"},
    {"created_at", "timestamptz", "NOT NULL", "default now()"},
    {"shipped_at", "timestamptz"},
    {"note", "text"}
  };

  /** The path to the table, each crumb with its level's icon, the schema with its tables. */
  private static List<Breadcrumb.Crumb> crumbs(Showcase state) {
    Symbol[] levels = {Symbol.SERVER, Symbol.DATABASE, Symbol.FOLDER, Symbol.TABLE};
    List<Samples.Node> nodes = Samples.path(state.catalog, state.path.toArray(String[]::new));
    List<Breadcrumb.Crumb> crumbs = new ArrayList<>();
    for (Samples.Node node : nodes) {
      Breadcrumb.Crumb crumb = Breadcrumb.crumb(node.name, () -> state.say("go to " + node.name))
          .icon(levels[node.level]);
      crumbs.add(
          node.level == 2
              ? crumb.children(() -> node.children.stream()
                  .map(table -> Menu.item(table.name, () -> state.say("open " + table.name))
                      .icon(Symbol.TABLE))
                  .toList())
              : crumb);
    }
    return crumbs;
  }

  /**
   * The sheet of a table, as a database tool has it: the path to it and its tools over a card of
   * its properties beside a card of its columns, and a popover that adds a filter.
   */
  private static El details(Showcase state) {
    Theme theme = Theme.CURRENT.get();
    El crumbs = Breadcrumb.breadcrumb(state.crumbs, crumbs(state)).key("path");
    El tools = Toolbar.toolbar(
            state.tools,
            "Table",
            List.of(
                Toolbar.button(Symbol.ARROW_CLOCKWISE, "Refresh", () -> state.say("refresh")),
                Toolbar.split(
                    PRIMARY,
                    Symbol.PLAY,
                    "Run",
                    () -> state.say("run"),
                    state.runMenu,
                    List.of(
                        Menu.item("Run selection", () -> state.say("run selection"))
                            .icon(Symbol.TEXT_T),
                        Menu.item("Explain", () -> state.say("explain")).icon(Symbol.BRANCH))),
                Toolbar.group(
                    Toolbar.toggle(
                        Symbol.TEXT_T,
                        "Wrap values",
                        state.wrapValues,
                        on -> state.wrapValues = on),
                    Toolbar.toggle(
                        Symbol.EYE,
                        "System columns",
                        state.showSystem,
                        on -> state.showSystem = on),
                    Toolbar.button(Symbol.COPY, "Copy DDL", () -> state.say("copy DDL"))),
                Toolbar.spacer(),
                Toolbar.text(Symbol.ARROW_EXPORT_LTR, "Export", () -> state.say("export")),
                Toolbar.text(Symbol.ARROW_IMPORT, "Import", () -> state.say("import"))))
        .key("tools");
    El filterButton =
        Button.button(SECONDARY, Symbol.FILTER, "Filter", state.filter::toggle).key("filter");
    El form = Popover.form(
        filterButton,
        state.filter,
        "Add a filter",
        col()
            .gap(theme.space().m())
            .w(240)
            .children(
                field(
                    "Column",
                    64,
                    textField(state.filterColumn, "Column", value -> state.filterColumn = value)
                        .key("filter-column")
                        .grow(1)),
                field(
                    "Value",
                    64,
                    textField(state.filterValue, "Value", value -> state.filterValue = value)
                        .key("filter-value")
                        .grow(1))),
        List.of(
            button(SECONDARY, "Cancel", state.filter::close),
            button(PRIMARY, "Add", () -> {
                  state.filters.add(state.filterColumn + " = " + state.filterValue);
                  state.filter.close();
                })
                .key("add-filter")));
    El filters =
        row().gap(theme.space().s()).align(Style.Align.CENTER).child(label(MUTED, "Filters"));
    for (String filter : state.filters) {
      filters.child(Badge.badge(
          filter,
          Badge.options()
              .toolbar()
              .icon(Symbol.FILTER)
              .onRemove(() -> state.filters.remove(filter))));
    }
    El head = Card.card(
        row()
            .gap(theme.space().m())
            .align(Style.Align.CENTER)
            .children(
                Icon.icon(Symbol.TABLE, Icon.Variant.MUTED),
                crumbs,
                Badge.badge("1,000,000 rows", Badge.options().toolbar())),
        row().gap(theme.space().s()).align(Style.Align.CENTER).children(tools, form),
        filters);

    El ownerLink = label("shop_admin")
        .role("link")
        .color(theme.prose().link())
        .focusable()
        .focusRing(theme.accent().edge())
        .cursor(Cursor.POINTER)
        .onPress(() -> state.say("owner shop_admin"));
    El properties = Card.card(
            Card.header("Properties", "orders in shop.public"),
            Properties.groups(
                List.of(
                    Properties.group(
                        "General",
                        List.of(
                            Properties.property("Schema", "public"),
                            Properties.property("Owner", ownerLink, "shop_admin"),
                            Properties.property(
                                "Primary key", Badge.badge(Badge.Kind.ACCENT, "id"), "id"),
                            Properties.property("Rows", "1,000,000 (estimated)"),
                            Properties.property(
                                "Comment",
                                "Every order a customer placed, with what it"
                                    + " cost and where it is on its way"))),
                    Properties.group(
                        "Storage",
                        List.of(
                            Properties.property("Size", "128 MB, of which 42 MB indexes"),
                            Properties.property("Tablespace", "pg_default").mono(),
                            Properties.property("Access method", "heap").mono(),
                            Properties.property("Last vacuum", "2026-11-12 03:00:14")))),
                state.app == null ? null : state.app.clipboard(),
                state.copied))
        .grow(1)
        .minSize(320, 0);

    El columns = col();
    for (int index = 0; index < COLUMNS.length; index++) {
      String[] column = COLUMNS[index];
      boolean primary = column.length > 2 && column[2].equals("PK");
      float line = theme.size().control();
      // The keys as quiet badges, PK in a restrained accent; the other constraints as muted text.
      El constraints = row().h(line).gap(theme.space().s()).align(Style.Align.CENTER);
      List<String> rest = new ArrayList<>();
      for (int part = 2; part < column.length; part++) {
        switch (column[part]) {
          case "PK" -> constraints.child(Badge.badge(Badge.Kind.ACCENT, column[part]));
          case "FK" -> constraints.child(Badge.badge(column[part]));
          default -> rest.add(column[part]);
        }
      }
      if (!rest.isEmpty()) {
        constraints.child(label(MUTED, String.join(" · ", rest)));
      }
      // The name and type, then the constraints at the right, or on a line of their own below
      // them where the card is narrow; every line as high as a control.
      El text = row()
          .wrap()
          .grow(1)
          .minSize(0, 0)
          .gap(theme.space().s())
          .align(Style.Align.CENTER)
          .child(row()
              .h(line)
              .gap(theme.space().s())
              .align(Style.Align.CENTER)
              .child(label(MONO, column[0]).w(110).shrink(0))
              .child(label(MONO, column[1]).color(theme.text().muted())))
          .child(el().grow(1))
          .child(constraints);
      text.style().rowGap = Len.ZERO;
      columns.child(row()
          .key("column-" + index)
          .role("row")
          .label(column[0])
          .padding(theme.space().xs(), theme.space().l())
          .gap(theme.space().s())
          .border(
              0,
              0,
              index == COLUMNS.length - 1 ? 0 : theme.size().stroke(),
              0,
              theme.border().subtle())
          .child(row()
              .h(line)
              .align(Style.Align.CENTER)
              .child(Icon.icon(primary ? Symbol.KEY : Symbol.COLUMN_SINGLE, Icon.Variant.MUTED)))
          .child(text));
    }
    El columnsCard = Card.flush(
            Card.toolbar(
                "Columns",
                COLUMNS.length + " columns",
                Button.icon(GHOST, Symbol.ADD, "Add column", () -> state.say("add column"))),
            Divider.divider(),
            columns)
        .grow(1)
        .minSize(320, 0);
    // Side by side where there is room, the columns below the properties where there is not.
    return col()
        .gap(theme.space().m())
        .children(
            head,
            row()
                .wrap()
                .gap(theme.space().m())
                .align(Style.Align.START)
                .children(properties, columnsCard));
  }

  // Progress

  private static El progress(Showcase state) {
    Theme theme = Theme.CURRENT.get();
    double fraction = state.downloaded / Showcase.SIZE;
    Progress.State downloadState = state.failed
        ? Progress.State.ERROR
        : fraction >= 1 ? Progress.State.DONE : Progress.State.NORMAL;
    String detail = state.failed
        ? "Failed at " + mb(state.downloaded)
        : fraction >= 1
            ? "Done · " + mb(Showcase.SIZE)
            : state.downloading
                ? mb(state.downloaded) + " of " + mb(Showcase.SIZE) + " · "
                    + Math.round((Showcase.SIZE - state.downloaded) / 16) + " s left"
                : "Not started";
    El download = card(
        "Download",
        label(MUTED, "A made-up file of 80 MB, 4 MB each quarter of a second."),
        Progress.labeled("orders-2024.csv", fraction, detail, downloadState)
            .key("download")
            .wFull(),
        row()
            .gap(theme.space().m())
            .align(Style.Align.CENTER)
            .child(Button.button(
                    PRIMARY,
                    state.downloading ? "Downloading" : "Download",
                    state::startDownload,
                    Button.options()
                        .icon(Symbol.ARROW_DOWNLOAD)
                        .state(state.downloading ? Button.State.BUSY : Button.State.READY))
                .key("start"))
            .child(button(SECONDARY, "Let it fail", state.downloading ? state::failDownload : null)
                .key("fail")));
    El states = card(
        "States",
        field("Empty", Progress.bar(0).grow(1)),
        field("42 %", Progress.bar(0.42).grow(1)),
        field("Failed", Progress.bar(0.64, Progress.State.ERROR).grow(1)),
        field("Done", Progress.bar(1, Progress.State.DONE).grow(1)),
        field("Paused", Progress.bar(0.42, Progress.State.PAUSED).grow(1)),
        field("Running", Progress.indeterminate().grow(1)));
    El spinners = card(
        "Spinners",
        row()
            .gap(theme.space().l())
            .align(Style.Align.CENTER)
            .child(Progress.spinner())
            .child(Progress.spinner(20))
            .child(Progress.spinner(24))
            .child(row()
                .gap(theme.space().s())
                .align(Style.Align.CENTER)
                .child(Progress.spinner().label("Connecting"))
                .child(label(MUTED, "Connecting to staging…"))),
        row()
            .gap(theme.space().m())
            .align(Style.Align.CENTER)
            .child(Button.button(
                PRIMARY,
                "Running",
                null,
                Button.options().icon(Symbol.PLAY).state(Button.State.BUSY)))
            .child(Button.button(
                SECONDARY,
                "Refreshing",
                null,
                Button.options().icon(Symbol.ARROW_CLOCKWISE).state(Button.State.BUSY))));
    // A query that runs: the line lies over the top of the results, under the toolbar.
    El results = col()
        .grow(1)
        .minSize(0, 0)
        .padding(theme.space().l())
        .child(label(MUTED, "Waiting for the server…"));
    El query = Card.flush(
            Card.toolbar(
                "orders by month.sql",
                "running",
                Button.button(
                    PRIMARY,
                    "Run",
                    null,
                    Button.options().icon(Symbol.PLAY).state(Button.State.BUSY))),
            Divider.divider(),
            el().h(0).child(Progress.line().absolute(0, 0)),
            results)
        .h(160);
    return col()
        .gap(theme.space().l())
        .child(row()
            .gap(theme.space().l())
            .align(Style.Align.START)
            .child(col().gap(theme.space().l()).grow(1).basis(0).child(download).child(states))
            .child(col().gap(theme.space().l()).grow(1).basis(0).child(spinners).child(query)));
  }

  private static String mb(double megabytes) {
    return String.format(Locale.ROOT, "%.1f MB", megabytes);
  }

  // Typography

  private static El type() {
    Theme theme = Theme.CURRENT.get();
    Theme.Type type = theme.type();
    El styles = col()
        .gap(theme.space().m())
        .child(style("title", type.title(), theme.text().primary(), "Connection settings"))
        .child(style("heading", type.heading(), theme.text().primary(), "Connections and schemas"))
        .child(style("strong", type.strong(), theme.text().primary(), "public.orders"))
        .child(style(
            "body",
            type.body(),
            theme.text().primary(),
            "The quick brown fox jumps over the lazy dog. 0123456789"))
        .child(style(
            "body, muted",
            type.body(),
            theme.text().muted(),
            "Hints, captions, values beside their names: 12 rows in 18 ms"))
        .child(style(
            "body, disabled",
            type.body(),
            theme.text().disabled(),
            "A control that cannot be used now"))
        .child(style(
            "small, muted",
            type.small(),
            theme.text().muted(),
            "Beside what it is about: UTF-8 · Ln 12, Col 4 · 3 of 18"))
        .child(style(
            "mono",
            type.mono(),
            theme.text().primary(),
            "SELECT id, amount FROM orders WHERE status = 'paid';"));

    Font sans = type.body().font().get();
    Font mono = type.mono().font().get();
    float size = type.body().size();
    String[] numbers = {"1111.11", "98765.40", "7.00", "10000.01", "449.90", "0.05"};
    El figures = row()
        .gap(theme.space().xxl())
        .child(figures("body", sans, size, numbers))
        .child(figures("body, +tnum", sans, size, numbers, "+tnum"))
        .child(figures("mono", mono, type.mono().size(), numbers))
        .child(col()
            .gap(theme.space().s())
            .grow(1)
            .child(label(MUTED, "Width of each digit, in pixels at scale 1"))
            .child(label(MONO, "body       " + digits(sans, size)))
            .child(label(MONO, "body +tnum " + digits(sans, size, "+tnum")))
            .child(label(MONO, "mono       " + digits(mono, type.mono().size()))));

    String forms = "Il1| O0o rn m {}[]() -> != => ++";
    El letters = col()
        .gap(theme.space().s())
        .child(label(forms))
        .child(label(MONO, forms))
        .child(label(MONO, forms).fontFeatures("+calt"));
    return col()
        .gap(theme.space().xl())
        .child(card("Styles", styles))
        .child(card("Figures in a right-aligned column", figures))
        .child(card("Letterforms: body, mono, mono with ligatures (+calt)", letters));
  }

  private static El style(String name, Theme.TextStyle style, int color, String sample) {
    Theme theme = Theme.CURRENT.get();
    Font font = style.font().get();
    int line = Math.round((font.ascent() + font.descent() + font.lineGap()) * style.size());
    String spec =
        String.format(Locale.ROOT, "%s · %s px · line %d px", font, trim(style.size()), line);
    return row()
        .gap(theme.space().l())
        .align(Style.Align.CENTER)
        .child(col().w(300).shrink(0).child(label(name)).child(label(MUTED, spec)))
        .child(El.text(sample).font(font).fontSize(style.size()).color(color));
  }

  private static El figures(
      String name, Font font, float size, String[] numbers, String... features) {
    Theme theme = Theme.CURRENT.get();
    El column =
        col().gap(theme.space().xs()).align(Style.Align.END).w(120).child(label(MUTED, name));
    for (String number : numbers) {
      column.child(El.text(number)
          .font(font)
          .fontSize(size)
          .color(theme.text().primary())
          .fontFeatures(features));
    }
    return column;
  }

  /** The advance of each digit from 0 to 9 at scale 1, to two decimals. */
  private static String digits(Font font, float size, String... features) {
    StringBuilder widths = new StringBuilder();
    for (char digit = '0'; digit <= '9'; digit++) {
      widths.append(String.format(
          Locale.ROOT,
          "%5.2f",
          font.shape(String.valueOf(digit), size, 1, features).width()));
    }
    return widths.toString();
  }

  private static String trim(float value) {
    return value == Math.rint(value) ? String.valueOf((int) value) : String.valueOf(value);
  }

  // Color

  private record Swatch(String name, int argb, int base) {}

  private static List<List<Swatch>> swatches() {
    Theme theme = Theme.CURRENT.get();
    Theme.Surfaces surfaces = theme.surface();
    Theme.Grid grid = theme.grid();
    return List.of(
        List.of(
            new Swatch("window", surfaces.window(), 0),
            new Swatch("panel", surfaces.panel(), 0),
            new Swatch("raised", surfaces.raised(), 0),
            new Swatch("hover", surfaces.hover(), surfaces.raised()),
            new Swatch("pressed", surfaces.pressed(), surfaces.raised()),
            new Swatch("scrim", surfaces.scrim(), surfaces.window())),
        List.of(
            new Swatch("primary", theme.text().primary(), 0),
            new Swatch("muted", theme.text().muted(), 0),
            new Swatch("disabled", theme.text().disabled(), 0),
            new Swatch("onAccent", theme.text().onAccent(), 0)),
        List.of(
            new Swatch("subtle", theme.border().subtle(), 0),
            new Swatch("strong", theme.border().strong(), 0),
            new Swatch("focus", theme.accent().edge(), 0)),
        List.of(
            new Swatch("color", theme.accent().color(), 0),
            new Swatch("hover", theme.accent().hover(), 0),
            new Swatch("pressed", theme.accent().pressed(), 0),
            new Swatch("selection", theme.accent().selection(), surfaces.raised())),
        List.of(
            new Swatch("danger", theme.status().danger(), 0),
            new Swatch("warning", theme.status().warning(), 0),
            new Swatch("success", theme.status().success(), 0)),
        List.of(
            new Swatch("header", grid.header(), 0),
            new Swatch("row", grid.row(), 0),
            new Swatch("stripe", grid.stripe(), 0),
            new Swatch("selected", grid.selected(), 0),
            new Swatch("selectedInactive", grid.selectedInactive(), 0),
            new Swatch("cursor", grid.cursor(), 0)),
        List.of(
            new Swatch("thumb", theme.scrollbars().thumb(), 0),
            new Swatch("thumbHovered", theme.scrollbars().thumbHovered(), 0),
            new Swatch("thumbDragged", theme.scrollbars().thumbDragged(), 0)),
        List.of(
            new Swatch("closeHover", theme.caption().closeHover(), 0),
            new Swatch("onClose", theme.caption().onClose(), 0),
            new Swatch("closePressed", theme.caption().closePressed(), 0)));
  }

  private static final String[] GROUPS = {
    "surface", "text", "border", "accent", "status", "grid", "scrollbars", "caption"
  };

  private static El color() {
    Theme theme = Theme.CURRENT.get();
    List<List<Swatch>> groups = swatches();
    El sheet = col().gap(theme.space().m());
    int columns = groups.stream().mapToInt(List::size).max().orElse(0);
    for (int index = 0; index < groups.size(); index++) {
      El row = row()
          .gap(theme.space().m())
          .align(Style.Align.START)
          .child(label(MUTED, GROUPS[index]).w(80).shrink(0));
      for (Swatch swatch : groups.get(index)) {
        row.child(swatch(swatch));
      }
      for (int column = groups.get(index).size(); column < columns; column++) {
        row.child(el().grow(1).basis(0)); // empty, so that the columns line up with the longest row
      }
      sheet.child(row);
    }
    return sheet;
  }

  private static El swatch(Swatch swatch) {
    Theme theme = Theme.CURRENT.get();
    El chip = el().h(28)
        .radius(theme.radius().small())
        .clip()
        .border(theme.size().stroke(), theme.border().subtle());
    if (swatch.base() != 0) { // a translucent color, over the surface it lies on
      chip.fill(swatch.base()).child(el().wFull().hFull().fill(swatch.argb()));
    } else {
      chip.fill(swatch.argb());
    }
    String hex = swatch.base() != 0
        ? String.format("#%08X", swatch.argb())
        : String.format("#%06X", swatch.argb() & 0xFFFFFF);
    return col()
        .grow(1)
        .basis(0)
        .minSize(0, 0)
        .gap(theme.space().xs())
        .child(chip)
        .child(label(swatch.name()))
        .child(label(MONO, hex).color(theme.text().muted()));
  }

  // Contrast

  private static El contrast() {
    Theme theme = Theme.CURRENT.get();
    List<Integer> backs = Contrast.backgrounds(theme);
    El matrix = col().gap(theme.space().xs());
    El header = row().gap(theme.space().xs()).child(el().w(150).shrink(0));
    for (String name : Contrast.BACKGROUNDS) {
      header.child(label(MUTED, name).w(CELL).shrink(0).ellipsis().minSize(0, 0));
    }
    matrix.child(header);
    for (Contrast.Foreground front : Contrast.foregrounds(theme)) {
      El line = row()
          .gap(theme.space().xs())
          .align(Style.Align.CENTER)
          .child(col()
              .w(150)
              .shrink(0)
              .child(label(front.name()))
              .child(label(
                  MUTED,
                  front.needs() == 0
                      ? "no minimum"
                      : "needs " + trim((float) front.needs()) + ":1")));
      for (int index = 0; index < backs.size(); index++) {
        line.child(ratio(front.argb(), backs.get(index), front.usedOn(index) ? front.needs() : -1));
      }
      matrix.child(line);
    }
    Theme.Accent accentColors = theme.accent();
    int[] fills = {accentColors.color(), accentColors.hover(), accentColors.pressed()};
    El accent = row()
        .gap(theme.space().xs())
        .align(Style.Align.CENTER)
        .child(col()
            .w(150)
            .shrink(0)
            .child(label("on the accent"))
            .child(label(MUTED, "color, hover, pressed")));
    for (int fill : fills) {
      accent.child(ratio(theme.text().onAccent(), fill, Contrast.TEXT));
    }
    accent
        .child(el().w(theme.space().l()))
        .child(label(MUTED, "primary button's ring").w(150));
    for (int index = 0; index < fills.length; index++) {
      accent.child(ratio(theme.text().primary(), fills[index], index == 0 ? Contrast.MARK : -1));
    }
    El other = row()
        .gap(theme.space().xs())
        .align(Style.Align.CENTER)
        .child(col()
            .w(150)
            .shrink(0)
            .child(label("selected text"))
            .child(label(MUTED, "in a field, on raised")))
        .child(ratio(
            theme.text().primary(),
            Theme.over(theme.surface().raised(), accentColors.selection()),
            Contrast.TEXT))
        .child(el().w(theme.space().l() + CELL * 2 + theme.space().xs() * 2))
        .child(label(MUTED, "cursor on the selection").w(150))
        .child(ratio(theme.grid().cursor(), theme.grid().selected(), Contrast.MARK));
    return col()
        .gap(theme.space().l())
        .child(matrix)
        .child(accent)
        .child(other)
        .child(col()
            .gap(theme.space().xs())
            .child(label(
                    MUTED,
                    "Each cell is the pair itself: the ratio in the foreground on "
                        + "the background. An edge marks a pair the kit draws, in the danger color when"
                        + " it is below its minimum.")
                .wrapText())
            .child(label(
                    MUTED,
                    "Hover is the overlay on raised and on panel; Theme.over and the "
                        + "renderer both mix it in linear light.")
                .wrapText()));
  }

  private static final float CELL = 64;

  /**
   * A cell of the matrix: the ratio, written in the foreground on the background, with an edge
   * where the pair has a minimum ({@code needs} of 0 or more) and in the danger color where it
   * misses it.
   */
  private static El ratio(int front, int back, double needs) {
    Theme theme = Theme.CURRENT.get();
    double ratio = Contrast.ratio(front, back);
    boolean fails = needs > 0 && ratio < needs;
    El cell = row()
        .w(CELL)
        .h(28)
        .shrink(0)
        .center()
        .fill(back)
        .radius(theme.radius().small())
        .child(El.text(String.format(Locale.ROOT, "%.2f", ratio))
            .font(theme.type().mono().font().get())
            .fontSize(theme.type().mono().size())
            .color(front)
            .textBackground(back));
    if (needs >= 0) {
      cell.border(
          fails ? 2 : theme.size().stroke(),
          fails ? theme.status().danger() : theme.border().strong());
    }
    return cell;
  }

  // Icons

  private static El icons() {
    Theme theme = Theme.CURRENT.get();
    Symbol[] symbols = Symbol.values();
    int columns = 5;
    El sheet = row().gap(theme.space().m()).align(Style.Align.START);
    int perColumn = (symbols.length + columns - 1) / columns;
    for (int columnIndex = 0; columnIndex < columns; columnIndex++) {
      El column = col().w(160).shrink(0).gap(theme.space().xs());
      for (int index = columnIndex * perColumn;
          index < Math.min(symbols.length, (columnIndex + 1) * perColumn);
          index++) {
        column.child(row()
            .gap(theme.space().m())
            .align(Style.Align.CENTER)
            .child(Icon.icon(symbols[index]))
            .child(
                label(symbols[index].name().toLowerCase(Locale.ROOT)).ellipsis().minSize(0, 0)));
      }
      sheet.child(column);
    }
    return col()
        .gap(theme.space().m())
        .child(label(
            MUTED,
            "Size " + trim(theme.size().icon()) + ", in the text color, " + symbols.length
                + " symbols"))
        .child(sheet);
  }
}
