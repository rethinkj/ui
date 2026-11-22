package com.rethinkj.ui.examples.docs;

import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.State.BUSY;
import static com.rethinkj.ui.kit.Button.State.DISABLED;
import static com.rethinkj.ui.kit.Button.State.READY;
import static com.rethinkj.ui.kit.Button.Variant.GHOST;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Image;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.Avatar;
import com.rethinkj.ui.kit.Badge;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Checkbox;
import com.rethinkj.ui.kit.ComboBox;
import com.rethinkj.ui.kit.DatePicker;
import com.rethinkj.ui.kit.FilterList;
import com.rethinkj.ui.kit.Form;
import com.rethinkj.ui.kit.Format;
import com.rethinkj.ui.kit.HuePicker;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.MultiSelect;
import com.rethinkj.ui.kit.NumberField;
import com.rethinkj.ui.kit.Popover;
import com.rethinkj.ui.kit.Radio;
import com.rethinkj.ui.kit.Segmented;
import com.rethinkj.ui.kit.Select;
import com.rethinkj.ui.kit.Slider;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.TextField;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Toggle;
import com.rethinkj.ui.kit.ValueField;
import com.rethinkj.ui.render.Pixels;
import com.rethinkj.ui.text.Font;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** The examples of the pages about buttons, choices, fields and small parts. */
final class Controls {

  int runs;
  boolean running = true;
  double downloaded = 0.42;
  boolean pinned;
  boolean autocommit = true;
  boolean readOnly;
  int indent;
  int mode;
  int driver;
  final Menu.State driverMenu = new Menu.State();
  int schema = 1;
  final ComboBox.State schemaBox = new ComboBox.State();
  String host = "localhost";
  final ComboBox.State hostBox = new ComboBox.State();
  int product;
  final ComboBox.State productBox = new ComboBox.State();
  List<String> schemas = List.of("public", "sales");
  final MultiSelect.State schemasState = new MultiSelect.State();
  String chosenTable = "orders";
  final ComboBox.State chosenTableBox = new ComboBox.State();
  final ComboBox.State clearableTableBox = new ComboBox.State();
  int fetchSize = 4;
  int kind = -1;
  Theme.Hue color = Theme.Hue.TEAL;
  final Popover.State colorPopover = new Popover.State();
  int fontSize = 13;
  String filter = "";
  String table = "";
  String password = "hunter2";
  boolean revealed;
  String note = "Ship by Friday,\nfrom the north warehouse.";
  String sql = "SELECT id,\n\tname\nFROM t;";
  final Menu.State runMenu = new Menu.State();
  final Menu.State exportMenu = new Menu.State();
  final Image logo = logoImage();
  // docs:start value-state
  BigDecimal amount = new BigDecimal("12.50");
  final ValueField.Draft amountDraft = new ValueField.Draft();
  LocalDate due; // null: no date yet
  final ValueField.Draft dueDraft = new ValueField.Draft();
  // docs:end value-state
  // docs:start date-picker-state
  LocalDate release = LocalDate.of(2026, 11, 28);
  final DatePicker.State releasePicker = new DatePicker.State();
  LocalDateTime backup = LocalDateTime.of(2026, 11, 28, 14, 30);
  final DatePicker.State backupPicker = new DatePicker.State();
  // docs:end date-picker-state

  void run() {
    runs++;
  }

  void cancel() {
    runs = 0;
  }

  void refresh() {
    runs++;
  }

  void start() {
    running = true;
  }

  void pinned(Boolean on) {
    pinned = on;
  }

  void runSelection() {
    runs++;
  }

  void explainPlan() {
    runs++;
  }

  static El buttons(Controls state) {
    Theme theme = Theme.CURRENT.get();
    // docs:start button
    El run = Button.button(PRIMARY, Symbol.PLAY, "Run", state::run);
    El cancel = Button.button(SECONDARY, "Cancel", state::cancel);
    El format = Button.button(GHOST, "Format", null); // null: disabled
    El refresh = Button.icon(GHOST, Symbol.ARROW_CLOCKWISE, "Refresh", state::refresh);
    El busy = Button.button(
        PRIMARY, "Running", state::start, Button.options().state(state.running ? BUSY : READY));
    // docs:end button
    // docs:start button-toggle
    El pin = Button.toggle(Symbol.PIN, "Pin", state.pinned, state::pinned);
    El split = Button.split(
        PRIMARY,
        Symbol.PLAY,
        "Run",
        state::run,
        state.runMenu,
        List.of(
            Menu.item("Run selection", state::runSelection),
            Menu.item("Explain plan", state::explainPlan)));
    // docs:end button-toggle
    return Frame.of(
        row().gap(theme.space().s()).children(run, cancel, format, refresh, busy),
        row().gap(theme.space().s()).children(pin, split));
  }

