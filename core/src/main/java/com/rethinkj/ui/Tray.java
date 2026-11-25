package com.rethinkj.ui;

import com.rethinkj.ui.window.WindowIcon;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A system tray icon with a hover tooltip and a context menu, as {@link App#tray} shows it.
 *
 * <pre>{@code
 * app.tray(
 *     Tray.of(
 *         icon,
 *         "Lights",
 *         Tray.button("Show window", () -> showWindow(app)),
 *         Tray.checkbox("Sync", state.syncing, checked -> setSyncing(app, checked)),
 *         Tray.separator(),
 *         Tray.submenu(
 *             "Theme",
 *             Tray.button("Light", () -> setDark(app, false)),
 *             Tray.button("Dark", () -> setDark(app, true))),
 *         Tray.separator(),
 *         Tray.button("Quit", () -> quit(app))),
 *     error -> status = error);
 * }</pre>
 *
 * @param icon the icon shown in the notification area or status bar
 * @param tooltip text shown on hover on Windows, or null for none; Linux AppIndicator shows no
 *     tooltip
 * @param entries the context menu's entries, in the order they appear
 */
public record Tray(WindowIcon icon, String tooltip, List<Entry> entries) {

  private static final Separator SEPARATOR = new Separator();

  public Tray {
    Objects.requireNonNull(icon, "icon");
    entries = List.copyOf(entries);
  }

  /** A tray icon with this icon, tooltip and menu entries. */
  public static Tray of(WindowIcon icon, String tooltip, Entry... entries) {
    return new Tray(icon, tooltip, List.of(entries));
  }

  /** A tray icon with this icon, tooltip and menu entries. */
  public static Tray of(WindowIcon icon, String tooltip, List<Entry> entries) {
    return new Tray(icon, tooltip, entries);
  }

  /** An enabled menu button that runs {@code onClick} when clicked. */
  public static Button button(String label, Runnable onClick) {
    return new Button(label, onClick, true);
  }

  /**
   * An enabled menu checkbox starting in {@code checked}; clicking it toggles its state and hands
   * the new state to {@code onToggle}.
   */
  public static Checkbox checkbox(String label, boolean checked, Consumer<Boolean> onToggle) {
    return new Checkbox(label, checked, onToggle, true);
  }

  /** A horizontal separator between groups of entries. */
  public static Separator separator() {
    return SEPARATOR;
  }

  /** An enabled submenu holding {@code entries}. */
  public static Submenu submenu(String label, Entry... entries) {
    return new Submenu(label, List.of(entries), true);
  }

  /** An enabled submenu holding {@code entries}. */
  public static Submenu submenu(String label, List<Entry> entries) {
    return new Submenu(label, entries, true);
  }

  /** One entry of a tray context menu. */
  public sealed interface Entry permits Button, Checkbox, Separator, Submenu {}

  /**
   * A clickable menu item.
   *
   * @param label the text shown in the menu, non-empty
   * @param onClick runs as a posted task on the app's thread when clicked
   * @param enabled whether the item can be clicked
   */
  public record Button(String label, Runnable onClick, boolean enabled) implements Entry {
    public Button {
      checkLabel(label);
      Objects.requireNonNull(onClick, "onClick");
    }

    /** This button disabled. */
    public Button disabled() {
      return disabled(true);
    }

    /** This button disabled when {@code disabled} is true, enabled otherwise. */
    public Button disabled(boolean disabled) {
      return new Button(label, onClick, !disabled);
    }
  }

  /**
   * A toggleable menu item with a check mark.
   *
   * @param label the text shown in the menu, non-empty
   * @param checked whether the item starts checked
   * @param onToggle runs as a posted task on the app's thread with the new checked state
   * @param enabled whether the item can be clicked
   */
  public record Checkbox(String label, boolean checked, Consumer<Boolean> onToggle, boolean enabled)
      implements Entry {
    public Checkbox {
      checkLabel(label);
      Objects.requireNonNull(onToggle, "onToggle");
    }

    /** This checkbox disabled. */
    public Checkbox disabled() {
      return disabled(true);
    }

    /** This checkbox disabled when {@code disabled} is true, enabled otherwise. */
    public Checkbox disabled(boolean disabled) {
      return new Checkbox(label, checked, onToggle, !disabled);
    }
  }

  /** A separator line between entries. */
  public record Separator() implements Entry {}

  /**
   * A menu entry that opens a nested menu.
   *
   * @param label the text shown in the parent menu, non-empty
   * @param entries the submenu's entries
   * @param enabled whether the submenu can be opened
   */
  public record Submenu(String label, List<Entry> entries, boolean enabled) implements Entry {
    public Submenu {
      checkLabel(label);
      entries = List.copyOf(entries);
    }

    /** This submenu disabled. */
    public Submenu disabled() {
      return disabled(true);
    }

    /** This submenu disabled when {@code disabled} is true, enabled otherwise. */
    public Submenu disabled(boolean disabled) {
      return new Submenu(label, entries, !disabled);
    }
  }

  private static void checkLabel(String label) {
    Objects.requireNonNull(label, "label");
    if (label.isEmpty()) {
      throw new IllegalArgumentException("A tray entry's label cannot be empty");
    }
  }
}
