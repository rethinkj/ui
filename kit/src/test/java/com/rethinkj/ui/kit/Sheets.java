package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.Card.card;
import static com.rethinkj.ui.kit.Divider.divider;
import static com.rethinkj.ui.kit.Form.field;
import static com.rethinkj.ui.kit.Form.form;
import static com.rethinkj.ui.kit.Group.expander;
import static com.rethinkj.ui.kit.Group.group;
import static com.rethinkj.ui.kit.Group.linkRow;
import static com.rethinkj.ui.kit.Group.switchRow;
import static com.rethinkj.ui.kit.Label.label;
import static com.rethinkj.ui.kit.Section.section;
import static com.rethinkj.ui.kit.TextField.textField;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.Button.Variant;
import com.rethinkj.ui.text.TextRendering;
import java.util.List;

/**
 * Views of the structural pieces, for their tests: cards in their variants and nested, a group of
 * rows of every kind, dividers, a form with its labels beside and above the fields, and the
 * choices of a page of settings. The state is the fields of this class, which the handlers change.
 */
final class Sheets {

  final Theme t;
  boolean wrap = true;
  boolean numbers;
  boolean advanced = true;
  boolean spaces = true;
  int font;
  int endings;
  int theme = 1;
  int tabSize = 4;
  int fontSize = 13;
  int page = 1;
  int presses;
  int selectedCard = 0;
  String host = "db.example.com";
  String port = "54x";
  final Menu.State fonts = new Menu.State();

  Sheets(Theme t) {
    this.t = t;
  }