  void drop() {
    runs = 0;
  }

  void discard() {
    runs = 0;
  }

  void deleteRow() {
    runs--;
  }

  void save() {
    runs = 0;
  }

  void exportCsv() {
    runs++;
  }

  void exportJson() {
    runs++;
  }

  static El buttonOptions(Controls state) {
    Theme theme = Theme.CURRENT.get();
    // docs:start button-options
    // A red confirmation, a red secondary action and a quiet red one for a toolbar.
    Button.Options danger = Button.options().tone(Button.Tone.DANGER);
    El drop = Button.button(PRIMARY, "Drop table", state::drop, danger);
    El discard = Button.button(SECONDARY, "Discard", state::discard, danger);
    El delete = Button.icon(GHOST, Symbol.DELETE, "Delete row", state::deleteRow, danger);
    // Off while nothing changed, with its handler kept: as a null handler, Tab passes it by.
    El save = Button.button(
        SECONDARY, "Save", state::save, Button.options().state(state.runs > 0 ? READY : DISABLED));
    // Busy: a spinner in place of the icon; it takes no press and keeps the focus.
    El refresh = Button.icon(
        GHOST,
        Symbol.ARROW_CLOCKWISE,
        "Refresh",
        state::start,
        Button.options().state(state.running ? BUSY : READY));
    // Busy with a known length: a ring fills in place of the icon as the download goes on.
    El download = Button.icon(
        GHOST,
        Symbol.ARROW_DOWNLOAD,
        "Download update",
        state::start,
        Button.options().state(state.running ? BUSY : READY).progress(state.downloaded));
    El run = Button.split(
        PRIMARY,
        "Run",
        state::start,
        state.runMenu,
        List.of(Menu.item("Run selection", state::runSelection), Menu.item("Cancel", () -> {})),
        Button.options().icon(Symbol.PLAY).state(state.running ? BUSY : READY));
    // Busy with its verb, as wide as either text; its shortcut shows in its tooltip.
    El commit = Button.button(
        SECONDARY,
        "Commit",
        state::save,
        Button.options()
            .state(state.running ? BUSY : READY)
            .busyText("Committing…")
            .shortcut(Key.CONTROL | Key.SHIFT, "C"));
    // A button that opens a menu, and a large one for the one action of an empty view.
    El export = Button.menu(
        SECONDARY,
        "Export",
        state.exportMenu,
        List.of(Menu.item("CSV", state::exportCsv), Menu.item("JSON", state::exportJson)));
    El connect = Button.button(
        PRIMARY,
        "New connection",
        state::save,
        Button.options().icon(Symbol.ADD).size(Button.Size.LARGE));
    // docs:end button-options
    return Frame.of(
        row().gap(theme.space().s()).children(drop, discard, delete),
        row().gap(theme.space().s()).children(save, refresh, download, run),
        row().gap(theme.space().s()).align(Style.Align.CENTER).children(commit, export, connect));
  }

  void toggleAutocommit() {
    autocommit = !autocommit;
  }

  void toggleReadOnly() {
    readOnly = !readOnly;
  }

  static El toggles(Controls state) {
    // docs:start toggle
    El autocommit = Toggle.toggle(state.autocommit, "Autocommit", state::toggleAutocommit);
    // docs:end toggle
    // docs:start checkbox
    El readOnly = Checkbox.checkbox(state.readOnly, "Read only", state::toggleReadOnly);
    // docs:end checkbox
    return Frame.of(autocommit, readOnly);
  }

  void indent(int value) {
    indent = value;
  }

  void mode(int value) {
    mode = value;
  }

  void driver(int value) {
    driver = value;
  }

  void fetchSize(int value) {
    fetchSize = value;
  }

  void fontSize(int value) {
    fontSize = value;
  }

