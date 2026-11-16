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
import com.rethinkj.ui.kit.CommandPalette;
import com.rethinkj.ui.kit.Dialog;
import com.rethinkj.ui.kit.FilterList;
import com.rethinkj.ui.kit.HuePicker;
import com.rethinkj.ui.kit.Label;
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

  Theme theme = Theme.DARK;
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
    Theme theme = state.theme;
    // docs:start menu
    List<Menu.Item> items = List.of(
        Menu.item("Run selection", state::run).shortcut(Key.CONTROL, "Enter"),
        Menu.item("Autocommit", state::toggleAutocommit).checked(state.autocommit),
        Menu.separator(),
        Menu.submenu(
            "Export",
            List.of(Menu.item("CSV", state::csv), Menu.item("JSON", null)))); // null: disabled
    El button =
        Button.button(theme, SECONDARY, "Options", state.options::toggle).key("options");
    El dropdown = Menu.dropdown(theme, button, state.options, items);
    // docs:end menu
    // docs:start context-menu
    El row = row()
        .key("row")
        .padding(8)
        .child(Label.label(theme, "orders"))
        .role("listitem")
        .label("orders");
    El withMenu = Menu.contextMenu(
        theme,
        row,
        state.context,
        List.of(Menu.item("Open", state::open), Menu.item("Copy name", state::copy)));
    // docs:end context-menu
    return Frame.of(theme, dropdown, withMenu).onKey(Menu.shortcuts(items));
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
          .leading(
              itemTheme -> HuePicker.swatch(itemTheme, each)) // built for each place the item shows
          .checked(each == state.hue)); // the check mark goes to the item's end
    }
    Menu.Item color = Menu.submenu("Color", colors);
    // docs:end menu-leading
    return List.of(color);
  }

  static El menuBar(Overlays state) {
    Theme theme = state.theme;
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
        .child(row().padding(4).child(MenuBar.menuBar(theme, state.bar, menus)))
        .child(el().grow(1))
        .onKey(MenuBar.keys(state.bar, menus)) // Alt, F10, Alt+F, and Ctrl+N
        .onKeyUp(MenuBar.keysUp(state.bar, menus)); // Alt alone gives the bar the keys
    // docs:end menu-bar
    return root;
  }

  static El popover(Overlays state) {
    Theme theme = state.theme;
    // docs:start popover
    El button =
        Button.button(theme, SECONDARY, Symbol.FILTER, "Filter", state.filterPopover::toggle);
    El filter = Popover.form(
        theme,
        button,
        state.filterPopover,
        "Add a filter",
        TextField.textField(theme, state.filter, "status = 'paid'", state::filter)
            .label("Condition"),
        List.of(
            Button.button(theme, SECONDARY, "Cancel", state.filterPopover::close),
            Button.button(theme, PRIMARY, "Add", state.filterPopover::close)));
    // docs:end popover
    return Frame.of(theme, filter);
  }

  static El tooltip(Overlays state) {
    Theme theme = state.theme;
    // docs:start tooltip
    El refresh = Tooltip.tooltip(
        theme,
        Button.button(theme, GHOST, Symbol.ARROW_CLOCKWISE, "Refresh", state::refresh)
            .key("refresh"),
        "Refresh the schema (F5)");
    // docs:end tooltip
    return Frame.of(theme, refresh);
  }

  static El dialog(Overlays state) {
    Theme theme = state.theme;
    // docs:start dialog
    El open = Button.button(theme, SECONDARY, "Rename…", state::startRename);
    El dialog = !state.renaming
        ? null
        : Dialog.dialog(
            theme,
            "Rename table",
            TextField.textField(theme, state.newName, "Name", state::newName).label("Name"),
            List.of(
                Button.button(theme, SECONDARY, "Cancel", state::cancel),
                Button.button(
                    theme, PRIMARY, "Rename", state.newName.isBlank() ? null : state::rename)),
            state::cancel);
    El root = col() // the window: its background, some room, and the dialog over it
        .wFull()
        .hFull()
        .fill(theme.surface().window())
        .padding(theme.space().l())
        .gap(theme.space().m())
        .align(Style.Align.START)
        .children(Label.label(theme, state.name), open)
        .overlay(dialog);
    // docs:end dialog
    return root;
  }

  /** A dialog with what its options add; on the Dialog page, not run on a script. */
  static El dialogOptions(Overlays state) {
    Theme theme = state.theme;
    // docs:start dialog-options
    El save = Button.button(theme, PRIMARY, "Save", state::save);
    El dialog = Dialog.dialog(
        theme,
        "Edit connection",
        TextField.textField(theme, state.newName, "Name", state::newName).label("Name"),
        List.of(Button.button(theme, SECONDARY, "Cancel", state::cancel), save),
        state::cancel,
        Dialog.options()
            .leading(HuePicker.swatch(theme, Theme.Hue.RED)) // the connection's color
            .trailing(
                Badge.badge(theme, "Read only"),
                Button.icon(theme, GHOST, Symbol.DISMISS, "Close", state::cancel))
            .start(Button.button(theme, SECONDARY, "Test connection", state::test))
            .defaultButton(save));
    // docs:end dialog-options
    return Frame.of(theme, Label.label(theme, state.name)).overlay(dialog);
  }

  static El toast(Overlays state) {
    Theme theme = state.theme;
    // docs:start toast
    El drop = Button.button(theme, SECONDARY, "Drop orders", state::drop);
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
                : Toast.toast(
                    theme, "Dropped " + state.dropped, "Undo", state::restore, state::dismiss));
    // docs:end toast
    return root;
  }

  static El palette(Overlays state) {
    Theme theme = state.theme;
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
        .child(Label.label(theme, "Control+Shift+P opens the palette."))
        .onKey(Menu.shortcuts(menus).or(CommandPalette.keys(state.palette)))
        .overlay(CommandPalette.palette(
            theme,
            state.palette,
            CommandPalette.commands(menus),
            List.of(
                new CommandPalette.Source("@", "to open a table", FilterList.Entries.of(tables)))));
    // docs:end command-palette
    return root;
  }
}
