package com.rethinkj.ui.examples;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.GHOST;
import static com.rethinkj.ui.kit.TitleBar.titleBar;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Clipboard;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Highlighter;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.RowHeights;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.ScrollState;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.editor.DiffView;
import com.rethinkj.ui.java.Java;
import com.rethinkj.ui.json.Json;
import com.rethinkj.ui.kit.AppIcon;
import com.rethinkj.ui.kit.Badge;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.Chat;
import com.rethinkj.ui.kit.Divider;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Markdown;
import com.rethinkj.ui.kit.MarkdownDocument;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.NavList;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Select;
import com.rethinkj.ui.kit.Split;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.render.GpuPresenter;
import com.rethinkj.ui.sql.Sql;
import com.rethinkj.ui.text.TextRendering;
import com.rethinkj.ui.window.Window;
import com.rethinkj.ui.window.WindowLoop;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * An agent chat app in a frameless window: a sidebar of sessions grouped by project on the left, a
 * streaming Markdown transcript with tool calls and a growing composer in the middle, and a
 * collapsible changes panel with file diffs on the right. No network: a simulated agent streams
 * replies with tool calls, lists and syntax-highlighted code blocks on the app's clock.
 */
public final class AgentChat {

  private static final List<String> MODELS = List.of("Balanced", "Thorough", "Fast");
  private static final List<String> SAMPLE_ATTACHMENTS =
      List.of("orders_explain.json", "OrderRepository.java", "schema.sql");
  private static final double STEP_SECONDS = 0.2;
  // The narrowest the cards get. The window's minimum width holds all three, the gaps between them
  // and the margins.
  private static final float SESSIONS_MIN = 180;
  private static final float CHAT_MIN = 420;
  private static final float CHANGES_MIN = 260;
  private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

  record Project(String name, List<Session> sessions) {}

  record ChangedFile(
      String path, int added, int removed, String language, String before, String after) {
    String fileName() {
      int slash = path.lastIndexOf('/');
      return slash < 0 ? path : path.substring(slash + 1);
    }

    String directory() {
      int slash = path.lastIndexOf('/');
      return slash < 0 ? "" : path.substring(0, slash);
    }
  }

  static final class ToolCall {
    final Icon icon;
    final String title;
    Chat.ToolState state;
    boolean open;
    String detail;
    final boolean mono;

    ToolCall(Icon icon, String title, Chat.ToolState state, String detail, boolean mono) {
      this.icon = icon;
      this.title = title;
      this.state = state;
      this.detail = detail;
      this.mono = mono;
    }
  }

  static final class Message {
    final int id;
    final boolean fromUser;
    int revision;
    String time;
    final Button.Copied copied = new Button.Copied();
    final String userText;
    final List<ToolCall> tools = new ArrayList<>();
    String markdownSource = "";
    MarkdownDocument document = Markdown.parse("");
    boolean working;
    String workingText;

    private Message(int id, boolean fromUser, String time, String userText) {
      this.id = id;
      this.fromUser = fromUser;
      this.time = time;
      this.userText = userText;
    }

    static Message user(int id, String time, String text) {
      return new Message(id, true, time, text);
    }

    static Message agent(int id, String time, String markdown, List<ToolCall> tools) {
      Message message = new Message(id, false, time, null);
      message.tools.addAll(tools);
      message.markdownSource = markdown;
      message.document = Markdown.parse(markdown);
      return message;
    }

    static Message streaming(int id, String workingText) {
      Message message = new Message(id, false, null, null);
      message.working = true;
      message.workingText = workingText;
      return message;
    }
  }

  static final class Session {
    final String id;
    final String project;
    String title;
    final String branch;
    Instant updatedAt;
    boolean pinned;
    final List<Message> messages = new ArrayList<>();
    final ScrollState scroll = new ScrollState().followEnd();
    final RowHeights heights = new RowHeights(
        index -> 96, index -> messages.get(index).id, index -> messages.get(index).revision);
    final List<String> attachments = new ArrayList<>();
    final List<ChangedFile> files = new ArrayList<>();
    final DiffView.State diffState = new DiffView.State();
    String draft = "";
    int selectedFile;
    boolean streaming;
    int streamRun;

