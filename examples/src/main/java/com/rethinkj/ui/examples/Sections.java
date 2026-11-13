package com.rethinkj.ui.examples;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.GHOST;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.Checkbox.checkbox;
import static com.rethinkj.ui.kit.Label.Variant.HEADING;
import static com.rethinkj.ui.kit.Label.Variant.MONO;
import static com.rethinkj.ui.kit.Label.Variant.MUTED;
import static com.rethinkj.ui.kit.Label.label;
import static com.rethinkj.ui.kit.TextField.textField;
import static com.rethinkj.ui.kit.Toggle.toggle;

import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.examples.Samples.Node;
import com.rethinkj.ui.examples.Showcase.Section;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.CodeEditor;
import com.rethinkj.ui.kit.Completion;
import com.rethinkj.ui.kit.DataGrid;
import com.rethinkj.ui.kit.DataGrid.Align;
import com.rethinkj.ui.kit.DataGrid.Column;
import com.rethinkj.ui.kit.Dialog;
import com.rethinkj.ui.kit.Divider;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Select;
import com.rethinkj.ui.kit.Split;
import com.rethinkj.ui.kit.Sql;
import com.rethinkj.ui.kit.Split.Direction;
import com.rethinkj.ui.kit.Split.Position;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Tabs;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Tooltip;
import com.rethinkj.ui.kit.Tree;
import com.rethinkj.ui.text.Font;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The sections of the {@link Showcase}, each a view of the app's state in one theme. */
final class Sections {

  private Sections() {}

  /** The section the state chooses, under its title, in an area that scrolls. */
  static El section(Showcase s, Theme t) {
    Section section = s.section;
    El body = switch (section) {
      case BUTTONS -> buttons(s, t);
      case FIELDS -> fields(s, t);
      case MENUS -> menus(s, t);
      case GRID -> grid(s, t);
      case TREE -> tree(s, t);
      case WORKBENCH -> workbench(s, t);
      case PROGRESS -> progress(s, t);
      case TYPE -> type(t);
      case COLOUR -> colour(t);
      case CONTRAST -> contrast(t);
      case ICONS -> icons(t);
    };
    // A key per section, so that what one section leaves does not fade out in the next.
    return col().key(section.name().toLowerCase(Locale.ROOT)).label(section.title).grow(1)
        .minSize(0, 0)
        .scroll().scrollbars(t.scrollbars())
        .padding(t.space().xxl()).gap(t.space().l())
        .child(col().gap(t.space().xs())
            .child(label(t, HEADING, section.title))
            .child(label(t, MUTED, section.description)))
        .child(body);
  }

  /** The list of sections, a tree of leaves with an icon each. */
  record Nav(Theme t) implements Tree.Model<Section> {

    @Override
    public List<Section> roots() {
      return List.of(Section.values());
    }

    @Override
    public List<Section> children(Section node) {
      return List.of();
    }

    @Override
    public boolean isLeaf(Section node) {
      return true;
    }

    @Override
    public String label(Section node) {
      return node.title;
    }

    @Override
    public El icon(Section node) {
      return Icon.icon(t, node.symbol, Icon.Variant.MUTED);
    }
  }

  // Parts the sections share

  /** A card: an area set apart on the window, with a title above its content. */
  private static El card(Theme t, String title, El... content) {
    return Card.card(t, Card.header(t, title, null)).gap(t.space().m()).children(content);
  }

  /** A row of a form: the name in a column of its own, then the control, which it labels. */
  private static El field(Theme t, String name, El control) {
    return row().gap(t.space().m()).align(Style.Align.CENTER)
        .child(label(t, name).w(96).shrink(0))
        .child(control.label(name));
  }

  /** A button with an icon before its text, or with only an icon when the text is null. */
  static El toolButton(Theme t, Button.Variant variant, Symbol symbol, String text,
      String label, Runnable onPress) {
    return text == null ? Button.icon(t, variant, symbol, label, onPress)
        : Button.button(t, variant, symbol, text, onPress);
  }

  private static El iconButton(Theme t, Symbol symbol, String label, Runnable onPress) {
    return Button.icon(t, GHOST, symbol, label, onPress);
  }

  /** A vertical line between groups of a toolbar. */
  private static El divider(Theme t) {
    return el().size(t.size().stroke(), t.size().control() - 2 * t.space().s()).shrink(0)
        .margin(0, t.space().xs(), 0, t.space().xs()).fill(t.border().subtle());
  }

  /** A strip of tools on the panel's surface. */
  private static El toolbar(Theme t) {
    return row().padding(t.space().xs()).gap(t.space().xs()).align(Style.Align.CENTER)
        .fill(t.surface().panel()).border(t.size().stroke(), t.border().subtle())
        .radius(t.radius().medium());
  }

  // Buttons

