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
    App<Sheets> app = new App<>(this, view);
    app.focusRing(t.border().focus());
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  /** Cards in their three variants, with a header, nested, pressable and selected. */
  El cards() {
    El tiles = row().gap(t.space().m());
    String[] names = {"localhost", "staging", "prod-eu"};
    for (int i = 0; i < names.length; i++) {
      int at = i;
      tiles.child(Card.pressable(t, card(t, label(t, Label.Variant.STRONG, names[i]),
              label(t, Label.Variant.MUTED, names[i] + ".example.com")).key("tile-" + i).grow(1),
          names[i], i == selectedCard, i == 2 ? null : () -> selectedCard = at));
    }
    return col().wFull().hFull().padding(t.space().xl()).gap(t.space().l())
        .fill(t.surface().window())
        .children(
            card(t,
                Card.header(t, "Query history", "The last fifty statements run in this window",
                    button(t, Variant.GHOST, "Clear", () -> presses++)),
                label(t, "SELECT * FROM orders WHERE id = 42"),
                card(t, Card.Variant.OUTLINED,
                    label(t, Label.Variant.MUTED, "A card in a card shares its surface")),
                Form.buttons(t, button(t, Variant.SECONDARY, "Export…", () -> presses++))),
            row().gap(t.space().m()).children(
                card(t, Card.Variant.FILLED, label(t, "Filled")).grow(1),
                card(t, Card.Variant.ELEVATED, label(t, "Elevated")).grow(1),
                card(t, NavList.navList(t, List.of(NavList.item("orders"),
                    NavList.item("customers"), NavList.item("invoices")), 1, i -> {}))
                    .padding(0).gap(0).grow(1)),
            tiles);
  }

  /** A section with a group of rows of every kind, and dividers. */
  El rows() {
    return col().wFull().hFull().padding(t.space().xl()).gap(t.space().l())
        .fill(t.surface().window())
        .children(
            section(t, "Editor", "How text looks and behaves while you type",
                group(t,
                    switchRow(t, Symbol.TEXT_T, "Word wrap",
                        "Wrap long lines at the edge of the editor instead of scrolling sideways",
                        wrap, () -> wrap = !wrap),
                    switchRow(t, "Line numbers", null, numbers, () -> numbers = !numbers),
                    Group.row(t, "Font", null,
                        Select.select(t, List.of("JetBrains Mono", "Inter"), font, fonts,
                            i -> font = i).key("font").w(160)),
                    Group.row(t, "Tab size", "Columns per tab stop",
                        NumberField.numberField(t, tabSize, 1, 16, 1, v -> tabSize = v)),
                    linkRow(t, Symbol.KEY, "Keymap", null, "Default", () -> presses++),
                    expander(t, "Advanced", "Options most people never change", advanced,
                        () -> advanced = !advanced,
                        switchRow(t, "Insert spaces", null, spaces, () -> spaces = !spaces),
                        Group.box(t, label(t, "Line endings"),
                            Radio.radioGroup(t, List.of("LF", "CRLF", "As in the file"),
                                endings, i -> endings = i).label("Line endings"))),
                    switchRow(t, "Telemetry", "Managed by your organisation", false, null))),
            divider(t),
            divider(t, "or"),
            row().gap(t.space().m()).align(Style.Align.CENTER).h(24).children(
                label(t, "Left"), Divider.vertical(t), label(t, "Right")));
  }

  /** The same form with labels beside and above, with help, an error and required fields. */
  El form() {
    return row().wFull().hFull().padding(t.space().xl()).gap(t.space().xxl())
        .fill(t.surface().window())
        .children(form(t, Form.Labels.BESIDE), form(t, Form.Labels.ABOVE).w(200));
  }

  private El form(Theme t, Form.Labels labels) {
    String at = labels.name().toLowerCase(java.util.Locale.ROOT) + "-";
    return col().gap(t.space().l()).children(
        Form.form(t, labels,
            field("Host", textField(t, host, "Name or address", v -> host = v).w(180))
                .required(),
            field("Port", textField(t, port, "5432", v -> port = v).key(at + "port").w(72))
                .help("From 1 to 65535").error(port.matches("\\d+") ? null : "Digits only"),
            field("Database", textField(t, "", "Name", v -> {}).w(180))
                .help("Leave empty for the user's own")),
        Form.buttons(t, button(t, Variant.SECONDARY, "Cancel", () -> {}),
            button(t, Variant.PRIMARY, "Save", () -> {})));
  }

  /** The choices settings need: segments, radios, a number field and a slider. */
  El choices() {
    return col().wFull().hFull().padding(t.space().xl()).gap(t.space().l())
        .fill(t.surface().window())
        .children(
            Segmented.segmented(t, List.of("Light", "Dark", "System"), theme, i -> theme = i)
                .label("Theme"),
            Radio.radioGroup(t, List.of("LF", "CRLF", "As in the file"), endings,
                i -> endings = i).label("Line endings"),
            NumberField.numberField(t, tabSize, 1, 16, 1, v -> tabSize = v).key("tabs"),
            Slider.slider(t, fontSize, 11, 16, 1, v -> fontSize = v).label("Font size"),
            Callout.callout(t, Callout.Kind.WARNING, "Restart to apply",
                "The interface font changes after the next start.",
                button(t, Variant.SECONDARY, "Restart", () -> presses++)),
            EmptyState.emptyState(t, Symbol.SEARCH, "No connections match",
                "Try another name or host.", button(t, Variant.SECONDARY, "Clear filter",
                    () -> presses++)));
  }
}
