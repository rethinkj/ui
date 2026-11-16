package com.rethinkj.ui.examples.docs;

import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.GHOST;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;

import com.rethinkj.ui.El;
import com.rethinkj.ui.kit.Avatar;
import com.rethinkj.ui.kit.Badge;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Checkbox;
import com.rethinkj.ui.kit.ComboBox;
import com.rethinkj.ui.kit.Form;
import com.rethinkj.ui.kit.Format;
import com.rethinkj.ui.kit.HuePicker;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.NumberField;
import com.rethinkj.ui.kit.Radio;
import com.rethinkj.ui.kit.Segmented;
import com.rethinkj.ui.kit.Select;
import com.rethinkj.ui.kit.Slider;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.TextField;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Toggle;
import com.rethinkj.ui.kit.ValueField;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** The examples of the pages about buttons, choices, fields and small parts. */
final class Controls {

  Theme theme = Theme.DARK;
  int runs;
  boolean running = true;
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
  int fetchSize = 4;
  int kind = -1;
  Theme.Hue color = Theme.Hue.TEAL;
  int fontSize = 13;
  String filter = "";
  String table = "";
  String password = "hunter2";
  boolean revealed;
  String note = "Ship by Friday,\nfrom the north warehouse.";
  String sql = "SELECT id,\n\tname\nFROM t;";
  final Menu.State runMenu = new Menu.State();
  // docs:start value-state
  BigDecimal amount = new BigDecimal("12.50");
  final ValueField.Draft amountDraft = new ValueField.Draft();
  LocalDate due; // null: no date yet
  final ValueField.Draft dueDraft = new ValueField.Draft();
  // docs:end value-state

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
    Theme theme = state.theme;
    // docs:start button
    El run = Button.button(theme, PRIMARY, Symbol.PLAY, "Run", state::run);
    El cancel = Button.button(theme, SECONDARY, "Cancel", state::cancel);
    El format = Button.button(theme, GHOST, "Format", null); // null: disabled
    El refresh = Button.icon(theme, GHOST, Symbol.ARROW_CLOCKWISE, "Refresh", state::refresh);
    El busy = Button.button(theme, PRIMARY, null, "Running", state.running, state::start);
    // docs:end button
    // docs:start button-toggle
    El pin = Button.toggle(theme, Symbol.PIN, "Pin", state.pinned, state::pinned);
    El split = Button.split(
        theme,
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
        theme,
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

  static El buttonOptions(Controls state) {
    Theme theme = state.theme;
    // docs:start button-options
    // A red confirmation, a red secondary action and a quiet red one for a toolbar.
    Button.Options danger = Button.options().tone(Button.Tone.DANGER);
    El drop = Button.button(theme, PRIMARY, "Drop table", state::drop, danger);
    El discard = Button.button(theme, SECONDARY, "Discard", state::discard, danger);
    El delete = Button.icon(theme, GHOST, Symbol.DELETE, "Delete row", state::deleteRow, danger);
    // Off while nothing changed, with its handler kept: as a null handler, Tab passes it by.
    El save = Button.button(
        theme, SECONDARY, "Save", state::save, Button.options().enabled(state.runs > 0));
    // Busy: a spinner in place of the icon; it takes no press and keeps the focus.
    El refresh = Button.icon(
        theme,
        GHOST,
        Symbol.ARROW_CLOCKWISE,
        "Refresh",
        state::start,
        Button.options().busy(state.running));
    El run = Button.split(
        theme,
        PRIMARY,
        "Run",
        state::start,
        state.runMenu,
        List.of(Menu.item("Run selection", state::runSelection), Menu.item("Cancel", () -> {})),
        Button.options().icon(Symbol.PLAY).busy(state.running));
    // docs:end button-options
    return Frame.of(
        theme,
        row().gap(theme.space().s()).children(drop, discard, delete),
        row().gap(theme.space().s()).children(save, refresh, run));
  }

  void toggleAutocommit() {
    autocommit = !autocommit;
  }

  void toggleReadOnly() {
    readOnly = !readOnly;
  }

  static El toggles(Controls state) {
    Theme theme = state.theme;
    // docs:start toggle
    El autocommit = Toggle.toggle(theme, state.autocommit, "Autocommit", state::toggleAutocommit);
    // docs:end toggle
    // docs:start checkbox
    El readOnly = Checkbox.checkbox(theme, state.readOnly, "Read only", state::toggleReadOnly);
    // docs:end checkbox
    return Frame.of(theme, autocommit, readOnly);
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
    Theme theme = state.theme;
    // docs:start radio
    El indent = Radio.radioGroup(theme, List.of("Spaces", "Tabs"), state.indent, state::indent)
        .label("Indent with");
    // docs:end radio
    // docs:start segmented
    El mode = Segmented.segmented(theme, List.of("Rows", "Cells"), state.mode, state::mode)
        .label("Select");
    // docs:end segmented
    // docs:start select
    El driver = Select.select(
            theme,
            List.of("PostgreSQL", "MySQL", "SQLite"),
            state.driver,
            state.driverMenu,
            state::driver)
        .label("Driver");
    // docs:end select
    // docs:start number-field
    El fetch =
        NumberField.numberField(theme, "Fetch size", state.fetchSize, 1, 16, 1, state::fetchSize);
    // docs:end number-field
    // docs:start slider
    El size = Slider.slider(theme, state.fontSize, 11, 16, 1, state::fontSize).label("Font size");
    // docs:end slider
    return Frame.of(theme, indent, mode, driver, fetch, size);
  }

  void kind(int value) {
    kind = value;
  }

  void color(Theme.Hue value) {
    color = value;
  }

  static El swatches(Controls state) {
    Theme theme = state.theme;
    // docs:start swatches
    El kind = Radio.swatches(
            theme,
            List.of(
                Radio.option("Table", itemTheme -> Icon.icon(itemTheme, Symbol.TABLE)),
                Radio.option("View", itemTheme -> Icon.icon(itemTheme, Symbol.TABLE_SIMPLE)),
                Radio.option("Folder", itemTheme -> Icon.icon(itemTheme, Symbol.FOLDER))),
            state.kind, // -1: none chosen yet
            state::kind)
        .label("Kind");
    // docs:end swatches
    // docs:start hue-picker
    El color = HuePicker.huePicker(theme, state.color, "None", state::color).label("Color");
    // docs:end hue-picker
    return Frame.of(theme, kind, color);
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
    Theme theme = state.theme;
    List<Schema> schemas = List.of(
        new Schema("accounting", "finance"),
        new Schema("public", "postgres"),
        new Schema("sales", "shop"));
    // docs:start select-typed
    El isolation = Select.select(
            theme,
            List.of(Isolation.values()),
            state.isolation,
            choice -> choice.title,
            state.isolationMenu,
            state::isolation)
        .label("Isolation");
    // docs:end select-typed
    // docs:start radio-typed
    El level = Radio.radioGroup(
            theme, List.of(Isolation.values()), state.level, choice -> choice.title, state::level)
        .label("Level");
    // docs:end radio-typed
    // docs:start segmented-typed
    El shown = Segmented.segmented( // null: none chosen yet
            theme, List.of(Isolation.values()), state.shown, choice -> choice.title, state::shown)
        .label("Show");
    // docs:end segmented-typed
    // docs:start combo-box-typed
    El schema = ComboBox.comboBox(
            theme,
            schemas,
            state.schemaValue,
            Schema::name,
            state.schemaValueBox,
            state::schemaValue)
        .label("Schema");
    // docs:end combo-box-typed
    // docs:start combo-items-typed
    El owned = ComboBox.items(
            theme,
            schemas,
            state.owned,
            option -> ComboBox.item(option.name()).icon(Symbol.DATABASE).detail(option.owner()),
            state.ownedBox,
            state::owned)
        .label("Owned schema");
    // docs:end combo-items-typed
    return Frame.of(theme, isolation, level, shown, schema, owned);
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
    Theme theme = state.theme;
    // docs:start combo-box
    List<String> schemas = List.of("accounting", "public", "sales", "staging");
    El schema = ComboBox.comboBox(theme, schemas, state.schema, state.schemaBox, state::schema)
        .label("Schema");
    El host = ComboBox.free(
            theme,
            state.host,
            List.of("localhost", "db.staging.internal"),
            state.hostBox,
            state::host)
        .label("Host");
    // docs:end combo-box
    // docs:start combo-items
    List<ComboBox.Item> products = List.of(
        ComboBox.item("PostgreSQL").icon(Symbol.DATABASE).detail("9.6 or later"),
        ComboBox.item("SQLite").icon(Symbol.DOCUMENT).detail("a file"));
    El product = ComboBox.items(theme, products, state.product, state.productBox, state::product)
        .label("Database");
    // docs:end combo-items
    return Frame.of(theme, schema, host, product);
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
    Theme theme = state.theme;
    // docs:start text-field
    El filter = TextField.withIcon(
        theme,
        TextField.textField(theme, state.filter, "Filter tables", state::filter),
        Symbol.SEARCH);
    El name = TextField.textField(theme, state.table, "Table name", state::table)
        .key("table")
        .maxLength(63);
    El invalid =
        TextField.invalid(theme, TextField.textField(theme, "2 orders", "Name", value -> {}));
    // docs:end text-field
    // docs:start password-field
    El password = TextField.revealable(
        theme,
        TextField.passwordField(theme, state.password, "Password", state::password)
            .key("password"),
        state.revealed,
        state::toggleRevealed);
    // docs:end password-field
    // docs:start text-area
    El note =
        TextField.textArea(theme, state.note, "Note", state::note).lines(3).w(280);
    El code = TextField.codeArea(theme, state.sql, "SQL", state::sql).lines(3).w(280);
    // docs:end text-area
    return Frame.of(theme, filter, name, invalid, password, note, code);
  }

  void amount(BigDecimal value) {
    amount = value;
  }

  void due(LocalDate value) {
    due = value;
  }

  void saveForm() {
    runs++;
  }

  static El values(Controls state) {
    Theme theme = state.theme;
    // docs:start value-field
    El form = Form.form(
        theme,
        Form.Labels.BESIDE,
        Form.field(
                "Amount",
                ValueField.valueField(
                        theme, Format.DECIMAL, state.amount, state.amountDraft, state::amount)
                    .key("amount")
                    .w(120))
            .required(),
        Form.field(
                "Due",
                ValueField.valueField(
                        theme,
                        Format.DATE.optional(),
                        state.due,
                        "2026-11-13",
                        state.dueDraft,
                        state::due)
                    .key("due")
                    .w(120))
            .help("Empty for none"));
    El save = Button.button(
        theme,
        PRIMARY,
        "Save",
        // A text that is no value leaves the last valid one in the state: save only without one.
        state.amountDraft.error() == null && state.dueDraft.error() == null
            ? state::saveForm
            : null);
    // docs:end value-field
    return Frame.of(theme, form, save);
  }

  void removeFilter() {
    filter = "";
  }

  static El parts(Controls state) {
    Theme theme = state.theme;
    // docs:start label
    El title = Label.label(theme, Label.Variant.HEADING, "Orders");
    El hint = Label.label(theme, Label.Variant.MUTED, "1 000 000 rows");
    El id = Label.label(theme, Label.Variant.MONO, "a3f9c2e1");
    // docs:end label
    // docs:start icon
    El table = Icon.icon(theme, Symbol.TABLE);
    El key = Icon.icon(theme, Symbol.KEY, Icon.Variant.WARNING);
    El error = Icon.icon(theme, Symbol.ERROR_CIRCLE, Icon.Variant.DANGER, 20);
    // docs:end icon
    // docs:start badge
    El type = Badge.badge(theme, "bigint");
    El primary = Badge.badge(theme, Badge.Kind.ACCENT, "PK");
    El filter = Badge.badge(
        theme,
        "status = paid",
        Badge.options().kind(Badge.Kind.ACCENT).onRemove(state::removeFilter));
    // docs:end badge
    // docs:start avatar
    El server = Avatar.avatar(theme, "prod-eu");
    El local = Avatar.avatar(theme, "localhost", Symbol.SERVER, 24);
    // docs:end avatar
    return Frame.of(
        theme,
        row().gap(theme.space().m()).children(title, hint, id),
        row().gap(theme.space().m()).children(table, key, error),
        row().gap(theme.space().s()).children(type, primary, filter),
        row().gap(theme.space().s()).children(server, local));
  }
}