  private static El buttons(Showcase s, Theme t) {
    El tools = toolbar(t)
        .child(toolButton(t, PRIMARY, Symbol.PLAY, "Run", "Run", () -> s.say("run")).key("run"))
        .child(toolButton(t, SECONDARY, Symbol.STOP, "Stop", "Stop", null))
        .child(divider(t))
        .child(iconButton(t, Symbol.ARROW_CLOCKWISE, "Refresh", () -> s.say("refresh")))
        .child(iconButton(t, Symbol.ARROW_DOWNLOAD, "Export", () -> s.say("export")))
        .child(iconButton(t, Symbol.FILTER, "Filter", () -> s.say("filter")))
        .child(iconButton(t, Symbol.HISTORY, "History", null))
        .child(el().grow(1))
        .child(toolButton(t, GHOST, Symbol.SETTINGS, "Settings", "Settings",
            () -> s.say("settings")));
    El actions = card(t, "Actions",
        row().gap(t.space().m()).align(Style.Align.CENTER)
            .child(button(t, PRIMARY, "Run query", () -> s.say("run")).key("primary"))
            .child(button(t, SECONDARY, "Cancel", () -> s.say("cancel")).key("cancel"))
            .child(button(t, GHOST, "Format", () -> s.say("format")).key("format")),
        label(t, MUTED, "Disabled"),
        row().gap(t.space().m()).align(Style.Align.CENTER)
            .child(button(t, PRIMARY, "Run query", null))
            .child(button(t, SECONDARY, "Cancel", null))
            .child(button(t, GHOST, "Format", null)),
        label(t, MUTED, "With icons"),
        row().gap(t.space().m()).align(Style.Align.CENTER)
            .child(toolButton(t, PRIMARY, Symbol.ADD, "New connection", "New connection",
                () -> s.say("new")))
            .child(toolButton(t, SECONDARY, Symbol.ARROW_IMPORT, "Import", "Import",
                () -> s.say("import")))
            .child(toolButton(t, GHOST, Symbol.COPY, "Copy", "Copy", () -> s.say("copy"))));
    El options = card(t, "Connection options",
        toggle(t, s.autocommit, "Auto-commit", () -> s.autocommit = !s.autocommit)
            .key("autocommit"),
        toggle(t, s.readOnly, "Read-only connection", () -> s.readOnly = !s.readOnly),
        toggle(t, s.nullText, "Show NULL as text", () -> s.nullText = !s.nullText),
        toggle(t, true, "Keep alive (set by the server)", null),
        toggle(t, false, "Single session (set by the server)", null),
        el().h(t.space().xs()),
        checkbox(t, s.ssl, "Require SSL", () -> s.ssl = !s.ssl).key("ssl"),
        checkbox(t, s.savePassword, "Save password", () -> s.savePassword = !s.savePassword),
        checkbox(t, true, "Trust server certificate (managed)", null),
        checkbox(t, false, "Use SSH tunnel (not installed)", null));
    El footer = row().gap(t.space().m()).justify(Style.Content.END).align(Style.Align.CENTER)
        .padding(t.space().m()).fill(t.surface().panel())
        .border(t.size().stroke(), t.border().subtle()).radius(t.radius().large())
        .child(label(t, MUTED, "A dialog's footer").grow(1))
        .child(button(t, SECONDARY, "Cancel", () -> s.say("cancel")))
        .child(button(t, SECONDARY, "Apply", () -> s.say("apply")))
        .child(button(t, PRIMARY, "OK", () -> s.say("ok")).key("ok"));
    return col().gap(t.space().l())
        .child(tools)
        .child(row().gap(t.space().l()).align(Style.Align.START)
            .child(actions.grow(1).basis(0))
            .child(options.grow(1).basis(0)))
        .child(footer);
  }

  // Text fields

  static final List<String> DRIVERS = List.of("PostgreSQL", "MySQL", "SQLite", "SQL Server");
  static final List<String> ISOLATION = List.of(
      "Read uncommitted", "Read committed", "Repeatable read", "Serializable");

  private static El fields(Showcase s, Theme t) {
    El states = card(t, "States",
        field(t, "Empty", textField(t, s.search, "Search tables", v -> s.search = v)
            .key("empty")),
        field(t, "Filled", textField(t, s.table, "Table", v -> s.table = v).key("filled")),
        field(t, "Selected", textField(t, s.alias, "Alias", v -> s.alias = v).key("selecting")),
        field(t, "Read-only", textField(t, "jdbc:postgresql://localhost:5432/shop", "URL",
            v -> {}).readOnly().key("readonly")),
        field(t, "Disabled", textField(t, "postgres", "User", null).key("disabled")),
        field(t, "Placeholder", textField(t, "", "Optional", null).key("empty-disabled")));
    El form = card(t, "New connection",
        field(t, "Name", textField(t, s.name, "Name", v -> s.name = v).key("name").w(280)),
        row().gap(t.space().m()).align(Style.Align.CENTER)
            .child(field(t, "Host", textField(t, s.host, "Name or address", v -> s.host = v)
                .key("host").w(176)))
            .child(label(t, "Port"))
            .child(textField(t, s.port, "5432", v -> s.port = v).key("port").label("Port")
                .maxLength(5).w(64)),
        field(t, "Database", textField(t, s.database, "Name", v -> s.database = v)
            .key("database").w(280)),
        field(t, "User", textField(t, s.user, "Role", v -> s.user = v).key("user").w(280)),
        field(t, "Driver", Select.select(t, DRIVERS, s.driver, s.driverMenu,
            i -> s.driver = i).key("driver")),
        field(t, "Isolation", Select.select(t, ISOLATION, s.isolation, s.isolationMenu,
            i -> s.isolation = i).key("isolation")),
        row().padding(0, 0, 0, 96 + t.space().m())
            .child(checkbox(t, s.ssl, "Require SSL", () -> s.ssl = !s.ssl)),
        row().gap(t.space().m()).justify(Style.Content.END)
            .child(button(t, SECONDARY, "Test connection", () -> s.say("test")))
            .child(button(t, PRIMARY, "Save", s.user.isBlank() ? null : () -> s.say("save"))));
    El editor = CodeEditor.codeEditor(t, s.sql, s.sqlEditor, Sql.of(t), v -> s.sql = v)
        .key("sql").label("Query").lines(5).wFull();
    El code = card(t, "Code editor",
        label(t, MUTED, "Line numbers, colours and brackets; a word's second letter, or"
            + " Control+Space, completes keywords and names."),
        Completion.complete(t, s.app, editor, s.sqlEditor.caret(), s.sqlCompletion,
            Sections::complete));
    return col().gap(t.space().l())
        .child(row().gap(t.space().l()).align(Style.Align.START)
            .child(states.w(360).shrink(0))
            .child(form.grow(1)))
        .child(code);
  }

