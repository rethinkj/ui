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
}
