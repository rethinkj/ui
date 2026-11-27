package com.rethinkj.ui.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.Theme;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentChatTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-24T14:35:00Z"), ZoneOffset.UTC);

  /** Runs {@link AgentChat} on {@code agent-chat.txt}; see {@link Snaps#check}. */
  @Test
  void theScriptStreamsAnAnswerAndSnapsBothThemes() throws IOException {
    Snaps.check("agent-chat", args -> AgentChat.run(args, CLOCK));
    assertTrue(Snaps.line(
            "agent-chat", "agent-chat-dark", "label=\"Inspect EXPLAIN plan for open orders\"")
        .contains("expanded"));
    assertTrue(Snaps.line("agent-chat", "agent-chat-dark", "CREATE INDEX CONCURRENTLY")
        .contains("orders_status_created_idx"));
    assertTrue(
        Snaps.line("agent-chat", "agent-chat-dark", "label=\"0042_orders_status_created.sql\"")
            .contains("role=option"));
    String closedButton = Snaps.line("agent-chat", "agent-chat-dark", "#model-picker ");
    assertTrue(closedButton.contains("label=\"Atlas 3 High\""), closedButton);
    assertTrue(closedButton.contains("expanded=false"), closedButton);
    String openButton = Snaps.line("agent-chat", "agent-chat-picker-dark", "#model-picker ");
    assertTrue(
        openButton.contains("expanded") && !openButton.contains("expanded=false"), openButton);
    assertTrue(Snaps.line("agent-chat", "agent-chat-picker-dark", "label=\"Choose a model\"")
        .contains("role=dialog"));
  }

  /**
   * The model picker opens above its button, filters by provider and search text, updates
   * reasoning and speed while staying open, closes on model choice, Escape or outside press, and
   * returns focus to its button.
   */
  @Test
  void modelPickerUpdatesSelectionAndRestoresFocus(@TempDir Path out) throws IOException {
    Snaps.assumeText();
    AgentChat state = new AgentChat(CLOCK);
    state.app = new App<>(state, AgentChat::view).provide(Theme.CURRENT, AgentChat::theme);
    Script.run("""
        size 1280 800 scale 2
        frame
        focus #model-picker
        key Enter
        frame
        advance 0.25
        frame
        """, state.app, out);
    assertTrue(state.modelPopover.isOpen());

    Script.run("key Escape\nframe\nadvance 0.25\nframe\n", state.app, out);
    assertFalse(state.modelPopover.isOpen());
    assertEquals("model-picker", state.app.focused().key());

    Script.run("""
        press #model-picker
        frame
        advance 0.25
        frame
        press role=button label="Solstice"
        frame
        """, state.app, out);
    assertTrue(state.modelPopover.isOpen());
    String filteredByProvider = state.app.tree().dump();
    assertTrue(filteredByProvider.contains("role=option label=\"Comet Pro\""), filteredByProvider);
    assertFalse(filteredByProvider.contains("role=option label=\"Atlas 3\""), filteredByProvider);

    Script.run("""
        press role=option label="Medium"
        frame
        press role=radio label="Fast"
        frame
        """, state.app, out);
    assertTrue(state.modelPopover.isOpen());
    assertEquals("Medium", state.level);
    assertEquals(1, state.speed);
    assertTrue(state.app.tree().dump().contains("label=\"Atlas 3 Medium\""));

    Script.run("""
        press role=textbox label="Search models"
        type "flash"
        frame
        key Enter
        frame
        advance 0.25
        frame
        """, state.app, out);
    assertFalse(state.modelPopover.isOpen());
    assertEquals("Comet Flash", state.model.name());
    assertTrue(state.app.tree().dump().contains("label=\"Comet Flash Medium\""));

    Script.run("""
        press #model-picker
        frame
        advance 0.25
        frame
        press 100,100
        frame
        advance 0.25
        frame
        """, state.app, out);
    assertFalse(state.modelPopover.isOpen());
  }

  /** Streaming starts with a running tool call and working indicator, and stop cancels it. */
  @Test
  void streamingCanBeStoppedMidReply(@TempDir Path out) throws IOException {
    Snaps.assumeText();
    AgentChat state = new AgentChat(CLOCK);
    state.app = new App<>(state, AgentChat::view).provide(Theme.CURRENT, AgentChat::theme);
    Script.run("""
        size 1280 800 scale 2
        frame
        frame
        """, state.app, out);
    int initialMessages = state.session.messages.size();

    Script.run("""
        press role=textbox label="Ask about storefront…"
        type "Check the query plan"
        key Enter
        frame
        advance 0.25
        frame
        """, state.app, out);
    assertTrue(state.session.streaming);
    assertTrue(state.app.tree().dump().contains("role=status"));
    assertTrue(state.app.tree().dump().contains("busy"));

    Script.run("press role=button label=\"Stop\"\nframe\nadvance 2\nframe\n", state.app, out);
    assertFalse(state.session.streaming);
    assertTrue(state.app.tree().dump().contains("invalid"));
    assertEquals(initialMessages + 2, state.session.messages.size());
  }

  /** Pressing a closed project's new-session action opens the group and selects the new session. */
  @Test
  void projectActionCreatesAndSelectsASessionInAClosedProject(@TempDir Path out)
      throws IOException {
    Snaps.assumeText();
    AgentChat state = new AgentChat(CLOCK);
    state.app = new App<>(state, AgentChat::view).provide(Theme.CURRENT, AgentChat::theme);
    assertTrue(state.closedProjects.contains("infra"));
    Script.run("""
        size 1280 800 scale 2
        frame
        press role=button label="New session in infra"
        frame
        """, state.app, out);

    assertFalse(state.closedProjects.contains("infra"));
    assertEquals("infra", state.session.project);
    AgentChat.Project infra = state.projects.stream()
        .filter(project -> "infra".equals(project.name()))
        .findFirst()
        .orElseThrow();
    assertEquals(state.session, infra.sessions().getFirst());
    String row = state
        .app
        .tree()
        .dump()
        .lines()
        .filter(line -> line.contains("#session-" + state.session.id + " "))
        .findFirst()
        .orElseThrow();
    assertTrue(row.contains("role=option label=\"New session\""), row);
    assertTrue(row.contains("selected"), row);
  }
}