  private static final List<String> NAMES =
      List.of("orders", "customers", "products", "name", "total", "created_at");

  /** The names and keywords that start with the word, names first. */
  private static List<Completion.Entry> complete(String word) {
    String start = word.toLowerCase(Locale.ROOT);
    List<Completion.Entry> entries = new java.util.ArrayList<>();
    NAMES.stream().filter(n -> n.startsWith(start))
        .forEach(n -> entries.add(new Completion.Entry(n, Symbol.TABLE, "name")));
    Sql.keywords().stream().sorted()
        .filter(k -> k.toLowerCase(Locale.ROOT).startsWith(start) && !k.equalsIgnoreCase(word))
        .forEach(k -> entries.add(new Completion.Entry(k, null, "keyword")));
    return entries;
  }

  // Menus and overlays

  static final String[] SAVED = {
    "monthly revenue", "orders without invoice", "top customers 2026", "slow queries",
    "refunds by country", "inventory below threshold"};
  private static final String[] EDITED = {
    "today 09:12", "today 08:47", "yesterday", "3 days ago", "last week", "March 2"};

  private static El menus(Showcase s, Theme t) {
    List<Menu.Item> items = List.of(
        Menu.item("Run selection", () -> s.say("run selection")).shortcut("Ctrl+Enter"),
        Menu.item("Explain plan", () -> s.say("explain")).shortcut("Ctrl+Shift+E"),
        Menu.separator(),
        Menu.item("Auto-commit", () -> s.autocommit = !s.autocommit).checked(s.autocommit),
        Menu.item("Line numbers", () -> s.lineNumbers = !s.lineNumbers).checked(s.lineNumbers),
        Menu.item("Word wrap", () -> s.wordWrap = !s.wordWrap).checked(s.wordWrap),
        Menu.separator(),
        Menu.submenu("Export results", List.of(
            Menu.item("CSV", () -> s.say("csv")),
            Menu.item("JSON", () -> s.say("json")),
            Menu.item("SQL inserts", () -> s.say("sql")),
            Menu.item("Markdown table", null))),
        Menu.item("Close console", null).shortcut("Ctrl+W"));
    El tools = toolbar(t)
        .child(toolButton(t, PRIMARY, Symbol.PLAY, "Run", "Run", () -> s.say("run")))
        .child(Menu.dropdown(t, button(t, SECONDARY, "Options", s.options::toggle)
            .key("options"), s.options, items))
        .child(el().grow(1))
        .child(button(t, SECONDARY, "Rename…", s::startRename).key("rename"))
        .child(divider(t))
        .child(iconButton(t, Symbol.ARROW_CLOCKWISE, "Refresh (F5)", () -> s.say("refresh"))
            .key("refresh"))
        .child(iconButton(t, Symbol.COPY, "Copy the results as CSV", () -> s.say("copy"))
            .key("copy"));

    El list = col().key("saved").role("list").label("Saved queries");
    for (int i = 0; i < s.queries.length; i++) {
      int index = i;
      boolean selected = i == s.saved;
      El item = row().key("saved-" + i).role("listitem").label(s.queries[i]).selected(selected)
          .h(t.grid().rowHeight()).padding(0, t.space().s()).gap(t.space().s())
          .align(Style.Align.CENTER).radius(t.radius().small())
          .fill(selected ? t.grid().selectedInactive() : 0)
          .onPress(() -> s.saved = index)
          .onSecondaryPress((x, y) -> {
            s.saved = index;
            s.context.openAt(x, y);
          })
          .child(Icon.icon(t, Symbol.DOCUMENT, Icon.Variant.MUTED))
          .child(label(t, s.queries[i]).grow(1))
          .child(label(t, MUTED, EDITED[i]));
      if (!selected) {
        item.whenHovered(e -> e.fill(t.surface().hover()));
      }
      list.child(item);
    }
    List<Menu.Item> actions = List.of(
        Menu.item("Open", () -> s.say("open")).shortcut("Enter"),
        Menu.item("Rename…", s::startRename).shortcut("F2"),
        Menu.item("Duplicate", () -> s.say("duplicate")).shortcut("Ctrl+D"),
        Menu.separator(),
        Menu.item("Copy name", () -> s.say("copy name")).shortcut("Ctrl+C"),
        Menu.item("Share…", null),
        Menu.separator(),
        Menu.item("Delete", () -> s.say("delete")).shortcut("Delete"));
    El saved = card(t, "Saved queries", Menu.contextMenu(t, list, s.context, actions))
        .w(420);
    El hints = col().gap(t.space().s()).grow(1)
        .child(label(t, MUTED, "The secondary button on a query opens its menu."))
        .child(label(t, MUTED, "Refresh and Copy show a tooltip after half a second."))
        .child(label(t, MUTED, "Rename… opens a modal dialog over a scrim."))
        .child(label(t, s.said.isEmpty() ? "" : "Last action: " + s.said).key("said"));
    return col().gap(t.space().l())
        .child(tools)
        .child(row().gap(t.space().l()).align(Style.Align.START).child(saved).child(hints));
  }

