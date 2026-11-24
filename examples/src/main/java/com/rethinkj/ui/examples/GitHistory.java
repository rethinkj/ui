package com.rethinkj.ui.examples;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.diagram.git.GitLog.gitLog;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.TitleBar.titleBar;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Highlighter;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Work;
import com.rethinkj.ui.diagram.git.Change;
import com.rethinkj.ui.diagram.git.Commit;
import com.rethinkj.ui.diagram.git.CommitDetails;
import com.rethinkj.ui.diagram.git.GitLog;
import com.rethinkj.ui.diagram.git.Ref;
import com.rethinkj.ui.editor.DiffView;
import com.rethinkj.ui.java.Java;
import com.rethinkj.ui.json.Json;
import com.rethinkj.ui.kit.AppIcon;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.render.GpuPresenter;
import com.rethinkj.ui.sql.Sql;
import com.rethinkj.ui.text.TextRendering;
import com.rethinkj.ui.window.Window;
import com.rethinkj.ui.window.WindowLoop;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The history of a git repository: the log with its graph in pages, and below it the selected
 * commit's message, changed files and the diff of the chosen file. The log can show all branches,
 * the current one or another, and only first parents; each change of view reads the log again.
 * Every git call runs off the app's thread. The repository is the current folder, or the one
 * given as the first argument.
 */
public final class GitHistory {

  /** The commits read at a time. */
  static final int PAGE = 200;

  /** Where the history comes from: git in a folder, or stored output in a test. */
  interface Source {
    /** The repository's name, for the footer. */
    String name();

    List<Ref> refs() throws IOException;

    /** How many commits the log from these commits has. */
    int count(List<String> tips, boolean firstParent) throws IOException;

    /**
     * The commits of the log from these commits after the first {@code skip}, at most {@code
     * limit}; with {@code firstParent}, each with its first parent only.
     */
    List<Commit> commits(List<String> tips, boolean firstParent, int skip, int limit)
        throws IOException;

    Details details(Commit commit) throws IOException;

    /** The file before and after the change, from the commit's first parent. */
    FileDiff diff(Commit commit, Change change) throws IOException;
  }

  /** A commit's message body and changed files. */
  record Details(String body, List<Change> changes) {}

  /** A changed file's text before and after the commit. */
  record FileDiff(String before, String after) {}

  private record FirstPage(List<Ref> refs, List<String> tips, int count, List<Commit> commits) {}

  final Source source;
  final GitLog.State log;
  final DiffView.State diffState = new DiffView.State();
  App<GitHistory> app;

  final List<Commit> commits = new ArrayList<>();
  List<Ref> refs = List.of();
  int count;
  GitLog.Scope scope = GitLog.Scope.ALL;
  boolean firstParent;
  private List<String> tips = List.of(); // where the log starts, as the refs were when it was read
  boolean complete; // the last page is read
  String error;
  private Work reading; // a page of the log

  private Commit shown; // the commit whose details are read or being read
  Details details;
  private Work readingDetails;
  Change chosen;
  FileDiff diff;
  private Work readingDiff;

  GitHistory(Source source, Clock clock) {
    this.source = source;
    this.log = new GitLog.State(clock);
  }

  // docs:start git-history-read
  /** Reads the log anew, from its first page, for the scope and first-parent view. */
  void reload() {
    cancel(reading);
    commits.clear();
    complete = false;
    error = null;
    log.clearSelection();
    show(null);
    GitLog.Scope scope = this.scope;
    boolean firstParent = this.firstParent;
    reading = app.background(
        "git log",
        () -> {
          List<Ref> refs = source.refs();
          List<String> tips = tips(refs, scope);
          return new FirstPage(
              refs,
              tips,
              source.count(tips, firstParent),
              source.commits(tips, firstParent, 0, PAGE));
        },
        this::firstPage,
        this::logFailed);
  }

  /** Reads the next page of older commits, which the log asks for at its end. */
  void more() {
    if (reading != null) {
      return;
    }
    List<String> tips = this.tips;
    boolean firstParent = this.firstParent;
    int skip = commits.size();
    reading = app.background(
        "git log after " + skip,
        () -> source.commits(tips, firstParent, skip, PAGE),
        this::append,
        this::logFailed);
  }
  // docs:end git-history-read

  /** The commits a scope's log starts from: those of the refs it takes. */
  static List<String> tips(List<Ref> refs, GitLog.Scope scope) {
    return refs.stream()
        .filter(ref -> switch (scope.kind()) {
          case ALL -> true;
          case CURRENT -> ref.kind() == Ref.Kind.HEAD;
          case BRANCH ->
            (ref.kind() == Ref.Kind.BRANCH || ref.kind() == Ref.Kind.HEAD)
                && ref.name().equals(scope.branch());
        })
        .map(Ref::commit)
        .distinct()
        .toList();
  }

