package com.rethinkj.ui.java;

import com.rethinkj.ui.editor.DocComment;
import com.rethinkj.ui.kit.MarkdownDocument;
import com.rethinkj.ui.kit.MarkdownDocument.Block;
import com.rethinkj.ui.kit.MarkdownDocument.Code;
import com.rethinkj.ui.kit.MarkdownDocument.CodeBlock;
import com.rethinkj.ui.kit.MarkdownDocument.Emphasis;
import com.rethinkj.ui.kit.MarkdownDocument.HardBreak;
import com.rethinkj.ui.kit.MarkdownDocument.Heading;
import com.rethinkj.ui.kit.MarkdownDocument.Image;
import com.rethinkj.ui.kit.MarkdownDocument.Inline;
import com.rethinkj.ui.kit.MarkdownDocument.Item;
import com.rethinkj.ui.kit.MarkdownDocument.Link;
import com.rethinkj.ui.kit.MarkdownDocument.ListBlock;
import com.rethinkj.ui.kit.MarkdownDocument.Paragraph;
import com.rethinkj.ui.kit.MarkdownDocument.Quote;
import com.rethinkj.ui.kit.MarkdownDocument.SoftBreak;
import com.rethinkj.ui.kit.MarkdownDocument.Strike;
import com.rethinkj.ui.kit.MarkdownDocument.Strong;
import com.rethinkj.ui.kit.MarkdownDocument.Table;
import com.rethinkj.ui.kit.MarkdownDocument.Text;
import com.rethinkj.ui.kit.MarkdownDocument.ThematicBreak;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Java doc comments — traditional Javadoc ({@code /** ... *}{@code /}) and Markdown doc
 * comments ({@code ///}) — into {@link DocComment}. Linear time without recursion or regular
 * expressions, safe for untrusted input.
 */
final class JavadocParser {

  private static final int MAX_DEPTH = 32;
  private static final int MAX_TAG_SCAN = 4096;
  // Lone surrogates: Markdown decodes an entity of one to U+FFFD, so only this parser writes them.
  private static final char TAG_OPEN = '\uD800';
  private static final char TAG_CLOSE = '\uDC00';
  private static final Pattern PLACEHOLDER = Pattern.compile("\uD800(\\d{1,9})\uDC00");
  private static final String NAME =
      "\\p{javaJavaIdentifierStart}[\\p{javaJavaIdentifierPart}.]*+(?<!\\.)";
  // module/, a type, #member and parameters: java.base/java.util.List#add(int, Object), reset()
  private static final Pattern REFERENCE =
      Pattern.compile(NAME + "/|(?:" + NAME + "/)?(?:" + NAME + "|(?:" + NAME
          + ")?#\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)(?:\\([^()]*\\))?");

  private JavadocParser() {}

  static DocComment parse(String raw) {
    String normalized = normalizeLineEndings(raw);
    int first = skipWhitespace(normalized, 0);
    if (normalized.startsWith("///", first)) {
      return parseLines(stripTripleSlash(normalized), true);
    }
    if (normalized.startsWith("/**", first)
        && !(normalized.length() == first + 4 && normalized.startsWith("/**/", first))) {
      return parseLines(stripJavadocDelimiters(normalized.substring(first)), false);
    }
    return new DocComment(null, List.of(), null, List.of(), List.of(), null);
  }

  private static String normalizeLineEndings(String text) {
    if (text.indexOf('\r') < 0) {
      return text;
    }
    StringBuilder out = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\r') {
        out.append('\n');
        if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
          i++;
        }
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }

  /** Strips {@code ///} prefixes and the common leading indentation across non-blank lines. */
  private static List<String> stripTripleSlash(String text) {
    String[] rawLines = text.split("\n", -1);
    List<String> stripped = new ArrayList<>(rawLines.length);
    for (String line : rawLines) {
      int k = skipHorizontalSpace(line, 0);
      if (line.startsWith("///", k)) {
        stripped.add(line.substring(k + 3));
      } else {
        stripped.add(line);
      }
    }
    return stripCommonIndent(stripped);
  }

  /** Strips {@code /**}, {@code *}{@code /} and leading {@code *} prefixes from Javadoc lines. */
  private static List<String> stripJavadocDelimiters(String text) {
    String inner = text.substring(3); // past /**
    int end = inner.length();
    while (end > 0 && isHorizontalSpace(inner.charAt(end - 1))) {
      end--;
    }
    boolean hadClose = end >= 2 && inner.charAt(end - 2) == '*' && inner.charAt(end - 1) == '/';
    if (hadClose) {
      end -= 2;
    }
    inner = inner.substring(0, end);
    String[] rawLines = inner.split("\n", -1);
    List<String> lines = new ArrayList<>(rawLines.length);
    for (int idx = 0; idx < rawLines.length; idx++) {
      String line = rawLines[idx];
      if (idx == 0) {
        int k = 0;
        while (k < line.length() && line.charAt(k) == '*') {
          k++;
        }
        if (k == line.length() || isBlank(line, k)) {
          line = "";
        } else {
          if (k > 0) {
            line = line.substring(k);
          }
          if (!line.isEmpty() && isHorizontalSpace(line.charAt(0))) {
            line = line.substring(1);
          }
        }
      } else {
        int k = skipHorizontalSpace(line, 0);
        if (k < line.length() && line.charAt(k) == '*') {
          while (k < line.length() && line.charAt(k) == '*') {
            k++;
          }
          line = line.substring(k); // the white space after it stays, as in javadoc
        }
      }
      if (hadClose && idx == rawLines.length - 1) {
        int trim = line.length();
        while (trim > 0 && line.charAt(trim - 1) == '*') {
          trim--;
        }
        if (trim < line.length() && isBlank(line, 0, trim)) {
          line = "";
        }
      }
      lines.add(line);
    }
    int from = 0;
    while (from < lines.size() && lines.get(from).isBlank()) {
      from++;
    }
    int to = lines.size();
    while (to > from && lines.get(to - 1).isBlank()) {
      to--;
    }
    return lines.subList(from, to);
  }

  private static List<String> stripCommonIndent(List<String> lines) {
    int minIndent = Integer.MAX_VALUE;
    for (String line : lines) {
      if (line.isBlank()) {
        continue;
      }
      int indent = skipHorizontalSpace(line, 0);
      if (indent < minIndent) {
        minIndent = indent;
      }
    }
    if (minIndent == Integer.MAX_VALUE || minIndent == 0) {
      return lines;
    }
    List<String> out = new ArrayList<>(lines.size());
    for (String line : lines) {
      out.add(line.isBlank() ? "" : line.substring(minIndent));
    }
    return out;
  }

  private record Section(String tag, StringBuilder content) {}

  /** Splits body and block tags, then parses each into {@link DocComment}. */
  private static DocComment parseLines(List<String> lines, boolean markdown) {
    List<Section> sections = splitBlockTags(lines, markdown);
    StringBuilder bodyText = new StringBuilder();
    List<DocComment.Entry> params = new ArrayList<>();
    StringBuilder returnText = new StringBuilder();
    List<DocComment.Entry> exceptions = new ArrayList<>();
    List<MarkdownDocument> sees = new ArrayList<>();
    boolean hasDeprecated = false;
    StringBuilder deprecatedText = new StringBuilder();

    for (Section section : sections) {
      String tag = section.tag();
      String content = section.content().toString();
      if (markdown && tag != null) {
        content = dedentContinuation(content, tag);
      }
      if (tag == null) {
        appendSection(bodyText, content);
        continue;
      }
      switch (tag) {
        case "param" -> {
          Split split = splitFirstWord(content);
          if (split == null) {
            appendSection(bodyText, "@" + tag + (content.isBlank() ? "" : " " + content.strip()));
          } else {
            MarkdownDocument desc = parseContent(split.rest(), markdown);
            params.add(new DocComment.Entry(split.head(), null, desc));
          }
        }
        case "return" -> appendSection(returnText, content);
        case "throws", "exception" -> {
          Split split = splitFirstWord(content);
          if (split == null) {
            appendSection(bodyText, "@" + tag + (content.isBlank() ? "" : " " + content.strip()));
          } else {
            MarkdownDocument desc = parseContent(split.rest(), markdown);
            exceptions.add(new DocComment.Entry(split.head(), null, desc));
          }
        }
        case "see" -> {
          MarkdownDocument seeDoc = parseSee(content, markdown);
          if (seeDoc != null) {
            sees.add(seeDoc);
          }
        }
        case "deprecated" -> {
          hasDeprecated = true;
          appendSection(deprecatedText, content);
        }
        default -> {
          String unknown = "@" + tag + (content.isEmpty() ? "" : " " + content);
          appendSection(bodyText, unknown);
        }
      }
    }

    MarkdownDocument body = parseContent(bodyText.toString(), markdown);
    MarkdownDocument returns = parseContent(returnText.toString(), markdown);
    MarkdownDocument deprecated = null;
    if (hasDeprecated) {
      MarkdownDocument depDoc = parseContent(deprecatedText.toString(), markdown);
      deprecated = depDoc != null ? depDoc : new MarkdownDocument(List.of());
    }
    return new DocComment(body, params, returns, exceptions, sees, deprecated);
  }

  /**
   * A {@code ///} block tag's text with the lines that go on its first line dedented, up to the
   * first blank line outside a code block or a line that starts a block tag: {@code @param x}
   * followed by an indented description is prose, a list item opened on the first line keeps its
   * lines, and an indented code block after a blank line stays code.
   */
  private static String dedentContinuation(String content, String tag) {
    List<String> lines = Arrays.asList(content.split("\n", -1));
    if (lines.size() < 2) {
      return content;
    }
    List<String> rest = lines.subList(1, lines.size());
    // Each line is dedented by the least indentation of the lines up to it, so that its dedent
    // depends only on the lines before it and a code fence's lines lose no more than its opener.
    // The lines of a list item opened on the tag's first line are already indented to it. A code
    // fence opened there is dedented by the indentation its code and closer share: the lines
    // before the closer are code anyway.
    String first = asMarkdown(lines.getFirst(), tag);
    String fence = fenceOpener(first);
    int indent = opensListItem(first) || fence != null ? 0 : Integer.MAX_VALUE;
    int codeIndent = Integer.MAX_VALUE;
    for (String line : fence == null ? List.<String>of() : rest) {
      int lineIndent = skipHorizontalSpace(line, 0);
      int end = lineIndent;
      while (end < line.length() && line.charAt(end) == fence.charAt(0)) {
        end++;
      }
      if (end - lineIndent >= fence.length()
          && skipHorizontalSpace(line, end) == line.length()
          && lineIndent - Math.min(codeIndent, lineIndent) <= 3) {
        indent = Math.min(codeIndent, lineIndent);
        break;
      }
      if (!line.isBlank()) {
        codeIndent = Math.min(codeIndent, lineIndent);
      }
    }
    List<String> dedented = new ArrayList<>(rest.size());
    for (String line : rest) {
      int lineIndent = skipHorizontalSpace(line, 0);
      if (!line.isBlank()) {
        indent = Math.min(indent, lineIndent);
      }
      dedented.add(line.substring(Math.min(lineIndent, indent)));
    }
    BitSet code = MarkdownDocument.codeLines(
        asMarkdown(lines.getFirst() + "\n" + String.join("\n", dedented), tag));
    int prose = 0;
    // a bare tag's text starts on a following line: blank lines before it go on the tag too
    while (lines.getFirst().isBlank() && prose < rest.size() && rest.get(prose).isBlank()) {
      prose++;
    }
    while (prose < rest.size()
        && (code.get(prose + 1)
            || !rest.get(prose).isBlank() && matchBlockTag(rest.get(prose), true) == null)) {
      prose++;
    }
    List<String> out = new ArrayList<>(lines.size());
    out.add(lines.getFirst());
    out.addAll(dedented.subList(0, prose));
    out.addAll(rest.subList(prose, rest.size()));
    return String.join("\n", out);
  }

  /** The run of backticks or tildes that opens a code fence at the start of {@code line}, or null. */
  private static String fenceOpener(String line) {
    int start = skipHorizontalSpace(line, 0);
    int end = start;
    while (end < line.length() && line.charAt(end) == line.charAt(start)) {
      end++;
    }
    boolean opens = start <= 3
        && end - start >= 3
        && (line.charAt(start) == '~' || line.charAt(start) == '`' && line.indexOf('`', end) < 0);
    return opens ? line.substring(start, end) : null;
  }

  /** Whether {@code line} starts with a list item's marker. */
  private static boolean opensListItem(String line) {
    int marker = skipHorizontalSpace(line, 0);
    int end = marker;
    while (end < line.length()
        && end - marker < 9
        && line.charAt(end) >= '0'
        && line.charAt(end) <= '9') {
      end++;
    }
    String markers = end > marker ? ".)" : "-+*";
    if (marker > 3 || end == line.length() || markers.indexOf(line.charAt(end)) < 0) {
      return false;
    }
    return end + 1 == line.length() || isHorizontalSpace(line.charAt(end + 1));
  }

  /**
   * A tag's text as Markdown reads it, line for line: without the name a {@code param} or {@code
   * throws} tag starts with, and after {@code @name} for a tag that the body shows as text.
   */
  private static String asMarkdown(String content, String tag) {
    switch (tag) {
      case "return", "see", "deprecated" -> {
        return content;
      }
      case "param", "throws", "exception" -> {}
      default -> {
        return "@" + tag + " " + content;
      }
    }
    int start = skipWhitespace(content, 0);
    int end = start;
    while (end < content.length() && !Character.isWhitespace(content.charAt(end))) {
      end++;
    }
    return content.substring(0, start) + content.substring(skipHorizontalSpace(content, end));
  }

  /**
   * The code lines of a section as Markdown reads it, line 0 being the section's first line: the
   * body's first line, or what follows a block tag's name.
   */
  private static BitSet sectionCodeLines(String tag, String first, List<String> following) {
    String text = first + (following.isEmpty() ? "" : "\n" + String.join("\n", following));
    return MarkdownDocument.codeLines(
        tag == null ? text : asMarkdown(dedentContinuation(text, tag), tag));
  }

  private static void appendSection(StringBuilder target, String content) {
    if (content.isBlank()) {
      return;
    }
    if (!target.isEmpty()) {
      target.append("\n\n");
    }
    target.append(content);
  }

  /**
   * Splits stripped lines into the initial body section (tag {@code null}) and subsequent block tag
   * sections, ignoring {@code @} inside code blocks (Markdown) or inside {@code <pre>} and inline
   * {@code {@...}} tags (Javadoc).
   */
  private static List<Section> splitBlockTags(List<String> lines, boolean markdown) {
    List<Section> sections = new ArrayList<>();
    Section current = new Section(null, new StringBuilder());
    sections.add(current);

    if (markdown) {
      // a tag section keeps every line, so that line k of its text is line start + k
      int start = 0;
      String first = lines.isEmpty() ? "" : lines.getFirst();
      BitSet code = null;
      int windowEnd = -1;
      for (int i = 0; i < lines.size(); i++) {
        String line = lines.get(i);
        Split tag = matchBlockTag(line, true);
        if (tag != null && i > start) {
          // Code status depends only on the lines before, so the window may run past the
          // section; it grows by doubling to keep the comment's parsing linear.
          if (code == null || i > windowEnd) {
            windowEnd = code == null ? i : Math.min(lines.size() - 1, start + 2 * (i - start));
            code = sectionCodeLines(current.tag(), first, lines.subList(start + 1, windowEnd + 1));
          }
          if (code.get(i - start)) {
            tag = null;
          }
        }
        if (tag != null) {
          current = new Section(tag.head(), new StringBuilder(tag.rest()));
          sections.add(current);
          start = i;
          first = tag.rest();
          code = null;
        } else if (current.tag() != null) {
          current.content().append('\n').append(line);
        } else {
          appendLine(current.content(), line);
        }
      }
      return sections;
    }

    // Javadoc <pre> and {@...} state.
    boolean inPre = false;
    int braceDepth = 0;

    for (String line : lines) {
      if (!inPre && braceDepth == 0) {
        Split tag = matchBlockTag(line, false);
        if (tag != null) {
          current = new Section(tag.head(), new StringBuilder(tag.rest()));
          sections.add(current);
          int[] state = updateJavadocState(tag.rest(), inPre, braceDepth);
          inPre = state[0] != 0;
          braceDepth = state[1];
          continue;
        }
      }
      appendLine(current.content(), line);
      int[] state = updateJavadocState(line, inPre, braceDepth);
      inPre = state[0] != 0;
      braceDepth = state[1];
    }
    return sections;
  }

  private static void appendLine(StringBuilder sb, String line) {
    if (!sb.isEmpty()) {
      sb.append('\n');
    }
    sb.append(line);
  }

  /** Tracks {@code <pre>} and {@code {@...}} brace depth across lines of traditional Javadoc. */
  private static int[] updateJavadocState(String line, boolean inPre, int braceDepth) {
    int n = line.length();
    for (int i = 0; i < n; i++) {
      char c = line.charAt(i);
      if (braceDepth > 0) {
        if (c == '{') {
          braceDepth++;
        } else if (c == '}') {
          braceDepth--;
        }
      } else if (c == '{'
          && i + 2 < n
          && line.charAt(i + 1) == '@'
          && isAsciiLetter(line.charAt(i + 2))) {
        braceDepth = 1;
        i++;
      } else if (c == '<') {
        int tagEnd = matchSimpleTag(line, i, "pre", false);
        if (tagEnd > i) {
          inPre = true;
          i = tagEnd - 1;
        } else {
          int closeEnd = matchSimpleTag(line, i, "pre", true);
          if (closeEnd > i) {
            inPre = false;
            i = closeEnd - 1;
          }
        }
      }
    }
    return new int[] {inPre ? 1 : 0, braceDepth};
  }

  /**
   * If {@code line} starts a block tag ({@code @name ...}), returns its name and the rest; else
   * {@code null}. In Markdown mode, lines indented by 4+ columns are indented code, not tags.
   */
  private static Split matchBlockTag(String line, boolean markdown) {
    int cols = 0;
    int i = 0;
    int n = line.length();
    while (i < n && isHorizontalSpace(line.charAt(i))) {
      cols += line.charAt(i) == '\t' ? 4 - cols % 4 : 1;
      i++;
    }
    if (markdown && cols >= 4) {
      return null;
    }
    if (i >= n || line.charAt(i) != '@') {
      return null;
    }
    int nameStart = i + 1;
    if (nameStart >= n || !isAsciiLetter(line.charAt(nameStart))) {
      return null;
    }
    int nameEnd = nameStart + 1;
    while (nameEnd < n && isTagNameChar(line.charAt(nameEnd))) {
      nameEnd++;
    }
    if (nameEnd < n && !isHorizontalSpace(line.charAt(nameEnd))) {
      return null;
    }
    String name = line.substring(nameStart, nameEnd);
    int restStart = skipHorizontalSpace(line, nameEnd);
    String rest = restStart < n ? line.substring(restStart) : "";
    return new Split(name, rest);
  }

  /** A text split in two: a block tag's name, a first word or a reference, and the rest. */
  private record Split(String head, String rest) {}

  /** Splits the first whitespace-delimited token and the remainder, or {@code null} if blank. */
  private static Split splitFirstWord(String text) {
    int n = text.length();
    int i = skipWhitespace(text, 0);
    if (i >= n) {
      return null;
    }
    int wordEnd = i;
    while (wordEnd < n && !Character.isWhitespace(text.charAt(wordEnd))) {
      wordEnd++;
    }
    String word = text.substring(i, wordEnd);
    int restStart = wordEnd;
    if (restStart < n && isHorizontalSpace(text.charAt(restStart))) {
      restStart = skipHorizontalSpace(text, restStart);
    } else if (restStart < n && text.charAt(restStart) == '\n') {
      restStart++;
    }
    return new Split(word, text.substring(restStart));
  }

  private static MarkdownDocument parseContent(String text, boolean markdown) {
    if (text == null || text.isBlank()) {
      return null;
    }
    if (markdown) {
      MarkdownDocument doc = parseMarkdown(text, false);
      return doc.blocks().isEmpty() ? null : doc;
    }
    return parseJavadoc(text);
  }

  private static MarkdownDocument parseSee(String content, boolean markdown) {
    String trimmed = content.strip();
    if (trimmed.isEmpty()) {
      return null;
    }
    if (trimmed.startsWith("\"")) {
      return parseContent(trimmed, markdown);
    }
    if (startsWithAnchor(trimmed)) {
      return parseJavadoc(trimmed);
    }
    if (markdown
        && (trimmed.startsWith("[")
            || trimmed.startsWith("http://")
            || trimmed.startsWith("https://"))) {
      return parseContent(trimmed, true);
    }
    Split refLabel = splitRefAndLabel(trimmed);
    String ref = refLabel.head();
    String label = refLabel.rest();
    if (ref.isEmpty()) {
      return parseContent(trimmed, markdown);
    }
    List<Inline> linkContent;
    if (label.isEmpty()) {
      linkContent = List.of(new Code(ref));
    } else if (markdown) {
      MarkdownDocument labelDoc = parseMarkdown(label, true);
      linkContent = firstParagraphInlines(labelDoc, label);
    } else {
      linkContent = parseJavadocInlines(label, true);
    }
    return new MarkdownDocument(List.of(new Paragraph(List.of(new Link(ref, "", linkContent)))));
  }

  /**
   * Parses Markdown doc comment text with JEP 467 inline tags and Java reference links. Top-level
   * {@code {@...}} tags are replaced by private-use placeholders before Markdown parsing so tags
   * in prose expand via {@link JavadocBuilder} while tags inside code spans, code blocks, link
   * destinations/titles, and images are restored literally.
   */
  private static MarkdownDocument parseMarkdown(String text, boolean inLink) {
    List<String> tags = new ArrayList<>();
    String prepared = substituteInlineTags(text, tags);
    Set<String> resolvedRefs = new HashSet<>();
    MarkdownDocument doc = MarkdownDocument.parse(
        prepared,
        inLink
            ? null
            : label -> {
              String ref = resolveJavaReference(label);
              if (ref != null) {
                resolvedRefs.add(ref);
              }
              return ref;
            });
    if (!inLink && tags.isEmpty() && resolvedRefs.isEmpty()) {
      return doc;
    }
    return new MarkdownDocument(rewriteBlocks(doc.blocks(), tags, resolvedRefs, inLink));
  }

  private static String substituteInlineTags(String text, List<String> tags) {
    // a text with a lone surrogate of its own keeps its tags as text, so that it cannot fake one
    if (!text.contains("{@") || text.codePoints().anyMatch(c -> c == TAG_OPEN || c == TAG_CLOSE)) {
      return text;
    }
    Map<Integer, Integer> inlineTagEnd = JavadocBuilder.matchInlineTags(text);
    if (inlineTagEnd.isEmpty()) {
      return text;
    }
    BitSet codeLines = MarkdownDocument.codeLines(text);
    int n = text.length();
    int[] lineOf = codeLines.isEmpty() ? null : lineNumbers(text);
    int nextCode = codeLines.nextSetBit(0);
    StringBuilder out = new StringBuilder(n);
    int i = 0;
    int tick = -1;
    while (i < n) {
      Integer end = text.charAt(i) == '{' ? inlineTagEnd.get(i) : null;
      if (end != null) {
        if (tick < i) {
          tick = text.indexOf('`', i);
          if (tick < 0) {
            tick = n;
          }
        }
        boolean touchesCode = false;
        if (lineOf != null) {
          int startLine = lineOf[i];
          if (nextCode >= 0 && nextCode < startLine) {
            nextCode = codeLines.nextSetBit(startLine);
          }
          touchesCode = nextCode >= 0 && nextCode <= lineOf[end];
        }
        // shortcut: a tag containing a backtick stays text, since an inline Markdown code span may
        // start or end inside it; fenced and indented code blocks are excluded via codeLines.
        if (tick > end && !touchesCode) {
          int index = tags.size();
          tags.add(text.substring(i, end + 1));
          out.append(TAG_OPEN).append(index).append(TAG_CLOSE);
          i = end + 1;
          continue;
        }
      }
      out.append(text.charAt(i));
      i++;
    }
    return out.toString();
  }

  private static int[] lineNumbers(String text) {
    int n = text.length();
    int[] lineOf = new int[n];
    int line = 0;
    for (int i = 0; i < n; i++) {
      lineOf[i] = line;
      if (text.charAt(i) == '\n') {
        line++;
      }
    }
    return lineOf;
  }

  private static List<Block> rewriteBlocks(
      List<Block> blocks, List<String> tags, Set<String> resolvedRefs, boolean inLink) {
    List<Block> out = new ArrayList<>(blocks.size());
    for (Block block : blocks) {
      switch (block) {
        case Paragraph p -> {
          List<Inline> content = rewriteInlines(p.content(), tags, resolvedRefs, inLink);
          if (!content.isEmpty()) {
            out.add(new Paragraph(content));
          }
        }
        case Heading h ->
          out.add(new Heading(
              h.level(), rewriteInlines(h.content(), tags, resolvedRefs, inLink), h.id()));
        case CodeBlock c ->
          out.add(new CodeBlock(
              restorePlaceholders(c.language(), tags), restorePlaceholders(c.code(), tags)));
        case Quote q ->
          out.add(new Quote(q.alert(), rewriteBlocks(q.blocks(), tags, resolvedRefs, inLink)));
        case ListBlock l -> {
          List<Item> items = new ArrayList<>(l.items().size());
          for (Item item : l.items()) {
            items.add(
                new Item(item.task(), rewriteBlocks(item.blocks(), tags, resolvedRefs, inLink)));
          }
          out.add(new ListBlock(l.ordered(), l.start(), l.tight(), items));
        }
        case Table t -> {
          List<List<Inline>> header = new ArrayList<>(t.header().size());
          for (List<Inline> cell : t.header()) {
            header.add(rewriteInlines(cell, tags, resolvedRefs, inLink));
          }
          List<List<List<Inline>>> rows = new ArrayList<>(t.rows().size());
          for (List<List<Inline>> row : t.rows()) {
            List<List<Inline>> rewrittenRow = new ArrayList<>(row.size());
            for (List<Inline> cell : row) {
              rewrittenRow.add(rewriteInlines(cell, tags, resolvedRefs, inLink));
            }
            rows.add(rewrittenRow);
          }
          out.add(new Table(t.aligns(), header, rows));
        }
        case ThematicBreak tb -> out.add(tb);
      }
    }
    return out;
  }

  private static List<Inline> rewriteInlines(
      List<Inline> inlines, List<String> tags, Set<String> resolvedRefs, boolean inLink) {
    List<Inline> out = new ArrayList<>(inlines.size());
    for (Inline inline : inlines) {
      switch (inline) {
        case Text t -> expandText(t.text(), tags, inLink, out);
        case Code c -> out.add(new Code(restorePlaceholders(c.code(), tags)));
        case Emphasis e -> {
          List<Inline> content = rewriteInlines(e.content(), tags, resolvedRefs, inLink);
          if (!content.isEmpty()) {
            out.add(new Emphasis(content));
          }
        }
        case Strong s -> {
          List<Inline> content = rewriteInlines(s.content(), tags, resolvedRefs, inLink);
          if (!content.isEmpty()) {
            out.add(new Strong(content));
          }
        }
        case Strike s -> {
          List<Inline> content = rewriteInlines(s.content(), tags, resolvedRefs, inLink);
          if (!content.isEmpty()) {
            out.add(new Strike(content));
          }
        }
        case Link l -> {
          String dest = restorePlaceholders(l.destination(), tags);
          String title = restorePlaceholders(l.title(), tags);
          List<Inline> content;
          // [String] reads as {@link String}, [a list][List] as {@linkplain List a list}
          if (resolvedRefs.contains(dest) && l.content().equals(List.of(new Text(dest)))) {
            content = List.of(new Code(dest));
          } else {
            content = rewriteInlines(l.content(), tags, resolvedRefs, true);
          }
          if (inLink) {
            out.addAll(content);
          } else {
            out.add(new Link(dest, title, content));
          }
        }
        case Image img ->
          out.add(new Image(
              restorePlaceholders(img.source(), tags),
              restorePlaceholders(img.title(), tags),
              restorePlaceholders(img.alt(), tags)));
        case SoftBreak sb -> out.add(sb);
        case HardBreak hb -> out.add(hb);
      }
    }
    return mergeAdjacentText(out);
  }

  private static void expandText(String text, List<String> tags, boolean inLink, List<Inline> out) {
    Matcher m = PLACEHOLDER.matcher(text);
    int pos = 0;
    while (m.find()) {
      String tag = tag(m, tags);
      if (tag == null) {
        continue;
      }
      if (m.start() > pos) {
        out.add(new Text(text.substring(pos, m.start())));
      }
      out.addAll(parseJavadocInlineTag(tag, inLink));
      pos = m.end();
    }
    if (pos < text.length()) {
      out.add(new Text(text.substring(pos)));
    }
  }

  private static String restorePlaceholders(String text, List<String> tags) {
    return text.indexOf(TAG_OPEN) < 0
        ? text
        : PLACEHOLDER
            .matcher(text)
            .replaceAll(
                m -> Matcher.quoteReplacement(Objects.requireNonNullElse(tag(m, tags), m.group())));
  }

  /** The tag a placeholder stands for, or {@code null} for one an entity like {@code &#xE000;} wrote. */
  private static String tag(MatchResult placeholder, List<String> tags) {
    int index = Integer.parseInt(placeholder.group(1));
    return index < tags.size() ? tags.get(index) : null;
  }

  /**
   * The reference a link label {@code [String]}, {@code [java.util.List]} or {@code
   * [List#add(Object)]} names, with {@code \[} and {@code \]} unescaped, or {@code null} when the
   * label is not a Java reference.
   */
  private static String resolveJavaReference(String label) {
    return REFERENCE.matcher(label).matches() && !label.isEmpty()
        ? collapseWhitespace(label.replace("\\[", "[").replace("\\]", "]"))
        : null;
  }

  private static List<Inline> firstParagraphInlines(MarkdownDocument doc, String fallback) {
    for (Block b : doc.blocks()) {
      if (b instanceof Paragraph p && !p.content().isEmpty()) {
        return p.content();
      }
    }
    return List.of(new Text(fallback));
  }

  private static boolean startsWithAnchor(String s) {
    if (s.length() < 3 || s.charAt(0) != '<') {
      return false;
    }
    char a = s.charAt(1);
    char after = s.charAt(2);
    return (a == 'a' || a == 'A') && (Character.isWhitespace(after) || after == '>');
  }

  /**
   * Splits a Javadoc reference and optional label, keeping spaces inside {@code (...)} as part of
   * the reference (for example, {@code Foo#bar(int, String) the label}).
   */
  private static Split splitRefAndLabel(String raw) {
    int n = raw.length();
    int i = skipWhitespace(raw, 0);
    int start = i;
    int parens = 0; // and angle brackets: Map<String, Integer> is one reference
    while (i < n) {
      char c = raw.charAt(i);
      if (c == '(' || c == '<') {
        parens++;
      } else if ((c == ')' || c == '>') && parens > 0) {
        parens--;
      } else if (Character.isWhitespace(c) && parens == 0) {
        break;
      }
      i++;
    }
    String ref = collapseWhitespace(raw.substring(start, i));
    String label = raw.substring(i).strip();
    return new Split(ref, label);
  }

  // ---- Traditional Javadoc parser (direct MarkdownDocument builder, non-recursive, O(n))

  private enum InlineKind {
    ROOT,
    STRONG,
    EMPHASIS,
    LINK
  }

  private static final class InlineFrame {
    final InlineKind kind;
    final String destination;
    final List<Inline> children = new ArrayList<>();

    InlineFrame(InlineKind kind, String destination) {
      this.kind = kind;
      this.destination = destination;
    }
  }

  private static final class ListFrame {
    final boolean ordered;
    final List<List<Block>> items = new ArrayList<>();

    ListFrame(boolean ordered) {
      this.ordered = ordered;
    }
  }

  private static MarkdownDocument parseJavadoc(String source) {
    JavadocBuilder builder = new JavadocBuilder(source, false);
    builder.parseBlocks();
    List<Block> blocks = builder.finish();
    return blocks.isEmpty() ? null : new MarkdownDocument(blocks);
  }

  private static List<Inline> parseJavadocInlines(String source, boolean inLink) {
    JavadocBuilder builder = new JavadocBuilder(source, inLink);
    builder.parseInlineOnly();
    return builder.finishInlines();
  }

  private static List<Inline> parseJavadocInlineTag(String tagSource, boolean inLink) {
    JavadocBuilder builder = new JavadocBuilder(tagSource, inLink);
    builder.parseInlineOnly();
    return builder.finishInlineTag();
  }

  private static final class JavadocBuilder {
    private final String s;
    private final int n;
    private final Map<Integer, Integer> inlineTagEnd;
    private final boolean outerLink;

    private final List<Block> rootBlocks = new ArrayList<>();
    private final Deque<ListFrame> listStack = new ArrayDeque<>();
    private final List<InlineFrame> inlineStack = new ArrayList<>();
    private final StringBuilder prose = new StringBuilder();

    private boolean noCloseCode;
    private boolean noClosePre;

    JavadocBuilder(String s, boolean outerLink) {
      this.s = s;
      this.n = s.length();
      this.outerLink = outerLink;
      this.inlineTagEnd = s.contains("{@") ? matchInlineTags(s) : Map.of();
      inlineStack.add(new InlineFrame(InlineKind.ROOT, null));
    }

    /** Matches every {@code {@...}} tag start to its closing {@code }} in one O(n) pass. */
    private static Map<Integer, Integer> matchInlineTags(String s) {
      Map<Integer, Integer> result = new HashMap<>();
      int[] stack = new int[16];
      boolean[] isTag = new boolean[16];
      int top = 0;
      int len = s.length();
      for (int i = 0; i < len; i++) {
        char c = s.charAt(i);
        if (c == '{') {
          if (top == stack.length) {
            stack = Arrays.copyOf(stack, top * 2);
            isTag = Arrays.copyOf(isTag, top * 2);
          }
          stack[top] = i;
          isTag[top] = i + 2 < len && s.charAt(i + 1) == '@' && isAsciiLetter(s.charAt(i + 2));
          top++;
        } else if (c == '}' && top > 0) {
          top--;
          if (isTag[top]) {
            result.put(stack[top], i);
          }
        }
      }
      return result;
    }

    void parseBlocks() {
      int pos = 0;
      while (pos < n) {
        char c = s.charAt(pos);
        if (c == '\n') {
          int blankEnd = blankLineRun(pos);
          if (blankEnd > pos) {
            flushParagraph();
            pos = blankEnd;
            continue;
          }
          prose.append(' ');
          pos++;
        } else if (c == '{' && inlineTagEnd.containsKey(pos)) {
          pos = handleInlineTag(pos);
        } else if (c == '<') {
          int next = handleHtmlTag(pos, true);
          if (next > pos) {
            pos = next;
          } else {
            prose.append(c);
            pos++;
          }
        } else if (c == '&') {
          int next = handleEntity(pos);
          if (next > pos) {
            pos = next;
          } else {
            prose.append(c);
            pos++;
          }
        } else {
          prose.append(c);
          pos++;
        }
      }
      flushParagraph();
      while (!listStack.isEmpty()) {
        closeList();
      }
    }

    void parseInlineOnly() {
      int pos = 0;
      while (pos < n) {
        char c = s.charAt(pos);
        if (c == '{' && inlineTagEnd.containsKey(pos)) {
          pos = handleInlineTag(pos);
        } else if (c == '<') {
          int next = handleHtmlTag(pos, false);
          if (next > pos) {
            pos = next;
          } else {
            prose.append(c);
            pos++;
          }
        } else if (c == '&') {
          int next = handleEntity(pos);
          if (next > pos) {
            pos = next;
          } else {
            prose.append(c);
            pos++;
          }
        } else {
          prose.append(c);
          pos++;
        }
      }
    }

    List<Block> finish() {
      return rootBlocks;
    }

    List<Inline> finishInlines() {
      flushProse();
      unwindInlineStack();
      List<Inline> normalized = normalizeInlines(inlineStack.getFirst().children);
      inlineStack.clear();
      inlineStack.add(new InlineFrame(InlineKind.ROOT, null));
      return normalized;
    }

    List<Inline> finishInlineTag() {
      flushProse();
      unwindInlineStack();
      List<Inline> merged = mergeAdjacentText(inlineStack.getFirst().children);
      inlineStack.clear();
      inlineStack.add(new InlineFrame(InlineKind.ROOT, null));
      return merged;
    }

    /** If a blank line starts at {@code nl} (index of {@code \n}), returns the index past it. */
    private int blankLineRun(int nl) {
      int i = nl + 1;
      int afterBlank = -1;
      while (i < n) {
        int lineEnd = skipHorizontalSpace(s, i);
        if (lineEnd < n && s.charAt(lineEnd) == '\n') {
          afterBlank = lineEnd + 1;
          i = afterBlank;
        } else {
          break;
        }
      }
      return afterBlank;
    }

    private int handleInlineTag(int pos) {
      int end = inlineTagEnd.get(pos);
      int nameStart = pos + 2;
      int nameEnd = nameStart;
      while (nameEnd < end && isTagNameChar(s.charAt(nameEnd))) {
        nameEnd++;
      }
      if (nameEnd < end && !Character.isWhitespace(s.charAt(nameEnd))) {
        prose.append(s, pos, end + 1);
        return end + 1;
      }
      String name = s.substring(nameStart, nameEnd);
      int bodyStart = nameEnd;
      if (bodyStart < end) {
        char sep = s.charAt(bodyStart);
        if (sep == '\n') {
          bodyStart = skipHorizontalSpace(s, bodyStart + 1);
        } else if (Character.isWhitespace(sep)) {
          bodyStart++;
        }
      }
      String body = s.substring(bodyStart, end);
      switch (name) {
        case "code" -> {
          flushProse();
          String code = body.replace('\n', ' ');
          if (!code.isEmpty()) {
            appendInline(new Code(code));
          }
        }
        case "literal" -> {
          flushProse();
          if (!body.isEmpty()) {
            appendInline(new Text(body));
          }
        }
        case "link", "linkplain" -> {
          Split refLabel = splitRefAndLabel(body);
          String ref = refLabel.head();
          String label = refLabel.rest();
          if (ref.isEmpty()) {
            prose.append(s, pos, end + 1);
          } else {
            flushProse();
            List<Inline> content = outerLink && !label.isEmpty()
                ? List.of(new Text(label))
                : !label.isEmpty()
                    ? parseJavadocInlines(label, true)
                    : name.equals("link") ? List.of(new Code(ref)) : List.of(new Text(ref));
            if (isInsideLink()) {
              for (Inline in : content) {
                appendInline(in);
              }
            } else {
              appendInline(new Link(ref, "", content));
            }
          }
        }
        default -> prose.append(s, pos, end + 1);
      }
      return end + 1;
    }

    private int handleHtmlTag(int pos, boolean allowBlocks) {
      int i = pos + 1;
      if (i >= n) {
        return pos;
      }
      boolean closing = s.charAt(i) == '/';
      if (closing) {
        i++;
      }
      int nameStart = i;
      while (i < n && isAsciiLetter(s.charAt(i))) {
        i++;
      }
      if (i == nameStart) {
        return pos;
      }
      String tagName = s.substring(nameStart, i).toLowerCase(Locale.ROOT);
      if (!isKnownHtmlTag(tagName, allowBlocks)) {
        return pos;
      }
      if (i < n
          && !Character.isWhitespace(s.charAt(i))
          && s.charAt(i) != '>'
          && s.charAt(i) != '/') {
        return pos;
      }
      int tagEnd = findTagEnd(s, i);
      if (tagEnd < 0) {
        return pos;
      }
      String attrs = s.substring(i, tagEnd);
      int after = tagEnd + 1;

      if (allowBlocks) {
        switch (tagName) {
          case "p" -> {
            flushParagraph();
            return after;
          }
          case "ul", "ol" -> {
            if (!closing) {
              flushParagraph();
              if (listStack.size() < MAX_DEPTH) {
                listStack.push(new ListFrame(tagName.equals("ol")));
              }
            } else if (!listStack.isEmpty()) {
              flushParagraph();
              closeList();
            }
            return after;
          }
          case "li" -> {
            flushParagraph();
            if (!closing) {
              if (listStack.isEmpty()) {
                listStack.push(new ListFrame(false));
              }
              listStack.peek().items.add(new ArrayList<>());
            }
            return after;
          }
          case "pre" -> {
            if (!closing) {
              if (noClosePre) {
                return pos;
              }
              int closeStart = findClosePre(after);
              if (closeStart < 0) {
                noClosePre = true;
                return pos;
              }
              int closeEnd = findTagEnd(s, closeStart + 5);
              if (closeEnd < 0) {
                noClosePre = true;
                return pos;
              }
              flushParagraph();
              String code = extractPreCode(s.substring(after, closeStart));
              currentBlocks().add(new CodeBlock("", code));
              return closeEnd + 1;
            }
            return after;
          }
          default -> {}
        }
      }

      switch (tagName) {
        case "br" -> {
          if (!closing) {
            flushProse();
            appendInline(new HardBreak());
          }
          return after;
        }
        case "b", "strong" -> {
          flushProse();
          if (!closing) {
            if (inlineStack.size() <= MAX_DEPTH) {
              inlineStack.add(new InlineFrame(InlineKind.STRONG, null));
            }
          } else {
            closeInlineFrame(InlineKind.STRONG);
          }
          return after;
        }
        case "i", "em" -> {
          flushProse();
          if (!closing) {
            if (inlineStack.size() <= MAX_DEPTH) {
              inlineStack.add(new InlineFrame(InlineKind.EMPHASIS, null));
            }
          } else {
            closeInlineFrame(InlineKind.EMPHASIS);
          }
          return after;
        }
        case "code" -> {
          if (!closing) {
            if (noCloseCode) {
              return pos;
            }
            int closeStart = findCloseCode(after);
            if (closeStart < 0) {
              noCloseCode = true;
              return pos;
            }
            int closeEnd = findTagEnd(s, closeStart + 6);
            if (closeEnd < 0) {
              noCloseCode = true;
              return pos;
            }
            flushProse();
            inlineCode(after, closeStart);
            return closeEnd + 1;
          }
          return after;
        }
        case "a" -> {
          flushProse();
          if (!closing) {
            String href = extractHref(attrs);
            if (href != null && !isInsideLink() && inlineStack.size() <= MAX_DEPTH) {
              inlineStack.add(new InlineFrame(InlineKind.LINK, href));
            }
          } else {
            closeInlineFrame(InlineKind.LINK);
          }
          return after;
        }
        default -> {
          return pos;
        }
      }
    }

    private int findCloseCode(int from) {
      int i = from;
      while (i < n) {
        if (s.charAt(i) == '{' && inlineTagEnd.containsKey(i)) {
          i = inlineTagEnd.get(i) + 1;
          continue;
        }
        if (s.charAt(i) == '<' && matchSimpleTag(s, i, "code", true) > i) {
          return i;
        }
        i++;
      }
      return -1;
    }

    private int findClosePre(int from) {
      int i = from;
      while (i < n) {
        if (s.charAt(i) == '{' && inlineTagEnd.containsKey(i)) {
          i = inlineTagEnd.get(i) + 1;
          continue;
        }
        if (s.charAt(i) == '<' && matchSimpleTag(s, i, "pre", true) > i) {
          return i;
        }
        i++;
      }
      return -1;
    }

    /**
     * Appends the text of {@code <code>...</code>} between {@code from} and {@code to} as code,
     * decoding entities and unwrapping {@code {@code}} and {@code {@literal}}; another inline tag
     * inside, such as {@code {@link}}, stays what it is between the pieces of code.
     */
    private void inlineCode(int from, int to) {
      StringBuilder code = new StringBuilder();
      int i = from;
      while (i < to) {
        char c = s.charAt(i);
        if (c == '{' && inlineTagEnd.containsKey(i)) {
          int end = inlineTagEnd.get(i);
          String unwrapped = unwrapCodeOrLiteral(s, i, end);
          if (unwrapped != null) {
            code.append(unwrapped);
            i = end + 1;
          } else {
            appendCode(code);
            i = handleInlineTag(i);
            flushProse();
          }
        } else if (c == '&') {
          int[] next = new int[1];
          String decoded = decodeEntity(s, i, next);
          code.append(decoded != null ? decoded : "&");
          i = decoded != null ? next[0] : i + 1;
        } else {
          code.append(c == '\n' ? ' ' : c);
          i++;
        }
      }
      appendCode(code);
    }

    private void appendCode(StringBuilder code) {
      if (!code.isEmpty()) {
        appendInline(new Code(code.toString()));
        code.setLength(0);
      }
    }

    /**
     * Extracts text inside {@code <pre>...</pre>}: unwraps {@code {@code ...}} or {@code
     * <code>...</code>}, decodes entities outside {@code {@code}}/{@code {@literal}}, strips
     * common indentation and ensures each line ends in a line feed.
     */
    private static String extractPreCode(String raw) {
      String trimmed = raw.strip();
      // Check for <pre><code>...</code></pre>.
      int openCodeEnd = matchSimpleTag(trimmed, 0, "code", false);
      if (openCodeEnd > 0) {
        int closeCodeStart = trimmed.length() - 7;
        if (closeCodeStart >= openCodeEnd
            && matchSimpleTag(trimmed, closeCodeStart, "code", true) == trimmed.length()) {
          raw = trimmed.substring(openCodeEnd, closeCodeStart);
        }
      }
      Map<Integer, Integer> tags = raw.contains("{@") ? matchInlineTags(raw) : Map.of();
      StringBuilder out = new StringBuilder(raw.length());
      int i = 0;
      while (i < raw.length()) {
        char c = raw.charAt(i);
        if (c == '{' && tags.containsKey(i)) {
          int end = tags.get(i);
          String unwrapped = unwrapCodeOrLiteral(raw, i, end);
          if (unwrapped != null) {
            out.append(unwrapped);
          } else {
            out.append(raw, i, end + 1);
          }
          i = end + 1;
        } else if (c == '&') {
          int[] next = new int[1];
          String decoded = decodeEntity(raw, i, next);
          if (decoded != null) {
            out.append(decoded);
            i = next[0];
          } else {
            out.append(c);
            i++;
          }
        } else {
          out.append(c);
          i++;
        }
      }
      String text = out.toString();
      List<String> lines = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
      if (!lines.isEmpty() && lines.getFirst().isBlank()) {
        lines.removeFirst();
      }
      if (!lines.isEmpty() && lines.getLast().isBlank()) {
        lines.removeLast();
      }
      if (lines.isEmpty()) {
        return "";
      }
      List<String> dedented = stripCommonIndent(lines);
      StringBuilder code = new StringBuilder();
      for (String line : dedented) {
        code.append(line).append('\n');
      }
      return code.toString();
    }

    /** If {@code raw[start..end]} is {@code {@code ...}} or {@code {@literal ...}}, unwraps it. */
    private static String unwrapCodeOrLiteral(String raw, int start, int end) {
      int nameStart = start + 2;
      int nameEnd = nameStart;
      while (nameEnd < end && isTagNameChar(raw.charAt(nameEnd))) {
        nameEnd++;
      }
      if (nameEnd < end && !Character.isWhitespace(raw.charAt(nameEnd))) {
        return null;
      }
      String name = raw.substring(nameStart, nameEnd);
      if (!name.equals("code") && !name.equals("literal")) {
        return null;
      }
      int bodyStart = nameEnd;
      if (bodyStart < end && isHorizontalSpace(raw.charAt(bodyStart))) {
        bodyStart++;
      }
      return raw.substring(bodyStart, end);
    }

    private int handleEntity(int pos) {
      int[] next = new int[1];
      String decoded = decodeEntity(s, pos, next);
      if (decoded != null) {
        prose.append(decoded);
        return next[0];
      }
      return pos;
    }

    private boolean isInsideLink() {
      if (outerLink) {
        return true;
      }
      for (int i = 1; i < inlineStack.size(); i++) {
        if (inlineStack.get(i).kind == InlineKind.LINK) {
          return true;
        }
      }
      return false;
    }

    private void appendInline(Inline inline) {
      inlineStack.getLast().children.add(inline);
    }

    private void flushProse() {
      if (prose.isEmpty()) {
        return;
      }
      String collapsed = collapseProseWhitespace(prose.toString());
      prose.setLength(0);
      if (!collapsed.isEmpty()) {
        appendInline(new Text(collapsed));
      }
    }

    private void closeInlineFrame(InlineKind kind) {
      int match = -1;
      for (int i = inlineStack.size() - 1; i >= 1; i--) {
        if (inlineStack.get(i).kind == kind) {
          match = i;
          break;
        }
      }
      if (match < 0) {
        return;
      }
      while (inlineStack.size() > match) {
        popInlineFrame();
      }
    }

    private void unwindInlineStack() {
      while (inlineStack.size() > 1) {
        popInlineFrame();
      }
    }

    private void popInlineFrame() {
      InlineFrame frame = inlineStack.removeLast();
      List<Inline> merged = mergeAdjacentText(frame.children);
      if (merged.isEmpty()) {
        return;
      }
      Inline built = switch (frame.kind) {
        case STRONG -> new Strong(merged);
        case EMPHASIS -> new Emphasis(merged);
        case LINK -> new Link(frame.destination, "", merged);
        case ROOT -> throw new AssertionError();
      };
      inlineStack.getLast().children.add(built);
    }

    private void flushParagraph() {
      flushProse();
      unwindInlineStack();
      List<Inline> children = inlineStack.getFirst().children;
      if (!children.isEmpty()) {
        List<Inline> normalized = normalizeInlines(children);
        children.clear();
        if (!normalized.isEmpty()) {
          currentBlocks().add(new Paragraph(normalized));
        }
      }
    }

    private List<Block> currentBlocks() {
      if (listStack.isEmpty()) {
        return rootBlocks;
      }
      ListFrame list = listStack.peek();
      if (list.items.isEmpty()) {
        list.items.add(new ArrayList<>());
      }
      return list.items.getLast();
    }

    private void closeList() {
      ListFrame frame = listStack.pop();
      if (frame.items.isEmpty()) {
        return;
      }
      List<Item> items = new ArrayList<>(frame.items.size());
      for (List<Block> blocks : frame.items) {
        if (!blocks.isEmpty()) {
          items.add(new Item(null, blocks));
        }
      }
      if (!items.isEmpty()) {
        currentBlocks().add(new ListBlock(frame.ordered, 1, true, items));
      }
    }
  }

  private static boolean isKnownHtmlTag(String tagName, boolean allowBlocks) {
    return switch (tagName) {
      case "b", "strong", "i", "em", "code", "a", "br" -> true;
      case "p", "ul", "ol", "li", "pre" -> allowBlocks;
      default -> false;
    };
  }

  /** Finds the closing {@code >} of an HTML tag starting at {@code from}, respecting quotes. */
  private static int findTagEnd(String s, int from) {
    int limit = Math.min(s.length(), from + MAX_TAG_SCAN);
    char quote = 0;
    for (int i = from; i < limit; i++) {
      char c = s.charAt(i);
      if (quote != 0) {
        if (c == quote) {
          quote = 0;
        }
      } else if (c == '"' || c == '\'') {
        quote = c;
      } else if (c == '>') {
        return i;
      }
    }
    return -1;
  }

  /**
   * Matches {@code <name ...>} (when {@code closing == false}) or {@code </name>} (when {@code
   * closing == true}) at {@code at}, case-insensitively. Returns the index past {@code >}, or
   * {@code at} when it does not match.
   */
  private static int matchSimpleTag(String s, int at, String name, boolean closing) {
    int n = s.length();
    int i = at;
    if (i >= n || s.charAt(i) != '<') {
      return at;
    }
    i++;
    if (closing) {
      if (i >= n || s.charAt(i) != '/') {
        return at;
      }
      i++;
    }
    int len = name.length();
    if (i + len > n) {
      return at;
    }
    for (int j = 0; j < len; j++) {
      if (Character.toLowerCase(s.charAt(i + j)) != name.charAt(j)) {
        return at;
      }
    }
    i += len;
    if (i < n && !Character.isWhitespace(s.charAt(i)) && s.charAt(i) != '>' && s.charAt(i) != '/') {
      return at;
    }
    int end = findTagEnd(s, i);
    return end < 0 ? at : end + 1;
  }

  /** Extracts and decodes the {@code href} attribute from an {@code <a>} tag's attribute string. */
  private static String extractHref(String attrs) {
    int n = attrs.length();
    int i = 0;
    while (i < n) {
      while (i < n && (Character.isWhitespace(attrs.charAt(i)) || attrs.charAt(i) == '/')) {
        i++;
      }
      int nameStart = i;
      while (i < n
          && !Character.isWhitespace(attrs.charAt(i))
          && attrs.charAt(i) != '='
          && attrs.charAt(i) != '/') {
        i++;
      }
      if (i == nameStart) {
        break;
      }
      String attrName = attrs.substring(nameStart, i);
      while (i < n && Character.isWhitespace(attrs.charAt(i))) {
        i++;
      }
      if (i >= n || attrs.charAt(i) != '=') {
        continue;
      }
      i++;
      while (i < n && Character.isWhitespace(attrs.charAt(i))) {
        i++;
      }
      if (i >= n) {
        break;
      }
      String value;
      char c = attrs.charAt(i);
      if (c == '"' || c == '\'') {
        int valStart = i + 1;
        int close = attrs.indexOf(c, valStart);
        if (close < 0) {
          return null;
        }
        value = attrs.substring(valStart, close);
        i = close + 1;
      } else {
        int valStart = i;
        while (i < n && !Character.isWhitespace(attrs.charAt(i)) && attrs.charAt(i) != '>') {
          i++;
        }
        value = attrs.substring(valStart, i);
      }
      if (attrName.equalsIgnoreCase("href")) {
        return decodeEntities(value.strip());
      }
    }
    return null;
  }

  private static String decodeEntities(String text) {
    if (text.indexOf('&') < 0) {
      return text;
    }
    StringBuilder out = new StringBuilder(text.length());
    int[] next = new int[1];
    int i = 0;
    while (i < text.length()) {
      if (text.charAt(i) == '&') {
        String decoded = decodeEntity(text, i, next);
        if (decoded != null) {
          out.append(decoded);
          i = next[0];
          continue;
        }
      }
      out.append(text.charAt(i++));
    }
    return out.toString();
  }

  /** Decodes a common HTML entity at {@code p}, storing the index past {@code ;} in {@code next}. */
  private static String decodeEntity(String s, int p, int[] next) {
    int semi = -1;
    int limit = Math.min(s.length(), p + 16);
    for (int q = p + 1; q < limit; q++) {
      if (s.charAt(q) == ';') {
        semi = q;
        break;
      }
    }
    if (semi < 0) {
      return null;
    }
    String body = s.substring(p + 1, semi);
    next[0] = semi + 1;
    if (body.startsWith("#")) {
      boolean hex = body.length() > 1 && (body.charAt(1) == 'x' || body.charAt(1) == 'X');
      String digits = body.substring(hex ? 2 : 1);
      if (digits.isEmpty() || digits.length() > (hex ? 6 : 7)) {
        return null;
      }
      for (int i = 0; i < digits.length(); i++) {
        char d = digits.charAt(i);
        boolean valid = hex ? Character.digit(d, 16) >= 0 : d >= '0' && d <= '9';
        if (!valid) {
          return null;
        }
      }
      int code = Integer.parseInt(digits, hex ? 16 : 10);
      if (code == 0 || code > 0x10FFFF || code >= 0xD800 && code <= 0xDFFF) {
        code = 0xFFFD;
      }
      return Character.toString(code);
    }
    return switch (body) {
      case "lt" -> "<";
      case "gt" -> ">";
      case "amp" -> "&";
      case "quot" -> "\"";
      case "apos" -> "'";
      case "nbsp" -> "\u00A0";
      default -> null;
    };
  }

  /** Merges adjacent {@link Text} nodes and trims spaces at paragraph ends and around breaks. */
  private static List<Inline> normalizeInlines(List<Inline> raw) {
    List<Inline> merged = mergeAdjacentText(raw);
    if (merged.isEmpty()) {
      return List.of();
    }
    List<Inline> out = new ArrayList<>(merged.size());
    for (int i = 0; i < merged.size(); i++) {
      Inline in = merged.get(i);
      if (in instanceof Text(String text)) {
        boolean trimStart = out.isEmpty() || out.getLast() instanceof HardBreak;
        boolean trimEnd = i + 1 == merged.size() || merged.get(i + 1) instanceof HardBreak;
        if (trimStart) {
          text = stripLeadingSpaces(text);
        }
        if (trimEnd) {
          text = stripTrailingSpaces(text);
        }
        if (!text.isEmpty()) {
          out.add(new Text(text));
        }
      } else {
        out.add(in);
      }
    }
    return out;
  }

  private static List<Inline> mergeAdjacentText(List<Inline> parts) {
    List<Inline> out = new ArrayList<>(parts.size());
    StringBuilder text = new StringBuilder();
    for (Inline in : parts) {
      if (in instanceof Text t) {
        text.append(t.text());
      } else {
        if (!text.isEmpty()) {
          out.add(new Text(text.toString()));
          text.setLength(0);
        }
        out.add(in);
      }
    }
    if (!text.isEmpty()) {
      out.add(new Text(text.toString()));
    }
    return out;
  }

  private static String collapseProseWhitespace(String text) {
    StringBuilder out = new StringBuilder(text.length());
    boolean space = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == ' ' || c == '\t' || c == '\n') {
        if (!space) {
          out.append(' ');
          space = true;
        }
      } else {
        out.append(c);
        space = false;
      }
    }
    return out.toString();
  }

  private static String collapseWhitespace(String text) {
    return collapseProseWhitespace(text).strip();
  }

  private static String stripLeadingSpaces(String s) {
    int i = 0;
    while (i < s.length() && s.charAt(i) == ' ') {
      i++;
    }
    return i == 0 ? s : s.substring(i);
  }

  private static String stripTrailingSpaces(String s) {
    int end = s.length();
    while (end > 0 && s.charAt(end - 1) == ' ') {
      end--;
    }
    return end == s.length() ? s : s.substring(0, end);
  }

  private static int skipWhitespace(String s, int from) {
    int i = from;
    while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
      i++;
    }
    return i;
  }

  private static int skipHorizontalSpace(String s, int from) {
    int i = from;
    while (i < s.length() && isHorizontalSpace(s.charAt(i))) {
      i++;
    }
    return i;
  }

  private static boolean isHorizontalSpace(char c) {
    return c == ' ' || c == '\t';
  }

  private static boolean isBlank(String s, int from) {
    return isBlank(s, from, s.length());
  }

  private static boolean isBlank(String s, int from, int to) {
    for (int i = from; i < to; i++) {
      if (!Character.isWhitespace(s.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private static boolean isAsciiLetter(char c) {
    return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
  }

  private static boolean isTagNameChar(char c) {
    return isAsciiLetter(c) || c >= '0' && c <= '9' || c == '_' || c == '-' || c == '.';
  }
}
