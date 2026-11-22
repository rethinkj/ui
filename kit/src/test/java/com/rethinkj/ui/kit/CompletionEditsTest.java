package com.rethinkj.ui.kit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** How {@link Completion}'s list follows the edits of its field, without native libraries. */
class CompletionEditsTest {

  @Test
  void editsKeepTheListOpenOnlyWithinACodeWord() {
    Completion.State state = new Completion.State();
    state.open();
    Completion.follow(state, "SELECT cu", "SELECT cu,");
    assertFalse(state.isOpen(), "a character that is no word's ends it");
    state.open();
    Completion.follow(state, "SELECT cus", "SELECT cu");
    assertTrue(state.isOpen(), "deleting within the word keeps it");
    Completion.follow(state, "SELECT c", "SELECT ");
    assertFalse(state.isOpen(), "the word gone, it closes");
    Completion.follow(state, "SELECT ", "SELECT customers");
    assertFalse(state.isOpen(), "a paste opens nothing");
  }
}
