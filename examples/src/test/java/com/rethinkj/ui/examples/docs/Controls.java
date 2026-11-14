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

  final Theme t = Theme.DARK;
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
  Theme.Hue colour = Theme.Hue.TEAL;
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

  static El buttons(Controls s) {
    Theme t = s.t;
    // docs:start button
    El run = Button.button(t, PRIMARY, Symbol.PLAY, "Run", () -> s.runs++);
    El cancel = Button.button(t, SECONDARY, "Cancel", () -> s.runs = 0);
    El format = Button.button(t, GHOST, "Format", null); // null: disabled
    El refresh = Button.icon(t, GHOST, Symbol.ARROW_CLOCKWISE, "Refresh", () -> s.runs++);
    El busy = Button.button(t, PRIMARY, null, "Running", s.running, () -> s.running = true);
    // docs:end button
    // docs:start button-toggle
    El pin = Button.toggle(t, Symbol.PIN, "Pin", s.pinned, on -> s.pinned = on);
    El split = Button.split(
        t,
        PRIMARY,
        Symbol.PLAY,
        "Run",
        () -> s.runs++,
        s.runMenu,
        List.of(
            Menu.item("Run selection", () -> s.runs++), Menu.item("Explain plan", () -> s.runs++)));
    // docs:end button-toggle
    return Frame.of(
        t,
        row().gap(t.space().s()).children(run, cancel, format, refresh, busy),
        row().gap(t.space().s()).children(pin, split));
  }

  static El buttonOptions(Controls s) {
    Theme t = s.t;
    // docs:start button-options
    // A red confirmation, a red secondary action and a quiet red one for a toolbar.
    Button.Options danger = Button.options().tone(Button.Tone.DANGER);
    El drop = Button.button(t, PRIMARY, "Drop table", () -> s.runs = 0, danger);
    El discard = Button.button(t, SECONDARY, "Discard", () -> s.runs = 0, danger);
    El delete = Button.icon(t, GHOST, Symbol.DELETE, "Delete row", () -> s.runs--, danger);
    // Off while nothing changed, with its handler kept: as a null handler, Tab passes it by.
    El save =
        Button.button(t, SECONDARY, "Save", () -> s.runs = 0, Button.options().enabled(s.runs > 0));
    // Busy: a spinner in place of the icon; it takes no press and keeps the focus.
    El refresh = Button.icon(
        t,
        GHOST,
        Symbol.ARROW_CLOCKWISE,
        "Refresh",
        () -> s.running = true,
        Button.options().busy(s.running));
    El run = Button.split(
        t,
        PRIMARY,
        "Run",
        () -> s.running = true,
        s.runMenu,
        List.of(Menu.item("Run selection", () -> s.runs++), Menu.item("Cancel", () -> {})),
        Button.options().icon(Symbol.PLAY).busy(s.running));
    // docs:end button-options
    return Frame.of(
        t,
        row().gap(t.space().s()).children(drop, discard, delete),
        row().gap(t.space().s()).children(save, refresh, run));
  }

  static El toggles(Controls s) {
    Theme t = s.t;
    // docs:start toggle
    El autocommit =
        Toggle.toggle(t, s.autocommit, "Autocommit", () -> s.autocommit = !s.autocommit);
    // docs:end toggle
    // docs:start checkbox
    El readOnly = Checkbox.checkbox(t, s.readOnly, "Read only", () -> s.readOnly = !s.readOnly);
    // docs:end checkbox
    return Frame.of(t, autocommit, readOnly);
  }

  static El choices(Controls s) {
    Theme t = s.t;
    // docs:start radio
    El indent = Radio.radioGroup(t, List.of("Spaces", "Tabs"), s.indent, i -> s.indent = i)
        .label("Indent with");
    // docs:end radio
    // docs:start segmented
    El mode = Segmented.segmented(t, List.of("Rows", "Cells"), s.mode, i -> s.mode = i)
        .label("Select");
    // docs:end segmented
    // docs:start select
    El driver = Select.select(
            t, List.of("PostgreSQL", "MySQL", "SQLite"), s.driver, s.driverMenu, i -> s.driver = i)
        .label("Driver");
    // docs:end select
    // docs:start number-field
    El fetch =
        NumberField.numberField(t, "Fetch size", s.fetchSize, 1, 16, 1, v -> s.fetchSize = v);
    // docs:end number-field
    // docs:start slider
    El size = Slider.slider(t, s.fontSize, 11, 16, 1, v -> s.fontSize = v).label("Font size");
    // docs:end slider
    return Frame.of(t, indent, mode, driver, fetch, size);
  }

  static El swatches(Controls s) {
    Theme t = s.t;
    // docs:start swatches
    El kind = Radio.swatches(
            t,
            List.of(
                Radio.option("Table", th -> Icon.icon(th, Symbol.TABLE)),
                Radio.option("View", th -> Icon.icon(th, Symbol.TABLE_SIMPLE)),
                Radio.option("Folder", th -> Icon.icon(th, Symbol.FOLDER))),
            s.kind, // -1: none chosen yet
            i -> s.kind = i)
        .label("Kind");
    // docs:end swatches
    // docs:start hue-picker
    El colour = HuePicker.huePicker(t, s.colour, "None", hue -> s.colour = hue).label("Colour");
    // docs:end hue-picker
    return Frame.of(t, kind, colour);
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

  static El typed(Controls s) {
    Theme t = s.t;
    List<Schema> schemas = List.of(
        new Schema("accounting", "finance"),
        new Schema("public", "postgres"),
        new Schema("sales", "shop"));
    // docs:start select-typed
    El isolation = Select.select(
            t,
            List.of(Isolation.values()),
            s.isolation,
            i -> i.title,
            s.isolationMenu,
            i -> s.isolation = i)
        .label("Isolation");
    // docs:end select-typed
    // docs:start radio-typed
    El level = Radio.radioGroup(
            t, List.of(Isolation.values()), s.level, i -> i.title, i -> s.level = i)
        .label("Level");
    // docs:end radio-typed
    // docs:start segmented-typed
    El shown = Segmented.segmented( // null: none chosen yet
            t, List.of(Isolation.values()), s.shown, i -> i.title, i -> s.shown = i)
        .label("Show");
    // docs:end segmented-typed
    // docs:start combo-box-typed
    El schema = ComboBox.comboBox(
            t, schemas, s.schemaValue, Schema::name, s.schemaValueBox, v -> s.schemaValue = v)
        .label("Schema");
    // docs:end combo-box-typed
    // docs:start combo-items-typed
    El owned = ComboBox.items(
            t,
            schemas,
            s.owned,
            v -> ComboBox.item(v.name()).icon(Symbol.DATABASE).detail(v.owner()),
            s.ownedBox,
            v -> s.owned = v)
        .label("Owned schema");
    // docs:end combo-items-typed
    return Frame.of(t, isolation, level, shown, schema, owned);
  }

  static El combos(Controls s) {
    Theme t = s.t;
    // docs:start combo-box
    List<String> schemas = List.of("accounting", "public", "sales", "staging");
    El schema =
        ComboBox.comboBox(t, schemas, s.schema, s.schemaBox, i -> s.schema = i).label("Schema");
    El host = ComboBox.free(
            t, s.host, List.of("localhost", "db.staging.internal"), s.hostBox, v -> s.host = v)
        .label("Host");
    // docs:end combo-box
    // docs:start combo-items
    List<ComboBox.Item> products = List.of(
        ComboBox.item("PostgreSQL").icon(Symbol.DATABASE).detail("9.6 or later"),
        ComboBox.item("SQLite").icon(Symbol.DOCUMENT).detail("a file"));
    El product =
        ComboBox.items(t, products, s.product, s.productBox, i -> s.product = i).label("Database");
    // docs:end combo-items
    return Frame.of(t, schema, host, product);
  }

  static El fields(Controls s) {
    Theme t = s.t;
    // docs:start text-field
    El filter = TextField.withIcon(
        t, TextField.textField(t, s.filter, "Filter tables", v -> s.filter = v), Symbol.SEARCH);
    El name = TextField.textField(t, s.table, "Table name", v -> s.table = v)
        .key("table")
        .maxLength(63);
    El invalid = TextField.invalid(t, TextField.textField(t, "2 orders", "Name", v -> {}));
    // docs:end text-field
    // docs:start password-field
    El password = TextField.revealable(
        t,
        TextField.passwordField(t, s.password, "Password", v -> s.password = v).key("password"),
        s.revealed,
        () -> s.revealed = !s.revealed);
    // docs:end password-field
    // docs:start text-area
    El note = TextField.textArea(t, s.note, "Note", v -> s.note = v).lines(3).w(280);
    El code = TextField.codeArea(t, s.sql, "SQL", v -> s.sql = v).lines(3).w(280);
    // docs:end text-area
    return Frame.of(t, filter, name, invalid, password, note, code);
  }

  static El values(Controls s) {
    Theme t = s.t;
    // docs:start value-field
    El form = Form.form(
        t,
        Form.Labels.BESIDE,
        Form.field(
                "Amount",
                ValueField.valueField(t, Format.DECIMAL, s.amount, s.amountDraft, v -> s.amount = v)
                    .key("amount")
                    .w(120))
            .required(),
        Form.field(
                "Due",
                ValueField.valueField(
                        t, Format.DATE.optional(), s.due, "2026-11-13", s.dueDraft, v -> s.due = v)
                    .key("due")
                    .w(120))
            .help("Empty for none"));
    El save = Button.button(
        t,
        PRIMARY,
        "Save",
        // A text that is no value leaves the last valid one in the state: save only without one.
        s.amountDraft.error() == null && s.dueDraft.error() == null ? () -> s.runs++ : null);
    // docs:end value-field
    return Frame.of(t, form, save);
  }

  static El parts(Controls s) {
    Theme t = s.t;
    // docs:start label
    El title = Label.label(t, Label.Variant.HEADING, "Orders");
    El hint = Label.label(t, Label.Variant.MUTED, "1 000 000 rows");
    El id = Label.label(t, Label.Variant.MONO, "a3f9c2e1");
    // docs:end label
    // docs:start icon
    El table = Icon.icon(t, Symbol.TABLE);
    El key = Icon.icon(t, Symbol.KEY, Icon.Variant.WARNING);
    El error = Icon.icon(t, Symbol.ERROR_CIRCLE, Icon.Variant.DANGER, 20);
    // docs:end icon
    // docs:start badge
    El type = Badge.badge(t, "bigint");
    El primary = Badge.badge(t, Badge.Kind.ACCENT, "PK");
    El filter = Badge.badge(
        t, "status = paid", Badge.options().kind(Badge.Kind.ACCENT).onRemove(() -> s.filter = ""));
    // docs:end badge
    // docs:start avatar
    El server = Avatar.avatar(t, "prod-eu");
    El local = Avatar.avatar(t, "localhost", Symbol.SERVER, 24);
    // docs:end avatar
    return Frame.of(
        t,
        row().gap(t.space().m()).children(title, hint, id),
        row().gap(t.space().m()).children(table, key, error),
        row().gap(t.space().s()).children(type, primary, filter),
        row().gap(t.space().s()).children(server, local));
  }
}
