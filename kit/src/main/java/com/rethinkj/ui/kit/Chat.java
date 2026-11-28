package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Icon.icon;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.Clipboard;
import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.Drop;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.RowHeights;
import com.rethinkj.ui.ScrollState;
import com.rethinkj.ui.Style;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/**
 * Chat messages, transcript and composer in the theme's tokens: a virtual transcript that follows
 * the newest message ({@link #transcript}), a user message in a plain or composed bubble at the end
 * side ({@link #user}, {@link #text}), collapsible or static context blocks ({@link #context}), an
 * agent's answer as Markdown prose or composed parts over the full width ({@link #agent}),
 * collapsible one-line tool call rows ({@link #tool}), quiet collapsible tool call groups with an
 * optional latest-step preview ({@link #toolGroup}, {@link #step}), a spinner line while the agent
 * is still working ({@link #working}), and a growing composer card with an action bar ({@link
 * #composer}).
 *
 * <pre>{@code
 * El question = Chat.user(
 *     "Why did the orders query slow down?",
 *     Chat.options().copy(app.clipboard()).time("14:31"));
 * El tool = Chat.tool(
 *     Symbol.CODE, "Read queries/orders.sql", Chat.ToolState.DONE,
 *     state.toolOpen, state::toggleTool,
 *     Label.label(Label.Variant.MONO, "SELECT * FROM orders WHERE status = 'open';"));
 * El answer = Chat.agent(
 *     Chat.options().copy(app.clipboard(), state.replySource).time("14:32"),
 *     tool,
 *     Markdown.fragment(state.reply, Markdown.options().copy(app.clipboard())),
 *     state.working ? Chat.working() : null);
 * El input = Chat.composer(
 *     state.draft, state::draft, state::send,
 *     Chat.composerOptions()
 *         .attachments(state.files, state::removeFile)
 *         .working(state.working, state::stop)
 *         .onFiles(state::addFiles));
 * }</pre>
 *
 * <p>All state stays in the app: whether a tool call, tool call group or context block is open is
 * passed in and flipped by its {@code onToggle} handler, copy confirmation uses a caller-owned
 * {@link Button.Copied}, and the composer receives its text, attachments and working flag from the
 * app. Passing a null {@code onToggle} disables expanding a tool call or tool call group, and turns
 * a context block's header into a static row without a chevron.
 *
 * <p>In a tree dump, {@link #transcript} contains a {@code role=log} virtual list labeled by {@link
 * Texts#transcript} and, while scrolled away from the end, a {@code role=button} labeled by {@link
 * Texts#jumpToNewest}; user and agent messages are {@code role=article} labeled by {@link
 * Texts#userMessage} and {@link Texts#agentMessage}, a tool call's header row is {@code
 * role=button} with the title as its label, {@link Texts#toolState} as its {@code value}, {@code
 * expanded} or {@code expanded=false}, {@code busy} while running and {@code invalid} when failed,
 * a {@link #toolGroup} is {@code role=group} labeled by its title with a {@code role=button} header
 * carrying the aggregate {@link Texts#toolState} and metadata in {@code value}, {@code expanded} /
 * {@code expanded=false}, {@code busy} while any step runs and {@code invalid} when any step
 * failed, an optional {@code role=button} toggle with {@code expanded=false} for hidden earlier
 * steps, and visible step rows matching {@link #tool}, a toggleable context block's header is
 * {@code role=button} with the title as its label, optional metadata as its {@code value} and
 * {@code expanded} / {@code expanded=false} (or {@code role=group} when static), {@link #working}
 * is {@code role=status}, and {@link #composer} contains a {@code role=textbox} field, removable
 * attachment chips, and a {@code role=button} labeled by {@link Texts#send} or {@link Texts#stop}.
 */
public final class Chat {

  private static final int COMPOSER_MAX_LINES = 6;

  /** The execution state shown on a {@link #tool} call row or {@link #toolGroup} step. */
  public enum ToolState {
    /** The tool is running: shows a {@link Progress#spinner} and sets {@code busy}. */
    RUNNING,
    /** The tool finished without error: shows a check mark in {@link Theme.Status#success}. */
    DONE,
    /** The tool failed: shows an error icon in {@link Theme.Status#danger} and sets {@code invalid}. */
    FAILED
  }

  /**
   * One tool call step inside a {@link #toolGroup}: its optional icon, title, execution state,
   * controlled {@code open} flag, optional toggle handler, and optional detail elements shown when
   * open. Null detail elements are skipped.
   */
  public record Step(
      Icon icon, String title, ToolState state, boolean open, Runnable onToggle, List<El> details) {
    public Step {
      Objects.requireNonNull(title, "title");
      Objects.requireNonNull(state, "state");
      details = details == null
          ? List.of()
          : details.stream().filter(Objects::nonNull).toList();
    }
  }

  /**
   * Quiet footer options and Markdown settings for {@link #user} and {@link #agent}; {@link
   * #options()} is a plain one.
   */
  public static final class Options {
    private String time;
    private Clipboard clipboard;
    private String copyText;
    private Button.Copied copied;
    private List<El> actions = List.of();
    private Markdown.Options markdown;