    Session(String id, String project, String title, String branch, Instant updatedAt) {
      this.id = id;
      this.project = project;
      this.title = title;
      this.branch = branch;
      this.updatedAt = updatedAt;
    }
  }

  private sealed interface Step {
    record StartTool(Icon icon, String title, boolean mono, String workingText) implements Step {}

    record FinishTool(int index, Chat.ToolState state, String detail, String workingText)
        implements Step {}

    record AppendMarkdown(String chunk, String workingText) implements Step {}

    record AddFile(ChangedFile file) implements Step {}
  }

  final Clock clock;
  final List<Project> projects = new ArrayList<>();
  final Set<String> closedProjects = new HashSet<>();
  final Menu.State modelMenu = new Menu.State();
  Session session;
  int model;
  int nextMessageId = 1;
  int nextSessionNumber = 1;
  int nextAttachment;
  int replyCursor;
  boolean dark = true;
  Split.Position sidebar = Split.Position.first(244);
  Split.Position inspector = Split.Position.second(352);
  App<AgentChat> app;

  AgentChat(Clock clock) {
    this.clock = clock;
    Instant now = clock.instant();

    Session orders = new Session(
        "orders",
        "storefront",
        "Slow orders query",
        "perf/orders-index",
        now.minus(Duration.ofMinutes(4)));
    orders.pinned = true;
    orders.messages.add(Message.user(
        nextId(),
        formatTime(now.minus(Duration.ofMinutes(6))),
        "Can you check why the open orders query is scanning the whole table?"));
    ToolCall readSchema = new ToolCall(
        Symbol.DATABASE,
        "Inspect schema for orders",
        Chat.ToolState.DONE,
        "orders (id bigint PK, customer_id bigint, status text, total_cents int, created_at"
            + " timestamptz)\nIndexes: orders_pkey (id), orders_customer_idx (customer_id)",
        true);
    orders.messages.add(Message.agent(
        nextId(),
        formatTime(now.minus(Duration.ofMinutes(4))),
        "The `orders` table has indexes on `id` and `customer_id`, but nothing covering"
            + " `(status, created_at)`. Ask if you want me to inspect the repository query and"
            + " draft a migration.",
        List.of(readSchema)));
    orders.files.add(new ChangedFile(
        "src/main/java/com/storefront/OrderRepository.java", 3, 2, "java", """
        public List<Order> findOpen(int limit) {
          return db.query(
              "SELECT id, customer_id, total_cents, created_at FROM orders WHERE status = 'open'"
                  + " ORDER BY created_at DESC LIMIT ?",
              limit);
        }""", """
        public List<Order> findOpen(int limit) {
          return db.query(
              "SELECT id, customer_id, total_cents, created_at FROM orders "
                  + "WHERE status = ? ORDER BY created_at DESC LIMIT ?",
              OrderStatus.OPEN.value(),
              limit);
        }"""));

    Session webhooks = new Session(
        "webhooks",
        "storefront",
        "Retry failed webhooks",
        "feat/webhook-backoff",
        now.minus(Duration.ofHours(7)));
    webhooks.messages.add(Message.user(
        nextId(),
        formatTime(now.minus(Duration.ofHours(7))),
        "Add exponential backoff with jitter for webhook deliveries."));
    webhooks.messages.add(Message.agent(
        nextId(),
        formatTime(now.minus(Duration.ofHours(7))),
        """
        Added capped exponential backoff in `WebhookDispatcher`:

        - Base delay starts at `2s`, doubling up to `10m` across five attempts.
        - Full jitter spreads retries evenly when an endpoint recovers.

        ```java
        Duration nextDelay(int attempt) {
          long baseMillis = Math.min(600_000L, 2_000L << Math.min(attempt, 9));
          return Duration.ofMillis(random.nextLong(baseMillis / 2, baseMillis + 1));
        }
        ```""",
        List.of(new ToolCall(
            Symbol.CODE,
            "Edit WebhookDispatcher.java",
            Chat.ToolState.DONE,
            "Updated retry schedule and unit tests (12 passed).",
            false))));
    webhooks.files.add(new ChangedFile(
        "src/main/java/com/storefront/WebhookDispatcher.java", 2, 1, "java", """
        Duration nextDelay(int attempt) {
          return Duration.ofSeconds(30);
        }""", """
        Duration nextDelay(int attempt) {
          long baseMillis = Math.min(600_000L, 2_000L << Math.min(attempt, 9));
          return Duration.ofMillis(random.nextLong(baseMillis / 2, baseMillis + 1));
        }"""));

    Session rollup = new Session(
        "rollup", "analytics", "Daily revenue rollup", "main", now.minus(Duration.ofDays(1)));
    rollup.messages.add(Message.user(
        nextId(),
        formatTime(now.minus(Duration.ofDays(1))),
        "Summarize the timezone bug in the daily revenue materialized view."));
    rollup.messages.add(Message.agent(
        nextId(),
        formatTime(now.minus(Duration.ofDays(1))),
        "The view grouped by `date_trunc('day', created_at)` in the session's local timezone"
            + " instead of `AT TIME ZONE 'UTC'`, shifting late-evening orders into the next day.",
        List.of()));

    Session deploy = new Session(
        "deploy",
        "infra",
        "Zero-downtime blue/green",
        "ops/blue-green",
        now.minus(Duration.ofDays(2)));
    Session alerts =
        new Session("alerts", "infra", "Tune disk alerts", "main", now.minus(Duration.ofDays(4)));

    projects.add(new Project("storefront", new ArrayList<>(List.of(orders, webhooks))));
    projects.add(new Project("analytics", new ArrayList<>(List.of(rollup))));
    projects.add(new Project("infra", new ArrayList<>(List.of(deploy, alerts))));
    closedProjects.add("infra");
    session = orders;
  }