  static El choices(Controls state) {
    // docs:start radio
    El indent = Radio.radioGroup(List.of("Spaces", "Tabs"), state.indent, state::indent)
        .label("Indent with");
    // docs:end radio
    // docs:start segmented
    El mode =
        Segmented.segmented(List.of("Rows", "Cells"), state.mode, state::mode).label("Select");
    // docs:end segmented
    // docs:start select
    El driver = Select.select(
            List.of("PostgreSQL", "MySQL", "SQLite"), state.driver, state.driverMenu, state::driver)
        .label("Driver");
    // docs:end select
    // docs:start number-field
    El fetch = NumberField.numberField("Fetch size", state.fetchSize, 1, 16, 1, state::fetchSize);
    // docs:end number-field
    // docs:start slider
    El size = Slider.slider(state.fontSize, 11, 16, 1, state::fontSize).label("Font size");
    // docs:end slider
    return Frame.of(indent, mode, driver, fetch, size);
  }

  void kind(int value) {
    kind = value;
  }

  void color(Theme.Hue value) {
    color = value;
  }

  static El swatches(Controls state) {
    // docs:start swatches
    El kind = Radio.swatches(
            List.of(
                Radio.option("Table", () -> Icon.icon(Symbol.TABLE)),
                Radio.option("View", () -> Icon.icon(Symbol.TABLE_SIMPLE)),
                Radio.option("Folder", () -> Icon.icon(Symbol.FOLDER))),
            state.kind, // -1: none chosen yet
            state::kind)
        .label("Kind");
    // docs:end swatches
    // docs:start hue-picker
    El color = HuePicker.huePicker(state.color, "None", state::color).label("Color");
    // docs:end hue-picker
    return Frame.of(kind, color);
  }

  static El huePickerButton(Controls state) {
    // docs:start hue-picker-button
    El form = Form.form(
        Form.Labels.ABOVE,
        Form.row(
            Form.field("Host", TextField.textField(state.host, "localhost", state::host))
                .grow(),
            Form.field(
                "Color", HuePicker.button(state.color, "None", state.colorPopover, state::color))));
    // docs:end hue-picker-button
    return Frame.of(form.w(320));
  }

  // docs:start choice-types
  enum Isolation {
    READ_COMMITTED("Read committed"),
    REPEATABLE_READ("Repeatable read"),
    SERIALIZABLE("Serializable");

    final String title;

    Isolation(String title) {
      this.title = title;
    }
  }

  record Schema(String name, String owner) {}
  // docs:end choice-types

  Isolation isolation = Isolation.READ_COMMITTED;
  final Menu.State isolationMenu = new Menu.State();
  Isolation level = Isolation.SERIALIZABLE;
  Isolation shown;
  Schema schemaValue;
  final ComboBox.State schemaValueBox = new ComboBox.State();
  Schema owned;
  final ComboBox.State ownedBox = new ComboBox.State();

  void isolation(Isolation value) {
    isolation = value;
  }

  void level(Isolation value) {
    level = value;
  }

  void shown(Isolation value) {
    shown = value;
  }

  void schemaValue(Schema value) {
    schemaValue = value;
  }

  void owned(Schema value) {
    owned = value;
  }

  static El typed(Controls state) {
    List<Schema> schemas = List.of(
        new Schema("accounting", "finance"),
        new Schema("public", "postgres"),
        new Schema("sales", "shop"));
    // docs:start select-typed
    El isolation = Select.select(
            List.of(Isolation.values()),
            state.isolation,
            choice -> choice.title,
            state.isolationMenu,
            state::isolation)
        .label("Isolation");
    // docs:end select-typed
    // docs:start radio-typed
    El level = Radio.radioGroup(
            List.of(Isolation.values()), state.level, choice -> choice.title, state::level)
        .label("Level");
    // docs:end radio-typed
    // docs:start segmented-typed
    El shown = Segmented.segmented( // null: none chosen yet
            List.of(Isolation.values()), state.shown, choice -> choice.title, state::shown)
        .label("Show");
    // docs:end segmented-typed
    // docs:start combo-box-typed
    El schema = ComboBox.comboBox(
            schemas, state.schemaValue, Schema::name, state.schemaValueBox, state::schemaValue)
        .label("Schema");
    // docs:end combo-box-typed
    // docs:start combo-items-typed
    El owned = ComboBox.items(
            schemas,
            state.owned,
            option -> ComboBox.item(option.name()).icon(Symbol.DATABASE).detail(option.owner()),
            state.ownedBox,
            state::owned)
        .label("Owned schema");
    // docs:end combo-items-typed
    return Frame.of(isolation, level, shown, schema, owned);
  }