    private Options() {}

    /** Muted time text shown under the message (for example {@code "14:32"}); null for none. */
    public Options time(String time) {
      this.time = time;
      return this;
    }

    /**
     * Adds a quiet copy button under the message that writes the message's text to {@code
     * clipboard}. For {@link #user(String, Options)} this is the message text; for {@link
     * #agent(MarkdownDocument, Options)} this is {@link Markdown#text(MarkdownDocument)}. Null
     * turns copying off. For composed messages ({@link #user(Options, El...)} and {@link
     * #agent(Options, El...)}), no copy button is shown unless {@link #copy(Clipboard, String)}
     * supplies the text.
     */
    public Options copy(Clipboard clipboard) {
      return copy(clipboard, null);
    }

    /**
     * Adds a quiet copy button under the message that writes {@code text} to {@code clipboard},
     * for composed messages ({@link #user(Options, El...)} and {@link #agent(Options, El...)}) or
     * when copying source text other than the rendered plain text.
     */
    public Options copy(Clipboard clipboard, String text) {
      this.clipboard = clipboard;
      this.copyText = text;
      return this;
    }

    /**
     * State for the copy button's brief confirmation check mark and tooltip (and for code blocks
     * in {@link #agent(MarkdownDocument, Options)} when {@link #markdown} is omitted); null for no
     * confirmation.
     */
    public Options copied(Button.Copied copied) {
      this.copied = copied;
      return this;
    }

    /** Extra quiet actions shown under the message beside the copy button and time. */
    public Options actions(El... actions) {
      this.actions = List.of(Section.present(actions));
      return this;
    }

    /**
     * Options forwarded to {@link Markdown#fragment} by {@link #agent(MarkdownDocument, Options)}.
     * When omitted, {@link Markdown#options()} is used (with {@link #copy(Clipboard)} and {@link
     * #copied(Button.Copied)} forwarded to code blocks if set).
     */
    public Options markdown(Markdown.Options options) {
      this.markdown = options;
      return this;
    }
  }

  /**
   * Options for {@link #composer}: placeholder, working/stop state, attachment chips, action bar
   * slots and file drops; {@link #composerOptions()} is a plain one.
   */
  public static final class ComposerOptions {
    private String placeholder;
    private boolean working;
    private Runnable onStop;
    private List<String> attachments = List.of();
    private IntConsumer onRemoveAttachment;
    private List<El> start = List.of();
    private List<El> end = List.of();
    private Consumer<List<Path>> onFiles;

    private ComposerOptions() {}

    /**
     * Placeholder shown while the field is empty, and its accessible label; null uses {@link
     * Texts#composerPlaceholder()}.
     */
    public ComposerOptions placeholder(String placeholder) {
      this.placeholder = placeholder;
      return this;
    }

    /**
     * While {@code working} is true, the send button turns into a stop button that runs {@code
     * onStop} (disabled when {@code onStop} is null) and Enter does not send.
     */
    public ComposerOptions working(boolean working, Runnable onStop) {
      this.working = working;
      this.onStop = onStop;
      return this;
    }

    /**
     * Attachment chips shown in a row above the action bar; {@code onRemove} receives the index of the chip
     * whose remove button was pressed, or null for non-removable chips.
     */
    public ComposerOptions attachments(List<String> names, IntConsumer onRemove) {
      this.attachments = names == null ? List.of() : List.copyOf(names);
      this.onRemoveAttachment = onRemove;
      return this;
    }

    /**
     * Controls placed at the start of the action bar, such as an attach button. Nulls are skipped.
     */
    public ComposerOptions start(El... elements) {
      this.start = List.of(Section.present(elements));
      return this;
    }

    /**
     * Controls placed at the end of the action bar before the send/stop button, such as a {@link
     * Select} for the model. Nulls are skipped.
     */
    public ComposerOptions end(El... elements) {
      this.end = List.of(Section.present(elements));
      return this;
    }

    /**
     * Runs when files are dropped onto the composer ({@link El#onFileDrop} at {@link
     * Drop.Phase#DROP}).
     */
    public ComposerOptions onFiles(Consumer<List<Path>> onFiles) {
      this.onFiles = onFiles;
      return this;
    }
  }

  private Chat() {}

  public static Options options() {
    return new Options();
  }

  public static ComposerOptions composerOptions() {
    return new ComposerOptions();
  }

  /** A user message in a right-aligned bubble without footer actions or time. */
  public static El user(String text) {
    return user(text, options());
  }

  /**
   * A user message in a bubble at the end side (right), as wide as its text up to {@link
   * Theme.Chat#bubbleWidth} of the message's width, with optional quiet actions and muted time
   * below it.
   */
  public static El user(String text, Options options) {
    Objects.requireNonNull(text, "text");
    Objects.requireNonNull(options, "options");
    Theme theme = Theme.CURRENT.get();
    El bubble = row().child(text(text));
    bubble.style().maxWidth = Len.percent(theme.chat().bubbleWidth());
    return userMessage(theme, options, options.copyText != null ? options.copyText : text, bubble);
  }