  void scope(GitLog.Scope scope) {
    this.scope = scope;
    reload();
  }

  void firstParent(boolean on) {
    firstParent = on;
    reload();
  }

  private void firstPage(FirstPage page) {
    count = page.count();
    refs = page.refs();
    tips = page.tips();
    append(page.commits());
    if (!commits.isEmpty()) {
      log.select(0);
    }
  }

  private void append(List<Commit> page) {
    reading = null;
    commits.addAll(page);
    complete = page.size() < PAGE;
  }

  private void logFailed(Exception failure) {
    reading = null;
    complete = true;
    failed(failure);
  }

  private void failed(Exception failure) {
    error = failure.getMessage();
  }

  /**
   * Reads the details of {@code commit} when it is not the one shown, and then the diff of its
   * first file. Called as the view is built, since the log tells no one about its selection.
   */
  void show(Commit commit) {
    if (commit == shown) {
      return;
    }
    shown = commit;
    details = null;
    cancel(readingDetails);
    choose(null);
    if (commit != null) {
      readingDetails = app.background(
          "details of " + commit.id(),
          () -> source.details(commit),
          details -> {
            this.details = details;
            if (!details.changes().isEmpty()) {
              choose(details.changes().getFirst());
            }
          },
          this::failed);
    }
  }

  /** Shows the diff of {@code change} in the shown commit. */
  void choose(Change change) {
    chosen = change;
    diff = null;
    cancel(readingDiff);
    if (change != null) {
      Commit commit = shown;
      readingDiff = app.background(
          "diff of " + change.path(),
          () -> source.diff(commit, change),
          diff -> this.diff = diff,
          this::failed);
    }
  }

  private static void cancel(Work work) {
    if (work != null) {
      work.cancel();
    }
  }

  /** The commit under the cursor, or null with none or a range selected. */
  Commit selected() {
    int cursor = log.cursor();
    return cursor < 0 || cursor >= commits.size() || log.hasRange() ? null : commits.get(cursor);
  }

  // docs:start git-history-view
  public static El view(GitHistory state) {
    state.show(state.selected());
    Details details = state.details;
    El panel = CommitDetails.details(
        state.log,
        state.commits,
        state.refs,
        CommitDetails.options()
            .body(details == null ? null : details.body())
            .changes(details == null ? null : details.changes())
            .chosen(state.chosen)
            .onChoose(state::choose)
            .diff(state.diffView()));
    return gitLog(
        state.log,
        state.commits,
        state.refs,
        GitLog.options()
            .details(panel)
            .scope(state.scope, state::scope)
            .firstParent(state.firstParent, state::firstParent)
            .onMore(state.complete ? null : state::more)
            .total(state.count)
            .status(state.error != null ? state.error : state.source.name()),
        button(Button.Variant.SECONDARY, Symbol.ARROW_SYNC, "Refresh", state::reload));
  }
  // docs:end git-history-view

  private El diffView() {
    if (diff == null) {
      return null;
    }
    return DiffView.unified(app, diff.before(), diff.after(), diffState, highlighter(chosen.path()))
        .wFull()
        .h(240);
  }

  static Highlighter highlighter(String path) {
    String name = path.toLowerCase(Locale.ROOT);
    if (name.endsWith(".java")) {
      return Java.of();
    }
    if (name.endsWith(".sql")) {
      return Sql.of();
    }
    return name.endsWith(".json") ? Json.of() : null;
  }

  public static El window(GitHistory state) {
    Theme theme = Theme.CURRENT.get();
    return col()
        .wFull()
        .hFull()
        .fill(theme.surface().window())
        .child(titleBar(AppIcon.appIcon(16, Identity.TEAL, Symbol.BRANCH), "Git History"))
        .child(view(state)
            .grow(1)
            .minSize(0, 0)
            .margin(0, theme.space().m(), theme.space().m(), theme.space().m()));
  }

  public static void main(String[] args) throws IOException {
    boolean folderGiven = args.length > 0 && !args[0].startsWith("--");
    Path folder = Path.of(folderGiven ? args[0] : ".");
    String[] scriptArgs = folderGiven ? Arrays.copyOfRange(args, 1, args.length) : args;
    run(scriptArgs, new Git(folder), Clock.systemDefaultZone());
  }

