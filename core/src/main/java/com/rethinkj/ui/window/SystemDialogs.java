package com.rethinkj.ui.window;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import com.rethinkj.ui.Dialogs;
import com.rethinkj.ui.FileDialog;
import com.rethinkj.ui.MessageBox;
import java.io.File;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The system's dialogs through SDL, for an app in a window: a {@link WindowLoop} gives each of
 * its apps the dialogs of its window ({@link com.rethinkj.ui.App#dialogs}). Also, for an app
 * whose window cannot open, {@link #show} and {@link #reportFailure} show a message box with no
 * window at all.
 *
 * <p>A file dialog ({@code SDL_ShowFileDialogWithProperties}) returns at once and answers later
 * through a callback. Where that comes from differs. On Windows, SDL shows the dialog
 * ({@code IFileOpenDialog}, owned by the window and so modal to it) on a thread of its own, which
 * calls back. On Linux, SDL asks the XDG desktop portal ({@code
 * org.freedesktop.portal.FileChooser} over D-Bus) first, which shows the desktop's own dialog,
 * modal to the window where the portal can, and answers in a D-Bus signal that SDL reads when it
 * pumps events, so the callback runs on the windows' thread; while a dialog is open, the loop
 * therefore waits at most {@link #PUMP} milliseconds at a time, as SDL itself pumps D-Bus only
 * every 3 seconds while it waits. Without a portal SDL runs zenity, on a thread of its own, and
 * without either the dialog fails at once, with a message that says what to install. The callback
 * copies the paths before it returns, since SDL frees them then, and hands on only Java values.
 *
 * <p>A message box ({@code SDL_ShowMessageBox}) blocks the thread that shows it until it closes;
 * SDL wants that to be the thread of its parent window, here the windows' thread. So all windows
 * stand still while it is open: they draw nothing, and posted tasks wait. That suits what it is
 * for, a message when the window cannot show one; inside a working window the kit's {@code
 * Dialog} asks. On Linux SDL shows it with zenity where it can, else, on X11, draws it itself.
 */
public final class SystemDialogs implements Dialogs {

  /** At most this many milliseconds between pumps of SDL's events while a file dialog is open. */
  static final int PUMP = 50;

  private static final Map<Long, Pending> PENDING = new ConcurrentHashMap<>();
  private static final AtomicLong IDS = new AtomicLong();

  private record Pending(Arena arena, Consumer<FileDialog.Choice> chosen,
      Consumer<String> failed) {}

  /** The upcall stub, made on first use and kept for the life of the process. */
  private static final class Upcalls {
    static final MemorySegment FILES = Window.upcall(MethodHandles.lookup(),
        SystemDialogs.class, "answer", Sdl.DIALOG_FILE_CALLBACK);
  }

  private final Window window;

  /** The dialogs of a window, which they belong to; null for none. */
  SystemDialogs(Window window) {
    this.window = window;
  }

  /** Registers the callers of an answer SDL will give, as {@link #file} does; for tests. */
  static long expect(Consumer<FileDialog.Choice> chosen, Consumer<String> failed) {
    long id = IDS.incrementAndGet();
    PENDING.put(id, new Pending(Arena.ofShared(), chosen, failed));
    return id;
  }

  /** The callback SDL calls, for tests. */
  static MemorySegment callback() {
    return Upcalls.FILES;
  }

  /** Whether a file dialog has not answered yet, for which the loop pumps SDL's events. */
  static boolean waiting() {
    return !PENDING.isEmpty();
  }

  @Override
  public void file(FileDialog dialog, Consumer<FileDialog.Choice> chosen,
      Consumer<String> failed) {
    if (window != null && !window.isOpen()) {
      failed.accept("The window of this dialog has closed.");
      return;
    }
    Arena arena = Arena.ofShared(); // until SDL has answered: it may read the filters until then
    long id = IDS.incrementAndGet();
    int properties = 0;
    boolean registered = false;
    try {
      properties = Sdl.createProperties();
      if (properties == 0) {
        throw new IllegalStateException(Sdl.getError());
      }
      List<FileDialog.Filter> filters = dialog.filters();
      if (!filters.isEmpty()) {
        MemorySegment array = arena.allocate(Sdl.FILTER_SIZE * filters.size(), 8);
        for (int i = 0; i < filters.size(); i++) {
          FileDialog.Filter filter = filters.get(i);
          array.set(ADDRESS, i * Sdl.FILTER_SIZE + Sdl.FILTER_NAME,
              arena.allocateFrom(filter.name()));
          array.set(ADDRESS, i * Sdl.FILTER_SIZE + Sdl.FILTER_PATTERN,
              arena.allocateFrom(String.join(";", filter.extensions())));
        }
        Sdl.setPointerProperty(properties, Sdl.PROP_FILE_DIALOG_FILTERS_POINTER, array);
        Sdl.setNumberProperty(properties, Sdl.PROP_FILE_DIALOG_NFILTERS_NUMBER, filters.size());
      }
      if (window != null) {
        Sdl.setPointerProperty(properties, Sdl.PROP_FILE_DIALOG_WINDOW_POINTER,
            window.sdlWindow());
      }
      String location = location(dialog);
      if (location != null) {
        Sdl.setStringProperty(properties, Sdl.PROP_FILE_DIALOG_LOCATION_STRING, location);
      }
      if (dialog.title() != null) {
        Sdl.setStringProperty(properties, Sdl.PROP_FILE_DIALOG_TITLE_STRING, dialog.title());
      }
      Sdl.setBooleanProperty(properties, Sdl.PROP_FILE_DIALOG_MANY_BOOLEAN, dialog.multiple());
      PENDING.put(id, new Pending(arena, chosen, failed));
      registered = true;
      Sdl.showFileDialogWithProperties(switch (dialog.kind()) {
        case OPEN -> Sdl.FILEDIALOG_OPENFILE;
        case SAVE -> Sdl.FILEDIALOG_SAVEFILE;
        case FOLDER -> Sdl.FILEDIALOG_OPENFOLDER;
      }, Upcalls.FILES, MemorySegment.ofAddress(id), properties);
    } catch (RuntimeException e) {
      // Once registered, the callback may have answered already, and closed the arena.
      if (!registered || PENDING.remove(id) != null) {
        arena.close();
        failed.accept("The file dialog could not open: " + e.getMessage());
      }
    } finally {
      if (properties != 0) {
        Sdl.destroyProperties(properties); // SDL copied what it keeps
      }
    }
  }

  /**
   * Where the dialog starts, as SDL takes it: the folder with a separator after it, so that
   * Windows does not take its last part for a file's name, or for a save dialog the folder and
   * the suggested name; null for neither.
   */
  static String location(FileDialog dialog) {
    String folder = dialog.folder() == null ? null : dialog.folder().toAbsolutePath().toString();
    if (dialog.fileName() != null) {
      return folder == null ? dialog.fileName() : Path.of(folder, dialog.fileName()).toString();
    }
    return folder == null || folder.endsWith(File.separator) ? folder : folder + File.separator;
  }

  /** SDL_DialogFileCallback: on the windows' thread or on one of SDL's own. */
  private static void answer(MemorySegment userdata, MemorySegment files, int filter) {
    Pending pending = PENDING.remove(userdata.address());
    if (pending == null) {
      return;
    }
    try {
      if (files.equals(MemorySegment.NULL)) {
        pending.failed.accept(reason(Sdl.getError()));
      } else {
        pending.chosen.accept(new FileDialog.Choice(paths(files), filter));
      }
    } catch (Throwable t) { // one that left the upcall would end the JVM
      try {
        pending.failed.accept("The file dialog's answer could not be read: " + t);
      } catch (Throwable ignored) {
        // nothing is left to tell
      }
    } finally {
      pending.arena.close();
    }
  }

  /**
   * The paths of SDL's list, a null-terminated array of UTF-8 strings, copied. Empty strings are
   * left out: zenity's cancel comes as one.
   */
  static List<Path> paths(MemorySegment files) {
    MemorySegment list = files.reinterpret(Long.MAX_VALUE);
    List<Path> paths = new ArrayList<>();
    for (long i = 0; ; i++) {
      MemorySegment path = list.getAtIndex(ADDRESS, i);
      if (path.equals(MemorySegment.NULL)) {
        return paths;
      }
      String text = Sdl.string(path);
      if (!text.isEmpty()) {
        try {
          paths.add(Path.of(text));
        } catch (InvalidPathException e) {
          throw new IllegalStateException("The system chose a path Java cannot use: " + text, e);
        }
      }
    }
  }

  /** SDL's error as the app gets it, with what to do where Linux has no dialog. */
  static String reason(String error) {
    if (error == null || error.isEmpty()) {
      return "The system shows no file dialog here.";
    }
    if (error.contains("File dialog driver unsupported")) {
      return "This desktop has no file dialog for the app: neither the XDG desktop portal"
          + " (org.freedesktop.portal.FileChooser over D-Bus, from xdg-desktop-portal with a"
          + " backend such as xdg-desktop-portal-gtk) nor zenity is there. Install one of them."
          + " (SDL: " + error + ")";
    }
    return error;
  }

  @Override
  public void message(MessageBox box, IntConsumer pressed, Consumer<String> failed) {
    int button;
    try {
      button = show(box, window);
    } catch (RuntimeException e) {
      failed.accept(e.getMessage());
      return;
    }
    pressed.accept(button);
  }

  /**
   * Shows a message box of the system now, with no window, and waits until the user closes it:
   * for an app whose window cannot open. Call it on the thread that opens windows, or that would
   * have, usually {@code main}'s. It needs SDL, but not a window or a display's GPU.
   *
   * @return the index of the button pressed; the cancel button's when the box was closed without
   *     one
   * @throws IllegalStateException with SDL's reason when no box can show, as without a display
   */
  public static int show(MessageBox box) {
    return show(box, null);
  }

  private static int show(MessageBox box, Window parent) {
    if (parent != null && !parent.isOpen()) {
      parent = null;
    }
    try (Arena arena = Arena.ofConfined()) {
      List<String> buttons = box.buttons();
      MemorySegment data = arena.allocate(Sdl.MESSAGEBOX_DATA_SIZE, 8);
      MemorySegment array = arena.allocate(Sdl.BUTTON_DATA_SIZE * buttons.size(), 8);
      for (int i = 0; i < buttons.size(); i++) {
        long at = i * Sdl.BUTTON_DATA_SIZE;
        int flags = (i == box.defaultButton() ? Sdl.MESSAGEBOX_BUTTON_RETURNKEY_DEFAULT : 0)
            | (i == box.cancelButton() ? Sdl.MESSAGEBOX_BUTTON_ESCAPEKEY_DEFAULT : 0);
        array.set(JAVA_INT, at + Sdl.BUTTON_DATA_FLAGS, flags);
        array.set(JAVA_INT, at + Sdl.BUTTON_DATA_ID, i);
        array.set(ADDRESS, at + Sdl.BUTTON_DATA_TEXT, arena.allocateFrom(buttons.get(i)));
      }
      int kind = switch (box.kind()) {
        case INFORMATION -> Sdl.MESSAGEBOX_INFORMATION;
        case WARNING -> Sdl.MESSAGEBOX_WARNING;
        case ERROR -> Sdl.MESSAGEBOX_ERROR;
      };
      data.set(JAVA_INT, Sdl.MESSAGEBOX_FLAGS, kind | Sdl.MESSAGEBOX_BUTTONS_LEFT_TO_RIGHT);
      data.set(ADDRESS, Sdl.MESSAGEBOX_WINDOW,
          parent == null ? MemorySegment.NULL : parent.sdlWindow());
      data.set(ADDRESS, Sdl.MESSAGEBOX_TITLE, arena.allocateFrom(box.title()));
      data.set(ADDRESS, Sdl.MESSAGEBOX_MESSAGE, arena.allocateFrom(box.text()));
      data.set(JAVA_INT, Sdl.MESSAGEBOX_NUMBUTTONS, buttons.size());
      data.set(ADDRESS, Sdl.MESSAGEBOX_BUTTONS, array);
      data.set(ADDRESS, Sdl.MESSAGEBOX_COLOR_SCHEME, MemorySegment.NULL);
      MemorySegment id = arena.allocate(JAVA_INT);
      id.set(JAVA_INT, 0, -1);
      if (!Sdl.showMessageBox(data, id)) {
        throw new IllegalStateException("The message box could not show: " + Sdl.getError());
      }
      int button = id.get(JAVA_INT, 0);
      return button >= 0 && button < buttons.size() ? button : box.cancelButton();
    }
  }

  /** What {@link #reportFailure} runs. */
  public interface Start<E extends Exception> {
    void run() throws E;
  }

  /**
   * Runs {@code start}, the code that opens the app's window and runs its loop, and when that
   * throws, shows the reason in a message box of the system ({@link #show}) before throwing it
   * on: a user who started the app from a shortcut learns why it did not open, as when the GPU
   * has no adapter or a native library cannot load, instead of nothing at all. Where no box can
   * show either, what it threw gets that reason as a suppressed exception.
   *
   * <pre>{@code
   * public static void main(String[] args) throws IOException {
   *   SystemDialogs.reportFailure("Documents", () -> WindowLoop.run(window, app, presenter));
   * }
   * }</pre>
   *
   * @param app the app's name, the box's title
   */
  public static <E extends Exception> void reportFailure(String app, Start<E> start) throws E {
    try {
      start.run();
    } catch (RuntimeException | Error e) {
      tell(app, e);
      throw e;
    } catch (Exception e) {
      tell(app, e);
      throw e;
    }
  }

  private static void tell(String app, Throwable failure) {
    try {
      show(MessageBox.error(app, failureText(app, failure)));
    } catch (Throwable t) {
      failure.addSuppressed(t);
    }
  }

  /** The text of {@link #reportFailure}'s box: what failed, and each cause after it. */
  static String failureText(String app, Throwable failure) {
    StringBuilder text = new StringBuilder(app).append(" stopped because of an error.\n");
    for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
      text.append('\n').append(t.getClass().getSimpleName());
      if (t.getMessage() != null) {
        text.append(": ").append(t.getMessage());
      }
    }
    return text.toString();
  }
}