  /**
   * A composed user message in a right-aligned bubble spanning {@link Theme.Chat#bubbleWidth} of
   * the message's width, stacking {@code parts} (such as {@link #text} and {@link #context}) in a
   * column with optional quiet actions and muted time below it. Null parts are skipped. Supply the
   * raw prompt via {@link Options#copy(Clipboard, String)} to show a copy button in the footer.
   */
  public static El user(Options options, El... parts) {
    Objects.requireNonNull(options, "options");
    Theme theme = Theme.CURRENT.get();
    El bubble = col().gap(theme.space().m()).children(Section.present(parts));
    bubble.style().width = Len.percent(theme.chat().bubbleWidth());
    return userMessage(theme, options, options.copyText, bubble);
  }

  /**
   * A read-only selectable paragraph in {@link Theme.Prose#body} for composed messages, joining
   * the enclosing {@link #transcript}'s selection scope.
   */
  public static El text(String text) {
    Objects.requireNonNull(text, "text");
    Theme theme = Theme.CURRENT.get();
    Theme.TextStyle style = theme.prose().body();
    El body = El.text(text)
        .editable(ignored -> {})
        .readOnly()
        .multiline()
        .wrapText()
        .fitLines()
        .skipTab()
        .role("paragraph")
        .font(style.font().get())
        .fontSize(style.size())
        .color(theme.text().primary())
        .selectionColor(theme.accent().selection())
        .caretColor(theme.text().primary())
        .shrink(1);
    body.style().minWidth = Len.ZERO;
    return body;
  }

  /** An agent message showing {@code content} as a full-width {@link Markdown#fragment}. */
  public static El agent(MarkdownDocument content) {
    return agent(content, options());
  }

  /**
   * An agent message showing {@code content} as a full-width {@link Markdown#fragment}, with
   * optional quiet actions and muted time below it.
   */
  public static El agent(MarkdownDocument content, Options options) {
    Objects.requireNonNull(content, "content");
    Objects.requireNonNull(options, "options");
    Markdown.Options md = options.markdown != null
        ? options.markdown
        : options.clipboard != null
            ? Markdown.options().copy(options.clipboard).copied(options.copied)
            : Markdown.options();
    String copyText = options.copyText != null
        ? options.copyText
        : options.clipboard != null ? Markdown.text(content) : null;
    return agentMessage(options, copyText, new El[] {Markdown.fragment(content, md)});
  }

  /**
   * An agent message composed of full-width parts stacked in order, such as {@link #tool} calls,
   * {@link #toolGroup}s, {@link Markdown#fragment} blocks and {@link #working}. Null parts are
   * skipped.
   */
  public static El agent(El... parts) {
    return agent(options(), parts);
  }

  /**
   * An agent message composed of full-width parts stacked in order, with optional quiet actions and
   * muted time below them. Null parts are skipped.
   */
  public static El agent(Options options, El... parts) {
    Objects.requireNonNull(options, "options");
    return agentMessage(options, options.copyText, Section.present(parts));
  }

  /**
   * A one-line tool call row with an optional icon, a title, a state indicator (spinner while
   * running, check mark when done, error icon when failed) and a chevron that opens to show {@code
   * details} (any elements: text, code, a diff). Controlled: {@code open} decides whether the
   * details show and {@code onToggle} runs when pressed; null {@code onToggle} disables toggling.
   * Null detail elements are skipped.
   */
  public static El tool(
      Icon icon, String title, ToolState state, boolean open, Runnable onToggle, El... details) {
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(state, "state");
    Theme theme = Theme.CURRENT.get();
    Texts texts = Texts.CURRENT.get();
    boolean enabled = onToggle != null;
    String stateText = texts.toolState(state);
    return disclosure(
        theme,
        icon,
        title,
        toolIndicator(state, stateText),
        open,
        onToggle,
        !enabled,
        true,
        theme.border().subtle(),
        Section.present(details),
        head -> configureToolHead(head, title, stateText, state, open, enabled));
  }

  /**
   * Creates a {@link Step} for {@link #toolGroup}: controlled like {@link #tool}, with {@code
   * open} deciding whether {@code details} show, {@code onToggle} running when pressed (or null to
   * disable toggling), and null detail elements skipped.
   */
  public static Step step(
      Icon icon, String title, ToolState state, boolean open, Runnable onToggle, El... details) {
    El[] present = details == null ? new El[0] : Section.present(details);
    return new Step(icon, title, state, open, onToggle, List.of(present));
  }

