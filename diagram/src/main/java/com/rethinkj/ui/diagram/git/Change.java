package com.rethinkj.ui.diagram.git;

import java.util.Objects;

/**
 * One file changed by a commit or commit range, as shown in {@link CommitDetails}.
 *
 * @param status how the file changed
 * @param path the file's path in the repository (after the rename for {@link Status#RENAMED})
 * @param oldPath the file's path before the rename; required for {@link Status#RENAMED}, {@code
 *     null} for every other status
 * @param added number of added lines, 0 or more
 * @param removed number of removed lines, 0 or more
 */
public record Change(Status status, String path, String oldPath, int added, int removed) {

  /** How a file changed in a commit. */
  public enum Status {
    /** A newly added file. */
    ADDED,
    /** An existing file whose contents changed. */
    MODIFIED,
    /** A deleted file. */
    DELETED,
    /** A file moved or renamed from {@link Change#oldPath()} to {@link Change#path()}. */
    RENAMED
  }

  public Change {
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(path, "path");
    if (status == Status.RENAMED) {
      Objects.requireNonNull(oldPath, "oldPath");
    } else if (oldPath != null) {
      throw new IllegalArgumentException(
          "oldPath must be null unless status is RENAMED, was " + oldPath);
    }
    if (added < 0 || removed < 0) {
      throw new IllegalArgumentException(
          "added and removed must be 0 or more, were " + added + " and " + removed);
    }
  }
}