  App<Sheets> app(java.util.function.Function<Sheets, El> view) {
    App<Sheets> app = new App<>(this, view).provide(Theme.CURRENT, sheets -> sheets.t);
    app.focusRing(t.accent().edge());
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  /** Cards in their three variants, with a header, nested, pressable and selected. */
  El cards() {
    El tiles = row().gap(t.space().m());
    String[] names = {"localhost", "staging", "prod-eu"};
    for (int i = 0; i < names.length; i++) {
      int at = i;
      tiles.child(Card.pressable(
          card(
                  label(Label.Variant.STRONG, names[i]),
                  label(Label.Variant.MUTED, names[i] + ".example.com"))
              .key("tile-" + i)
              .grow(1),
          names[i],
          i == selectedCard,
          i == 2 ? null : () -> selectedCard = at));
    }
    return col()
        .wFull()
        .hFull()
        .padding(t.space().xl())
        .gap(t.space().l())
        .fill(t.surface().window())
        .children(
            card(
                Card.header(
                    "Query history",
                    "The last fifty statements run in this window",
                    button(Variant.GHOST, "Clear", () -> presses++)),
                label("SELECT * FROM orders WHERE id = 42"),
                card(
                    Card.Variant.OUTLINED,
                    label(Label.Variant.MUTED, "A card in a card shares its surface")),
                Form.buttons(button(Variant.SECONDARY, "Export…", () -> presses++))),
            row()
                .gap(t.space().m())
                .children(
                    card(Card.Variant.FILLED, label("Filled")).grow(1),
                    card(Card.Variant.ELEVATED, label("Elevated")).grow(1),
                    card(NavList.navList(
                            List.of(
                                NavList.item("orders"),
                                NavList.item("customers"),
                                NavList.item("invoices")),
                            1,
                            i -> {}))
                        .padding(0)
                        .gap(0)
                        .grow(1)),
            tiles);
  }

  /** A section with a group of rows of every kind, and dividers. */
  El rows() {
    return col()
        .wFull()
        .hFull()
        .padding(t.space().xl())
        .gap(t.space().l())
        .fill(t.surface().window())
        .children(
            section(
                "Editor",
                "How text looks and behaves while you type",
                group(
                    switchRow(
                        Symbol.TEXT_T,
                        "Word wrap",
                        "Wrap long lines at the edge of the editor instead of scrolling sideways",
                        wrap,
                        () -> wrap = !wrap),
                    switchRow("Line numbers", null, numbers, () -> numbers = !numbers),
                    Group.row(
                        "Font",
                        null,
                        Select.select(
                                List.of("JetBrains Mono", "Inter"), font, fonts, i -> font = i)
                            .key("font")
                            .w(160)),
                    Group.row(
                        "Tab size",
                        "Columns per tab stop",
                        NumberField.numberField(tabSize, 1, 16, 1, v -> tabSize = v)),
                    linkRow(Symbol.KEY, "Keymap", null, "Default", () -> presses++),
                    expander(
                        "Advanced",
                        "Options most people never change",
                        advanced,
                        () -> advanced = !advanced,
                        switchRow("Insert spaces", null, spaces, () -> spaces = !spaces),
                        Group.box(
                            label("Line endings"),
                            Radio.radioGroup(
                                    List.of("LF", "CRLF", "As in the file"),
                                    endings,
                                    i -> endings = i)
                                .label("Line endings"))),
                    switchRow("Telemetry", "Managed by your organization", false, null))),
            divider(),
            divider("or"),
            row()
                .gap(t.space().m())
                .align(Style.Align.CENTER)
                .h(24)
                .children(label("Left"), Divider.vertical(), label("Right")));
  }

  /** The same form with labels beside and above, with help, an error and required fields. */
  El form() {
    return row()
        .wFull()
        .hFull()
        .padding(t.space().xl())
        .gap(t.space().xxl())
        .fill(t.surface().window())
        .children(form(t, Form.Labels.BESIDE), form(t, Form.Labels.ABOVE).w(200));
  }

  private El form(Theme t, Form.Labels labels) {
    String at = labels.name().toLowerCase(java.util.Locale.ROOT) + "-";
    return col()
        .gap(t.space().l())
        .children(
            Form.form(
                labels,
                field("Host", textField(host, "Name or address", v -> host = v).w(180))
                    .required(),
                field(
                        "Port",
                        textField(port, "5432", v -> port = v).key(at + "port").w(72))
                    .help("From 1 to 65535")
                    .error(port.matches("\\d+") ? null : "Digits only"),
                field("Database", textField("", "Name", v -> {}).w(180))
                    .help("Leave empty for the user's own")),
            Form.buttons(
                button(Variant.SECONDARY, "Cancel", () -> {}),
                button(Variant.PRIMARY, "Save", () -> {})));
  }

  /**
   * A form of two groups with one label column and a divider between them, its host and port in
   * one row with an error under the port and help under the host, beside and above.
   */
  El formGroups() {
    return row()
        .wFull()
        .hFull()
        .padding(t.space().xl())
        .gap(t.space().xxl())
        .fill(t.surface().window())
        .children(
            formGroups(Form.Labels.BESIDE).grow(1).minSize(0, 0),
            formGroups(Form.Labels.ABOVE).w(220).shrink(0));
  }

  /** The form with labels beside alone, for a narrow window, where its row wraps. */
  El formGroupsNarrow() {
    return col()
        .wFull()
        .hFull()
        .padding(t.space().xl())
        .fill(t.surface().window())
        .child(formGroups(Form.Labels.BESIDE));
  }

  El formGroups(Form.Labels labels) {
    String at = labels.name().toLowerCase(java.util.Locale.ROOT) + "-";
    return Form.form(
        labels,
        Form.group(
                "Server",
                Form.row(
                    field(
                            "Host",
                            textField(host, "Name or address", v -> host = v).key(at + "host"))
                        .required()
                        .help("A name or an address the server answers on")
                        .grow(),
                    field(
                            "Port",
                            textField(port, "5432", v -> port = v)
                                .key(at + "port")
                                .w(72))
                        .error(port.matches("\\d+") ? null : "Digits only")),
                field("Database", textField("", "Name", v -> {}).key(at + "database")))
            .description("Where the database runs"),
        Form.group(
                "Login",
                field("User", textField("", "Role", v -> {}).key(at + "user")),
                field("Password", TextField.passwordField("", null, v -> {}).key(at + "password")))
            .divided());
  }

  /**
   * Warnings below a select and below a host in a row, whose port has an error and a warning, of
   * which the error is shown.
   */
  El formWarning() {
    return col()
        .wFull()
        .hFull()
        .padding(t.space().xl())
        .fill(t.surface().window())
        .child(Form.form(
            Form.Labels.BESIDE,
            field(
                    "Driver",
                    Select.select(List.of("42.7.15", "42.7.14"), 0, fonts, i -> {})
                        .key("driver")
                        .w(180))
                .help("The version the connection loads")
                .warning("42.7.15 is 1 day old; 42.7.14 has been out for a month"),
            Form.row(
                field("Host", textField("10.8.0.4", null, v -> {}).key("host"))
                    .warning("Outside the local network: the connection is not encrypted")
                    .grow(),
                field("Port", textField(port, "5432", v -> port = v).key("port").w(72))
                    .error("Digits only")
                    .warning("Ports below 1024 need rights"))));
  }

  /** The choices settings need: segments, radios, a number field and a slider. */
  El choices() {
    return col()
        .wFull()
        .hFull()
        .padding(t.space().xl())
        .gap(t.space().l())
        .fill(t.surface().window())
        .children(
            Segmented.segmented(List.of("Light", "Dark", "System"), theme, i -> theme = i)
                .label("Theme"),
            Radio.radioGroup(List.of("LF", "CRLF", "As in the file"), endings, i -> endings = i)
                .label("Line endings"),
            NumberField.numberField(tabSize, 1, 16, 1, v -> tabSize = v).key("tabs"),
            Slider.slider(fontSize, 11, 16, 1, v -> fontSize = v).label("Font size"),
            Callout.callout(
                Callout.Kind.WARNING,
                "Restart to apply",
                "The interface font changes after the next start.",
                button(Variant.SECONDARY, "Restart", () -> presses++)),
            EmptyState.emptyState(
                Symbol.SEARCH,
                "No connections match",
                "Try another name or host.",
                button(Variant.SECONDARY, "Clear filter", () -> presses++)));
  }
  /**
   * Callouts: of every kind with room for their actions beside the text, and one with a longer
   * text and two buttons in a card as narrow as a dialog of 400, whose actions go below the text.
   */
  El callouts() {
    return col()
        .wFull()
        .hFull()
        .padding(t.space().xl())
        .gap(t.space().l())
        .fill(t.surface().window())
        .children(
            Callout.callout(
                Callout.Kind.SUCCESS,
                "Connected",
                "The server answered in 12 ms.",
                button(Variant.SECONDARY, "Open", () -> presses++)),
            Callout.callout(Callout.Kind.INFO, "Read only", "This connection writes nothing."),
            Callout.callout(
                Callout.Kind.DANGER,
                "Connection lost",
                null,
                button(Variant.SECONDARY, "Retry", () -> presses++)),
            card(Callout.callout(
                        Callout.Kind.WARNING,
                        "Unsaved changes",
                        "Three rows of orders have changes that are not committed yet. Closing"
                            + " the tab discards them.",
                        button(Variant.SECONDARY, "Discard", () -> presses++),
                        button(Variant.PRIMARY, "Commit", () -> presses++))
                    .key("narrow"))
                .w(400));
  }
}
