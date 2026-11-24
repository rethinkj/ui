package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Icon.icon;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.Clipboard;
import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Style;
import java.util.List;
import java.util.Objects;

/**
 * Chat messages in the theme's tokens: a user message in a bubble at the end side ({@link #user}),
 * an agent's answer as Markdown prose or composed parts over the full width ({@link #agent}),
 * collapsible one-line tool call rows ({@link #tool}), and a spinner line while the agent is still
 * working ({@link #working}).
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
 * }</pre>
 *
 * <p>All state stays in the app: whether a tool call is open is passed in and flipped by its {@code
 * onToggle} handler, and copy confirmation uses a caller-owned {@link Button.Copied}. Passing a
 * null {@code onToggle} disables expanding a tool call.
 *
 * <p>In a tree dump, user and agent messages are {@code role=article} labeled by {@link
 * Texts#userMessage} and {@link Texts#agentMessage}, a tool call's header row is {@code
 * role=button} with the title as its label, {@link Texts#toolState} as its {@code value}, {@code
 * expanded} or {@code expanded=false}, {@code busy} while running and {@code invalid} when failed,
 * and {@link #working} is {@code role=status}.
 */
public final class Chat {

  /** The execution state shown on a {@link #tool} call row. */
  public enum ToolState {
    /** The tool is running: shows a {@link Progress#spinner} and sets {@code busy}. */
    RUNNING,
    /** The tool finished without error: shows a check mark in {@link Theme.Status#success}. */
    DONE,
    /** The tool failed: shows an error icon in {@link Theme.Status#danger} and sets {@code invalid}. */
    FAILED
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
     * clipboard}. For {@link #user} this is the message text; for {@link #agent(MarkdownDocument,
     * Options)} this is {@link Markdown#text(MarkdownDocument)}. Null turns copying off.
     */
    public Options copy(Clipboard clipboard) {
      return copy(clipboard, null);
    }

    /**
     * Adds a quiet copy button under the message that writes {@code text} to {@code clipboard},
     * for {@link #agent(Options, El...)} or when copying source text other than the rendered plain
     * text.
     */
    public Options copy(Clipboard clipboard, String text) {
      this.clipboard = clipboard;
      this.copyText = text;
      return this;
    }

    /**
     * State for the copy button's brief confirmation check mark and tooltip; null for no
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
     * When omitted, {@link Markdown#options()} is used (with {@link #copy(Clipboard)} forwarded to
     * code blocks if set).
     */
    public Options markdown(Markdown.Options options) {
      this.markdown = options;
      return this;
    }
  }

  private Chat() {}

  public static Options options() {
    return new Options();
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
    Texts texts = Texts.CURRENT.get();
    Theme.TextStyle style = theme.prose().body();
    // Plain text breaks lines at \n only and has no tab stops; the copy action keeps the original.
    // shortcut: a tab shows as four spaces, not aligned to stops; use a field's tab stops if
    // needed.
    String shown = text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ");
    El body = El.text(shown)
        .wrapText()
        .role("paragraph")
        .font(style.font().get())
        .fontSize(style.size())
        .color(theme.text().primary())
        .shrink(1);
    body.style().minWidth = Len.ZERO;
    El bubble = row()
        .padding(theme.space().m(), theme.space().l())
        .radius(theme.radius().large())
        .fill(theme.chat().bubble())
        .border(theme.size().stroke(), theme.border().subtle())
        .child(body);
    bubble.style().maxWidth = Len.percent(theme.chat().bubbleWidth());
    bubble.style().minWidth = Len.ZERO;

    El message = col()
        .wFull()
        .role("article")
        .label(texts.userMessage())
        .align(Style.Align.END)
        .gap(theme.space().xs())
        .child(bubble);
    El footer = footer(theme, texts, options, options.copyText != null ? options.copyText : text);
    if (footer != null) {
      message.child(footer);
    }
    if (options.copied != null) {
      message.overlay(Button.ending(theme, options.copied));
    }
    return message;
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
            ? Markdown.options().copy(options.clipboard)
            : Markdown.options();
    String copyText = options.copyText != null
        ? options.copyText
        : options.clipboard != null ? Markdown.text(content) : null;
    return agentMessage(options, copyText, new El[] {Markdown.fragment(content, md)});
  }

  /**
   * An agent message composed of full-width parts stacked in order, such as {@link #tool} calls,
   * {@link Markdown#fragment} blocks and {@link #working}. Null parts are skipped.
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
    El[] present = Section.present(details);
    boolean showDetails = open && present.length > 0;
    String stateText = texts.toolState(state);
    float innerRadius = Math.max(0, theme.radius().medium() - theme.size().stroke());

    El head = row()
        .wFull()
        .role("button")
        .label(title)
        .value(stateText)
        .expanded(open)
        .disabled(!enabled)
        .busy(state == ToolState.RUNNING)
        .invalid(state == ToolState.FAILED)
        .padding(theme.space().s(), theme.space().m())
        .gap(theme.space().s())
        .align(Style.Align.CENTER)
        .motion(theme.motion().feedback());
    head.style().minHeight = Len.px(theme.size().control() + theme.space().s());
    if (showDetails) {
      head.radius(innerRadius, innerRadius, 0, 0);
    } else {
      head.radius(innerRadius);
    }
    if (icon != null) {
      head.child(icon(icon, enabled ? Icon.Variant.MUTED : Icon.Variant.DISABLED));
    }
    head.child(label(title)
        .color(enabled ? theme.text().primary() : theme.text().disabled())
        .maxLines(1)
        .ellipsis()
        .grow(1)
        .minSize(0, 0));
    El indicator = switch (state) {
      case RUNNING -> Progress.spinner().label(stateText);
      case DONE -> icon(Symbol.CHECKMARK_CIRCLE, Icon.Variant.SUCCESS).label(stateText);
      case FAILED -> icon(Symbol.ERROR_CIRCLE, Icon.Variant.DANGER).label(stateText);
    };
    head.child(indicator)
        .child(icon(
            open ? Symbol.CHEVRON_UP : Symbol.CHEVRON_DOWN,
            enabled ? Icon.Variant.MUTED : Icon.Variant.DISABLED));
    if (enabled) {
      head.focusable()
          .keepFocus()
          .focusRing(theme.accent().edge())
          .cursor(Cursor.POINTER)
          .onPress(onToggle)
          .whenHovered(e -> e.fill(theme.surface().hover()))
          .whenPressed(e -> e.fill(theme.surface().pressed()));
    }
    Tooltip.whenShortened(head, title);

    El box = col()
        .wFull()
        .radius(theme.radius().medium())
        .border(theme.size().stroke(), theme.border().subtle())
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
          .children(present);
      body.style().minWidth = Len.ZERO;
      box.child(Divider.divider()).child(body);
    }
    return box;
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