  void schema(int value) {
    schema = value;
  }

  void host(String value) {
    host = value;
  }

  void product(int value) {
    product = value;
  }

  static El combos(Controls state) {
    // docs:start combo-box
    List<String> schemas = List.of("accounting", "public", "sales", "staging");
    El schema =
        ComboBox.comboBox(schemas, state.schema, state.schemaBox, state::schema).label("Schema");
    El host = ComboBox.free(
            state.host, List.of("localhost", "db.staging.internal"), state.hostBox, state::host)
        .label("Host");
    // docs:end combo-box
    // docs:start combo-items
    List<ComboBox.Item> products = List.of(
        ComboBox.item("PostgreSQL").icon(Symbol.DATABASE).detail("9.6 or later"),
        ComboBox.item("SQLite").icon(Symbol.DOCUMENT).detail("a file"));
    El product = ComboBox.items(products, state.product, state.productBox, state::product)
        .label("Database");
    // docs:end combo-items
    return Frame.of(schema, host, product);
  }

  void schemas(List<String> value) {
    schemas = value;
  }

  static El multiSelect(Controls state) {
    // docs:start multi-select
    List<String> choices =
        List.of("accounting", "analytics", "billing", "inventory", "public", "sales", "staging");
    El schemas = MultiSelect.multiSelect(choices, state.schemas, state.schemasState, state::schemas)
        .label("Schemas")
        .w(240);
    // docs:end multi-select
    return Frame.of(schemas);
  }

  void selectTable(String name) {
    chosenTable = name;
  }

  void clearTable() {
    chosenTable = null;
  }

  List<FilterList.Entry> findTables(String query) {
    List<FilterList.Entry> found = new ArrayList<>();
    for (String name : List.of("orders", "order_items", "customers")) {
      if (name.contains(query)) {
        found.add(FilterList.entry(name, () -> selectTable(name))
            .group("Tables")
            .checked(name.equals(chosenTable)));
      }
    }
    if ("archived_orders".contains(query)) {
      found.add(FilterList.entry("archived_orders", null).group("Archive"));
    }
    return found;
  }

  static El comboEntries(Controls state) {
    Theme theme = Theme.CURRENT.get();
    // docs:start combo-entries
    El table = ComboBox.comboBox(
            FilterList.Entries.by(state::findTables),
            state.chosenTable,
            state.chosenTableBox,
            state::clearTable)
        .label("Table");
    // docs:end combo-entries
    El closed = ComboBox.comboBox(
            FilterList.Entries.by(state::findTables),
            state.chosenTable,
            state.clearableTableBox,
            state::clearTable)
        .label("Selected table");
    return Frame.of(row().gap(theme.space().m()).children(table, closed));
  }

  void filter(String value) {
    filter = value;
  }

  void table(String value) {
    table = value;
  }

  void password(String value) {
    password = value;
  }

  void toggleRevealed() {
    revealed = !revealed;
  }

  void note(String value) {
    note = value;
  }

  void sql(String value) {
    sql = value;
  }

  static El fields(Controls state) {
    // docs:start text-field
    El filter = TextField.searchField(state.filter, "Filter tables", state::filter);
    El name = TextField.textField(state.table, "Table name", state::table)
        .key("table")
        .maxLength(63);
    El invalid = TextField.invalid(TextField.textField("2 orders", "Name", value -> {}));
    // docs:end text-field
    // docs:start password-field
    El password = TextField.revealable(
        TextField.passwordField(state.password, "Password", state::password).key("password"),
        state.revealed,
        state::toggleRevealed);
    // docs:end password-field
    // docs:start text-area
    El note = TextField.textArea(state.note, "Note", state::note).lines(3).w(280);
    El code = TextField.codeArea(state.sql, "SQL", state::sql).lines(3).w(280);
    // docs:end text-area
    return Frame.of(filter, name, invalid, password, note, code);
  }

  void amount(BigDecimal value) {
    amount = value;
  }

  void due(LocalDate value) {
    due = value;
  }

  void release(LocalDate value) {
    release = value;
  }

  void backup(LocalDateTime value) {
    backup = value;
  }

