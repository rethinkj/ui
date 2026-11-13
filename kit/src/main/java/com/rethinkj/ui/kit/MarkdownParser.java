package com.rethinkj.ui.kit;

import com.rethinkj.ui.kit.MarkdownDocument.Align;
import com.rethinkj.ui.kit.MarkdownDocument.Block;
import com.rethinkj.ui.kit.MarkdownDocument.Inline;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Markdown for {@link MarkdownDocument#parse}, after the algorithm of the CommonMark
 * specification (0.31.2) and its reference implementation: blocks line by line with a stack of
 * open containers, then the inline parts of each paragraph, heading and table cell with a stack of
 * delimiters and one of brackets, so that the time grows with the length of the text.
 *
 * <p>Left out on purpose: HTML blocks and inline HTML (both stay text, which is what the component
 * shows), tabs inside a line's text after a container's marker other than right after it, bare
 * e-mail addresses as links, and containers or inline parts nested deeper than {@link #MAX_DEPTH},
 * which become text, so that nothing recurses without bound.
 */
final class MarkdownParser {

  /** Containers, and inline parts inside each other, at most this deep; deeper ones are text. */
  static final int MAX_DEPTH = 32;
  /** Link destinations and titles are looked for this far at most, so that each costs a bound. */
  private static final int MAX_SCAN = 4096;

  private static final int CODE_INDENT = 4;

  private MarkdownParser() {}

  static MarkdownDocument parse(String source) {
    String text = source == null ? "" : source;
    try {
      return read(text);
    } catch (RuntimeException | StackOverflowError e) {
      // shortcut: a fault of the reader shows the text as it is rather than failing the view;
      // the tests make sure it never comes to this.
      return new MarkdownDocument(List.of(new MarkdownDocument.CodeBlock("", text)));
    }
  }

  /** As {@link #parse}, but a fault of the reader throws: for the tests. */
  static MarkdownDocument read(String text) {
    return new MarkdownParser().run(text.replace('\0', '�'));
  }

  // ---- Blocks

  private static final int DOC = 0;
  private static final int QUOTE = 1;
  private static final int LIST = 2;
  private static final int ITEM = 3;
  private static final int PARA = 4;
  private static final int HEADING = 5;
  private static final int FENCE = 6;
  private static final int INDENTED = 7;
  private static final int BREAK = 8;
  private static final int TABLE = 9;

  /** A block while the lines come in. */
  private static final class B {
    final int type;
    B parent;
    final List<B> children = new ArrayList<>();
    boolean open = true;
    boolean lastLineBlank;
    int startLine;
    final StringBuilder content = new StringBuilder();
    int level; // a heading's
    // A list's and an item's marker.
    boolean ordered;
    char marker; // the bullet, or the delimiter after the number: . or )
    int start;
    int markerOffset;
    int padding;
    boolean tight = true;
    // A fenced code block's fence.
    char fenceChar;
    int fenceLength;
    int fenceOffset;
    // A table's.
    List<Align> aligns;
    List<String> header;
    final List<List<String>> rows = new ArrayList<>();

    B(int type) {
      this.type = type;
    }

    B last() {
      return children.isEmpty() ? null : children.getLast();
    }

    int depth() {
      int depth = 0;
      for (B b = parent; b != null; b = b.parent) {
        depth++;
      }
      return depth;
    }
  }

  private final B doc = new B(DOC);
  private B tip = doc;
  private B oldTip = doc;
  private B lastMatched = doc;
  private boolean allClosed = true;
  private final Map<String, String[]> refs = new HashMap<>();

  // The line being read, and where in it: an index, a column (tabs to the next multiple of 4).
  private String line = "";
  private int lineNumber;
  private int offset;
  private int column;
  private int nextNonspace;
  private int nextNonspaceColumn;
  private int indent;
  private boolean indented;
  private boolean blank;
  private boolean partiallyConsumedTab;

  private MarkdownDocument run(String text) {
    int at = 0;
    int length = text.length();
    while (at <= length) {
      int end = at;
      while (end < length && text.charAt(end) != '\n' && text.charAt(end) != '\r') {
        end++;
      }
      if (end == length && at == length && at > 0) {
        break; // the text ends in a line break: no last empty line
      }
      incorporate(text.substring(at, end));
      if (end < length
          && text.charAt(end) == '\r'
          && end + 1 < length
          && text.charAt(end + 1) == '\n') {
        end++;
      }
      at = end + 1;
    }
    while (tip != null) {
      B parent = tip.parent;
      finish(tip);
      tip = parent;
    }
    List<Block> blocks = new ArrayList<>();
    for (B b : doc.children) {
      convert(b, blocks);
    }
    return new MarkdownDocument(identify(blocks));
  }

  private void incorporate(String ln) {
    line = ln;
    lineNumber++;
    offset = 0;
    column = 0;
    blank = false;
    partiallyConsumedTab = false;
    B container = doc;
    oldTip = tip;
    boolean allMatched = true;
    B last;
    while ((last = container.last()) != null && last.open) {
      container = last;
      findNextNonspace();
      int result = continues(container);
      if (result == 2) {
        return;
      }
      if (result == 1) {
        allMatched = false;
        container = container.parent;
        break;
      }
    }
    allClosed = container == oldTip;
    lastMatched = container;
    boolean matchedLeaf = container.type == HEADING
        || container.type == BREAK
        || container.type == FENCE
        || container.type == INDENTED;
    while (!matchedLeaf) {
      findNextNonspace();
      int result = start(container);
      if (result == 0) {
        advanceNextNonspace();
        break;
      }
      container = tip;
      if (result == 2) {
        matchedLeaf = true;
      }
    }
    if (!allClosed && !blank && tip.type == PARA) {
      addLine(); // a lazy continuation line
      return;
    }
    closeUnmatched();
    if (blank && container.last() != null) {
      container.last().lastLineBlank = true;
    }
    int t = container.type;
    boolean lastLineBlank = blank
        && !(t == QUOTE
            || t == FENCE
            || t == ITEM && container.children.isEmpty() && container.startLine == lineNumber);
    for (B b = container; b != null; b = b.parent) {
      b.lastLineBlank = lastLineBlank;
    }
    if (t == FENCE || t == INDENTED || t == HEADING) {
      if (t != HEADING) {
        addLine();
      }
    } else if (t == TABLE) {
      if (!blank && offset < line.length()) {
        container.rows.add(cells(line.substring(offset)));
      }
    } else if (t == PARA) {
      if (!blank) {
        addLine();
      }
    } else if (offset < line.length() && !blank) {
      container = add(PARA);
      advanceNextNonspace();
      addLine();
    }
  }

  /** 0: the line continues the block; 1: it does not; 2: it closed the block and is used up. */
  private int continues(B b) {
    switch (b.type) {
      case QUOTE -> {
        if (!indented && peek(nextNonspace) == '>') {
          advanceNextNonspace();
          advanceOffset(1, false);
          if (isSpaceOrTab(peek(offset))) {
            advanceOffset(1, true);
          }
          return 0;
        }
        return 1;
      }
      case ITEM -> {
        if (blank) {
          if (b.children.isEmpty()) {
            return 1; // an item starts with at most one blank line
          }
          advanceNextNonspace();
        } else if (indent >= b.markerOffset + b.padding) {
          advanceOffset(b.markerOffset + b.padding, true);
        } else {
          return 1;
        }
        return 0;
      }
      case FENCE -> {
        if (!indented && peek(nextNonspace) == b.fenceChar) {
          int n = 0;
          int i = nextNonspace;
          while (peek(i) == b.fenceChar) {
            n++;
            i++;
          }
          while (isSpaceOrTab(peek(i))) {
            i++;
          }
          if (n >= b.fenceLength && i >= line.length()) {
            finish(b);
            tip = b.parent;
            return 2;
          }
        }
        for (int i = b.fenceOffset; i > 0 && isSpaceOrTab(peek(offset)); i--) {
          advanceOffset(1, true);
        }
        return 0;
      }
      case INDENTED -> {
        if (indent >= CODE_INDENT) {
          advanceOffset(CODE_INDENT, true);
        } else if (blank) {
          advanceNextNonspace();
        } else {
          return 1;
        }
        return 0;
      }
      case PARA, TABLE -> {
        return blank ? 1 : 0;
      }
      case HEADING, BREAK -> {
        return 1;
      }
      default -> {
        return 0; // the document and lists; items decide
      }
    }
  }

  private static final Pattern ATX = Pattern.compile("#{1,6}(?:[ \\t]+|$)");
  private static final Pattern FENCE_START = Pattern.compile("`{3,}(?!.*`)|~{3,}");
  private static final Pattern SETEXT = Pattern.compile("(?:=+|-+)[ \\t]*$");

  /** Three or more of one of {@code * _ -}, with nothing but spaces and tabs between them. */
  private static boolean thematicBreak(String rest) {
    char c = rest.charAt(0);
    int count = 0;
    for (int i = 0; i < rest.length(); i++) {
      char ch = rest.charAt(i);
      if (ch == c) {
        count++;
      } else if (!isSpaceOrTab(ch)) {
        return false;
      }
    }
    return count >= 3;
  }

  /** A table's delimiter row: cells of {@code -} with a colon at either end, between pipes. */
  private static boolean delimiterRow(String rest) {
    String s = rest.strip();
    if (s.startsWith("|")) {
      s = s.substring(1);
    }
    if (s.endsWith("|")) {
      s = s.substring(0, s.length() - 1);
    }
    if (s.isBlank()) {
      return false;
    }
    for (String cell : s.split("\\|", -1)) {
      String c = cell.strip();
      int from = c.startsWith(":") ? 1 : 0;
      int to = c.endsWith(":") && c.length() > from ? c.length() - 1 : c.length();
      if (to <= from) {
        return false;
      }
      for (int i = from; i < to; i++) {
        if (c.charAt(i) != '-') {
          return false;
        }
      }
    }
    return true;
  }

  /** 0: no block starts here; 1: a container started; 2: a leaf started. */
  private int start(B container) {
    char c = peek(nextNonspace);
    boolean interrupting = container.type == PARA || container.type == TABLE;
    if (!indented && c == '>' && container.depth() < MAX_DEPTH) {
      advanceNextNonspace();
      advanceOffset(1, false);
      if (isSpaceOrTab(peek(offset))) {
        advanceOffset(1, true);
      }
      closeUnmatched();
      add(QUOTE);
      return 1;
    }
    String rest = line.substring(Math.min(nextNonspace, line.length()));
    if (!indented && c == '#') {
      Matcher m = ATX.matcher(rest);
      if (m.lookingAt()) {
        advanceNextNonspace();
        advanceOffset(m.end(), false);
        closeUnmatched();
        B heading = add(HEADING);
        heading.level = m.group().strip().length();
        String content = line.substring(offset);
        content = content.replaceAll("^[ \\t]*#+[ \\t]*$", "").replaceAll("[ \\t]+#+[ \\t]*$", "");
        heading.content.append(content.strip());
        advanceOffset(line.length() - offset, false);
        return 2;
      }
    }
    if (!indented && (c == '`' || c == '~')) {
      Matcher m = FENCE_START.matcher(rest);
      if (m.lookingAt()) {
        closeUnmatched();
        B fence = add(FENCE);
        fence.fenceLength = m.end();
        fence.fenceChar = c;
        fence.fenceOffset = indent;
        advanceNextNonspace();
        advanceOffset(m.end(), false);
        return 2;
      }
    }
    if (!indented && container.type == PARA && c == '|'
        || !indented && container.type == PARA && rest.indexOf('|') >= 0
        || !indented && container.type == PARA && (c == '-' || c == ':') && delimiterRow(rest)) {
      if (table(container, rest)) {
        return 2;
      }
    }
    if (!indented
        && container.type == PARA
        && (c == '=' || c == '-')
        && SETEXT.matcher(rest).lookingAt()) {
      closeUnmatched();
      references(container);
      if (!container.content.isEmpty()) {
        B heading = new B(HEADING);
        heading.level = c == '=' ? 1 : 2;
        heading.content.append(container.content.toString().strip());
        heading.startLine = container.startLine;
        B parent = container.parent;
        parent.children.set(parent.children.size() - 1, heading);
        heading.parent = parent;
        tip = heading;
        advanceOffset(line.length() - offset, false);
        return 2;
      }
    }
    if (!indented && (c == '*' || c == '_' || c == '-') && thematicBreak(rest)) {
      closeUnmatched();
      add(BREAK);
      advanceOffset(line.length() - offset, false);
      return 2;
    }
    if ((!indented || container.type == LIST) && container.depth() < MAX_DEPTH) {
      B item = listMarker(container);
      if (item != null) {
        closeUnmatched();
        if (tip.type != LIST || !sameList(tip, item)) {
          B list = add(LIST);
          list.ordered = item.ordered;
          list.marker = item.marker;
          list.start = item.start;
        }
        B added = add(ITEM);
        added.ordered = item.ordered;
        added.marker = item.marker;
        added.start = item.start;
        added.markerOffset = item.markerOffset;
        added.padding = item.padding;
        return 1;
      }
    }
    if (indented && !interrupting && !blank) {
      advanceOffset(CODE_INDENT, true);
      closeUnmatched();
      add(INDENTED);
      return 2;
    }
    return 0;
  }

  /** A table whose header is the last line of the paragraph and whose delimiter row is this. */
  private boolean table(B para, String rest) {
    if (!delimiterRow(rest)) {
      return false;
    }
    String content = para.content.toString();
    int cut = content.lastIndexOf('\n', content.length() - 2) + 1;
    String headerLine = content.substring(cut).strip();
    if (headerLine.indexOf('|') < 0 && rest.indexOf('|') < 0) {
      return false;
    }
    List<String> header = cells(headerLine);
    List<String> delimiters = cells(rest);
    if (header.size() != delimiters.size()) {
      return false;
    }
    closeUnmatched();
    List<Align> aligns = new ArrayList<>();
    for (String d : delimiters) {
      boolean left = d.startsWith(":");
      boolean right = d.endsWith(":");
      aligns.add(
          left && right ? Align.CENTER : left ? Align.LEFT : right ? Align.RIGHT : Align.NONE);
    }
    B parent = para.parent;
    if (cut == 0) {
      parent.children.removeLast();
      tip = parent;
    } else {
      para.content.setLength(cut);
      finish(para);
      tip = parent;
    }
    B table = add(TABLE);
    table.aligns = aligns;
    table.header = header;
    advanceOffset(line.length() - offset, false);
    return true;
  }

  /** The cells of a table's row: split at unescaped pipes, without the outer ones, stripped. */
  private static List<String> cells(String row) {
    String s = row.strip();
    List<String> out = new ArrayList<>();
    StringBuilder cell = new StringBuilder();
    int from = s.startsWith("|") ? 1 : 0;
    for (int i = from; i < s.length(); i++) {
      char ch = s.charAt(i);
      if (ch == '\\' && i + 1 < s.length() && s.charAt(i + 1) == '|') {
        cell.append('|');
        i++;
      } else if (ch == '|') {
        out.add(cell.toString().strip());
        cell.setLength(0);
      } else {
        if (ch == '\\' && i + 1 < s.length()) {
          cell.append(ch);
          ch = s.charAt(++i);
        }
        cell.append(ch);
      }
    }
    if (!cell.isEmpty() || !s.endsWith("|") || s.length() == from) {
      String last = cell.toString().strip();
      if (!last.isEmpty() || out.isEmpty()) {
        out.add(last);
      }
    }
    return out;
  }

  private B listMarker(B container) {
    if (indent >= 4) {
      return null;
    }
    int i = nextNonspace;
    char c = peek(i);
    B item = new B(ITEM);
    int length;
    if (c == '*' || c == '+' || c == '-') {
      item.marker = c;
      length = 1;
    } else if (c >= '0' && c <= '9') {
      int digits = 0;
      while (digits < 10 && peek(i + digits) >= '0' && peek(i + digits) <= '9') {
        digits++;
      }
      char delimiter = peek(i + digits);
      if (digits > 9 || delimiter != '.' && delimiter != ')') {
        return null;
      }
      item.ordered = true;
      item.marker = delimiter;
      item.start = Integer.parseInt(line.substring(i, i + digits));
      if (container.type == PARA && item.start != 1) {
        return null;
      }
      length = digits + 1;
    } else {
      return null;
    }
    char after = peek(i + length);
    if (after != '\0' && !isSpaceOrTab(after)) {
      return null;
    }
    if (container.type == PARA || container.type == TABLE) {
      int j = i + length;
      while (isSpaceOrTab(peek(j))) {
        j++;
      }
      if (j >= line.length()) {
        return null; // an empty item does not interrupt a paragraph
      }
    }
    advanceNextNonspace();
    advanceOffset(length, true);
    int spacesStartColumn = column;
    int spacesStartOffset = offset;
    do {
      advanceOffset(1, true);
    } while (column - spacesStartColumn < 5 && isSpaceOrTab(peek(offset)));
    boolean blankItem = offset >= line.length();
    int spaces = column - spacesStartColumn;
    if (spaces >= 5 || spaces < 1 || blankItem) {
      item.padding = length + 1;
      column = spacesStartColumn;
      offset = spacesStartOffset;
      partiallyConsumedTab = false;
      if (isSpaceOrTab(peek(offset))) {
        advanceOffset(1, true);
      }
    } else {
      item.padding = length + spaces;
    }
    item.markerOffset = indent;
    return item;
  }

  private static boolean sameList(B list, B item) {
    return list.ordered == item.ordered && list.marker == item.marker;
  }

  private B add(int type) {
    while (!canContain(tip.type, type)) {
      B parent = tip.parent;
      finish(tip);
      tip = parent;
    }
    B b = new B(type);
    b.parent = tip;
    b.startLine = lineNumber;
    tip.children.add(b);
    tip = b;
    return b;
  }

  private static boolean canContain(int parent, int child) {
    return switch (parent) {
      case DOC, QUOTE, ITEM -> child != ITEM;
      case LIST -> child == ITEM;
      default -> false;
    };
  }

  private void closeUnmatched() {
    if (!allClosed) {
      while (oldTip != lastMatched) {
        B parent = oldTip.parent;
        finish(oldTip);
        oldTip = parent;
      }
      allClosed = true;
    }
  }

  private void addLine() {
    B b = tip;
    if (partiallyConsumedTab) {
      offset++; // the rest of the tab
      int spaces = 4 - column % 4;
      b.content.repeat(' ', spaces);
    }
    String text = line.substring(Math.min(offset, line.length()));
    if (b.type == PARA) {
      text = stripLeading(text);
    }
    b.content.append(text).append('\n');
  }

  private void finish(B b) {
    if (!b.open) {
      return;
    }
    b.open = false;
    tip = b.parent;
    switch (b.type) {
      case PARA -> {
        references(b);
        if (b.content.toString().isBlank() && b.parent != null) {
          b.parent.children.remove(b);
        }
      }
      case INDENTED -> {
        String code = b.content.toString();
        int end = code.length();
        // Blank lines at the end belong to what follows.
        while (end > 0) {
          int lineStart = code.lastIndexOf('\n', end - 2) + 1;
          if (!code.substring(lineStart, end).isBlank()) {
            break;
          }
          end = lineStart;
        }
        b.content.setLength(end);
      }
      case LIST -> b.tight = tight(b);
      default -> {}
    }
  }

  private static boolean tight(B list) {
    for (int i = 0; i < list.children.size(); i++) {
      B item = list.children.get(i);
      boolean lastItem = i + 1 == list.children.size();
      if (endsWithBlankLine(item) && !lastItem) {
        return false;
      }
      for (int j = 0; j < item.children.size(); j++) {
        B sub = item.children.get(j);
        if (endsWithBlankLine(sub) && (!lastItem || j + 1 < item.children.size())) {
          return false;
        }
      }
    }
    return true;
  }

  private static boolean endsWithBlankLine(B b) {
    while (b != null) {
      if (b.lastLineBlank) {
        return true;
      }
      b = b.type == LIST || b.type == ITEM ? b.last() : null;
    }
    return false;
  }

  /** Takes the link reference definitions off the start of a paragraph into {@link #refs}. */
  private void references(B para) {
    String content = para.content.toString();
    int at = 0;
    while (at < content.length() && content.charAt(at) == '[') {
      int n = reference(content, at);
      if (n == 0) {
        break;
      }
      at += n;
    }
    if (at > 0) {
      para.content.delete(0, at);
    }
  }

  /** Reads one definition at {@code start}; returns its length, 0 if there is none. */
  private int reference(String s, int start) {
    int label = linkLabel(s, start);
    if (label == 0) {
      return 0;
    }
    String raw = s.substring(start + 1, start + label - 1);
    int p = start + label;
    if (p >= s.length() || s.charAt(p) != ':') {
      return 0;
    }
    p = spaceNewlineSpace(s, p + 1);
    int[] end = new int[1];
    String destination = linkDestination(s, p, end);
    if (destination == null) {
      return 0;
    }
    p = end[0];
    int beforeTitle = p;
    p = spaceNewlineSpace(s, p);
    String title = null;
    if (p != beforeTitle) {
      title = linkTitle(s, p, end);
      if (title != null) {
        p = end[0];
      }
    }
    if (title == null) {
      title = "";
      p = beforeTitle;
    }
    int q = skipSpaces(s, p);
    if (q < s.length() && s.charAt(q) != '\n') {
      if (title.isEmpty()) {
        return 0;
      }
      title = "";
      q = skipSpaces(s, beforeTitle);
      if (q < s.length() && s.charAt(q) != '\n') {
        return 0;
      }
    }
    if (q < s.length()) {
      q++; // the line feed
    }
    String key = normalizeLabel(raw);
    if (key.isEmpty()) {
      return 0;
    }
    refs.putIfAbsent(key, new String[] {destination, title});
    return q - start;
  }

  // ---- The line cursor

  private char peek(int i) {
    return i < line.length() ? line.charAt(i) : '\0';
  }

  private static boolean isSpaceOrTab(char c) {
    return c == ' ' || c == '\t';
  }

  private void findNextNonspace() {
    int i = offset;
    int cols = column;
    char c;
    while ((c = peek(i)) != '\0') {
      if (c == ' ') {
        i++;
        cols++;
      } else if (c == '\t') {
        i++;
        cols += 4 - cols % 4;
      } else {
        break;
      }
    }
    blank = c == '\0';
    nextNonspace = i;
    nextNonspaceColumn = cols;
    indent = nextNonspaceColumn - column;
    indented = indent >= CODE_INDENT;
  }

  private void advanceNextNonspace() {
    offset = nextNonspace;
    column = nextNonspaceColumn;
    partiallyConsumedTab = false;
  }

  private void advanceOffset(int count, boolean columns) {
    char c;
    while (count > 0 && (c = peek(offset)) != '\0') {
      if (c == '\t') {
        int charsToTab = 4 - column % 4;
        if (columns) {
          partiallyConsumedTab = charsToTab > count;
          int advance = Math.min(charsToTab, count);
          column += advance;
          offset += partiallyConsumedTab ? 0 : 1;
          count -= advance;
        } else {
          partiallyConsumedTab = false;
          column += charsToTab;
          offset++;
          count--;
        }
      } else {
        partiallyConsumedTab = false;
        offset++;
        column++;
        count--;
      }
    }
  }

  private static String stripLeading(String s) {
    int i = 0;
    while (i < s.length() && isSpaceOrTab(s.charAt(i))) {
      i++;
    }
    return s.substring(i);
  }

  // ---- From blocks to the tree

  private void convert(B b, List<Block> out) {
    switch (b.type) {
      case PARA ->
        out.add(new MarkdownDocument.Paragraph(inlines(b.content.toString().strip())));
      case HEADING ->
        out.add(
            new MarkdownDocument.Heading(b.level, inlines(b.content.toString().strip()), ""));
      case FENCE -> {
        String content = b.content.toString();
        int cut = content.indexOf('\n');
        String info = cut < 0 ? content : content.substring(0, cut);
        String code = cut < 0 ? "" : content.substring(cut + 1);
        info = unescape(info.strip());
        int space = 0;
        while (space < info.length() && !Character.isWhitespace(info.charAt(space))) {
          space++;
        }
        out.add(new MarkdownDocument.CodeBlock(info.substring(0, space), code));
      }
      case INDENTED -> out.add(new MarkdownDocument.CodeBlock("", b.content.toString()));
      case BREAK -> out.add(new MarkdownDocument.ThematicBreak());
      case QUOTE -> {
        List<Block> blocks = new ArrayList<>();
        for (B child : b.children) {
          convert(child, blocks);
        }
        out.add(new MarkdownDocument.Quote(blocks));
      }
      case LIST -> {
        List<MarkdownDocument.Item> items = new ArrayList<>();
        for (B item : b.children) {
          List<Block> blocks = new ArrayList<>();
          Boolean task = null;
          for (int i = 0; i < item.children.size(); i++) {
            B child = item.children.get(i);
            if (i == 0 && child.type == PARA) {
              String content = child.content.toString();
              if (content.length() >= 3
                  && content.charAt(0) == '['
                  && " xX".indexOf(content.charAt(1)) >= 0
                  && content.charAt(2) == ']'
                  && (content.length() == 3
                      || isSpaceOrTab(content.charAt(3))
                      || content.charAt(3) == '\n')) {
                task = content.charAt(1) != ' ';
                String rest = content.substring(3).strip();
                if (!rest.isEmpty()) {
                  blocks.add(new MarkdownDocument.Paragraph(inlines(rest)));
                }
                continue;
              }
            }
            convert(child, blocks);
          }
          items.add(new MarkdownDocument.Item(task, blocks));
        }
        out.add(new MarkdownDocument.ListBlock(b.ordered, b.start, b.tight, items));
      }
      case TABLE -> {
        int columns = b.aligns.size();
        List<List<Inline>> header = new ArrayList<>();
        for (String cell : b.header) {
          header.add(inlines(cell));
        }
        List<List<List<Inline>>> rows = new ArrayList<>();
        for (List<String> row : b.rows) {
          List<List<Inline>> cells = new ArrayList<>();
          for (int i = 0; i < columns; i++) {
            cells.add(i < row.size() ? inlines(row.get(i)) : List.of());
          }
          rows.add(cells);
        }
        out.add(new MarkdownDocument.Table(b.aligns, header, rows));
      }
      default -> {}
    }
  }

  /** The blocks with each heading's anchor, GitHub's slug, made unique in the document. */
  private static List<Block> identify(List<Block> blocks) {
    Map<String, Integer> seen = new HashMap<>();
    return identify(blocks, seen);
  }

  private static List<Block> identify(List<Block> blocks, Map<String, Integer> seen) {
    List<Block> out = new ArrayList<>(blocks.size());
    for (Block b : blocks) {
      out.add(
          switch (b) {
            case MarkdownDocument.Heading h -> {
              String slug = slug(MarkdownDocument.plainText(h.content()));
              Integer n = seen.get(slug);
              String id = n == null ? slug : slug + "-" + n;
              seen.put(slug, n == null ? 1 : n + 1);
              yield new MarkdownDocument.Heading(h.level(), h.content(), id);
            }
            case MarkdownDocument.Quote q -> new MarkdownDocument.Quote(identify(q.blocks(), seen));
            case MarkdownDocument.ListBlock l ->
              new MarkdownDocument.ListBlock(
                  l.ordered(),
                  l.start(),
                  l.tight(),
                  l.items().stream()
                      .map(i -> new MarkdownDocument.Item(i.task(), identify(i.blocks(), seen)))
                      .toList());
            default -> b;
          });
    }
    return out;
  }

  /** GitHub's anchor of a heading: lower case, spaces as hyphens, other punctuation left out. */
  static String slug(String text) {
    StringBuilder out = new StringBuilder();
    text.toLowerCase(Locale.ROOT).strip().codePoints().forEach(c -> {
      if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
        out.appendCodePoint(c);
      } else if (c == ' ') {
        out.append('-');
      }
    });
    return out.toString();
  }

  // ---- Inline parts

  private static final int TEXT = 0;
  private static final int EMPH = 1;
  private static final int STRONG = 2;
  private static final int STRIKE = 3;
  private static final int CODE = 4;
  private static final int LINK = 5;
  private static final int IMAGE = 6;
  private static final int SOFT = 7;
  private static final int HARD = 8;
  private static final int ROOT = 9;

  /** An inline node while the parts are found: a doubly linked tree, as delimiters need. */
  private static final class N {
    final int kind;
    String text;
    String destination;
    String title;
    N parent;
    N first;
    N last;
    N prev;
    N next;

    N(int kind, String text) {
      this.kind = kind;
      this.text = text;
    }

    void append(N child) {
      child.unlink();
      child.parent = this;
      if (last != null) {
        last.next = child;
        child.prev = last;
        last = child;
      } else {
        first = last = child;
      }
    }

    void insertAfter(N sibling) {
      sibling.unlink();
      sibling.next = next;
      if (next != null) {
        next.prev = sibling;
      }
      sibling.prev = this;
      next = sibling;
      sibling.parent = parent;
      if (parent != null && parent.last == this) {
        parent.last = sibling;
      }
    }

    void unlink() {
      if (prev != null) {
        prev.next = next;
      } else if (parent != null) {
        parent.first = next;
      }
      if (next != null) {
        next.prev = prev;
      } else if (parent != null) {
        parent.last = prev;
      }
      parent = null;
      next = null;
      prev = null;
    }
  }

  private static final class Delimiter {
    final N node;
    final char c;
    int count;
    final int origCount;
    final boolean canOpen;
    final boolean canClose;
    Delimiter prev;
    Delimiter next;

    Delimiter(N node, char c, int count, boolean canOpen, boolean canClose) {
      this.node = node;
      this.c = c;
      this.count = count;
      this.origCount = count;
      this.canOpen = canOpen;
      this.canClose = canClose;
    }
  }

  private static final class Bracket {
    final N node;
    final boolean image;
    final int index; // after the opening bracket
    final Bracket prev;
    final Delimiter prevDelimiter;
    boolean active = true;
    boolean bracketAfter;

    Bracket(N node, boolean image, int index, Bracket prev, Delimiter prevDelimiter) {
      this.node = node;
      this.image = image;
      this.index = index;
      this.prev = prev;
      this.prevDelimiter = prevDelimiter;
    }
  }

  // The state of one run of the inline parser.
  private String subject;
  private int pos;
  private Delimiter delimiters;
  private Bracket brackets;
  private Map<Integer, int[]> backtickRuns;
  private Map<Integer, Integer> backtickNext;
  private int quoteFrom = -1;
  private char quoteChar;
  private int quoteFound;

  private List<Inline> inlines(String text) {
    subject = text;
    pos = 0;
    delimiters = null;
    brackets = null;
    backtickRuns = null;
    backtickNext = null;
    quoteFrom = -1;
    N root = new N(ROOT, null);
    while (pos < subject.length()) {
      parseInline(root);
    }
    processEmphasis(null);
    List<Inline> out = new ArrayList<>();
    collect(root, out, 0, false);
    return merge(out);
  }

  private void parseInline(N block) {
    char c = subject.charAt(pos);
    switch (c) {
      case '\n' -> newline(block);
      case '\\' -> backslash(block);
      case '`' -> code(block);
      case '*', '_', '~' -> delimiterRun(block, c);
      case '[' -> {
        N node = text("[");
        block.append(node);
        pos++;
        brackets = new Bracket(node, false, pos, brackets, delimiters);
        if (brackets.prev != null) {
          brackets.prev.bracketAfter = true;
        }
      }
      case '!' -> {
        if (pos + 1 < subject.length() && subject.charAt(pos + 1) == '[') {
          N node = text("![");
          block.append(node);
          pos += 2;
          brackets = new Bracket(node, true, pos, brackets, delimiters);
          if (brackets.prev != null) {
            brackets.prev.bracketAfter = true;
          }
        } else {
          block.append(text("!"));
          pos++;
        }
      }
      case ']' -> closeBracket(block);
      case '<' -> autolink(block);
      case '&' -> entity(block);
      default -> {
        if ((c == 'h' || c == 'w' || c == 'H' || c == 'W') && bareUrl(block)) {
          return;
        }
        int start = pos;
        pos++;
        while (pos < subject.length() && !special(subject.charAt(pos))) {
          pos++;
        }
        block.append(text(subject.substring(start, pos)));
      }
    }
  }

  private boolean special(char c) {
    return switch (c) {
      case '\n', '\\', '`', '*', '_', '~', '[', '!', ']', '<', '&' -> true;
      case 'h', 'w', 'H', 'W' -> pos > 0 && boundary(subject.charAt(pos - 1));
      default -> false;
    };
  }

  private static boolean boundary(char c) {
    return Character.isWhitespace(c) || c == '*' || c == '_' || c == '~' || c == '(';
  }

  private static N text(String s) {
    return new N(TEXT, s);
  }

  private void newline(N block) {
    pos++;
    N last = block.last;
    boolean hard = false;
    if (last != null && last.kind == TEXT && last.text.endsWith(" ")) {
      int end = last.text.length();
      while (end > 0 && last.text.charAt(end - 1) == ' ') {
        end--;
      }
      hard = last.text.length() - end >= 2;
      last.text = last.text.substring(0, end);
    }
    block.append(new N(hard ? HARD : SOFT, null));
    while (pos < subject.length() && isSpaceOrTab(subject.charAt(pos))) {
      pos++;
    }
  }

  private void backslash(N block) {
    pos++;
    if (pos < subject.length() && subject.charAt(pos) == '\n') {
      pos++;
      block.append(new N(HARD, null));
      while (pos < subject.length() && isSpaceOrTab(subject.charAt(pos))) {
        pos++;
      }
    } else if (pos < subject.length() && isAsciiPunctuation(subject.charAt(pos))) {
      block.append(text(String.valueOf(subject.charAt(pos))));
      pos++;
    } else {
      block.append(text("\\"));
    }
  }

  private static boolean isAsciiPunctuation(char c) {
    return c >= '!' && c <= '/'
        || c >= ':' && c <= '@'
        || c >= '[' && c <= '`'
        || c >= '{' && c <= '~';
  }

  /**
   * A code span from the run of backticks here to the next run as long, else the backticks as
   * text. The runs of the subject are found once, so that each run is looked at a bounded number
   * of times however many openers fail.
   */
  private void code(N block) {
    int start = pos;
    while (pos < subject.length() && subject.charAt(pos) == '`') {
      pos++;
    }
    int length = pos - start;
    if (backtickRuns == null) {
      Map<Integer, List<Integer>> runs = new HashMap<>();
      for (int i = 0; i < subject.length(); ) {
        if (subject.charAt(i) == '`') {
          int j = i;
          while (j < subject.length() && subject.charAt(j) == '`') {
            j++;
          }
          runs.computeIfAbsent(j - i, k -> new ArrayList<>()).add(i);
          i = j;
        } else {
          i++;
        }
      }
      backtickRuns = new HashMap<>();
      runs.forEach(
          (k, v) -> backtickRuns.put(k, v.stream().mapToInt(Integer::intValue).toArray()));
      backtickNext = new HashMap<>();
    }
    int[] runs = backtickRuns.get(length);
    int next = backtickNext.getOrDefault(length, 0);
    while (runs != null && next < runs.length && runs[next] < pos) {
      next++;
    }
    backtickNext.put(length, next);
    if (runs != null && next < runs.length) {
      int close = runs[next];
      String content = subject.substring(pos, close).replace('\n', ' ');
      if (content.length() >= 2
          && content.charAt(0) == ' '
          && content.charAt(content.length() - 1) == ' '
          && !content.isBlank()) {
        content = content.substring(1, content.length() - 1);
      }
      block.append(new N(CODE, content));
      pos = close + length;
      return;
    }
    block.append(text(subject.substring(start, pos)));
  }

  private void delimiterRun(N block, char c) {
    int start = pos;
    while (pos < subject.length() && subject.charAt(pos) == c) {
      pos++;
    }
    int count = pos - start;
    int before = start == 0 ? '\n' : subject.codePointBefore(start);
    int after = pos >= subject.length() ? '\n' : subject.codePointAt(pos);
    boolean afterSpace = isWhitespace(after);
    boolean afterPunct = isPunctuation(after);
    boolean beforeSpace = isWhitespace(before);
    boolean beforePunct = isPunctuation(before);
    boolean left = !afterSpace && (!afterPunct || beforeSpace || beforePunct);
    boolean right = !beforeSpace && (!beforePunct || afterSpace || afterPunct);
    boolean canOpen;
    boolean canClose;
    if (c == '_') {
      canOpen = left && (!right || beforePunct);
      canClose = right && (!left || afterPunct);
    } else {
      canOpen = left;
      canClose = right;
    }
    N node = text(subject.substring(start, pos));
    block.append(node);
    if ((canOpen || canClose) && (c != '~' || count <= 2)) {
      Delimiter d = new Delimiter(node, c, count, canOpen, canClose);
      d.prev = delimiters;
      if (delimiters != null) {
        delimiters.next = d;
      }
      delimiters = d;
    }
  }

  private static boolean isWhitespace(int c) {
    return c == ' '
        || c == '\t'
        || c == '\n'
        || c == '\r'
        || c == '\f'
        || Character.getType(c) == Character.SPACE_SEPARATOR;
  }

  private static boolean isPunctuation(int c) {
    return switch (Character.getType(c)) {
      case Character.CONNECTOR_PUNCTUATION,
          Character.DASH_PUNCTUATION,
          Character.START_PUNCTUATION,
          Character.END_PUNCTUATION,
          Character.INITIAL_QUOTE_PUNCTUATION,
          Character.FINAL_QUOTE_PUNCTUATION,
          Character.OTHER_PUNCTUATION,
          Character.MATH_SYMBOL,
          Character.CURRENCY_SYMBOL,
          Character.MODIFIER_SYMBOL,
          Character.OTHER_SYMBOL -> true;
      default -> false;
    };
  }

  private void removeDelimiter(Delimiter d) {
    if (d.prev != null) {
      d.prev.next = d.next;
    }
    if (d.next == null) {
      delimiters = d.prev;
    } else {
      d.next.prev = d.prev;
    }
  }

  /** Matches the delimiters above {@code bottom} into emphasis, strong and strikethrough. */
  private void processEmphasis(Delimiter bottom) {
    Map<String, Delimiter> openersBottom = new HashMap<>();
    Delimiter closer = delimiters;
    while (closer != null && closer.prev != bottom) {
      closer = closer.prev;
    }
    while (closer != null) {
      if (!closer.canClose) {
        closer = closer.next;
        continue;
      }
      String key = closer.c == '~'
          ? "~" + closer.count
          : closer.c + "" + (closer.canOpen ? 3 : 0) + closer.origCount % 3;
      Delimiter opener = closer.prev;
      boolean found = false;
      Delimiter floor = openersBottom.get(key);
      while (opener != null && opener != bottom && opener != floor) {
        boolean oddMatch = closer.c != '~'
            && (closer.canOpen || opener.canClose)
            && closer.origCount % 3 != 0
            && (opener.origCount + closer.origCount) % 3 == 0;
        if (opener.c == closer.c
            && opener.canOpen
            && !oddMatch
            && (closer.c != '~' || opener.count == closer.count)) {
          found = true;
          break;
        }
        opener = opener.prev;
      }
      Delimiter oldCloser = closer;
      if (found) {
        int use = closer.c == '~' ? closer.count : closer.count >= 2 && opener.count >= 2 ? 2 : 1;
        opener.count -= use;
        closer.count -= use;
        opener.node.text = opener.node.text.substring(0, opener.node.text.length() - use);
        closer.node.text = closer.node.text.substring(0, closer.node.text.length() - use);
        N emph = new N(closer.c == '~' ? STRIKE : use == 1 ? EMPH : STRONG, null);
        N n = opener.node.next;
        while (n != null && n != closer.node) {
          N next = n.next;
          emph.append(n);
          n = next;
        }
        opener.node.insertAfter(emph);
        // Delimiters between the two are text now.
        if (opener.next != closer) {
          opener.next = closer;
          closer.prev = opener;
        }
        if (opener.count == 0) {
          opener.node.unlink();
          removeDelimiter(opener);
        }
        if (closer.count == 0) {
          Delimiter next = closer.next;
          closer.node.unlink();
          removeDelimiter(closer);
          closer = next;
        }
      } else {
        closer = closer.next;
        openersBottom.put(key, oldCloser.prev);
        if (!oldCloser.canOpen) {
          removeDelimiter(oldCloser);
        }
      }
    }
    while (delimiters != null && delimiters != bottom) {
      removeDelimiter(delimiters);
    }
  }

  private void closeBracket(N block) {
    int start = pos;
    pos++;
    Bracket opener = brackets;
    if (opener == null) {
      block.append(text("]"));
      return;
    }
    if (!opener.active) {
      block.append(text("]"));
      brackets = opener.prev;
      return;
    }
    int saved = pos;
    String destination = null;
    String title = null;
    boolean matched = false;
    if (pos < subject.length() && subject.charAt(pos) == '(') {
      int p = spaceNewlineSpace(subject, pos + 1);
      int[] end = new int[1];
      String dest = linkDestination(subject, p, end);
      if (dest != null) {
        p = end[0];
        int beforeTitle = p;
        p = spaceNewlineSpace(subject, p);
        String t = null;
        if (p != beforeTitle) {
          t = linkTitle(subject, p, end);
          if (t != null) {
            p = spaceNewlineSpace(subject, end[0]);
          }
        }
        if (p < subject.length() && subject.charAt(p) == ')') {
          destination = dest;
          title = t == null ? "" : t;
          matched = true;
          pos = p + 1;
        }
      }
    }
    if (!matched) {
      String label = null;
      int n = linkLabel(subject, pos);
      if (n > 2) {
        label = subject.substring(pos + 1, pos + n - 1);
      } else if (!opener.bracketAfter) {
        label = subject.substring(opener.index, start);
      }
      if (label != null) {
        String[] ref = refs.get(normalizeLabel(label));
        if (ref != null) {
          destination = ref[0];
          title = ref[1];
          matched = true;
          pos += n;
        }
      }
      if (!matched) {
        pos = saved;
      }
    }
    if (!matched) {
      brackets = opener.prev;
      block.append(text("]"));
      return;
    }
    N link = new N(opener.image ? IMAGE : LINK, null);
    link.destination = destination;
    link.title = title;
    N n = opener.node.next;
    while (n != null) {
      N next = n.next;
      link.append(n);
      n = next;
    }
    block.append(link);
    processEmphasis(opener.prevDelimiter);
    brackets = opener.prev;
    opener.node.unlink();
    if (!opener.image) {
      // No links in links: the openers before this one are text now.
      for (Bracket b = brackets; b != null; b = b.prev) {
        if (!b.image) {
          if (!b.active) {
            break;
          }
          b.active = false;
        }
      }
    }
  }

  /** The length of a link label {@code [...]} at {@code at}, 0 if there is none. */
  private static int linkLabel(String s, int at) {
    if (at >= s.length() || s.charAt(at) != '[') {
      return 0;
    }
    int p = at + 1;
    while (p < s.length() && p - at <= 1000) {
      char c = s.charAt(p);
      if (c == '\\' && p + 1 < s.length() && isAsciiPunctuation(s.charAt(p + 1))) {
        p += 2;
      } else if (c == '[') {
        return 0;
      } else if (c == ']') {
        return p - at <= 1000 ? p - at + 1 : 0;
      } else {
        p++;
      }
    }
    return 0;
  }

  private static String normalizeLabel(String label) {
    return label
        .strip()
        .replaceAll("[ \\t\\r\\n]+", " ")
        .toUpperCase(Locale.ROOT)
        .toLowerCase(Locale.ROOT);
  }

  private static int skipSpaces(String s, int p) {
    while (p < s.length() && isSpaceOrTab(s.charAt(p))) {
      p++;
    }
    return p;
  }

  /** Spaces, at most one line feed, spaces. */
  private static int spaceNewlineSpace(String s, int p) {
    p = skipSpaces(s, p);
    if (p < s.length() && s.charAt(p) == '\n') {
      p = skipSpaces(s, p + 1);
    }
    return p;
  }

  /** A link destination at {@code p}, unescaped, with its end in {@code end[0]}; null if none. */
  private static String linkDestination(String s, int p, int[] end) {
    int limit = Math.min(s.length(), p + MAX_SCAN);
    if (p < s.length() && s.charAt(p) == '<') {
      int q = p + 1;
      while (q < limit) {
        char c = s.charAt(q);
        if (c == '\\' && q + 1 < limit) {
          q += 2;
        } else if (c == '>') {
          end[0] = q + 1;
          return unescape(s.substring(p + 1, q));
        } else if (c == '<' || c == '\n') {
          return null;
        } else {
          q++;
        }
      }
      return null;
    }
    int q = p;
    int depth = 0;
    while (q < limit) {
      char c = s.charAt(q);
      if (c == '\\' && q + 1 < limit && isAsciiPunctuation(s.charAt(q + 1))) {
        q += 2;
      } else if (c == '(') {
        if (++depth > 32) {
          return null;
        }
        q++;
      } else if (c == ')') {
        if (depth == 0) {
          break;
        }
        depth--;
        q++;
      } else if (c <= ' ') {
        break;
      } else {
        q++;
      }
    }
    if (q == limit && limit < s.length()
        || depth != 0
        || q == p && (q >= s.length() || s.charAt(q) != ')')) {
      return null;
    }
    end[0] = q;
    return unescape(s.substring(p, q));
  }

  /** A link title in quotes or parentheses at {@code p}, unescaped; null if none. */
  private String linkTitle(String s, int p, int[] end) {
    if (p >= s.length()) {
      return null;
    }
    char open = s.charAt(p);
    char close = open == '(' ? ')' : open;
    if (open != '"' && open != '\'' && open != '(') {
      return null;
    }
    int limit = Math.min(s.length(), p + MAX_SCAN);
    // The end found last time holds for every start before it: no closing mark lies between.
    if (s == subject
        && open != '('
        && quoteFrom >= 0
        && quoteChar == open
        && p >= quoteFrom
        && quoteFound > p) {
      int q = quoteFound;
      if (q < limit) {
        end[0] = q + 1;
        return unescape(s.substring(p + 1, q));
      }
      return null;
    }
    int q = p + 1;
    while (q < limit) {
      char c = s.charAt(q);
      if (c == '\\' && q + 1 < limit) {
        q += 2;
      } else if (c == close) {
        if (s == subject && open != '(') {
          quoteFrom = p;
          quoteChar = open;
          quoteFound = q;
        }
        end[0] = q + 1;
        return unescape(s.substring(p + 1, q));
      } else if (open == '(' && c == '(') {
        return null;
      } else {
        q++;
      }
    }
    return null;
  }

  private static final Pattern URI_AUTOLINK =
      Pattern.compile("<([A-Za-z][A-Za-z0-9.+-]{1,31}:[^<>\\x00-\\x20]*)>");
  private static final Pattern EMAIL_AUTOLINK = Pattern.compile(
      "<([a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?"
          + "(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*)>");

  private void autolink(N block) {
    Matcher m = URI_AUTOLINK.matcher(subject).region(pos, subject.length());
    boolean uri = m.lookingAt();
    if (!uri) {
      m = EMAIL_AUTOLINK.matcher(subject).region(pos, Math.min(subject.length(), pos + 512));
    }
    if (uri || m.lookingAt()) {
      String target = m.group(1);
      N link = new N(LINK, null);
      link.destination = uri ? target : "mailto:" + target;
      link.title = "";
      link.append(text(target));
      block.append(link);
      pos = m.end();
      return;
    }
    block.append(text("<"));
    pos++;
  }

  /** GitHub's autolinks without angle brackets: {@code www.}, {@code http://}, {@code https://}. */
  private boolean bareUrl(N block) {
    if (pos > 0 && !boundary(subject.charAt(pos - 1))) {
      return false;
    }
    String head =
        subject.substring(pos, Math.min(subject.length(), pos + 8)).toLowerCase(Locale.ROOT);
    int prefix = head.startsWith("https://")
        ? 8
        : head.startsWith("http://") ? 7 : head.startsWith("www.") ? 4 : 0;
    if (prefix == 0) {
      return false;
    }
    int stop = pos + prefix;
    while (stop < subject.length()
        && !Character.isWhitespace(subject.charAt(stop))
        && subject.charAt(stop) != '<') {
      stop++;
    }
    String url = subject.substring(pos, stop);
    String lower = url.toLowerCase(Locale.ROOT);
    int domainStart = lower.startsWith("www.") ? 0 : lower.indexOf("://") + 3;
    // Trailing punctuation is not part of it, nor a closing parenthesis without its opening one.
    int end = url.length();
    long opens = url.chars().filter(ch -> ch == '(').count();
    long closes = url.chars().filter(ch -> ch == ')').count();
    while (end > domainStart) {
      char c = url.charAt(end - 1);
      if ("?!.,:*_~'\"".indexOf(c) >= 0) {
        end--;
      } else if (c == ')') {
        if (closes > opens) {
          end--;
          closes--;
        } else {
          break;
        }
      } else if (c == ';') {
        int amp = url.lastIndexOf('&', end - 1);
        if (amp >= 0
            && url.substring(amp + 1, end - 1).chars().allMatch(Character::isLetterOrDigit)
            && amp + 1 < end - 1) {
          end = amp;
        } else {
          break;
        }
      } else {
        break;
      }
    }
    url = url.substring(0, end);
    // The domain: at least one period after www., and none of the last two segments with "_".
    int domainEnd = domainStart;
    while (domainEnd < url.length()
        && (Character.isLetterOrDigit(url.charAt(domainEnd))
            || "._-".indexOf(url.charAt(domainEnd)) >= 0)) {
      domainEnd++;
    }
    String domain = url.substring(domainStart, domainEnd);
    String[] segments = domain.split("\\.", -1);
    if (domain.isEmpty()
        || domain.endsWith(".")
        || segments.length < 2
        || segments[segments.length - 1].contains("_")
        || segments.length >= 2 && segments[segments.length - 2].contains("_")) {
      return false;
    }
    N link = new N(LINK, null);
    link.destination = lower.startsWith("www.") ? "http://" + url : url;
    link.title = "";
    link.append(text(url));
    block.append(link);
    pos += end;
    return true;
  }

  private void entity(N block) {
    int[] end = new int[1];
    String decoded = decodeEntity(subject, pos, end);
    if (decoded != null) {
      block.append(text(decoded));
      pos = end[0];
    } else {
      block.append(text("&"));
      pos++;
    }
  }

  /** The character of an entity at {@code p}, with its end in {@code end[0]}; null if none. */
  private static String decodeEntity(String s, int p, int[] end) {
    int semi = -1;
    for (int q = p + 1; q < s.length() && q <= p + 33; q++) {
      if (s.charAt(q) == ';') {
        semi = q;
        break;
      }
    }
    if (semi < 0) {
      return null;
    }
    String body = s.substring(p + 1, semi);
    end[0] = semi + 1;
    if (body.startsWith("#")) {
      boolean hex = body.length() > 1 && (body.charAt(1) == 'x' || body.charAt(1) == 'X');
      String digits = body.substring(hex ? 2 : 1);
      if (digits.isEmpty()
          || digits.length() > (hex ? 6 : 7)
          || !digits
              .chars()
              .allMatch(c -> hex ? Character.digit(c, 16) >= 0 : c >= '0' && c <= '9')) {
        return null;
      }
      int code = Integer.parseInt(digits, hex ? 16 : 10);
      if (code == 0 || code > 0x10FFFF || code >= 0xD800 && code <= 0xDFFF) {
        code = 0xFFFD;
      }
      return Character.toString(code);
    }
    return Entities.NAMES.get(body);
  }

  /** Backslash escapes and entities resolved, as in destinations, titles and info strings. */
  private static String unescape(String s) {
    if (s.indexOf('\\') < 0 && s.indexOf('&') < 0) {
      return s;
    }
    StringBuilder out = new StringBuilder();
    int[] end = new int[1];
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\\' && i + 1 < s.length() && isAsciiPunctuation(s.charAt(i + 1))) {
        out.append(s.charAt(++i));
      } else if (c == '&') {
        String decoded = decodeEntity(s, i, end);
        if (decoded != null) {
          out.append(decoded);
          i = end[0] - 1;
        } else {
          out.append(c);
        }
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }

  /** The nodes as the tree's parts; deeper than {@link #MAX_DEPTH} and links in links as text. */
  private static void collect(N parent, List<Inline> out, int depth, boolean inLink) {
    for (N n = parent.first; n != null; n = n.next) {
      switch (n.kind) {
        case TEXT -> {
          if (!n.text.isEmpty()) {
            out.add(new MarkdownDocument.Text(n.text));
          }
        }
        case CODE -> out.add(new MarkdownDocument.Code(n.text));
        case SOFT -> out.add(new MarkdownDocument.SoftBreak());
        case HARD -> out.add(new MarkdownDocument.HardBreak());
        case IMAGE -> {
          List<Inline> alt = new ArrayList<>();
          collect(n, alt, depth + 1, inLink);
          out.add(
              new MarkdownDocument.Image(n.destination, n.title, MarkdownDocument.plainText(alt)));
        }
        default -> {
          List<Inline> content = new ArrayList<>();
          if (depth >= MAX_DEPTH) {
            flatten(n, out); // its text alone, without recursion
            continue;
          }
          collect(n, content, depth + 1, inLink || n.kind == LINK);
          content = merge(content);
          if (n.kind == LINK && inLink) {
            out.addAll(content);
            continue;
          }
          out.add(
              switch (n.kind) {
                case EMPH -> new MarkdownDocument.Emphasis(content);
                case STRONG -> new MarkdownDocument.Strong(content);
                case STRIKE -> new MarkdownDocument.Strike(content);
                default -> new MarkdownDocument.Link(n.destination, n.title, content);
              });
        }
      }
    }
  }

  /** The text inside a node as one text part, walked without recursion however deep it is. */
  private static void flatten(N top, List<Inline> out) {
    StringBuilder text = new StringBuilder();
    N n = top.first;
    while (n != null) {
      switch (n.kind) {
        case TEXT, CODE -> text.append(n.text);
        case SOFT -> text.append(' ');
        case HARD -> text.append('\n');
        default -> {}
      }
      if (n.first != null) {
        n = n.first;
        continue;
      }
      while (n != null && n != top && n.next == null) {
        n = n.parent;
      }
      n = n == null || n == top ? null : n.next;
    }
    if (!text.isEmpty()) {
      out.add(new MarkdownDocument.Text(text.toString()));
    }
  }

  private static List<Inline> merge(List<Inline> parts) {
    List<Inline> out = new ArrayList<>(parts.size());
    StringBuilder text = new StringBuilder();
    for (Inline i : parts) {
      if (i instanceof MarkdownDocument.Text t) {
        text.append(t.text());
        continue;
      }
      if (!text.isEmpty()) {
        out.add(new MarkdownDocument.Text(text.toString()));
        text.setLength(0);
      }
      out.add(i);
    }
    if (!text.isEmpty()) {
      out.add(new MarkdownDocument.Text(text.toString()));
    }
    return out;
  }

  // ---- The tree as text

  static String tree(MarkdownDocument document) {
    StringBuilder out = new StringBuilder();
    for (Block b : document.blocks()) {
      tree(b, 0, out);
    }
    return out.toString();
  }

  private static void line(StringBuilder out, int depth, String text) {
    out.repeat("  ", depth).append(text).append('\n');
  }

  private static void tree(Block block, int depth, StringBuilder out) {
    switch (block) {
      case MarkdownDocument.Heading h -> {
        line(out, depth, "heading " + h.level() + " #" + h.id());
        tree(h.content(), depth + 1, out);
      }
      case MarkdownDocument.Paragraph p -> {
        line(out, depth, "paragraph");
        tree(p.content(), depth + 1, out);
      }
      case MarkdownDocument.CodeBlock c ->
        line(out, depth, "code " + quote(c.language()) + " " + quote(c.code()));
      case MarkdownDocument.Quote q -> {
        line(out, depth, "quote");
        q.blocks().forEach(b -> tree(b, depth + 1, out));
      }
      case MarkdownDocument.ListBlock l -> {
        line(
            out,
            depth,
            "list " + (l.ordered() ? "ordered start=" + l.start() : "bullet")
                + (l.tight() ? " tight" : " loose"));
        for (MarkdownDocument.Item item : l.items()) {
          line(
              out,
              depth + 1,
              item.task() == null ? "item" : item.task() ? "item task=[x]" : "item task=[ ]");
          item.blocks().forEach(b -> tree(b, depth + 2, out));
        }
      }
      case MarkdownDocument.ThematicBreak t -> line(out, depth, "hr");
      case MarkdownDocument.Table t -> {
        line(
            out,
            depth,
            "table "
                + String.join(
                    ",",
                    t.aligns().stream()
                        .map(a -> a.name().toLowerCase(Locale.ROOT))
                        .toList()));
        line(out, depth + 1, "header");
        for (List<Inline> cell : t.header()) {
          line(out, depth + 2, "cell");
          tree(cell, depth + 3, out);
        }
        for (List<List<Inline>> row : t.rows()) {
          line(out, depth + 1, "row");
          for (List<Inline> cell : row) {
            line(out, depth + 2, "cell");
            tree(cell, depth + 3, out);
          }
        }
      }
    }
  }

  private static void tree(List<Inline> parts, int depth, StringBuilder out) {
    for (Inline i : parts) {
      switch (i) {
        case MarkdownDocument.Text t -> line(out, depth, "text " + quote(t.text()));
        case MarkdownDocument.Emphasis e -> {
          line(out, depth, "emphasis");
          tree(e.content(), depth + 1, out);
        }
        case MarkdownDocument.Strong s -> {
          line(out, depth, "strong");
          tree(s.content(), depth + 1, out);
        }
        case MarkdownDocument.Strike s -> {
          line(out, depth, "strike");
          tree(s.content(), depth + 1, out);
        }
        case MarkdownDocument.Code c -> line(out, depth, "code " + quote(c.code()));
        case MarkdownDocument.Link l -> {
          line(
              out,
              depth,
              "link " + quote(l.destination())
                  + (l.title().isEmpty() ? "" : " " + quote(l.title())));
          tree(l.content(), depth + 1, out);
        }
        case MarkdownDocument.Image m ->
          line(
              out,
              depth,
              "image " + quote(m.source()) + (m.title().isEmpty() ? "" : " " + quote(m.title()))
                  + " alt=" + quote(m.alt()));
        case MarkdownDocument.SoftBreak s -> line(out, depth, "softbreak");
        case MarkdownDocument.HardBreak h -> line(out, depth, "hardbreak");
      }
    }
  }

  private static String quote(String s) {
    return "\""
        + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t")
        + "\"";
  }
}