  /** The rename dialog while the state asks for it, else null. */
  static El dialog(Showcase s, Theme t) {
    if (!s.renaming) {
      return null;
    }
    Runnable cancel = () -> s.renaming = false;
    Runnable rename = () -> {
      s.queries[s.saved] = s.rename;
      s.renaming = false;
    };
    El content = col().gap(t.space().s())
        .child(label(t, MUTED, "The query keeps its text and its history."))
        .child(textField(t, s.rename, "Name", v -> s.rename = v).key("new-name").label("Name")
            .w(320));
    return Dialog.dialog(t, "Rename query", content, List.of(
        button(t, SECONDARY, "Cancel", cancel),
        button(t, PRIMARY, "Rename", s.rename.isBlank() ? null : rename)), cancel);
  }

  // Data grid

  private static El grid(Showcase s, Theme t) {
    List<Column> columns = Samples.orderColumns(s.rows);
    DataGrid.Options options = DataGrid.options()
        .select(s.selection)
        .sort(s.sort, s::sort)
        .rowNumbers().frozen(1)
        .contextMenu(s.gridMenu, List.of());
    El grid = DataGrid.dataGrid(t, columns, s.rows.size(), options)
        .key("orders").label("Orders").h(432);
    long cells = DataGrid.cellCount(s.selection.ranges(), columns.size());
    String status = s.rows.size() + " rows in 23 ms"
        + (cells > 1 ? " · " + cells + " cells selected" : "")
        + " · sorted by customer, then amount descending";
    return Card.flush(t,
        Card.toolbar(t, "Result", s.rows.size() + " rows",
            toolButton(t, SECONDARY, Symbol.ARROW_DOWNLOAD, "Export", "Export",
                () -> s.say("export")),
            toolButton(t, PRIMARY, Symbol.PLAY, "Run", "Run", () -> s.say("run"))),
        row().padding(0, t.space().l(), t.space().m(), t.space().l())
            .child(label(t, MONO, "SELECT o.id, c.name AS customer, c.email, o.amount,"
                + " o.created_at, o.shipped_at, o.status, o.note FROM orders o JOIN customers c"
                + " ON …").color(t.text().muted()).ellipsis().minSize(0, 0)),
        Divider.divider(t),
        grid,
        Card.footer(t, label(t, MUTED, status).key("grid-status").ellipsis()));
  }

  // Tree

  /** The schema as the tree asks for it, with an icon per level. */
  record Schema(Theme t, List<Node> roots) implements Tree.Model<Node> {