  private int nextId() {
    return nextMessageId++;
  }

  Theme theme() {
    return dark ? Theme.DARK : Theme.LIGHT;
  }

  void toggleTheme() {
    dark = !dark;
  }

  void sidebarPosition(Split.Position position) {
    sidebar = position;
  }

  void inspectorPosition(Split.Position position) {
    inspector = position;
  }

  void toggleSidebar() {
    sidebar = sidebar.isCollapsed() ? sidebar.restore() : sidebar.collapse(Split.Part.FIRST);
  }

  void toggleInspector() {
    inspector =
        inspector.isCollapsed() ? inspector.restore() : inspector.collapse(Split.Part.SECOND);
  }

  void toggleProject(String name) {
    if (!closedProjects.remove(name)) {
      closedProjects.add(name);
    }
  }

  void selectSession(Session chosen) {
    if (chosen != null) {
      session = chosen;
    }
  }

  void togglePin(Session target) {
    target.pinned = !target.pinned;
  }

  void newSession() {
    Project project = projects.getFirst();
    closedProjects.remove(project.name());
    int number = nextSessionNumber++;
    Session created =
        new Session("new-" + number, project.name(), "New session", "main", clock.instant());
    project.sessions().addFirst(created);
    session = created;
  }

  void draft(String text) {
    session.draft = text;
  }

  void selectModel(int index) {
    model = index;
  }

  void attachSampleFile() {
    String name = SAMPLE_ATTACHMENTS.get(nextAttachment++ % SAMPLE_ATTACHMENTS.size());
    if (!session.attachments.contains(name)) {
      session.attachments.add(name);
    }
  }

  void removeAttachment(int index) {
    if (index >= 0 && index < session.attachments.size()) {
      session.attachments.remove(index);
    }
  }

  void addFiles(List<Path> paths) {
    for (Path path : paths) {
      String name = path.getFileName().toString();
      if (!session.attachments.contains(name)) {
        session.attachments.add(name);
      }
    }
  }

  void selectFile(int index) {
    if (index >= 0 && index < session.files.size()) {
      session.selectedFile = index;
    }
  }

  void toggleTool(Message message, ToolCall tool) {
    tool.open = !tool.open;
    message.revision++;
  }

