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

  Theme t = Theme.DARK;
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

  static El menus(Overlays s) {
    Theme t = s.t;
    // docs:start menu
    List<Menu.Item> items = List.of(
        Menu.item("Run selection", s::run).shortcut(Key.CONTROL, "Enter"),
        Menu.item("Autocommit", s::toggleAutocommit).checked(s.autocommit),
        Menu.separator(),
        Menu.submenu(
            "Export",
            List.of(Menu.item("CSV", s::csv), Menu.item("JSON", null)))); // null: disabled
    El button = Button.button(t, SECONDARY, "Options", s.options::toggle).key("options");
    El dropdown = Menu.dropdown(t, button, s.options, items);
    // docs:end menu
    // docs:start context-menu
    El row = row()
        .key("row")
        .padding(8)
        .child(Label.label(t, "orders"))
        .role("listitem")
        .label("orders");
    El withMenu = Menu.contextMenu(
        t, row, s.context, List.of(Menu.item("Open", s::open), Menu.item("Copy name", s::copy)));
    // docs:end context-menu
    return Frame.of(t, dropdown, withMenu).onKey(Menu.shortcuts(items));
  }

  Theme.Hue hue = Theme.Hue.RED;

  void hue(Theme.Hue value) {
    hue = value;
  }

  /** Items with a swatch before their text; shown on the page, not run as an example. */
  static List<Menu.Item> colours(Overlays s) {
    // docs:start menu-leading
    List<Menu.Item> colours = new ArrayList<>();
    for (Theme.Hue each : Theme.Hue.values()) {
      colours.add(Menu.item(HuePicker.name(each), () -> s.hue(each))
          .leading(t -> HuePicker.swatch(t, each)) // built for each place the item shows
          .checked(each == s.hue)); // the check mark goes to the item's end
    }
    Menu.Item colour = Menu.submenu("Colour", colours);
    // docs:end menu-leading
    return List.of(colour);
  }

  static El menuBar(Overlays s) {
    Theme t = s.t;
    // docs:start menu-bar
    List<Menu.Item> menus = List.of(
        Menu.submenu(
            "&File",
            List.of(
                Menu.item("New query", s::newQuery).icon(Symbol.ADD).shortcut(Key.CONTROL, "N"),
                Menu.item("Exit", s::exit))),
        Menu.submenu("&Edit", List.of(Menu.item("Undo", s::undo).shortcut(Key.CONTROL, "Z"))));
    El root = col()
        .wFull()
        .hFull()
        .fill(t.surface().window())
        .child(row().padding(4).child(MenuBar.menuBar(t, s.bar, menus)))
        .child(el().grow(1))
        .onKey(MenuBar.keys(s.bar, menus)) // Alt, F10, Alt+F, and Ctrl+N
        .onKeyUp(MenuBar.keysUp(s.bar, menus)); // Alt alone gives the bar the keys
    // docs:end menu-bar
    return root;
  }

  static El popover(Overlays s) {
    Theme t = s.t;
    // docs:start popover
    El button = Button.button(t, SECONDARY, Symbol.FILTER, "Filter", s.filterPopover::toggle);
    El filter = Popover.form(
        t,
        button,
        s.filterPopover,
        "Add a filter",
        TextField.textField(t, s.filter, "status = 'paid'", s::filter).label("Condition"),
        List.of(
            Button.button(t, SECONDARY, "Cancel", s.filterPopover::close),
            Button.button(t, PRIMARY, "Add", s.filterPopover::close)));
    // docs:end popover
    return Frame.of(t, filter);
  }

  static El tooltip(Overlays s) {
    Theme t = s.t;
    // docs:start tooltip
    El refresh = Tooltip.tooltip(
        t,
        Button.button(t, GHOST, Symbol.ARROW_CLOCKWISE, "Refresh", s::refresh).key("refresh"),
        "Refresh the schema (F5)");
    // docs:end tooltip
    return Frame.of(t, refresh);
  }

  static El dialog(Overlays s) {
    Theme t = s.t;
    // docs:start dialog
    El open = Button.button(t, SECONDARY, "Rename…", s::startRename);
    El dialog = !s.renaming
        ? null
        : Dialog.dialog(
            t,
            "Rename table",
            TextField.textField(t, s.newName, "Name", s::newName).label("Name"),
            List.of(
                Button.button(t, SECONDARY, "Cancel", s::cancel),
                Button.button(t, PRIMARY, "Rename", s.newName.isBlank() ? null : s::rename)),
            s::cancel);
    El root = col() // the window: its background, some room, and the dialog over it
        .wFull()
        .hFull()
        .fill(t.surface().window())
        .padding(t.space().l())
        .gap(t.space().m())
        .align(Style.Align.START)
        .children(Label.label(t, s.name), open)
        .overlay(dialog);
    // docs:end dialog
    return root;
  }

  /** A dialog with what its options add; on the Dialog page, not run on a script. */
  static El dialogOptions(Overlays s) {
    Theme t = s.t;
    // docs:start dialog-options
    El save = Button.button(t, PRIMARY, "Save", s::save);
    El dialog = Dialog.dialog(
        t,
        "Edit connection",
        TextField.textField(t, s.newName, "Name", s::newName).label("Name"),
        List.of(Button.button(t, SECONDARY, "Cancel", s::cancel), save),
        s::cancel,
        Dialog.options()
            .leading(HuePicker.swatch(t, Theme.Hue.RED)) // the connection's colour
            .trailing(
                Badge.badge(t, "Read only"),
                Button.icon(t, GHOST, Symbol.DISMISS, "Close", s::cancel))
            .start(Button.button(t, SECONDARY, "Test connection", s::test))
            .defaultButton(save));
    // docs:end dialog-options
    return Frame.of(t, Label.label(t, s.name)).overlay(dialog);
  }

  static El toast(Overlays s) {
    Theme t = s.t;
    // docs:start toast
    El drop = Button.button(t, SECONDARY, "Drop orders", s::drop);
    El root = col()
        .wFull()
        .hFull()
        .fill(t.surface().window())
        .padding(t.space().l())
        .align(Style.Align.START)
        .child(drop)
        .overlay(
            s.dropped == null
                ? null
                : Toast.toast(t, "Dropped " + s.dropped, "Undo", s::restore, s::dismiss));
    // docs:end toast
    return root;
  }

  static El palette(Overlays s) {
    Theme t = s.t;
    // docs:start command-palette
    List<Menu.Item> menus = List.of(Menu.submenu(
        "Query",
        List.of(
            Menu.item("Run", s::run).shortcut(Key.CONTROL, "Enter"),
            Menu.item("Format", s::format))));
    List<FilterList.Entry> tables = List.of(
        FilterList.entry("orders", s::orders).symbol(Symbol.TABLE).detail("public"),
        FilterList.entry("customers", s::customers).symbol(Symbol.TABLE));
    El root = col() // the root: without focus, only it gets the keys
        .wFull()
        .hFull()
        .fill(t.surface().window())
        .padding(t.space().l())
        .child(Label.label(t, "Control+Shift+P opens the palette."))
        .onKey(Menu.shortcuts(menus).or(CommandPalette.keys(s.palette)))
        .overlay(CommandPalette.palette(
            t,
            s.palette,
            CommandPalette.commands(menus),
            List.of(
                new CommandPalette.Source("@", "to open a table", FilterList.Entries.of(tables)))));
    // docs:end command-palette
    return root;
  }
}