    @Override
    public List<Node> children(Node node) {
      return node.children;
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
        case 0 -> Icon.icon(t, Symbol.SERVER, node.name.equals("localhost")
            ? Icon.Variant.SUCCESS : node.name.equals("prod-eu") ? Icon.Variant.DANGER
            : Icon.Variant.MUTED);
        case 1 -> Icon.icon(t, Symbol.DATABASE, Icon.Variant.MUTED);
        case 2 -> Icon.icon(t, Symbol.FOLDER, Icon.Variant.MUTED);
        case 3 -> Icon.icon(t, Symbol.TABLE, Icon.Variant.MUTED);
        default -> Icon.icon(t, node.name.equals("id") ? Symbol.KEY : Symbol.COLUMN_SINGLE,
            Icon.Variant.MUTED);
      };
    }
  }

  private static El schema(Theme t, Showcase s, Set<Node> open, Node selected,
      java.util.function.Consumer<Node> onSelect) {
    return Tree.tree(t, new Schema(t, s.catalog), open::contains, selected, onSelect,
        (node, opening) -> s.toggle(open, node, opening), null);
  }

  private static El tree(Showcase s, Theme t) {
    El tree = schema(t, s, s.open, s.node, n -> s.node = n).key("schema").label("Schema")
        .grow(1);
    El explorer = Card.flush(t,
        Card.toolbar(t, "Database", null,
            iconButton(t, Symbol.ADD, "Add a connection", () -> s.say("add")),
            iconButton(t, Symbol.ARROW_SYNC, "Synchronise", () -> s.say("sync")),
            iconButton(t, Symbol.FILTER, "Filter", () -> s.say("filter"))),
        Card.inset(t, tree)).w(300).h(588).shrink(0);
    Node table = s.node != null && s.node.level == 3 ? s.node : null;
    El details = col().grow(1).gap(t.space().m());
    if (table == null) {
      details.child(label(t, MUTED, "Select a table to see its columns."));
    } else {
      List<Node> cols = table.children;
      List<Column> columns = List.of(
          new Column("column", 140, Align.LEFT, false, i -> cols.get(i).name),
          new Column("type", 140, Align.LEFT, true, i -> cols.get(i).type),
          new Column("nullable", 80, Align.LEFT, false,
              i -> cols.get(i).name.endsWith("id") ? "no" : "yes"),
          new Column("default", 120, Align.LEFT, true, i -> cols.get(i).name.equals("id")
              ? "nextval(…)" : null));
      details
          .child(Card.flush(t,
              Card.toolbar(t, "public." + table.name,
                  cols.size() + " columns · 1,204,311 rows"),
              Divider.divider(t),
              DataGrid.dataGrid(t, columns, cols.size(),
                  DataGrid.options().select(s.columns)).key("columns").label("Columns").h(200)))
          .child(label(t, MUTED, "The grid's selection without the focus: "
              + "selectedInactive."));
    }
    return row().gap(t.space().l()).align(Style.Align.START).child(explorer).child(details);
  }

  // Tabs and split

  private static final String[] QUERY = {
    "SELECT date_trunc('month', o.created_at) AS month,",
    "       count(*) AS orders,",
    "       sum(o.amount) AS total",
    "FROM orders o",
    "WHERE o.status <> 'refunded'",
    "GROUP BY 1",
    "ORDER BY 1 DESC;"};
  private static final String[][] MONTHS = {
    {"2026-08-01", "1204", "98211.40"}, {"2026-07-01", "1388", "112874.10"},
    {"2026-06-01", "1102", "87300.00"}, {"2026-05-01", "998", "79112.95"},
    {"2026-04-01", "1250", "101446.20"}, {"2026-03-01", "731", "58009.15"}};

  private static El workbench(Showcase s, Theme t) {
    El tree = schema(t, s, s.benchOpen, s.benchNode, n -> s.benchNode = n)
        .key("bench-schema").label("Schema").grow(1);
    El side = Card.flush(t, Card.toolbar(t, "Database", null), Card.inset(t, tree));

    El lines = col().key("editor").label("Query").padding(t.space().m(), t.space().s());
    for (int i = 0; i < QUERY.length; i++) {
      lines.child(row().gap(t.space().l())
          .child(label(t, MONO, String.valueOf(i + 1)).color(t.text().disabled()).w(16))
          .child(label(t, MONO, QUERY[i])));
    }
    List<Column> columns = List.of(
        new Column("month", 120, Align.LEFT, true, i -> MONTHS[i][0]),
        new Column("orders", 80, Align.RIGHT, true, i -> MONTHS[i][1]),
        new Column("total", 110, Align.RIGHT, true, i -> MONTHS[i][2]));
    El results = col()
        .child(DataGrid.dataGrid(t, columns, MONTHS.length,
            DataGrid.options().select(s.results).rowNumbers()).key("results").label("Results")
            .grow(1).minSize(0, 0))
        .child(Card.footer(t, label(t, MUTED, "6 rows in 18 ms"), label(t, MUTED, "Tx: auto"),
            label(t, MUTED, "UTF-8")));
    El content = Split.split(t, Direction.COLUMN, s.editor, Position.share(0.42f),
        p -> s.editor = p, lines, 60, results, 80);
    El queries = Tabs.tabs(t, s.tabs, s.tab, i -> s.tab = i, i -> s.say("close " + i), content)
        .key("queries");

    // A window in small: tools on the page, the schema's card and the queries' card.
    El top = row().padding(t.space().xs(), t.space().m()).gap(t.space().xs())
        .align(Style.Align.CENTER)
        .child(iconButton(t, Symbol.PLAY, "Run", () -> s.say("run")))
        .child(iconButton(t, Symbol.STOP, "Stop", null))
        .child(divider(t))
        .child(iconButton(t, Symbol.ARROW_UNDO, "Undo", () -> s.say("undo")))
        .child(iconButton(t, Symbol.ARROW_REDO, "Redo", () -> s.say("redo")))
        .child(el().grow(1))
        .child(Icon.icon(t, Symbol.PLUG_CONNECTED, Icon.Variant.SUCCESS))
        .child(label(t, MUTED, "localhost / shop · PostgreSQL 17.2"));
    return col().h(588).clip().border(t.size().stroke(), t.border().subtle())
        .radius(t.radius().large()).fill(t.surface().window())
        .child(top)
        .child(Split.cards(t, Direction.ROW, s.sidebar, Position.first(200), p -> s.sidebar = p,
            side.label("Database"), 140, queries, 240).key("bench").grow(1).minSize(0, 0)
            .padding(0, t.space().m(), t.space().m(), t.space().m()));
  }

  // Progress

  private static El progress(Showcase s, Theme t) {
    double fraction = s.downloaded / Showcase.SIZE;
    Progress.State state = s.failed ? Progress.State.ERROR
        : fraction >= 1 ? Progress.State.DONE : Progress.State.NORMAL;
    String detail = s.failed ? "Failed at " + mb(s.downloaded)
        : fraction >= 1 ? "Done · " + mb(Showcase.SIZE)
        : s.downloading ? mb(s.downloaded) + " of " + mb(Showcase.SIZE) + " · "
            + Math.round((Showcase.SIZE - s.downloaded) / 16) + " s left"
        : "Not started";
    El download = card(t, "Download",
        label(t, MUTED, "A made-up file of 80 MB, 4 MB each quarter of a second."),
        Progress.labeled(t, "orders-2024.csv", fraction, detail, state).key("download")
            .wFull(),
        row().gap(t.space().m()).align(Style.Align.CENTER)
            .child(Button.button(t, PRIMARY, Symbol.ARROW_DOWNLOAD,
                s.downloading ? "Downloading" : "Download", s.downloading, s::startDownload)
                .key("start"))
            .child(button(t, SECONDARY, "Let it fail", s.downloading ? s::failDownload : null)
                .key("fail")));
    El states = card(t, "States",
        field(t, "Empty", Progress.bar(t, 0).grow(1)),
        field(t, "42 %", Progress.bar(t, 0.42).grow(1)),
        field(t, "Failed", Progress.bar(t, 0.64, Progress.State.ERROR).grow(1)),
        field(t, "Done", Progress.bar(t, 1, Progress.State.DONE).grow(1)),
        field(t, "Paused", Progress.bar(t, 0.42, Progress.State.PAUSED).grow(1)),
        field(t, "Running", Progress.indeterminate(t).grow(1)));
    El spinners = card(t, "Spinners",
        row().gap(t.space().l()).align(Style.Align.CENTER)
            .child(Progress.spinner(t))
            .child(Progress.spinner(t, 20))
            .child(Progress.spinner(t, 24))
            .child(row().gap(t.space().s()).align(Style.Align.CENTER)
                .child(Progress.spinner(t).label("Connecting"))
                .child(label(t, MUTED, "Connecting to staging…"))),
        row().gap(t.space().m()).align(Style.Align.CENTER)
            .child(Button.button(t, PRIMARY, Symbol.PLAY, "Running", true, () -> {}))
            .child(Button.button(t, SECONDARY, Symbol.ARROW_CLOCKWISE, "Refreshing", true,
                () -> {})));
    // A query that runs: the line lies over the top of the results, under the toolbar.
    El results = col().grow(1).minSize(0, 0).padding(t.space().l())
        .child(label(t, MUTED, "Waiting for the server…"));
    El query = Card.flush(t,
        Card.toolbar(t, "orders by month.sql", "running",
            Button.button(t, PRIMARY, Symbol.PLAY, "Run", true, () -> {})),
        Divider.divider(t),
        el().h(0).child(Progress.line(t).absolute(0, 0)),
        results).h(160);
    return col().gap(t.space().l())
        .child(row().gap(t.space().l()).align(Style.Align.START)
            .child(col().gap(t.space().l()).grow(1).basis(0).child(download).child(states))
            .child(col().gap(t.space().l()).grow(1).basis(0).child(spinners).child(query)));
  }

  private static String mb(double megabytes) {
    return String.format(Locale.ROOT, "%.1f MB", megabytes);
  }

  // Typography

  private static El type(Theme t) {
    Theme.Type type = t.type();
    El styles = col().gap(t.space().m())
        .child(style(t, "heading", type.heading(), t.text().primary(), "Connections and schemas"))
        .child(style(t, "body", type.body(), t.text().primary(),
            "The quick brown fox jumps over the lazy dog. 0123456789"))
        .child(style(t, "body, muted", type.body(), t.text().muted(),
            "Hints, captions, values beside their names: 12 rows in 18 ms"))
        .child(style(t, "body, disabled", type.body(), t.text().disabled(),
            "A control that cannot be used now"))
        .child(style(t, "mono", type.mono(), t.text().primary(),
            "SELECT id, amount FROM orders WHERE status = 'paid';"));

    Font sans = type.body().font().get();
    Font mono = type.mono().font().get();
    float size = type.body().size();
    String[] numbers = {"1111.11", "98765.40", "7.00", "10000.01", "449.90", "0.05"};
    El figures = row().gap(t.space().xxl())
        .child(figures(t, "body", sans, size, numbers))
        .child(figures(t, "body, +tnum", sans, size, numbers, "+tnum"))
        .child(figures(t, "mono", mono, type.mono().size(), numbers))
        .child(col().gap(t.space().s()).grow(1)
            .child(label(t, MUTED, "Width of each digit, in pixels at scale 1"))
            .child(label(t, MONO, "body       " + digits(sans, size)))
            .child(label(t, MONO, "body +tnum " + digits(sans, size, "+tnum")))
            .child(label(t, MONO, "mono       " + digits(mono, type.mono().size()))));

    String forms = "Il1| O0o rn m {}[]() -> != => ++";
    El letters = col().gap(t.space().s())
        .child(label(t, forms))
        .child(label(t, MONO, forms))
        .child(label(t, MONO, forms).fontFeatures("+calt"));
    return col().gap(t.space().xl())
        .child(card(t, "Styles", styles))
        .child(card(t, "Figures in a right-aligned column", figures))
        .child(card(t, "Letterforms: body, mono, mono with ligatures (+calt)", letters));
  }

  private static El style(Theme t, String name, Theme.TextStyle style, int colour,
      String sample) {
    Font font = style.font().get();
    int line = Math.round((font.ascent() + font.descent() + font.lineGap()) * style.size());
    String spec = String.format(Locale.ROOT, "%s · %s px · line %d px", font,
        trim(style.size()), line);
    return row().gap(t.space().l()).align(Style.Align.CENTER)
        .child(col().w(300).shrink(0)
            .child(label(t, name))
            .child(label(t, MUTED, spec)))
        .child(El.text(sample).font(font).fontSize(style.size()).color(colour));
  }

  private static El figures(Theme t, String name, Font font, float size, String[] numbers,
      String... features) {
    El column = col().gap(t.space().xs()).align(Style.Align.END).w(120)
        .child(label(t, MUTED, name));
    for (String n : numbers) {
      column.child(El.text(n).font(font).fontSize(size).color(t.text().primary())
          .fontFeatures(features));
    }
    return column;
  }

  /** The advance of each digit from 0 to 9 at scale 1, to two decimals. */
  private static String digits(Font font, float size, String... features) {
    StringBuilder widths = new StringBuilder();
    for (char d = '0'; d <= '9'; d++) {
      widths.append(String.format(Locale.ROOT, "%5.2f",
          font.shape(String.valueOf(d), size, 1, features).width()));
    }
    return widths.toString();
  }

  private static String trim(float value) {
    return value == Math.rint(value) ? String.valueOf((int) value) : String.valueOf(value);
  }

  // Colour

  private record Swatch(String name, int argb, int base) {}

  private static List<List<Swatch>> swatches(Theme t) {
    Theme.Surfaces s = t.surface();
    Theme.Grid g = t.grid();
    return List.of(
        List.of(new Swatch("window", s.window(), 0), new Swatch("panel", s.panel(), 0),
            new Swatch("raised", s.raised(), 0), new Swatch("hover", s.hover(), s.raised()),
            new Swatch("pressed", s.pressed(), s.raised()),
            new Swatch("scrim", s.scrim(), s.window())),
        List.of(new Swatch("primary", t.text().primary(), 0),
            new Swatch("muted", t.text().muted(), 0),
            new Swatch("disabled", t.text().disabled(), 0),
            new Swatch("onAccent", t.text().onAccent(), 0)),
        List.of(new Swatch("subtle", t.border().subtle(), 0),
            new Swatch("strong", t.border().strong(), 0),
            new Swatch("focus", t.accent().edge(), 0)),
        List.of(new Swatch("color", t.accent().color(), 0),
            new Swatch("hover", t.accent().hover(), 0),
            new Swatch("pressed", t.accent().pressed(), 0),
            new Swatch("selection", t.accent().selection(), s.raised())),
        List.of(new Swatch("danger", t.status().danger(), 0),
            new Swatch("warning", t.status().warning(), 0),
            new Swatch("success", t.status().success(), 0)),
        List.of(new Swatch("header", g.header(), 0), new Swatch("row", g.row(), 0),
            new Swatch("stripe", g.stripe(), 0), new Swatch("selected", g.selected(), 0),
            new Swatch("selectedInactive", g.selectedInactive(), 0),
            new Swatch("cursor", g.cursor(), 0)),
        List.of(new Swatch("thumb", t.scrollbars().thumb(), 0),
            new Swatch("thumbHovered", t.scrollbars().thumbHovered(), 0),
            new Swatch("thumbDragged", t.scrollbars().thumbDragged(), 0)),
        List.of(new Swatch("closeHover", t.caption().closeHover(), 0),
            new Swatch("onClose", t.caption().onClose(), 0)));
  }

  private static final String[] GROUPS = {
    "surface", "text", "border", "accent", "status", "grid", "scrollbars", "caption"};

  private static El colour(Theme t) {
    List<List<Swatch>> groups = swatches(t);
    El sheet = col().gap(t.space().l());
    for (int i = 0; i < groups.size(); i++) {
      El row = row().gap(t.space().m()).align(Style.Align.START)
          .child(label(t, MUTED, GROUPS[i]).w(80).shrink(0));
      for (Swatch swatch : groups.get(i)) {
        row.child(swatch(t, swatch));
      }
      sheet.child(row);
    }
    return sheet;
  }

  private static El swatch(Theme t, Swatch s) {
    El chip = el().h(28).radius(t.radius().small()).clip()
        .border(t.size().stroke(), t.border().subtle());
    if (s.base() != 0) { // a translucent colour, over the surface it lies on
      chip.fill(s.base()).child(el().wFull().hFull().fill(s.argb()));
    } else {
      chip.fill(s.argb());
    }
    String hex = s.base() != 0 ? String.format("#%08X", s.argb())
        : String.format("#%06X", s.argb() & 0xFFFFFF);
    return col().w(118).shrink(0).gap(t.space().xs())
        .child(chip)
        .child(label(t, s.name()))
        .child(label(t, MONO, hex).color(t.text().muted()));
  }

  // Contrast

  private static El contrast(Theme t) {
    List<Integer> backs = Contrast.backgrounds(t);
    El matrix = col().gap(t.space().xs());
    El header = row().gap(t.space().xs()).child(el().w(150).shrink(0));
    for (String name : Contrast.BACKGROUNDS) {
      header.child(label(t, MUTED, name).w(CELL).shrink(0).ellipsis().minSize(0, 0));
    }
    matrix.child(header);
    for (Contrast.Foreground front : Contrast.foregrounds(t)) {
      El line = row().gap(t.space().xs()).align(Style.Align.CENTER)
          .child(col().w(150).shrink(0)
              .child(label(t, front.name()))
              .child(label(t, MUTED, front.needs() == 0 ? "no minimum"
                  : "needs " + trim((float) front.needs()) + ":1")));
      for (int b = 0; b < backs.size(); b++) {
        line.child(ratio(t, front.argb(), backs.get(b),
            front.usedOn(b) ? front.needs() : -1));
      }
      matrix.child(line);
    }
    Theme.Accent a = t.accent();
    int[] fills = {a.color(), a.hover(), a.pressed()};
    El accent = row().gap(t.space().xs()).align(Style.Align.CENTER)
        .child(col().w(150).shrink(0)
            .child(label(t, "on the accent"))
            .child(label(t, MUTED, "color, hover, pressed")));
    for (int fill : fills) {
      accent.child(ratio(t, t.text().onAccent(), fill, Contrast.TEXT));
    }
    accent.child(el().w(t.space().l()))
        .child(label(t, MUTED, "primary button's ring").w(150));
    for (int i = 0; i < fills.length; i++) {
      accent.child(ratio(t, t.text().primary(), fills[i], i == 0 ? Contrast.MARK : -1));
    }
    El other = row().gap(t.space().xs()).align(Style.Align.CENTER)
        .child(col().w(150).shrink(0)
            .child(label(t, "selected text"))
            .child(label(t, MUTED, "in a field, on raised")))
        .child(ratio(t, t.text().primary(), Theme.over(t.surface().raised(), a.selection()),
            Contrast.TEXT))
        .child(el().w(t.space().l() + CELL * 2 + t.space().xs() * 2))
        .child(label(t, MUTED, "cursor on the selection").w(150))
        .child(ratio(t, t.grid().cursor(), t.grid().selected(), Contrast.MARK));
    return col().gap(t.space().l())
        .child(matrix)
        .child(accent)
        .child(other)
        .child(col().gap(t.space().xs())
            .child(label(t, MUTED, "Each cell is the pair itself: the ratio in the foreground on "
                + "the background. An edge marks a pair the kit draws, in the danger colour when"
                + " it is below its minimum."))
            .child(label(t, MUTED, "Hover is the overlay on raised and on panel; Theme.over and the "
                + "renderer both mix it in linear light.")));
  }

  private static final float CELL = 64;

  /**
   * A cell of the matrix: the ratio, written in the foreground on the background, with an edge
   * where the pair has a minimum ({@code needs} of 0 or more) and in the danger colour where it
   * misses it.
   */
  private static El ratio(Theme t, int front, int back, double needs) {
    double ratio = Contrast.ratio(front, back);
    boolean fails = needs > 0 && ratio < needs;
    El cell = row().w(CELL).h(28).shrink(0).center().fill(back).radius(t.radius().small())
        .child(El.text(String.format(Locale.ROOT, "%.2f", ratio))
            .font(t.type().mono().font().get()).fontSize(t.type().mono().size()).color(front)
            .textBackground(back));
    if (needs >= 0) {
      cell.border(fails ? 2 : t.size().stroke(), fails ? t.status().danger() : t.border().strong());
    }
    return cell;
  }

  // Icons

  private static El icons(Theme t) {
    Symbol[] symbols = Symbol.values();
    int columns = 5;
    El sheet = row().gap(t.space().m()).align(Style.Align.START);
    int perColumn = (symbols.length + columns - 1) / columns;
    for (int c = 0; c < columns; c++) {
      El column = col().w(160).shrink(0).gap(t.space().xs());
      for (int i = c * perColumn; i < Math.min(symbols.length, (c + 1) * perColumn); i++) {
        column.child(row().gap(t.space().m()).align(Style.Align.CENTER)
            .child(Icon.icon(t, symbols[i]))
            .child(label(t, symbols[i].name().toLowerCase(Locale.ROOT)).ellipsis()
                .minSize(0, 0)));
      }
      sheet.child(column);
    }
    return col().gap(t.space().m())
        .child(label(t, MUTED, "Size " + trim(t.size().icon()) + ", in the text colour, "
            + symbols.length + " symbols"))
        .child(sheet);
  }
}