  /**
   * A quiet collapsible group of tool calls ({@link Step}): a borderless header row with an
   * aggregate mark (a spinner while any step is {@link ToolState#RUNNING}, otherwise a check mark),
   * a title, optional muted metadata and a chevron. Controlled: when {@code open} is true all
   * steps show along a vertical track line below the header; when {@code open} is false, the last
   * {@code latest} steps show behind a quiet toggle row for the hidden earlier steps (or only the
   * header when {@code latest} is {@code 0}). Null {@code onToggle} disables toggling the group.
   * Null {@code title} falls back to {@link Texts#steps(int)}; null {@code meta} falls back to the
   * step count and any failure count (or only the failure count when {@code title} also fell back).
   * Null steps are skipped.
   *
   * @throws IllegalArgumentException if {@code latest} is negative
   */
  public static El toolGroup(
      String title, String meta, boolean open, Runnable onToggle, int latest, Step... steps) {
    if (latest < 0) {
      throw new IllegalArgumentException("latest must be 0 or more, was " + latest);
    }
    Theme theme = Theme.CURRENT.get();
    Texts texts = Texts.CURRENT.get();
    List<Step> present = steps == null
        ? List.of()
        : Arrays.stream(steps).filter(Objects::nonNull).toList();
    int total = present.size();
    boolean anyRunning = false;
    int failed = 0;
    for (Step step : present) {
      if (step.state() == ToolState.RUNNING) {
        anyRunning = true;
      } else if (step.state() == ToolState.FAILED) {
        failed++;
      }
    }
    ToolState aggregate =
        anyRunning ? ToolState.RUNNING : failed > 0 ? ToolState.FAILED : ToolState.DONE;

    boolean defaultTitle = title == null;
    String resolvedTitle = defaultTitle ? texts.steps(total) : title;
    String resolvedMeta;
    if (meta != null) {
      resolvedMeta = meta;
    } else if (defaultTitle) {
      resolvedMeta = failed > 0 ? texts.failedSteps(failed) : "";
    } else {
      resolvedMeta =
          failed > 0 ? texts.steps(total) + " · " + texts.failedSteps(failed) : texts.steps(total);
    }
    boolean hasMeta = !resolvedMeta.isEmpty();
    boolean enabled = onToggle != null;
    String stateText = texts.toolState(aggregate);
    String headerValue = hasMeta ? stateText + " · " + resolvedMeta : stateText;

    // A failure is shown in the meta text and on individual steps, not as a danger icon here.
    El aggregateMark = anyRunning
        ? Progress.spinner().label(texts.toolState(ToolState.RUNNING))
        : icon(Symbol.CHECKMARK_CIRCLE, Icon.Variant.SUCCESS)
            .label(texts.toolState(ToolState.DONE));

    El head = disclosureHead(
            theme,
            resolvedTitle,
            theme.space().s(),
            theme.space().m(),
            theme.size().control() + theme.space().s(),
            onToggle)
        .radius(theme.radius().medium());
    // The whole text of what may be cut short: the title, and the metadata after it.
    Tooltip.whenShortened(head, hasMeta ? resolvedTitle + " " + resolvedMeta : resolvedTitle)
        .role("button")
        .value(headerValue)
        .expanded(open)
        .disabled(!enabled)
        .busy(anyRunning)
        .invalid(failed > 0);
    head.child(aggregateMark);
    head.child(label(resolvedTitle)
        .color(enabled ? theme.text().primary() : theme.text().disabled())
        .maxLines(1)
        .ellipsis()
        .grow(hasMeta ? 0 : 1)
        .minSize(0, 0));
    if (hasMeta) {
      // As in a context block: the metadata takes only the room the title leaves.
      El metaLabel = label(Label.Variant.SMALL, resolvedMeta)
          .fontFeatures("+tnum")
          .maxLines(1)
          .ellipsis()
          .textAlign(Style.Align.END)
          .basis(0)
          .grow(1)
          .minSize(0, 0);
      if (!enabled) {
        metaLabel.color(theme.text().disabled());
      }
      head.child(metaLabel);
    }
    head.child(icon(
        open ? Symbol.CHEVRON_UP : Symbol.CHEVRON_DOWN,
        enabled ? Icon.Variant.MUTED : Icon.Variant.DISABLED));

    El group = col().wFull().role("group").label(resolvedTitle).child(head);
    group.style().minWidth = Len.ZERO;

    int shown = open ? total : Math.min(latest, total);
    int hidden = total - shown;
    if (shown == 0) {
      return group;
    }

    El list = col().grow(1).minSize(0, 0).gap(0);
    if (!open && latest > 0 && hidden > 0) {
      String earlierText = texts.showEarlierSteps(hidden);
      El earlier = disclosureHead(
              theme,
              earlierText,
              theme.space().xs(),
              theme.space().s(),
              theme.size().control() + theme.space().xs(),
              onToggle)
          .radius(theme.radius().small())
          .role("button")
          .expanded(false)
          .disabled(!enabled);
      Tooltip.whenShortened(earlier, earlierText);
      earlier.child(
          icon(Symbol.MORE_HORIZONTAL, enabled ? Icon.Variant.MUTED : Icon.Variant.DISABLED));
      El earlierLabel =
          label(Label.Variant.MUTED, earlierText).maxLines(1).ellipsis().grow(1).minSize(0, 0);
      if (!enabled) {
        earlierLabel.color(theme.text().disabled());
      }
      earlier.child(earlierLabel);
      list.child(earlier.key("earlier"));
    }

    int start = total - shown;
    for (int i = start; i < total; i++) {
      Step step = present.get(i);
      boolean stepEnabled = step.onToggle() != null;
      String stepStateText = texts.toolState(step.state());
      El stepRow = disclosureHead(
              theme,
              step.title(),
              theme.space().xs(),
              theme.space().s(),
              theme.size().control() + theme.space().xs(),
              step.onToggle())
          .radius(theme.radius().small());
      configureToolHead(
          stepRow, step.title(), stepStateText, step.state(), step.open(), stepEnabled);
      if (step.icon() != null) {
        stepRow.child(icon(step.icon(), stepEnabled ? Icon.Variant.MUTED : Icon.Variant.DISABLED));
      }
      stepRow.child(label(step.title())
          .color(stepEnabled ? theme.text().primary() : theme.text().disabled())
          .maxLines(1)
          .ellipsis()
          .grow(1)
          .minSize(0, 0));
      stepRow.child(toolIndicator(step.state(), stepStateText));
      stepRow.child(icon(
          step.open() ? Symbol.CHEVRON_UP : Symbol.CHEVRON_DOWN,
          stepEnabled ? Icon.Variant.MUTED : Icon.Variant.DISABLED));
      // Keyed by position in the whole group, so focus stays on its step as the window slides.
      list.child(stepRow.key("step-" + i));
      if (step.open() && !step.details().isEmpty()) {
        float insetStart = step.icon() != null
            ? theme.size().icon() + theme.space().s() + theme.space().s()
            : theme.space().s();
        El inset = col()
            .fill(theme.surface().band())
            .radius(theme.radius().small())
            .padding(theme.space().s(), theme.space().m())
            .gap(theme.space().s())
            .margin(0, 0, theme.space().xs(), insetStart)
            .clip()
            .children(step.details().toArray(El[]::new));
        inset.style().minWidth = Len.ZERO;
        list.child(inset.key("details-" + i));
      }
    }

    float lineStart = theme.space().m() + (theme.size().icon() - theme.size().stroke()) / 2;
    float listStart = Math.max(
        0, (theme.size().icon() - theme.size().stroke()) / 2 - theme.space().xs());
    El line =
        El.el().w(theme.size().stroke()).fill(theme.border().divider()).margin(0, 0, 0, lineStart);
    El body = row().wFull().gap(theme.space().s()).children(line, list.margin(0, 0, 0, listStart));
    body.style().minWidth = Len.ZERO;
    return group.child(body);
  }

