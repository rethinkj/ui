package com.rethinkj.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A dialog of the system in which the user chooses files or a folder, as {@link App#fileDialog}
 * shows it: open one or more files, save a file, or choose a folder.
 *
 * <pre>{@code
 * app.fileDialog(FileDialog.open("Open").filter("SQL", "sql").filter("Text", "txt", "md").many(),
 *     choice -> choice.paths().forEach(this::read), error -> status = error);
 * }</pre>
 *
 * @param kind what the user chooses
 * @param title the dialog's title, or null for the system's
 * @param filters the kinds of file the dialog offers, the first chosen at first; none offers all
 * @param folder where the dialog starts, or null for where the system starts it
 * @param fileName the name a save dialog suggests, or null
 * @param multiple whether the user may choose several files or folders
 */
public record FileDialog(
    Kind kind, String title, List<Filter> filters, Path folder, String fileName, boolean multiple) {

  private static final Pattern EXTENSION = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9_.-]*|\\*");

  public enum Kind {
    /** One or more files that exist. */
    OPEN,
    /** A file to write, which may not exist yet; the system asks before one is overwritten. */
    SAVE,
    /** One or more folders. */
    FOLDER
  }

  /**
   * A kind of file: its name in the dialog and the extensions of its files, without the dot, as
   * {@code sql}; {@code *} stands for every file.
   */
  public record Filter(String name, List<String> extensions) {
    public Filter {
      Objects.requireNonNull(name, "name");
      extensions = List.copyOf(extensions);
      if (extensions.isEmpty()) {
        throw new IllegalArgumentException("A filter needs an extension, or * for every file");
      }
      for (String extension : extensions) {
        if (!EXTENSION.matcher(extension).matches()) {
          throw new IllegalArgumentException("An extension has letters, digits, _, - and ."
              + " without the leading dot, as sql, or is * for every file: " + extension);
        }
      }
    }

    @Override
    public String toString() {
      return name + " (" + String.join(", ", extensions) + ")";
    }
  }

  /**
   * What the user chose.
   *
   * @param paths the files or folders, in the order the system gives them; empty when the user
   *     cancelled the dialog
   * @param filter the index of the filter chosen, or -1 where the system does not say, as the
   *     portals of Linux do not
   */
  public record Choice(List<Path> paths, int filter) {
    public Choice {
      paths = List.copyOf(paths);
    }

    public boolean cancelled() {
      return paths.isEmpty();
    }

    /** The first path, or null when the dialog was cancelled. */
    public Path path() {
      return paths.isEmpty() ? null : paths.getFirst();
    }
  }

  public FileDialog {
    Objects.requireNonNull(kind, "kind");
    filters = List.copyOf(filters);
    if (kind == Kind.FOLDER && !filters.isEmpty()) {
      throw new IllegalArgumentException("A folder dialog has no filters");
    }
    if (kind == Kind.SAVE && multiple) {
      throw new IllegalArgumentException("A save dialog chooses one file");
    }
    if (fileName != null && kind != Kind.SAVE) {
      throw new IllegalArgumentException("Only a save dialog suggests a file name");
    }
    if (fileName != null && (fileName.isEmpty() || fileName.contains("/")
        || fileName.contains("\\"))) {
      throw new IllegalArgumentException("A file name without a folder: " + fileName);
    }
  }

  /** A dialog that opens one file; {@link #many} lets it open several. */
  public static FileDialog open(String title) {
    return new FileDialog(Kind.OPEN, title, List.of(), null, null, false);
  }

  /** A dialog that chooses a file to save to. */
  public static FileDialog save(String title) {
    return new FileDialog(Kind.SAVE, title, List.of(), null, null, false);
  }

  /** A dialog that chooses a folder; {@link #many} lets it choose several. */
  public static FileDialog chooseFolder(String title) {
    return new FileDialog(Kind.FOLDER, title, List.of(), null, null, false);
  }

  /** This dialog with another filter after its others, as {@code filter("CSV", "csv")}. */
  public FileDialog filter(String name, String... extensions) {
    List<Filter> more = new ArrayList<>(filters);
    more.add(new Filter(name, List.of(extensions)));
    return new FileDialog(kind, title, more, folder, fileName, multiple);
  }

  /** This dialog starting in a folder. */
  public FileDialog in(Path folder) {
    return new FileDialog(kind, title, filters, folder, fileName, multiple);
  }

  /** This save dialog suggesting a file name, as {@code orders.csv}. */
  public FileDialog fileName(String name) {
    return new FileDialog(kind, title, filters, folder, name, multiple);
  }

  /** This dialog letting the user choose several files or folders. */
  public FileDialog many() {
    return new FileDialog(kind, title, filters, folder, fileName, true);
  }

  /** The dialog as a script's errors name it: its kind, title and filters. */
  @Override
  public String toString() {
    String what = switch (kind) {
      case OPEN -> multiple ? "an open dialog for several files" : "an open dialog";
      case SAVE -> "a save dialog";
      case FOLDER -> multiple ? "a dialog for several folders" : "a folder dialog";
    };
    StringBuilder text = new StringBuilder(what);
    if (title != null) {
      text.append(" titled \"").append(title).append('"');
    }
    if (!filters.isEmpty()) {
      text.append(" with the filters ");
      for (int i = 0; i < filters.size(); i++) {
        text.append(i == 0 ? "" : ", ").append(filters.get(i));
      }
    }
    if (fileName != null) {
      text.append(", suggesting ").append(fileName);
    }
    return text.toString();
  }
}
