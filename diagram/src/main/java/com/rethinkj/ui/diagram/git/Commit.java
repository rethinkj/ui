package com.rethinkj.ui.diagram.git;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A commit in a history laid out by {@link CommitGraph}.
 *
 * @param id the commit's identifier, such as its SHA-1 or SHA-256 hash
 * @param parents the commit's parent identifiers in order, first parent first; empty for a root
 *     commit
 * @param subject the first line of the commit message
 * @param author the commit's author
 * @param time when the commit was authored
 */
public record Commit(String id, List<String> parents, String subject, String author, Instant time) {

  public Commit {
    Objects.requireNonNull(id, "id");
    parents = List.copyOf(parents);
    Objects.requireNonNull(subject, "subject");
    Objects.requireNonNull(author, "author");
    Objects.requireNonNull(time, "time");
  }
}