  /**
   * A collapsible context block for attached code excerpts, queries, tables or files inside a
   * message: an optional icon, an ellipsized title, optional right-aligned metadata (such as a
   * line range or file size), and a chevron that opens to show {@code details} below a divider.
   * Controlled: {@code open} decides whether the details show and {@code onToggle} runs when
   * pressed. When {@code onToggle} is null the header is a static row without a chevron; if {@code
   * open} is true its details still show. Null detail elements are skipped.
   */
  public static El context(
      Icon icon, String title, String meta, boolean open, Runnable onToggle, El... details) {
    Objects.requireNonNull(title, "title");
    Theme theme = Theme.CURRENT.get();
    boolean hasMeta = meta != null && !meta.isEmpty();
    // The metadata takes only the room the title leaves, so the title is cut short last.
    El metaLabel = hasMeta
        ? label(Label.Variant.SMALL, meta)
            .fontFeatures("+tnum")
            .maxLines(1)
            .ellipsis()
            .textAlign(Style.Align.END)
            .basis(0)
            .grow(1)
            .minSize(0, 0)
        : null;
    return disclosure(
        theme,
        icon,
        title,
        metaLabel,
        open,
        onToggle,
        false,
        onToggle != null,
        theme.border().divider(),
        Section.present(details),
        head -> {
          // The whole text of what may be cut short: the title, and the metadata after it.
          Tooltip.whenShortened(head, hasMeta ? title + " " + meta : title);
          if (onToggle != null) {
            head.role("button").expanded(open);
            if (hasMeta) {
              head.value(meta);
            }
          } else {
            head.role("group");
          }
        });
  }

  /** A quiet line with a spinner and {@link Texts#working()} to place under the last message. */
  public static El working() {
    return working(null);
  }

  /**
   * A quiet line with a spinner and {@code text} (or {@link Texts#working()} when null) to place
   * under the last message while the agent works.
   */
  public static El working(String text) {
    Theme theme = Theme.CURRENT.get();
    String shown = text != null ? text : Texts.CURRENT.get().working();
    return row()
        .role("status")
        .label(shown)
        .gap(theme.space().s())
        .align(Style.Align.CENTER)
        .children(Progress.spinner(), label(Label.Variant.MUTED, shown));
  }