  static void run(String[] args, Source source, Clock clock) throws IOException {
    GitHistory state = new GitHistory(source, clock);
    App<GitHistory> app = new App<>(state, GitHistory::window);
    state.app = app;
    state.reload();
    // A script's images are stored, so they must not depend on the platform.
    app.textRendering(TextRendering.LINUX);
    if (Script.handle(args, app)) {
      return;
    }
    app.textRendering(TextRendering.platform());
    Window.Options options = Identity.options(
            GitHistory.class, "Git History", Identity.TEAL, Symbol.BRANCH)
        .frameless()
        .minimumSize(680, 460);
    try (Window window = Window.open("Git History", 1100, 820, options);
        GpuPresenter presenter = new GpuPresenter(window)) {
      WindowLoop.run(window, app, presenter);
    }
  }

  // docs:start git-history-parse
  /** The log's format: hash, parents, author, date and subject, apart by the unit separator. */
  static final String LOG_FORMAT = "%H%x1f%P%x1f%an%x1f%aI%x1f%s";

  /** The commits of a {@code git log} in {@link #LOG_FORMAT}. */
  static List<Commit> parseLog(String output) {
    List<Commit> commits = new ArrayList<>();
    for (String line : output.lines().toList()) {
      String[] fields = line.split("\u001f", 5);
      List<String> parents = fields[1].isEmpty() ? List.of() : List.of(fields[1].split(" "));
      commits.add(new Commit(
          fields[0],
          parents,
          fields[4],
          fields[2],
          OffsetDateTime.parse(fields[3]).toInstant()));
    }
    return commits;
  }
  // docs:end git-history-parse

  /** The refs format: the commit (a tag's target), the ref's name, a star for HEAD, a symref. */
  static final String REF_FORMAT =
      "%(if)%(*objectname)%(then)%(*objectname)%(else)%(objectname)%(end)"
          + "%09%(refname)%09%(HEAD)%09%(symref)";

  /**
   * The branches, remote branches and tags of a {@code git for-each-ref} in {@link #REF_FORMAT},
   * with the checked-out branch as HEAD, and a detached {@code head} where no branch is.
   */
  static List<Ref> parseRefs(String output, String head) {
    List<Ref> refs = new ArrayList<>();
    boolean onBranch = false;
    for (String line : output.lines().toList()) {
      String[] fields = line.split("\t", -1);
      String name = fields[1];
      if (!fields[3].isEmpty()) {
        continue; // a symbolic ref, as origin/HEAD
      } else if (name.startsWith("refs/heads/")) {
        boolean checkedOut = fields[2].equals("*");
        onBranch |= checkedOut;
        refs.add(
            new Ref(name.substring(11), checkedOut ? Ref.Kind.HEAD : Ref.Kind.BRANCH, fields[0]));
      } else if (name.startsWith("refs/remotes/")) {
        refs.add(new Ref(name.substring(13), Ref.Kind.REMOTE, fields[0]));
      } else if (name.startsWith("refs/tags/")) {
        refs.add(new Ref(name.substring(10), Ref.Kind.TAG, fields[0]));
      }
    }
    if (!onBranch && head != null) {
      refs.add(new Ref("HEAD", Ref.Kind.HEAD, head));
    }
    return refs;
  }

  /** The changed files of a {@code git show --name-status} and its {@code --numstat}. */
  static List<Change> parseChanges(String nameStatus, String numstat) {
    List<String> statuses = nameStatus.lines().filter(line -> !line.isEmpty()).toList();
    List<String> counts = numstat.lines().filter(line -> !line.isEmpty()).toList();
    List<Change> changes = new ArrayList<>();
    for (int index = 0; index < statuses.size(); index++) {
      String[] fields = statuses.get(index).split("\t");
      String[] lines = counts.get(index).split("\t");
      boolean moved = fields.length == 3; // a rename or copy: from, to
      Change.Status status = switch (fields[0].charAt(0)) {
        case 'A', 'C' -> Change.Status.ADDED;
        case 'D' -> Change.Status.DELETED;
        case 'R' -> Change.Status.RENAMED;
        default -> Change.Status.MODIFIED;
      };
      changes.add(new Change(
          status,
          fields[moved ? 2 : 1],
          status == Change.Status.RENAMED ? fields[1] : null,
          lines[0].equals("-") ? 0 : Integer.parseInt(lines[0]), // "-" for a binary file
          lines[1].equals("-") ? 0 : Integer.parseInt(lines[1])));
    }
    return changes;
  }

  /** Runs git in a repository's folder. */
  static final class Git implements Source {

    /** Larger files are not shown in the diff. */
    private static final int LARGEST = 512 * 1024;

    private final Path folder;

    Git(Path folder) {
      this.folder = folder.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
      Path name = folder.getFileName();
      return name == null ? folder.toString() : name.toString();
    }