  // docs:start agent-chat-stream
  void send() {
    String prompt = session.draft.strip();
    if (prompt.isEmpty() || session.streaming) {
      return;
    }
    Session target = session;
    if ("New session".equals(target.title)) {
      target.title = prompt.length() <= 36 ? prompt : prompt.substring(0, 35) + "…";
    }
    target.draft = "";
    target.attachments.clear();
    target.updatedAt = clock.instant();
    target.messages.add(Message.user(nextId(), formatTime(target.updatedAt), prompt));
    Message reply = Message.streaming(nextId(), "Planning next step…");
    target.messages.add(reply);
    target.streaming = true;
    int run = ++target.streamRun;
    List<Step> plan = scriptedReply(replyCursor++);
    scheduleStep(target, reply, run, plan, 0);
  }

  void stop() {
    if (!session.streaming) {
      return;
    }
    session.streaming = false;
    session.streamRun++;
    Message last = session.messages.isEmpty() ? null : session.messages.getLast();
    if (last != null && last.working) {
      last.working = false;
      last.workingText = null;
      last.time = formatTime(clock.instant());
      for (ToolCall tool : last.tools) {
        if (tool.state == Chat.ToolState.RUNNING) {
          tool.state = Chat.ToolState.FAILED;
          tool.detail = "Stopped before the tool finished.";
        }
      }
      last.revision++;
    }
  }

  private void scheduleStep(Session target, Message reply, int run, List<Step> plan, int index) {
    app.after(STEP_SECONDS, () -> {
      if (target.streamRun != run || !target.streaming) {
        return;
      }
      applyStep(target, reply, plan.get(index));
      if (index + 1 < plan.size()) {
        scheduleStep(target, reply, run, plan, index + 1);
      } else {
        target.streaming = false;
        reply.working = false;
        reply.workingText = null;
        reply.time = formatTime(clock.instant());
        reply.revision++;
        target.updatedAt = clock.instant();
      }
    });
  }

  private void applyStep(Session target, Message reply, Step step) {
    switch (step) {
      case Step.StartTool start -> {
        reply.tools.add(
            new ToolCall(start.icon(), start.title(), Chat.ToolState.RUNNING, null, start.mono()));
        reply.workingText = start.workingText();
        reply.revision++;
      }
      case Step.FinishTool finish -> {
        ToolCall tool = reply.tools.get(finish.index());
        tool.state = finish.state();
        tool.detail = finish.detail();
        reply.workingText = finish.workingText();
        reply.revision++;
      }
      case Step.AppendMarkdown append -> {
        reply.markdownSource += append.chunk();
        reply.document = reply.document.next(reply.markdownSource);
        reply.workingText = append.workingText();
        reply.revision++;
      }
      case Step.AddFile add -> upsertFile(target, add.file());
    }
  }
  // docs:end agent-chat-stream

  private void upsertFile(Session target, ChangedFile file) {
    for (int index = 0; index < target.files.size(); index++) {
      if (target.files.get(index).path().equals(file.path())) {
        target.files.set(index, file);
        target.selectedFile = index;
        return;
      }
    }
    target.files.add(0, file);
    target.selectedFile = 0;
  }

