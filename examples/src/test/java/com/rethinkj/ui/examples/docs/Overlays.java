package com.rethinkj.ui.examples.docs;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.GHOST;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.Badge;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.CommandPalette;
import com.rethinkj.ui.kit.Dialog;
import com.rethinkj.ui.kit.FilterList;
import com.rethinkj.ui.kit.HuePicker;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Markdown;
import com.rethinkj.ui.kit.MarkdownDocument;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.MenuBar;
import com.rethinkj.ui.kit.Popover;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.TextField;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Toast;
import com.rethinkj.ui.kit.Tooltip;
import java.util.ArrayList;
import java.util.List;

/** The examples of the pages about menus, dialogs and other overlays. */
final class Overlays {

  final Menu.State options = new Menu.State();
  final Menu.State context = new Menu.State();
  final MenuBar.State bar = new MenuBar.State();
  final Popover.State filterPopover = new Popover.State();
  final CommandPalette.State palette = new CommandPalette.State();
  boolean autocommit = true;
  boolean renaming;
  String name = "orders";
  String newName = "";
  String dropped;
  String filter = "";
  String said = "";
  String pattern = "(order";
  final MarkdownDocument changes = Markdown.parse("""
      ### Version 2.4
      - Tooltips show any content
      - Keys on keycaps in tooltips
      - Errors at fields in their color
      """);

  void run() {
    said = "run";
  }

  void toggleAutocommit() {
    autocommit = !autocommit;
  }

  void csv() {
    said = "csv";
  }

  void open() {
    said = "open";
  }

  void copy() {
    said = "copy";
  }

  void newQuery() {
    said = "new";
  }

  void exit() {
    said = "exit";
  }

  void undo() {
    said = "undo";
  }

  void filter(String value) {
    filter = value;
  }

  void refresh() {
    said = "refresh";
  }

  void update() {
    said = "update";
  }

  void pattern(String value) {
    pattern = value;
  }

  void newName(String value) {
    newName = value;
  }

  void startRename() {
    newName = name;
    renaming = true;
  }

  void rename() {
    name = newName;
    renaming = false;
  }

  void cancel() {
    renaming = false;
  }

  void save() {
    renaming = false;
  }

  void test() {
    said = "test";
  }

  void drop() {
    dropped = "orders";
  }

  void restore() {
    said = "restore";
  }

  void dismiss() {
    dropped = null;
  }

  void format() {
    said = "format";
  }

  void orders() {
    said = "orders";
  }

  void customers() {
    said = "customers";
  }

  static El menus(Overlays state) {
    // docs:start menu
    List<Menu.Item> items = List.of(
        Menu.item("Run selection", state::run).shortcut(Key.CONTROL, "Enter"),
        Menu.item("Autocommit", state::toggleAutocommit).checked(state.autocommit),
        Menu.separator(),
        Menu.submenu(
            "Export",
            List.of(Menu.item("CSV", state::csv), Menu.item("JSON", null)))); // null: disabled
    El button = Button.button(SECONDARY, "Options", state.options::toggle).key("options");
    El dropdown = Menu.dropdown(button, state.options, items);
    // docs:end menu
    // docs:start context-menu
    El row = row()
        .key("row")
        .padding(8)
        .child(Label.label("orders"))
        .role("listitem")
        .label("orders");
    El withMenu = Menu.contextMenu(
        row,
        state.context,
        List.of(Menu.item("Open", state::open), Menu.item("Copy name", state::copy)));
    // docs:end context-menu
    return Frame.of(dropdown, withMenu).onKey(Menu.shortcuts(items));
  }

  Theme.Hue hue = Theme.Hue.RED;

  void hue(Theme.Hue value) {
    hue = value;
  }

  /** Items with a swatch before their text; shown on the page, not run as an example. */
  static List<Menu.Item> colors(Overlays state) {
    // docs:start menu-leading
    List<Menu.Item> colors = new ArrayList<>();
    for (Theme.Hue each : Theme.Hue.values()) {
      colors.add(Menu.item(HuePicker.name(each), () -> state.hue(each))
          .leading(() -> HuePicker.swatch(each)) // built for each place the item shows
          .checked(each == state.hue)); // the check mark goes to the item's end
    }
    Menu.Item color = Menu.submenu("Color", colors);
    // docs:end menu-leading
    return List.of(color);
  }

  static El menuBar(Overlays state) {
    Theme theme = Theme.CURRENT.get();
    // docs:start menu-bar
    List<Menu.Item> menus = List.of(
        Menu.submenu(
            "&File",
            List.of(
                Menu.item("New query", state::newQuery).icon(Symbol.ADD).shortcut(Key.CONTROL, "N"),
                Menu.item("Exit", state::exit))),
        Menu.submenu("&Edit", List.of(Menu.item("Undo", state::undo).shortcut(Key.CONTROL, "Z"))));
    El root = col()
        .wFull()
        .hFull()
        .fill(theme.surface().window())
        .child(row().padding(4).child(MenuBar.menuBar(state.bar, menus)))
        .child(el().grow(1))
        .onKey(MenuBar.keys(state.bar, menus)) // Alt, F10, Alt+F, and Ctrl+N
        .onKeyUp(MenuBar.keysUp(state.bar, menus)); // Alt alone gives the bar the keys
    // docs:end menu-bar
    return root;
  }