  static El datePickers(Controls state) {
    // docs:start date-picker
    El release = Form.form(
        Form.Labels.ABOVE,
        Form.field(
            "Release date",
            DatePicker.datePicker(
                Format.DATE.optional(), state.release, state.releasePicker, state::release)),
        Form.field(
            "Next backup",
            DatePicker.dateTimePicker(
                Format.DATE_TIME, state.backup, state.backupPicker, state::backup)));
    // docs:end date-picker
    return Frame.of(release);
  }

  void saveForm() {
    runs++;
  }

  static El values(Controls state) {
    // docs:start value-field
    El form = Form.form(
        Form.Labels.BESIDE,
        Form.field(
                "Amount",
                ValueField.valueField(
                        Format.DECIMAL, state.amount, state.amountDraft, state::amount)
                    .key("amount")
                    .w(120))
            .required(),
        Form.field(
                "Due",
                ValueField.valueField(
                        Format.DATE.optional(), state.due, "2026-11-13", state.dueDraft, state::due)
                    .key("due")
                    .w(120))
            .help("Empty for none"));
    El save = Button.button(
        PRIMARY,
        "Save",
        // A text that is no value leaves the last valid one in the state: save only without one.
        state.amountDraft.error() == null && state.dueDraft.error() == null
            ? state::saveForm
            : null);
    // docs:end value-field
    return Frame.of(form, save);
  }

  void removeFilter() {
    filter = "";
  }

  static El parts(Controls state) {
    Theme theme = Theme.CURRENT.get();
    // docs:start label
    El title = Label.label(Label.Variant.HEADING, "Orders");
    El hint = Label.label(Label.Variant.MUTED, "1 000 000 rows");
    El id = Label.label(Label.Variant.MONO, "a3f9c2e1");
    // docs:end label
    // docs:start icon
    El table = Icon.icon(Symbol.TABLE);
    El key = Icon.icon(Symbol.KEY, Icon.Variant.WARNING);
    El error = Icon.icon(Symbol.ERROR_CIRCLE, Icon.Variant.DANGER, 20);
    // docs:end icon
    // docs:start badge
    El type = Badge.badge("bigint");
    El primary = Badge.badge(Badge.Kind.ACCENT, "PK");
    El filter = Badge.badge(
        "status = paid", Badge.options().kind(Badge.Kind.ACCENT).onRemove(state::removeFilter));
    // docs:end badge
    // docs:start avatar
    El server = Avatar.avatar("prod-eu");
    El local = Avatar.avatar("localhost", Symbol.SERVER, 24);
    // docs:end avatar
    return Frame.of(
        row().gap(theme.space().m()).children(title, hint, id),
        row().gap(theme.space().m()).children(table, key, error),
        row().gap(theme.space().s()).children(type, primary, filter),
        row().gap(theme.space().s()).children(server, local));
  }

  static El ownIcons(Controls state) {
    Theme theme = Theme.CURRENT.get();
    // docs:start icon-own
    Icon stop = Icon.glyph(Font.sans(), 0x25A0, "stop");
    Icon brand = Icon.image(state.logo, "brand");
    El stopButton =
        Button.icon(GHOST, stop, "Stop", state::cancel, Button.options().tone(Button.Tone.DANGER));
    El stopOff = Button.icon(GHOST, stop, "Stop", null, Button.options().tone(Button.Tone.DANGER));
    El brandButton = Button.icon(GHOST, brand, "Workspace", state::run);
    El brandOff = Button.icon(GHOST, brand, "Workspace", null);
    // docs:end icon-own
    return Frame.of(
        row().gap(theme.space().s()).children(stopButton, stopOff, brandButton, brandOff));
  }

  private static Image logoImage() {
    int size = 32;
    byte[] rgba = new byte[size * size * 4];
    for (int y = 0; y < size; y++) {
      for (int x = 0; x < size; x++) {
        int dx = Math.abs(x * 2 + 1 - size);
        int dy = Math.abs(y * 2 + 1 - size);
        if (dx + dy <= size - 4) {
          int at = (y * size + x) * 4;
          boolean top = y < size / 2;
          rgba[at] = (byte) (top ? 0x38 : 0x25);
          rgba[at + 1] = (byte) (top ? 0xBD : 0x63);
          rgba[at + 2] = (byte) (top ? 0xF8 : 0xEB);
          rgba[at + 3] = (byte) 0xFF;
        }
      }
    }
    return Image.of(new Pixels(size, size, rgba), 2);
  }
}