  private List<Step> scriptedReply(int turn) {
    if (turn % 2 == 0) {
      ChangedFile migration =
          new ChangedFile("migrations/0042_orders_status_created.sql", 8, 0, "sql", """
          -- Customer lookup index
          CREATE INDEX orders_customer_idx
            ON orders (customer_id);""", """
          -- Customer lookup index
          CREATE INDEX orders_customer_idx
            ON orders (customer_id);

          CREATE INDEX CONCURRENTLY
            orders_status_created_idx
            ON orders (
              status,
              created_at DESC
            )
            WHERE status = 'open';""");
      return List.of(
          new Step.StartTool(
              Symbol.SEARCH, "Inspect EXPLAIN plan for open orders", true, "Running query plan…"),
          new Step.FinishTool(0, Chat.ToolState.DONE, """
              Seq Scan on orders  (cost=0.00..48210.00 rows=1840 width=48)
                Filter: (status = 'open')
                Rows Removed by Filter: 1842910""", "Checking migration files…"),
          new Step.StartTool(
              Symbol.CODE,
              "Draft migrations/0042_orders_status_created.sql",
              false,
              "Drafting migration…"),
          new Step.AddFile(migration),
          new Step.FinishTool(
              1,
              Chat.ToolState.DONE,
              "Added partial composite index migration for open orders.",
              "Writing summary…"),
          new Step.AppendMarkdown("""
              PostgreSQL is doing a full sequential scan across **1.84M rows** to return 1,840 \
              open orders because no index starts with `status`.

              """, "Streaming answer…"),
          new Step.AppendMarkdown("""
              Recommended rollout:

              1. Add a partial composite index on `(status, created_at DESC)` using `CONCURRENTLY` so writes stay unblocked.
              2. Run `ANALYZE orders;` immediately afterward so the planner adopts the index.
              3. Keep the partial predicate `WHERE status = 'open'` so the index stays under 2 MB.

              """, "Streaming answer…"),
          new Step.AppendMarkdown("""
              ```sql
              CREATE INDEX CONCURRENTLY IF NOT EXISTS orders_status_created_idx
                ON orders (status, created_at DESC)
                WHERE status = 'open';

              ANALYZE orders;
              ```""", "Finishing…"));
    }
    return List.of(
        new Step.StartTool(
            Symbol.PLAY, "Estimate index size and build time", true, "Estimating index size…"),
        new Step.FinishTool(
            0,
            Chat.ToolState.DONE,
            "Estimated partial index size: 1.4 MB (vs 68 MB full index)\n"
                + "Expected build time: ~2.1s with CONCURRENTLY",
            "Writing answer…"),
        new Step.AppendMarkdown("""
            Because fewer than 0.1% of rows stay in `status = 'open'`, the partial index stays tiny:

            - **Index size:** ~1.4 MB in shared buffers instead of ~68 MB for all rows.
            - **Write overhead:** Only inserts and updates touching open orders maintain the index.

            ```sql
            SELECT pg_size_pretty(pg_relation_size('orders_status_created_idx'));
            ```""", "Finishing…"));
  }

  static String relativeTime(Instant updatedAt, Clock clock) {
    Duration elapsed = Duration.between(updatedAt, clock.instant());
    long minutes = Math.max(0, elapsed.toMinutes());
    if (minutes < 1) {
      return "now";
    }
    if (minutes < 60) {
      return minutes + "m";
    }
    long hours = elapsed.toHours();
    if (hours < 24) {
      return hours + "h";
    }
    return elapsed.toDays() + "d";
  }

  private String formatTime(Instant instant) {
    return TIME_FORMAT.format(instant.atZone(clock.getZone()));
  }

  static Highlighter highlighter(String language) {
    return switch (language.toLowerCase(Locale.ROOT)) {
      case "sql", "pgsql", "postgresql" -> Sql.of();
      case "java" -> Java.of();
      case "json" -> Json.of();
      default -> null;
    };
  }

  // docs:start agent-chat-sidebar
  static El sidebar(AgentChat state) {
    Theme theme = Theme.CURRENT.get();
    List<NavList.Entry> entries = new ArrayList<>();
    List<Session> rows = new ArrayList<>();
    int selected = -1;
    for (Project project : state.projects) {
      boolean open = !state.closedProjects.contains(project.name());
      entries.add(NavList.heading(project.name())
          .count(String.valueOf(project.sessions().size()))
          .open(open, () -> state.toggleProject(project.name())));
      rows.add(null);
      for (Session item : open ? project.sessions() : List.<Session>of()) {
        if (item == state.session) {
          selected = entries.size();
        }
        String age = relativeTime(item.updatedAt, state.clock);
        El trailing = row()
            .gap(theme.space().xs())
            .align(Style.Align.CENTER)
            .children(
                item.streaming ? Progress.spinner().label("Streaming") : null,
                Label.label(Label.Variant.SMALL, age).fontFeatures("+tnum"));
        entries.add(NavList.item(item.title)
            .key("session-" + item.id)
            .detail(item.branch)
            .trailing(trailing)
            .actions(Button.icon(
                    GHOST, Symbol.PIN, item.pinned ? "Unpin" : "Pin", () -> state.togglePin(item))
                .checked(item.pinned)));
        rows.add(item);
      }
    }
    El list = NavList.navList(entries, selected, index -> state.selectSession(rows.get(index)))
        .label("Sessions");
    return Card.flush(
            Card.toolbar(
                "Sessions", null, Button.icon(GHOST, Symbol.ADD, "New session", state::newSession)),
            Divider.divider(),
            Card.inset(list))
        .label("Sessions");
  }
  // docs:end agent-chat-sidebar