  /**
   * A virtual list of {@code count} chat messages centered in a column up to {@link
   * Theme.Prose#measure()} wide that follows the newest message while at the bottom, keeps its
   * place when scrolled up, and shows a floating button over its bottom while away from the end to
   * jump back. The transcript is a {@link El#selectionScope selection scope}, so a drag or
   * Shift+click selects across {@link #user user} messages and the {@link Markdown#fragment
   * Markdown} of agent messages, including ones scrolled out of view, Control+C copies them in
   * order, and {@link Find#find} gives it a find bar across all messages. Give it a height, or let
   * it {@link El#grow} in a column, as any scroll area.
   *
   * <p>The app keeps {@code scroll} and {@code heights} in its state and passes the same instances
   * on every build. {@code transcript} enables {@link ScrollState#followEnd()} on {@code scroll}.
   *
   * @param count number of messages
   * @param message builds the message element for an index, such as {@link #user} or {@link #agent}
   * @param scroll scroll state owned by the app
   * @param heights row heights owned by the app; construct with a row key ({@link
   *     RowHeights#RowHeights(java.util.function.IntToDoubleFunction, IntFunction)}) if older
   *     messages can be inserted at the top, and with a row version ({@link
   *     RowHeights#RowHeights(java.util.function.IntToDoubleFunction, IntFunction, IntFunction)})
   *     when wrapped with {@link Find#find} so out-of-view messages are not rebuilt on every build
   */
  public static El transcript(
      int count, IntFunction<El> message, ScrollState scroll, RowHeights heights) {
    return transcript(count, message, scroll, heights, null);
  }

  /**
   * A virtual chat transcript like {@link #transcript(int, IntFunction, ScrollState, RowHeights)}
   * that also calls {@code onLoadOlder} when the top of the list comes near the view (when row
   * {@code 0} is built, which is also the case for a first message taller than the view). One look at the top can call it more than once: the app ignores the calls while a
   * page loads, and passes {@code null} once no older messages remain. Construct {@code heights} with a stable key per message so inserting older messages at
   * index {@code 0} keeps what is in view at the same place.
   *
   * @param count number of messages
   * @param message builds the message element for an index, such as {@link #user} or {@link #agent}
   * @param scroll scroll state owned by the app
   * @param heights row heights owned by the app; construct with a row key, and with a row version
   *     ({@link RowHeights#RowHeights(java.util.function.IntToDoubleFunction, IntFunction,
   *     IntFunction)}) when wrapped with {@link Find#find} so out-of-view messages are not rebuilt
   *     on every build
   * @param onLoadOlder runs when row {@code 0} is built, or {@code null} for none
   */
  public static El transcript(
      int count,
      IntFunction<El> message,
      ScrollState scroll,
      RowHeights heights,
      Runnable onLoadOlder) {
    Objects.requireNonNull(message, "message");
    Objects.requireNonNull(scroll, "scroll");
    Objects.requireNonNull(heights, "heights");
    Theme theme = Theme.CURRENT.get();
    Texts texts = Texts.CURRENT.get();
    scroll.followEnd();
    float measure = theme.prose().measure();
    El list = col()
        .key("list")
        .role("log")
        .label(texts.transcript())
        .wFull()
        .grow(1)
        .minSize(0, 0)
        .padding(theme.space().m(), theme.space().xl())
        .scrollbars(theme.scrollbars())
        .scrollState(scroll)
        .rows(count, heights, index -> {
          El at = Objects.requireNonNull(message.apply(index), "message").wFull();
          at.style().maxWidth = Len.px(measure);
          return col().align(Style.Align.CENTER).padding(theme.space().m(), 0).child(at);
        });
    if (onLoadOlder != null) {
      list.onRowsBuilt((first, last) -> {
        if (first == 0) {
          onLoadOlder.run();
        }
      });
    }
    El root = col().minSize(0, 0).selectionScope().child(list);
    if (!scroll.atEnd()) {
      El jump = Button.button(
              Button.Variant.SECONDARY,
              texts.jumpToNewest(),
              scroll::scrollToEnd,
              Button.options().icon(Symbol.ARROW_DOWN))
          .key("jump-to-newest")
          .alignSelf(Style.Align.CENTER)
          // The button lies over the list, not in it: the wheel over it scrolls the list.
          .onWheel((deltaX, deltaY, modifiers, x, y) -> {
            scroll.scrollTo(scroll.x(), scroll.y() + deltaY);
            return deltaY != 0;
          });
      Style at = jump.style();
      at.position = Style.Position.ABSOLUTE;
      at.bottom = Len.px(theme.space().l());
      root.child(jump);
    }
    return root;
  }

  /**
   * A chat composer card with a growing multi-line field and a send button, using {@link
   * #composerOptions()}.
   */
  public static El composer(String text, Consumer<String> onChange, Runnable onSend) {
    return composer(text, onChange, onSend, composerOptions());
  }

