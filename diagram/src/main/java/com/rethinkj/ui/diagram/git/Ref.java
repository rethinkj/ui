package com.rethinkj.ui.diagram.git;

import java.util.Objects;

/**
 * One git ref pointing at a commit: a local branch, a remote-tracking branch, a tag, or {@code
 * HEAD}.
 *
 * <p>For {@link Kind#HEAD}, {@code name} is the local branch {@code HEAD} points to, or {@code
 * HEAD} when detached. For {@link Kind#REMOTE}, {@code name} is the remote-qualified name such as
 * {@code origin/main}; it matches a local branch whose name equals the part after the first {@code
 * /} when both point at the same {@code commit}.
 *
 * @param name the ref's display name: branch name, {@code remote/branch}, tag name, or {@code HEAD}
 * @param kind what kind of ref this is
 * @param commit the commit identifier this ref points at
 */
public record Ref(String name, Kind kind, String commit) {

  /** The kind of a git {@link Ref}. */
  public enum Kind {
    /** A local branch, such as {@code main}. */
    BRANCH,
    /** A remote-tracking branch, such as {@code origin/main}. */
    REMOTE,
    /** A tag, such as {@code v1.0}. */
    TAG,
    /** The checked-out {@code HEAD}, either naming its branch or {@code HEAD} when detached. */
    HEAD
  }

  public Ref {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(commit, "commit");
  }
}