  // docs:start agent-chat-transcript
  El renderMessage(Session current, int index) {
    Message message = current.messages.get(index);
    Clipboard clipboard = app.clipboard();
    if (message.fromUser) {
      return Chat.user(
          message.userText,
          Chat.options().copy(clipboard).copied(message.copied).time(message.time));
    }
    List<El> parts = new ArrayList<>();
    for (ToolCall tool : message.tools) {
      El detail = tool.detail == null
          ? null
          : Label.label(tool.mono ? Label.Variant.MONO : Label.Variant.MUTED, tool.detail)
              .wrapText();
      parts.add(Chat.tool(
          tool.icon, tool.title, tool.state, tool.open, () -> toggleTool(message, tool), detail));
    }
    if (!message.markdownSource.isEmpty()) {
      parts.add(Markdown.fragment(
          message.document, Markdown.options().copy(clipboard).languages(AgentChat::highlighter)));
    }
    if (message.working) {
      parts.add(Chat.working(message.workingText));
    }
    Chat.Options options = Chat.options().time(message.time);
    if (!message.working && !message.markdownSource.isEmpty()) {
      options.copy(clipboard, message.markdownSource).copied(message.copied);
    }
    return Chat.agent(options, parts.toArray(El[]::new));
  }

  static El chatPane(AgentChat state) {
    Theme theme = Theme.CURRENT.get();
    Session current = state.session;
    El transcript = Chat.transcript(
            current.messages.size(),
            index -> state.renderMessage(current, index),
            current.scroll,
            current.heights)
        .key("transcript-" + current.id)
        .grow(1);
    El composer = Chat.composer(
            current.draft,
            state::draft,
            state::send,
            Chat.composerOptions()
                .placeholder("Ask about " + current.project + "…")
                .working(current.streaming, state::stop)
                .attachments(current.attachments, state::removeAttachment)
                .start(Button.icon(GHOST, Symbol.ADD, "Attach file", state::attachSampleFile))
                .end(Select.select(MODELS, state.model, state.modelMenu, state::selectModel)
                    .label("Model")
                    .w(128))
                .onFiles(state::addFiles))
        .wFull();
    composer.style().maxWidth = Len.px(theme.prose().measure());
    El composerArea = col()
        .wFull()
        .align(Style.Align.CENTER)
        .padding(
            theme.space().s(),
            theme.space().xl(),
            theme.space().l(),
            theme.space().xl())
        .child(composer);
    El toggleChanges = Button.icon(
            GHOST, Symbol.PANEL_RIGHT, "Toggle changes", state::toggleInspector)
        .checked(!state.inspector.isCollapsed());
    El toolbar = current.streaming
        ? Card.toolbar(
            current.title,
            current.branch,
            Badge.badge(Badge.Kind.ACCENT, "Streaming"),
            toggleChanges)
        : Card.toolbar(current.title, current.branch, toggleChanges);
    return Card.flush(toolbar, Divider.divider(), transcript, composerArea).label("Chat");
  }
  // docs:end agent-chat-transcript