    @Override
    public int count(List<String> tips, boolean firstParent) throws IOException {
      return tips.isEmpty()
          ? 0
          : Integer.parseInt(git(walk(tips, firstParent, "rev-list", "--count")).strip());
    }

    @Override
    public List<Ref> refs() throws IOException {
      String head = text(run(List.of("rev-parse", "--verify", "--quiet", "HEAD"), null, true));
      return parseRefs(
          git(List.of("for-each-ref", "--format=" + REF_FORMAT)),
          head == null ? null : head.strip());
    }

    @Override
    public List<Commit> commits(List<String> tips, boolean firstParent, int skip, int limit)
        throws IOException {
      if (tips.isEmpty()) {
        return List.of();
      }
      String log = git(walk(
          tips,
          firstParent,
          "log",
          "--topo-order",
          "--format=" + LOG_FORMAT,
          "--skip=" + skip,
          "-n" + limit));
      return parseLog(log).stream()
          .map(commit -> firstParent && commit.parents().size() > 1
              ? new Commit(
                  commit.id(),
                  commit.parents().subList(0, 1),
                  commit.subject(),
                  commit.author(),
                  commit.time())
              : commit)
          .toList();
    }

    @Override
    public Details details(Commit commit) throws IOException {
      // -m --first-parent: a merge's changes against the branch it was merged into
      List<String> show = List.of("show", "-m", "--first-parent", "-M", "--format=", commit.id());
      return new Details(
          git(List.of("show", "--no-patch", "--format=%b", commit.id())).strip(),
          parseChanges(git(with(show, "--name-status")), git(with(show, "--numstat"))));
    }

    @Override
    public FileDiff diff(Commit commit, Change change) throws IOException {
      String before = change.status() == Change.Status.ADDED || commit.parents().isEmpty()
          ? ""
          : file(
              commit.parents().getFirst(),
              change.oldPath() != null ? change.oldPath() : change.path());
      String after =
          change.status() == Change.Status.DELETED ? "" : file(commit.id(), change.path());
      return new FileDiff(before, after);
    }

    private String file(String commit, String path) throws IOException {
      byte[] bytes = run(List.of("show", commit + ":" + path), null, false);
      if (bytes.length > LARGEST) {
        return "(too large to show)";
      }
      for (byte b : bytes) {
        if (b == 0) {
          return "(binary file)";
        }
      }
      return text(bytes);
    }

    /**
     * A walk of the history from {@code tips}: the command, then the tips on its standard input,
     * which holds more of them than a Windows command line.
     */
    private Walk walk(List<String> tips, boolean firstParent, String... command) {
      List<String> arguments = new ArrayList<>(List.of(command));
      if (firstParent) {
        arguments.add("--first-parent");
      }
      arguments.add("--stdin");
      return new Walk(arguments, String.join("\n", tips) + "\n");
    }

    private record Walk(List<String> arguments, String input) {}

    private String git(Walk walk) throws IOException {
      return text(run(walk.arguments(), walk.input(), false));
    }

    private static List<String> with(List<String> command, String argument) {
      List<String> longer = new ArrayList<>(command);
      longer.add(argument);
      return longer;
    }

    private String git(List<String> arguments) throws IOException {
      return text(run(arguments, null, false));
    }

    private static String text(byte[] bytes) {
      return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Git's output for {@code input} on its standard input, or null when it fails and {@code
     * mayFail}; else its message as the error.
     */
    private byte[] run(List<String> arguments, String input, boolean mayFail) throws IOException {
      // what the output is read as, whatever the user's configuration says
      List<String> command = new ArrayList<>(List.of(
          "git",
          "-c",
          "log.showSignature=false",
          "-c",
          "core.quotepath=false",
          "-c",
          "color.ui=false"));
      command.addAll(arguments);
      Process process = new ProcessBuilder(command).directory(folder.toFile()).start();
      try {
        try (var stdin = process.getOutputStream()) {
          if (input != null) {
            stdin.write(input.getBytes(StandardCharsets.UTF_8));
          }
        }
        // shortcut: stderr is read after stdout, which blocks should git write more than a pipe
        // holds to it
        byte[] output = process.getInputStream().readAllBytes();
        String message = text(process.getErrorStream().readAllBytes()).strip();
        int exit = process.waitFor();
        if (exit == 0) {
          return output;
        }
        if (mayFail) {
          return null;
        }
        throw new IOException(message.isEmpty() ? "git exited with " + exit : message);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IOException("git was cancelled", interrupted);
      } finally {
        process.destroyForcibly(); // also when the work is cancelled while git runs
      }
    }
  }
}