  /**
   * A chat composer card: a borderless multi-line field that grows with its text up to {@value
   * #COMPOSER_MAX_LINES} lines before scrolling, above removable attachment chips and an action
   * bar with {@code start} slots, {@code end} slots and a send button that turns into a stop button
   * while working. Plain Enter sends when the text is not blank and not working; Shift+Enter
   * inserts a new line; Enter during an input method composition does not send.
   *
   * @param text the current draft text from the app's state
   * @param onChange receives the updated text on each edit; null disables the field
   * @param onSend runs on plain Enter or pressing Send when {@code text} is not blank and not
   *     working; null disables sending
   * @param options placeholder, working/stop state, attachments, bar slots and file drop handler
   */
  public static El composer(
      String text, Consumer<String> onChange, Runnable onSend, ComposerOptions options) {
    Objects.requireNonNull(text, "text");
    Objects.requireNonNull(options, "options");
    Theme theme = Theme.CURRENT.get();
    Texts texts = Texts.CURRENT.get();
    String placeholder =
        options.placeholder != null ? options.placeholder : texts.composerPlaceholder();
    boolean canSend = !options.working && onSend != null && !text.isBlank();

    Theme.TextStyle body = theme.prose().body();
    El field = TextField.textArea(text, placeholder, onChange)
        .key("field")
        .wFull()
        .font(body.font().get())
        .fontSize(body.size())
        .lines(1)
        .maxLines(COMPOSER_MAX_LINES)
        .padding(theme.space().xs(), theme.space().s())
        .radius(0)
        .fill(0)
        .border(0, 0)
        .focusRing(0)
        .whenHovered(null)
        .whenFocused(null)
        .onKeyFirst(key -> {
          if (key.modifiers() == 0
              && (key.name().equals("Enter") || key.name().equals("NumpadEnter"))) {
            if (canSend && !key.repeat()) {
              onSend.run();
            }
            return true;
          }
          return false;
        });
    field.style().minWidth = Len.ZERO;

    // The app's slots get parents of their own, so their keys cannot meet the action's, and they
    // give way before the send/stop button does.
    El start = row()
        .key("start")
        .grow(1)
        .shrink(1)
        .gap(theme.space().s())
        .align(Style.Align.CENTER)
        .children(options.start.toArray(El[]::new));
    start.style().minWidth = Len.ZERO;
    El end = row()
        .key("end")
        .shrink(1)
        .gap(theme.space().s())
        .align(Style.Align.CENTER)
        .children(options.end.toArray(El[]::new));
    end.style().minWidth = Len.ZERO;
    El bar = row()
        .key("bar")
        .wFull()
        .gap(theme.space().s())
        .align(Style.Align.CENTER)
        .children(start, end);
    El action = options.working
        ? Button.icon(Button.Variant.SECONDARY, Symbol.STOP, texts.stop(), options.onStop)
            .key("action")
        : Button.icon(
                Button.Variant.PRIMARY, Symbol.ARROW_UP, texts.send(), canSend ? onSend : null)
            .key("action");
    bar.child(action.shrink(0));

    El card = Card.surface(theme, Card.Variant.OUTLINED)
        .wFull()
        .padding(theme.space().m())
        .gap(theme.space().s())
        .child(field);
    if (!options.attachments.isEmpty()) {
      El chips = row()
          .key("attachments")
          .wFull()
          .wrap()
          .gap(theme.space().xs())
          .align(Style.Align.CENTER)
          .motion(theme.motion().feedback());
      chips.style().minWidth = Len.ZERO;
      for (int i = 0; i < options.attachments.size(); i++) {
        String name = options.attachments.get(i);
        int index = i;
        Badge.Options badge = Badge.options().icon(Symbol.DOCUMENT).toolbar();
        if (options.onRemoveAttachment != null) {
          badge.onRemove(() -> options.onRemoveAttachment.accept(index));
        }
        El chip = Badge.badge(name, badge).key("attachment-" + i).shrink(1);
        chip.style().minWidth = Len.ZERO;
        chip.style().maxWidth = Len.percent(1);
        // A long name gives way to keep its remove button: the badge's icon, then its name.
        chip.children().get(1).maxLines(1).ellipsis().shrink(1).minSize(0, 0);
        chips.child(chip);
      }
      card.child(chips);
    }
    card.child(bar)
        .whenFocusWithin(e -> e.border(theme.size().stroke(), theme.accent().edge()));
    card.style().minWidth = Len.ZERO;
    if (options.onFiles != null) {
      card.onFileDrop(drop -> {
        if (drop.phase() == Drop.Phase.DROP && drop.payload() instanceof List<?> raw) {
          List<Path> paths = new ArrayList<>(raw.size());
          for (Object item : raw) {
            if (item instanceof Path path) {
              paths.add(path);
            }
          }
          if (!paths.isEmpty()) {
            options.onFiles.accept(List.copyOf(paths));
          }
        }
      });
    }
    return card;
  }

  private static El userMessage(Theme theme, Options options, String copyText, El bubble) {
    Texts texts = Texts.CURRENT.get();
    bubble
        .padding(theme.space().m(), theme.space().l())
        .radius(theme.radius().large())
        .fill(theme.chat().bubble())
        .border(theme.size().stroke(), theme.border().subtle());
    bubble.style().minWidth = Len.ZERO;

    El message = col()
        .wFull()
        .role("article")
        .label(texts.userMessage())
        .align(Style.Align.END)
        .gap(theme.space().xs())
        .child(bubble);
    El footer = footer(theme, texts, options, copyText);
    if (footer != null) {
      message.child(footer);
    }
    if (options.copied != null) {
      message.overlay(Button.ending(theme, options.copied));
    }
    return message;
  }