  static El inspectorPane(AgentChat state) {
    Theme theme = Theme.CURRENT.get();
    Session current = state.session;
    El close = Button.icon(GHOST, Symbol.DISMISS, "Close changes", state::toggleInspector);
    if (current.files.isEmpty()) {
      El empty = col()
          .grow(1)
          .minSize(0, 0)
          .align(Style.Align.CENTER)
          .justify(Style.Content.CENTER)
          .padding(theme.space().xl())
          .gap(theme.space().s())
          .children(
              Icon.icon(Symbol.DOCUMENT, Icon.Variant.MUTED),
              Label.label(Label.Variant.MUTED, "No file changes in this session yet."));
      return Card.flush(Card.toolbar("Changes", null, close), Divider.divider(), empty)
          .label("Changes");
    }
    int chosen = Math.clamp(current.selectedFile, 0, current.files.size() - 1);
    List<NavList.Entry> fileEntries = new ArrayList<>();
    for (ChangedFile file : current.files) {
      El stats = row()
          .gap(theme.space().xs())
          .align(Style.Align.CENTER)
          .children(
              file.added() > 0 ? Badge.badge(Badge.Kind.SUCCESS, "+" + file.added()) : null,
              file.removed() > 0 ? Badge.badge(Badge.Kind.DANGER, "-" + file.removed()) : null);
      NavList.Item entry = NavList.item(Symbol.DOCUMENT, file.fileName()).trailing(stats);
      if (!file.directory().isEmpty()) {
        entry = entry.detail(file.directory());
      }
      fileEntries.add(entry);
    }
    El fileList = NavList.navList(fileEntries, chosen, state::selectFile).label("Changed files");
    ChangedFile active = current.files.get(chosen);
    El diff = DiffView.unified(
            state.app,
            active.before(),
            active.after(),
            current.diffState,
            highlighter(active.language()))
        .key("diff-" + current.id + "-" + active.path())
        .wFull()
        .grow(1);
    String countText = current.files.size() == 1 ? "1 file" : current.files.size() + " files";
    return Card.flush(
            Card.toolbar("Changes", countText, close),
            Divider.divider(),
            col().padding(theme.space().s()).child(fileList),
            Divider.divider(),
            col().grow(1).minSize(0, 0).padding(theme.space().m()).child(diff))
        .label("Changes");
  }

  static El view(AgentChat state) {
    Theme theme = Theme.CURRENT.get();
    El main = Split.cards(
        Split.Direction.ROW,
        state.inspector,
        Split.Position.second(352).collapse(Split.Part.SECOND),
        state::inspectorPosition,
        chatPane(state),
        CHAT_MIN,
        inspectorPane(state),
        CHANGES_MIN);
    return col()
        .wFull()
        .hFull()
        .fill(theme.surface().window())
        .child(titleBar(
            AppIcon.appIcon(16, Identity.BLUE, Symbol.CODE),
            "Agent Chat",
            Button.icon(GHOST, Symbol.PANEL_LEFT, "Toggle sessions", state::toggleSidebar)
                .checked(!state.sidebar.isCollapsed()),
            Button.icon(GHOST, Symbol.WEATHER_MOON, "Dark theme", state::toggleTheme)
                .checked(state.dark)))
        .child(Split.cards(
                Split.Direction.ROW,
                state.sidebar,
                Split.Position.first(244).collapse(Split.Part.FIRST),
                state::sidebarPosition,
                sidebar(state),
                SESSIONS_MIN,
                main,
                state.inspector.isCollapsed()
                    ? CHAT_MIN
                    : CHAT_MIN + theme.space().m() + CHANGES_MIN)
            .grow(1)
            .margin(0, theme.space().m(), theme.space().m(), theme.space().m()));
  }

  public static void main(String[] args) throws IOException {
    run(args, Clock.systemDefaultZone());
  }

  static void run(String[] args, Clock clock) throws IOException {
    AgentChat state = new AgentChat(clock);
    App<AgentChat> app = new App<>(state, AgentChat::view).provide(Theme.CURRENT, AgentChat::theme);
    state.app = app;
    // A script's images are stored, so they must not depend on the platform.
    app.textRendering(TextRendering.LINUX);
    if (Script.handle(args, app)) {
      return;
    }
    app.textRendering(TextRendering.platform());
    Window.Options options = Identity.options(
            AgentChat.class, "Agent Chat", Identity.BLUE, Symbol.CODE)
        .frameless()
        .minimumSize(900, 500);
    try (Window window = Window.open("Agent Chat", 1280, 800, options);
        GpuPresenter presenter = new GpuPresenter(window)) {
      WindowLoop.run(window, app, presenter);
    }
  }
}
