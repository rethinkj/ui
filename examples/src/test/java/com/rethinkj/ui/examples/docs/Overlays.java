package com.rethinkj.ui.examples.docs;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.GHOST;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.CommandPalette;
import com.rethinkj.ui.kit.Dialog;
import com.rethinkj.ui.kit.FilterList;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.MenuBar;
import com.rethinkj.ui.kit.Popover;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.TextField;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Toast;
import com.rethinkj.ui.kit.Tooltip;
import java.util.List;

/** The examples of the pages about menus, dialogs and other overlays. */
final class Overlays {

  final Theme t = Theme.DARK;
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

  static El menus(Overlays s) {
    Theme t = s.t;
    // docs:start menu
    List<Menu.Item> items = List.of(
        Menu.item("Run selection", () -> s.said = "run").shortcut(Key.CONTROL, "Enter"),
        Menu.item("Autocommit", () -> s.autocommit = !s.autocommit).checked(s.autocommit),
        Menu.separator(),
        Menu.submenu("Export", List.of(
            Menu.item("CSV", () -> s.said = "csv"),
            Menu.item("JSON", null))));             // null: disabled
    El button = Button.button(t, SECONDARY, "Options", s.options::toggle).key("options");
    El dropdown = Menu.dropdown(t, button, s.options, items);
    // docs:end menu
    // docs:start context-menu
    El row = row().key("row").padding(8).child(Label.label(t, "orders"))
        .role("listitem").label("orders");
    El withMenu = Menu.contextMenu(t, row, s.context, List.of(
        Menu.item("Open", () -> s.said = "open"),
        Menu.item("Copy name", () -> s.said = "copy")));
    // docs:end context-menu
    return Frame.of(t, dropdown, withMenu).onKey(Menu.shortcuts(items));
  }

  static El menuBar(Overlays s) {
    Theme t = s.t;
    // docs:start menu-bar
    List<Menu.Item> menus = List.of(
        Menu.submenu("&File", List.of(
            Menu.item("New query", () -> s.said = "new").icon(Symbol.ADD)
                .shortcut(Key.CONTROL, "N"),
            Menu.item("Exit", () -> s.said = "exit"))),
        Menu.submenu("&Edit", List.of(
            Menu.item("Undo", () -> s.said = "undo").shortcut(Key.CONTROL, "Z"))));
    El root = col().wFull().hFull().fill(t.surface().window())
        .child(row().padding(4).child(MenuBar.menuBar(t, s.bar, menus)))
        .child(el().grow(1))
        .onKey(MenuBar.keys(s.bar, menus))         // Alt, F10, Alt+F, and Ctrl+N
        .onKeyUp(MenuBar.keysUp(s.bar, menus));    // Alt alone gives the bar the keys
    // docs:end menu-bar
    return root;
  }

  static El popover(Overlays s) {
    Theme t = s.t;
    // docs:start popover
    El button = Button.button(t, SECONDARY, Symbol.FILTER, "Filter", s.filterPopover::toggle);
    El filter = Popover.form(t, button, s.filterPopover, "Add a filter",
        TextField.textField(t, s.filter, "status = 'paid'", v -> s.filter = v).label("Condition"),
        List.of(Button.button(t, SECONDARY, "Cancel", s.filterPopover::close),
            Button.button(t, PRIMARY, "Add", () -> s.filterPopover.close())));
    // docs:end popover
    return Frame.of(t, filter);
  }

  static El tooltip(Overlays s) {
    Theme t = s.t;
    // docs:start tooltip
    El refresh = Tooltip.tooltip(t,
        Button.button(t, GHOST, Symbol.ARROW_CLOCKWISE, "Refresh", () -> s.said = "refresh")
            .key("refresh"),
        "Refresh the schema (F5)");
    // docs:end tooltip
    return Frame.of(t, refresh);
  }

  static El dialog(Overlays s) {
    Theme t = s.t;
    // docs:start dialog
    El open = Button.button(t, SECONDARY, "Rename…", () -> {
      s.newName = s.name;
      s.renaming = true;
    });
    Runnable cancel = () -> s.renaming = false;
    El dialog = !s.renaming ? null : Dialog.dialog(t, "Rename table",
        TextField.textField(t, s.newName, "Name", v -> s.newName = v).label("Name"),
        List.of(Button.button(t, SECONDARY, "Cancel", cancel),
            Button.button(t, PRIMARY, "Rename", s.newName.isBlank() ? null : () -> {
              s.name = s.newName;
              s.renaming = false;
            })),
        cancel);
    El root = Frame.of(t, Label.label(t, s.name), open).overlay(dialog);
    // docs:end dialog
    return root;
  }

  static El toast(Overlays s) {
    Theme t = s.t;
    // docs:start toast
    El drop = Button.button(t, SECONDARY, "Drop orders", () -> s.dropped = "orders");
    El root = Frame.of(t, drop).overlay(s.dropped == null ? null
        : Toast.toast(t, "Dropped " + s.dropped, "Undo", () -> s.said = "restore",
            () -> s.dropped = null));
    // docs:end toast
    return root;
  }

  static El palette(Overlays s) {
    Theme t = s.t;
    // docs:start command-palette
    List<Menu.Item> menus = List.of(Menu.submenu("Query", List.of(
        Menu.item("Run", () -> s.said = "run").shortcut(Key.CONTROL, "Enter"),
        Menu.item("Format", () -> s.said = "format"))));
    List<FilterList.Entry> tables = List.of(
        FilterList.entry("orders", () -> s.said = "orders").symbol(Symbol.TABLE).detail("public"),
        FilterList.entry("customers", () -> s.said = "customers").symbol(Symbol.TABLE));
    El root = Frame.of(t, Label.label(t, "Control+Shift+P opens the palette."))
        .onKey(Menu.shortcuts(menus).or(CommandPalette.keys(s.palette)))
        .overlay(CommandPalette.palette(t, s.palette, CommandPalette.commands(menus),
            List.of(new CommandPalette.Source("@", "to open a table",
                FilterList.Entries.of(tables)))));
    // docs:end command-palette
    return root;
  }
}