  private static El toolIndicator(ToolState state, String stateText) {
    return switch (state) {
      case RUNNING -> Progress.spinner().label(stateText);
      case DONE -> icon(Symbol.CHECKMARK_CIRCLE, Icon.Variant.SUCCESS).label(stateText);
      case FAILED -> icon(Symbol.ERROR_CIRCLE, Icon.Variant.DANGER).label(stateText);
    };
  }

  private static void configureToolHead(
      El head, String title, String stateText, ToolState state, boolean open, boolean enabled) {
    Tooltip.whenShortened(head, title)
        .role("button")
        .value(stateText)
        .expanded(open)
        .disabled(!enabled)
        .busy(state == ToolState.RUNNING)
        .invalid(state == ToolState.FAILED);
  }

  private static El disclosureHead(
      Theme theme, String label, float padY, float padX, float minHeight, Runnable onToggle) {
    El head = row()
        .wFull()
        .label(label)
        .padding(padY, padX)
        .gap(theme.space().s())
        .align(Style.Align.CENTER)
        .motion(theme.motion().feedback());
    head.style().minHeight = Len.px(minHeight);
    if (onToggle != null) {
      head.focusable()
          .keepFocus()
          .focusRing(theme.accent().edge())
          .cursor(Cursor.POINTER)
          .onPress(onToggle)
          .whenHovered(e -> e.fill(theme.surface().hover()))
          .whenPressed(e -> e.fill(theme.surface().pressed()));
    }
    return head;
  }

  private static El disclosure(
      Theme theme,
      Icon icon,
      String title,
      El trailing,
      boolean open,
      Runnable onToggle,
      boolean disabled,
      boolean showChevron,
      int borderColor,
      El[] details,
      Consumer<El> configureHead) {
    boolean showDetails = open && details.length > 0;
    float innerRadius = Math.max(0, theme.radius().medium() - theme.size().stroke());

    El head = disclosureHead(
        theme,
        title,
        theme.space().s(),
        theme.space().m(),
        theme.size().control() + theme.space().s(),
        onToggle);
    if (showDetails) {
      head.radius(innerRadius, innerRadius, 0, 0);
    } else {
      head.radius(innerRadius);
    }
    configureHead.accept(head);
    if (icon != null) {
      head.child(icon(icon, disabled ? Icon.Variant.DISABLED : Icon.Variant.MUTED));
    }
    head.child(label(title)
        .color(disabled ? theme.text().disabled() : theme.text().primary())
        .maxLines(1)
        .ellipsis()
        // A trailing element that grows, as a context's metadata, gets only the room left over.
        .grow(trailing != null && trailing.style().grow > 0 ? 0 : 1)
        .minSize(0, 0));
    if (trailing != null) {
      head.child(trailing);
    }
    if (showChevron) {
      head.child(icon(
          open ? Symbol.CHEVRON_UP : Symbol.CHEVRON_DOWN,
          disabled ? Icon.Variant.DISABLED : Icon.Variant.MUTED));
    }

    El box = col()
        .wFull()
        .radius(theme.radius().medium())
        .border(theme.size().stroke(), borderColor)
        .fill(theme.surface().band())
        .child(head);
    if (showDetails) {
      El body = col()
          .wFull()
          .padding(theme.space().m())
          .gap(theme.space().s())
          .fill(theme.surface().panel())
          .radius(0, 0, innerRadius, innerRadius)
          .clip()
          .children(details);
      body.style().minWidth = Len.ZERO;
      box.child(Divider.divider()).child(body);
    }
    return box;
  }

  private static El agentMessage(Options options, String copyText, El[] parts) {
    Theme theme = Theme.CURRENT.get();
    Texts texts = Texts.CURRENT.get();
    El message = col()
        .wFull()
        .role("article")
        .label(texts.agentMessage())
        .gap(theme.space().m());
    message.style().minWidth = Len.ZERO;
    for (El part : parts) {
      message.child(part);
    }
    El footer = footer(theme, texts, options, copyText);
    if (footer != null) {
      message.child(footer.alignSelf(Style.Align.START));
    }
    if (options.copied != null) {
      message.overlay(Button.ending(theme, options.copied));
    }
    return message;
  }

  private static El footer(Theme theme, Texts texts, Options options, String copyText) {
    boolean hasCopy = options.clipboard != null && copyText != null;
    boolean hasTime = options.time != null && !options.time.isEmpty();
    if (!hasCopy && options.actions.isEmpty() && !hasTime) {
      return null;
    }
    El bar = row().gap(theme.space().s()).align(Style.Align.CENTER);
    if (hasCopy) {
      bar.child(Button.copy(options.clipboard, copyText, texts.copyMessage(), options.copied)
          .key("copy"));
    }
    for (El action : options.actions) {
      bar.child(action.shrink(0));
    }
    if (hasTime) {
      bar.child(label(Label.Variant.SMALL, options.time).fontFeatures("+tnum").shrink(0));
    }
    return bar;
  }
}