  static El popover(Overlays state) {
    // docs:start popover
    El button = Button.button(SECONDARY, Symbol.FILTER, "Filter", state.filterPopover::toggle);
    El filter = Popover.form(
        button,
        state.filterPopover,
        "Add a filter",
        TextField.textField(state.filter, "status = 'paid'", state::filter).label("Condition"),
        List.of(
            Button.button(SECONDARY, "Cancel", state.filterPopover::close),
            Button.button(PRIMARY, "Add", state.filterPopover::close)));
    // docs:end popover
    return Frame.of(filter);
  }

  static El tooltip(Overlays state) {
    // docs:start tooltip
    El refresh = Tooltip.tooltip(
        Button.button(GHOST, Symbol.ARROW_CLOCKWISE, "Refresh", state::refresh).key("refresh"),
        "Refresh the schema",
        Tooltip.options()
            .shortcut(0, "F5")
            .description("Reads the tables, views and columns of the connection again."));
    // docs:end tooltip
    return Frame.of(refresh);
  }

  static El tooltipContent(Overlays state) {
    // docs:start tooltip-content
    El update = Tooltip.tooltip(
        Button.icon(GHOST, Symbol.ARROW_DOWNLOAD, "Update", state::update).key("update"),
        () -> Markdown.markdown(state.changes, Markdown.options())
            .padding(0)
            .w(240)
            .h(84));
    // docs:end tooltip-content
    return Frame.of(update);
  }

  static El tooltipKind(Overlays state) {
    // docs:start tooltip-kind
    El pattern = Tooltip.tooltip(
        TextField.invalid(TextField.textField(state.pattern, "Pattern", state::pattern)),
        "Unclosed group near index 6",
        Tooltip.options().kind(Callout.Kind.DANGER));
    // docs:end tooltip-kind
    return Frame.of(pattern.key("pattern"));
  }

  static El dialog(Overlays state) {
    Theme theme = Theme.CURRENT.get();
    // docs:start dialog
    El open = Button.button(SECONDARY, "Rename…", state::startRename);
    El dialog = !state.renaming
        ? null
        : Dialog.dialog(
            "Rename table",
            TextField.textField(state.newName, "Name", state::newName).label("Name"),
            List.of(
                Button.button(SECONDARY, "Cancel", state::cancel),
                Button.button(PRIMARY, "Rename", state.newName.isBlank() ? null : state::rename)),
            state::cancel);
    El root = col() // the window: its background, some room, and the dialog over it
        .wFull()
        .hFull()
        .fill(theme.surface().window())
        .padding(theme.space().l())
        .gap(theme.space().m())
        .align(Style.Align.START)
        .children(Label.label(state.name), open)
        .overlay(dialog);
    // docs:end dialog
    return root;
  }

  /** A dialog with what its options add; on the Dialog page, not run on a script. */
  static El dialogOptions(Overlays state) {
    // docs:start dialog-options
    El save = Button.button(PRIMARY, "Save", state::save);
    El dialog = Dialog.dialog(
        "Edit connection",
        TextField.textField(state.newName, "Name", state::newName).label("Name"),
        List.of(Button.button(SECONDARY, "Cancel", state::cancel), save),
        state::cancel,
        Dialog.options()
            .leading(HuePicker.swatch(Theme.Hue.RED)) // the connection's color
            .trailing(
                Badge.badge("Read only"),
                Button.icon(GHOST, Symbol.DISMISS, "Close", state::cancel))
            .start(Button.button(SECONDARY, "Test connection", state::test))
            .defaultButton(save));
    // docs:end dialog-options
    return Frame.of(Label.label(state.name)).overlay(dialog);
  }

  static El toast(Overlays state) {
    Theme theme = Theme.CURRENT.get();
    // docs:start toast
    El drop = Button.button(SECONDARY, "Drop orders", state::drop);
    El root = col()
        .wFull()
        .hFull()
        .fill(theme.surface().window())
        .padding(theme.space().l())
        .align(Style.Align.START)
        .child(drop)
        .overlay(
            state.dropped == null
                ? null
                : Toast.toast("Dropped " + state.dropped, "Undo", state::restore, state::dismiss));
    // docs:end toast
    return root;
  }

  static El palette(Overlays state) {
    Theme theme = Theme.CURRENT.get();
    // docs:start command-palette
    List<Menu.Item> menus = List.of(Menu.submenu(
        "Query",
        List.of(
            Menu.item("Run", state::run).shortcut(Key.CONTROL, "Enter"),
            Menu.item("Format", state::format))));
    List<FilterList.Entry> tables = List.of(
        FilterList.entry("orders", state::orders).symbol(Symbol.TABLE).detail("public"),
        FilterList.entry("customers", state::customers).symbol(Symbol.TABLE));
    El root = col() // the root: without focus, only it gets the keys
        .wFull()
        .hFull()
        .fill(theme.surface().window())
        .padding(theme.space().l())
        .child(Label.label("Control+Shift+P opens the palette."))
        .onKey(Menu.shortcuts(menus).or(CommandPalette.keys(state.palette)))
        .overlay(CommandPalette.palette(
            state.palette,
            CommandPalette.commands(menus),
            List.of(
                new CommandPalette.Source("@", "to open a table", FilterList.Entries.of(tables)))));
    // docs:end command-palette
    return root;
  }
}
