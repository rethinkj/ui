package com.rethinkj.ui.javascript;

import com.rethinkj.ui.editor.DocComment;
import com.rethinkj.ui.kit.MarkdownDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Parses a JSDoc or TSDoc comment ({@code /** ... *}{@code /}) into {@link DocComment}.
 *
 * <ul>
 *   <li>{@code @param}, {@code @arg}, {@code @argument}: optional {@code {type}}, optional
 *       {@code [name]} / {@code [name=default]} (preserved verbatim as the entry's name so
 *       optionality and defaults stay visible) or dotted {@code options.key}, and an optional
 *       {@code -} before the description.
 *   <li>{@code @returns}, {@code @return}: optional {@code {type}} is rendered as inline code
 *       before the description ({@code `type` — description}, or {@code `type`} alone).
 *   <li>{@code @throws}, {@code @exception}: {@code {Type}} becomes the entry's name; without
 *       braces, a TSDoc-style {@code Name - description} uses {@code Name}, and plain prose uses
 *       an empty name so the prose stays the description.
 *   <li>{@code @see}: each entry parsed as Markdown with inline links expanded.
 *   <li>{@code @deprecated}: bare {@code @deprecated} yields a non-null empty {@link
 *       MarkdownDocument}; absent yields {@code null}.
 *   <li>{@code @example}: appended to the body as a fenced code block tagged with the language
 *       ({@code javascript} or {@code typescript}), unless the example already starts with a fence.
 *       An optional leading {@code <caption>...</caption>} becomes a line above the fence.
 *   <li>Inline {@code {@link ...}}, {@code {@linkcode ...}} and {@code {@linkplain ...}} outside
 *       code spans and fenced blocks become Markdown links ({@code @linkcode} wraps the label in
 *       inline code).
 *   <li>Unknown block tags are ignored.
 * </ul>
 */
final class JsDoc {

  private record Tag(String name, String content) {}

  private JsDoc() {}

  static DocComment parse(String text, String language) {
    Objects.requireNonNull(text, "text");
    List<String> lines = stripComment(text);

    List<String> bodyLines = new ArrayList<>();
    List<Tag> tags = new ArrayList<>();

    String currentTag = null;
    StringBuilder currentContent = new StringBuilder();
    char fenceChar = 0;
    int fenceLength = 0;

    for (String line : lines) {
      int fence = fenceMarker(line, fenceChar, fenceLength);
      if (fenceChar != 0) {
        if (fence < 0) {
          fenceChar = 0;
          fenceLength = 0;
        }
        appendLine(currentTag, bodyLines, currentContent, line);
        continue;
      }
      if (fence > 0) {
        fenceChar = firstNonSpace(line);
        fenceLength = fence;
        appendLine(currentTag, bodyLines, currentContent, line);
        continue;
      }
      String tagName = blockTagName(line);
      if (tagName != null) {
        if (currentTag != null) {
          tags.add(new Tag(currentTag, trimBlankEdges(currentContent.toString())));
        }
        currentTag = tagName;
        currentContent.setLength(0);
        int at = line.indexOf('@');
        int after = at + 1 + tagName.length();
        while (after < line.length() && isHorizSpace(line.charAt(after))) {
          after++;
        }
        currentContent.append(line, after, line.length());
      } else {
        appendLine(currentTag, bodyLines, currentContent, line);
      }
    }
    if (currentTag != null) {
      tags.add(new Tag(currentTag, trimBlankEdges(currentContent.toString())));
    }

    StringBuilder bodyBuilder = new StringBuilder();
    String prose = trimBlankEdges(String.join("\n", bodyLines));
    if (!prose.isEmpty()) {
      bodyBuilder.append(expandInlineTags(prose));
    }

    List<DocComment.Entry> parameters = new ArrayList<>();
    String returnsMarkdown = null;
    List<DocComment.Entry> exceptions = new ArrayList<>();
    List<MarkdownDocument> see = new ArrayList<>();
    boolean deprecatedSeen = false;
    String deprecatedMarkdown = null;

    for (Tag tag : tags) {
      switch (tag.name) {
        case "param", "arg", "argument" -> {
          DocComment.Entry entry = parseParam(tag.content);
          if (entry != null) {
            parameters.add(entry);
          }
        }
        case "returns", "return" -> {
          String md = parseReturns(tag.content);
          if (md != null) {
            returnsMarkdown = returnsMarkdown == null ? md : returnsMarkdown + "\n\n" + md;
          }
        }
        case "throws", "exception" -> {
          DocComment.Entry entry = parseThrows(tag.content);
          if (entry != null) {
            exceptions.add(entry);
          }
        }
        case "see" -> {
          if (!tag.content.isEmpty()) {
            see.add(MarkdownDocument.parse(expandInlineTags(tag.content)));
          }
        }
        case "deprecated" -> {
          deprecatedSeen = true;
          if (!tag.content.isEmpty()) {
            String expanded = expandInlineTags(tag.content);
            deprecatedMarkdown =
                deprecatedMarkdown == null ? expanded : deprecatedMarkdown + "\n\n" + expanded;
          }
        }
        case "example" -> {
          String example = formatExample(tag.content, language);
          if (example != null) {
            if (!bodyBuilder.isEmpty()) {
              bodyBuilder.append("\n\n");
            }
            bodyBuilder.append(example);
          }
        }
        default -> {
          // Unknown tags are ignored.
        }
      }
    }

    MarkdownDocument body =
        bodyBuilder.isEmpty() ? null : MarkdownDocument.parse(bodyBuilder.toString());
    MarkdownDocument returns =
        returnsMarkdown == null ? null : MarkdownDocument.parse(returnsMarkdown);
    MarkdownDocument deprecated = !deprecatedSeen
        ? null
        : MarkdownDocument.parse(deprecatedMarkdown == null ? "" : deprecatedMarkdown);

    return new DocComment(body, parameters, returns, exceptions, see, deprecated);
  }

  private static void appendLine(
      String currentTag, List<String> bodyLines, StringBuilder currentContent, String line) {
    if (currentTag == null) {
      bodyLines.add(line);
    } else {
      currentContent.append('\n').append(line);
    }
  }

  /** Strips {@code /**}, {@code *}{@code /} and the leading {@code *} of subsequent lines. */
  private static List<String> stripComment(String text) {
    String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
    if (normalized.startsWith("/**")) {
      normalized = normalized.substring(3);
    }
    if (normalized.endsWith("*/")) {
      normalized = normalized.substring(0, normalized.length() - 2);
    }
    String[] raw = normalized.split("\n", -1);
    List<String> out = new ArrayList<>(raw.length);

    // Measure the column of '*' on starred lines so non-starred lines can drop the same file
    // indent.
    int starIndent = -1;
    for (int idx = 1; idx < raw.length; idx++) {
      int p = skipHorizSpace(raw[idx], 0);
      if (p < raw[idx].length() && raw[idx].charAt(p) == '*') {
        starIndent = starIndent < 0 ? p : Math.min(starIndent, p);
      }
    }

    for (int idx = 0; idx < raw.length; idx++) {
      String line = raw[idx];
      if (idx == 0) {
        if (!line.isEmpty() && isHorizSpace(line.charAt(0))) {
          line = line.substring(1);
        }
        if (raw.length == 1) {
          line = stripTrailingHorizSpace(line);
        }
        out.add(line);
      } else {
        int p = skipHorizSpace(line, 0);
        if (p < line.length() && line.charAt(p) == '*') {
          int after = p + 1;
          if (after < line.length() && isHorizSpace(line.charAt(after))) {
            after++;
          }
          String stripped = line.substring(after);
          if (idx == raw.length - 1) {
            stripped = stripTrailingHorizSpace(stripped);
          }
          out.add(stripped);
        } else if (p == line.length()) {
          out.add("");
        } else {
          int drop = starIndent >= 0 ? Math.min(p, starIndent) : p;
          String stripped = line.substring(drop);
          if (idx == raw.length - 1) {
            stripped = stripTrailingHorizSpace(stripped);
          }
          out.add(stripped);
        }
      }
    }
    return out;
  }

  private static DocComment.Entry parseParam(String content) {
    int n = content.length();
    int i = skipHorizSpace(content, 0);
    if (i >= n) {
      return null;
    }
    String type = null;
    if (content.charAt(i) == '{') {
      int close = findMatchingBrace(content, i);
      if (close < 0) {
        return null;
      }
      String rawType = content.substring(i + 1, close).trim();
      type = rawType.isEmpty() ? null : rawType;
      i = skipWhitespace(content, close + 1);
      if (i >= n) {
        return null;
      }
    }

    String name;
    if (content.charAt(i) == '[') {
      int close = findMatchingBracket(content, i);
      if (close < 0) {
        int end = skipNonWhitespace(content, i);
        name = content.substring(i, end);
        i = end;
      } else {
        name = content.substring(i, close + 1).trim();
        i = close + 1;
      }
    } else {
      int end = skipNonWhitespace(content, i);
      name = content.substring(i, end);
      i = end;
    }

    if (name.isEmpty()) {
      return null;
    }

    String desc = trimDescriptionAfterOptionalDash(content, i);
    MarkdownDocument description =
        desc.isEmpty() ? null : MarkdownDocument.parse(expandInlineTags(desc));
    return new DocComment.Entry(name, type, description);
  }

  private static String parseReturns(String content) {
    int n = content.length();
    int i = skipHorizSpace(content, 0);
    if (i >= n) {
      return null;
    }
    String type = null;
    if (content.charAt(i) == '{' && !content.startsWith("{@", i)) {
      int close = findMatchingBrace(content, i);
      if (close > i) {
        String rawType = content.substring(i + 1, close).trim();
        if (!rawType.isEmpty()) {
          type = rawType;
        }
        i = close + 1;
      }
    }
    String desc =
        type != null ? trimDescriptionAfterOptionalDash(content, i) : content.substring(i);
    desc = trimBlankEdges(desc);
    if (type == null && desc.isEmpty()) {
      return null;
    }
    if (type == null) {
      return expandInlineTags(desc);
    }
    String typeCode = inlineCode(type);
    if (desc.isEmpty()) {
      return typeCode;
    }
    return typeCode + " — " + expandInlineTags(desc);
  }

  private static DocComment.Entry parseThrows(String content) {
    int n = content.length();
    int i = skipHorizSpace(content, 0);
    if (i >= n) {
      return null;
    }
    if (content.startsWith("{@", i)) {
      // TSDoc's @throws {@link SyntaxError} description: the link's target names the error.
      int close = findMatchingBrace(content, i);
      String[] words =
          close > i ? content.substring(i + 2, close).trim().split("[\\s|]+", 3) : new String[0];
      if (words.length > 1) {
        String desc = trimDescriptionAfterOptionalDash(content, close + 1);
        MarkdownDocument description =
            desc.isEmpty() ? null : MarkdownDocument.parse(expandInlineTags(desc));
        return new DocComment.Entry(words[1], null, description);
      }
    }
    if (content.charAt(i) == '{') {
      int close = findMatchingBrace(content, i);
      if (close > i) {
        String name = content.substring(i + 1, close).trim();
        String desc = trimDescriptionAfterOptionalDash(content, close + 1);
        if (name.isEmpty() && desc.isEmpty()) {
          return null;
        }
        MarkdownDocument description =
            desc.isEmpty() ? null : MarkdownDocument.parse(expandInlineTags(desc));
        return new DocComment.Entry(name, null, description);
      }
    }
    // TSDoc style without braces: `ErrorName - description`
    int wordEnd = skipNonWhitespace(content, i);
    int afterWord = skipHorizSpace(content, wordEnd);
    if (wordEnd > i
        && isIdentifierLike(content, i, wordEnd)
        && afterWord < n
        && content.charAt(afterWord) == '-'
        && (afterWord + 1 == n || Character.isWhitespace(content.charAt(afterWord + 1)))) {
      String name = content.substring(i, wordEnd);
      String desc = trimBlankEdges(content.substring(afterWord + 1));
      MarkdownDocument description =
          desc.isEmpty() ? null : MarkdownDocument.parse(expandInlineTags(desc));
      return new DocComment.Entry(name, null, description);
    }
    // Plain prose without braces.
    String desc = trimBlankEdges(content.substring(i));
    if (desc.isEmpty()) {
      return null;
    }
    return new DocComment.Entry("", null, MarkdownDocument.parse(expandInlineTags(desc)));
  }

  private static String formatExample(String content, String language) {
    String trimmed = trimBlankEdges(content);
    if (trimmed.isEmpty()) {
      return null;
    }
    String caption = null;
    if (trimmed.startsWith("<caption>")) {
      int end = trimmed.indexOf("</caption>");
      if (end >= 0) {
        String cap = trimmed.substring("<caption>".length(), end).trim();
        if (!cap.isEmpty()) {
          caption = expandInlineTags(cap);
        }
        trimmed = trimBlankEdges(trimmed.substring(end + "</caption>".length()));
      }
    }
    if (trimmed.isEmpty()) {
      return caption;
    }
    // An example with a fenced code block of its own (TSDoc) is Markdown, prose and all.
    if (trimmed.lines().anyMatch(line -> fenceMarker(line, (char) 0, 0) > 0)) {
      String markdown = expandInlineTags(trimmed);
      return caption == null ? markdown : caption + "\n\n" + markdown;
    }
    String fence = "`".repeat(Math.max(3, longestBacktickRun(trimmed) + 1));
    String block = fence + language + "\n" + trimmed + "\n" + fence;
    return caption == null ? block : caption + "\n\n" + block;
  }

  /**
   * Rewrites inline {@code {@link ...}}, {@code {@linkcode ...}} and {@code {@linkplain ...}} tags
   * into Markdown links, leaving fenced code blocks and inline code spans untouched.
   */
  private static String expandInlineTags(String markdown) {
    if (!markdown.contains("{@link")) {
      return markdown;
    }
    String[] lines = markdown.split("\n", -1);
    StringBuilder out = new StringBuilder(markdown.length());
    char fenceChar = 0;
    int fenceLength = 0;

    for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
      if (lineIndex > 0) {
        out.append('\n');
      }
      String line = lines[lineIndex];
      int fence = fenceMarker(line, fenceChar, fenceLength);
      if (fenceChar != 0) {
        if (fence < 0) {
          fenceChar = 0;
          fenceLength = 0;
        }
        out.append(line);
        continue;
      }
      if (fence > 0) {
        fenceChar = firstNonSpace(line);
        fenceLength = fence;
        out.append(line);
        continue;
      }
      out.append(expandInlineTagsInLine(line));
    }
    return out.toString();
  }

  private static String expandInlineTagsInLine(String line) {
    if (!line.contains("{@link")) {
      return line;
    }
    StringBuilder out = new StringBuilder(line.length());
    int n = line.length();
    int i = 0;
    while (i < n) {
      char c = line.charAt(i);
      if (c == '`') {
        int ticks = 1;
        while (i + ticks < n && line.charAt(i + ticks) == '`') {
          ticks++;
        }
        int close = findClosingBackticks(line, i + ticks, ticks);
        if (close >= 0) {
          out.append(line, i, close + ticks);
          i = close + ticks;
        } else {
          out.append(line, i, i + ticks);
          i += ticks;
        }
      } else if (c == '{' && line.startsWith("{@link", i)) {
        int after = i + "{@link".length();
        boolean code = false;
        boolean matched = false;
        if (line.startsWith("code", after)
            && after + 4 < n
            && isHorizSpace(line.charAt(after + 4))) {
          code = true;
          after += 4;
          matched = true;
        } else if (line.startsWith("plain", after)
            && after + 5 < n
            && isHorizSpace(line.charAt(after + 5))) {
          after += 5;
          matched = true;
        } else if (after < n && isHorizSpace(line.charAt(after))) {
          matched = true;
        }
        if (matched) {
          int close = line.indexOf('}', after);
          if (close > after) {
            // JSDoc's [label]{@link target}: the bracketed text right before the tag is the label.
            int open = labelStart(out);
            String label = open >= 0 ? out.substring(open + 1, out.length() - 1) : null;
            String replacement = formatLink(line.substring(after, close).trim(), code, label);
            if (replacement != null) {
              if (open >= 0) {
                out.setLength(open);
              }
              out.append(replacement);
              i = close + 1;
              continue;
            }
          }
        }
        out.append(c);
        i++;
      } else {
        out.append(c);
        i++;
      }
    }
    return out.toString();
  }

  private static String formatLink(String body, boolean code, String bracketLabel) {
    if (body.isEmpty()) {
      return null;
    }
    String target;
    String label;
    int pipe = body.indexOf('|');
    if (pipe >= 0) {
      target = body.substring(0, pipe).trim();
      label = body.substring(pipe + 1).trim();
    } else {
      int space = -1;
      for (int i = 0; i < body.length(); i++) {
        if (Character.isWhitespace(body.charAt(i))) {
          space = i;
          break;
        }
      }
      if (space >= 0) {
        target = body.substring(0, space).trim();
        label = body.substring(space + 1).trim();
      } else {
        target = body;
        label = body;
      }
    }
    if (target.isEmpty()) {
      return null;
    }
    if (bracketLabel != null && !bracketLabel.isBlank()) {
      // Already Markdown in the source: kept as written.
      String text = bracketLabel.trim();
      return "[" + (code ? inlineCode(text) : text) + "](<" + escapeLinkDestination(target) + ">)";
    }
    if (label.isEmpty()) {
      label = target;
    }
    String renderedLabel = code ? inlineCode(label) : escapeLinkLabel(label);
    return "[" + renderedLabel + "](<" + escapeLinkDestination(target) + ">)";
  }

  /** The index of the {@code [} whose balanced {@code [...]} ends {@code out}, or -1. */
  private static int labelStart(StringBuilder out) {
    int depth = 0;
    for (int k = out.length() - 1; k >= 0; k--) {
      char c = out.charAt(k);
      if (k == out.length() - 1 && c != ']') {
        return -1;
      } else if (c == ']') {
        depth++;
      } else if (c == '[' && --depth == 0) {
        return k;
      }
    }
    return -1;
  }

  private static String inlineCode(String text) {
    int ticks = longestBacktickRun(text) + 1;
    String fence = "`".repeat(ticks);
    boolean pad = text.startsWith("`")
        || text.endsWith("`")
        || (text.startsWith(" ") && text.endsWith(" ") && !text.isBlank());
    return pad ? fence + " " + text + " " + fence : fence + text + fence;
  }

  private static String escapeLinkLabel(String text) {
    StringBuilder out = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '[' || c == ']' || c == '\\') {
        out.append('\\');
      }
      out.append(c);
    }
    return out.toString();
  }

  private static String escapeLinkDestination(String text) {
    StringBuilder out = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '<' || c == '>' || c == '\\') {
        out.append('\\');
      }
      if (c != '\n' && c != '\r') {
        out.append(c);
      }
    }
    return out.toString();
  }

  private static String trimDescriptionAfterOptionalDash(String content, int from) {
    int n = content.length();
    int i = skipHorizSpace(content, from);
    if (i < n
        && content.charAt(i) == '-'
        && (i + 1 == n || Character.isWhitespace(content.charAt(i + 1)))) {
      i = skipHorizSpace(content, i + 1);
    }
    return dedent(trimBlankEdges(content.substring(i)));
  }

  /** Drops leading horizontal spaces shared by all non-blank lines. */
  private static String dedent(String text) {
    if (text.isEmpty()) {
      return text;
    }
    String[] lines = text.split("\n", -1);
    int minIndent = Integer.MAX_VALUE;
    for (String line : lines) {
      if (!line.isBlank()) {
        minIndent = Math.min(minIndent, skipHorizSpace(line, 0));
      }
    }
    if (minIndent <= 0 || minIndent == Integer.MAX_VALUE) {
      return text;
    }
    StringBuilder out = new StringBuilder(text.length());
    for (int i = 0; i < lines.length; i++) {
      if (i > 0) {
        out.append('\n');
      }
      if (!lines[i].isBlank()) {
        out.append(lines[i], minIndent, lines[i].length());
      }
    }
    return out.toString();
  }

  private static int findMatchingBrace(String s, int open) {
    int depth = 0;
    for (int i = open; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\\' && i + 1 < s.length()) {
        i++;
      } else if (c == '{') {
        depth++;
      } else if (c == '}') {
        if (--depth == 0) {
          return i;
        }
      }
    }
    return -1;
  }

  private static int findMatchingBracket(String s, int open) {
    int depth = 0;
    for (int i = open; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\\' && i + 1 < s.length()) {
        i++;
      } else if (c == '[') {
        depth++;
      } else if (c == ']') {
        if (--depth == 0) {
          return i;
        }
      }
    }
    return -1;
  }

  /**
   * Returns the tag name (without {@code @}) when {@code line} starts a block tag, or {@code null}
   * otherwise.
   */
  private static String blockTagName(String line) {
    int n = line.length();
    int i = skipHorizSpace(line, 0);
    if (i >= n || line.charAt(i) != '@') {
      return null;
    }
    int start = i + 1;
    if (start >= n || !isAsciiLetter(line.charAt(start))) {
      return null;
    }
    int end = start + 1;
    while (end < n && isTagNameChar(line.charAt(end))) {
      end++;
    }
    if (end < n && !isHorizSpace(line.charAt(end))) {
      return null;
    }
    return line.substring(start, end);
  }

  /**
   * Detects Markdown code fences: returns positive fence length when opening a fence (while {@code
   * openChar == 0}), {@code -1} when closing an open fence, and {@code 0} otherwise.
   */
  private static int fenceMarker(String line, char openChar, int openLength) {
    int n = line.length();
    int indent = 0;
    while (indent < n && indent < 3 && line.charAt(indent) == ' ') {
      indent++;
    }
    if (indent >= n) {
      return 0;
    }
    char c = line.charAt(indent);
    if (c != '`' && c != '~') {
      return 0;
    }
    int count = 0;
    while (indent + count < n && line.charAt(indent + count) == c) {
      count++;
    }
    if (count < 3) {
      return 0;
    }
    if (openChar == 0) {
      if (c == '`' && line.indexOf('`', indent + count) >= 0) {
        return 0; // CommonMark: backtick info strings cannot contain backticks
      }
      return count;
    }
    if (c == openChar && count >= openLength) {
      int rest = skipHorizSpace(line, indent + count);
      if (rest == n) {
        return -1;
      }
    }
    return 0;
  }

  private static char firstNonSpace(String line) {
    int i = 0;
    while (i < line.length() && line.charAt(i) == ' ') {
      i++;
    }
    return i < line.length() ? line.charAt(i) : 0;
  }

  private static int findClosingBackticks(String line, int from, int count) {
    int n = line.length();
    int i = from;
    while (i < n) {
      if (line.charAt(i) == '`') {
        int run = 1;
        while (i + run < n && line.charAt(i + run) == '`') {
          run++;
        }
        if (run == count) {
          return i;
        }
        i += run;
      } else {
        i++;
      }
    }
    return -1;
  }

  private static int longestBacktickRun(String text) {
    int best = 0;
    int run = 0;
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == '`') {
        if (++run > best) {
          best = run;
        }
      } else {
        run = 0;
      }
    }
    return best;
  }

  private static String trimBlankEdges(String text) {
    String[] lines = text.split("\n", -1);
    int first = 0;
    while (first < lines.length && lines[first].isBlank()) {
      first++;
    }
    int last = lines.length - 1;
    while (last >= first && lines[last].isBlank()) {
      last--;
    }
    if (first > last) {
      return "";
    }
    StringBuilder out = new StringBuilder();
    for (int i = first; i <= last; i++) {
      if (i > first) {
        out.append('\n');
      }
      out.append(lines[i]); // two trailing spaces are a hard line break in Markdown
    }
    return out.toString();
  }

  private static boolean isIdentifierLike(String s, int from, int to) {
    char first = s.charAt(from);
    if (!Character.isLetter(first) && first != '_' && first != '$') {
      return false;
    }
    for (int i = from + 1; i < to; i++) {
      char c = s.charAt(i);
      if (!Character.isLetterOrDigit(c) && c != '_' && c != '$' && c != '.') {
        return false;
      }
    }
    return true;
  }

  private static int skipHorizSpace(String s, int from) {
    int i = from;
    while (i < s.length() && isHorizSpace(s.charAt(i))) {
      i++;
    }
    return i;
  }

  private static int skipWhitespace(String s, int from) {
    int i = from;
    while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
      i++;
    }
    return i;
  }

  private static int skipNonWhitespace(String s, int from) {
    int i = from;
    while (i < s.length() && !Character.isWhitespace(s.charAt(i))) {
      i++;
    }
    return i;
  }

  private static String stripTrailingHorizSpace(String s) {
    int end = s.length();
    while (end > 0 && isHorizSpace(s.charAt(end - 1))) {
      end--;
    }
    return end == s.length() ? s : s.substring(0, end);
  }

  private static boolean isHorizSpace(char c) {
    return c == ' ' || c == '\t';
  }

  private static boolean isAsciiLetter(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
  }

  private static boolean isTagNameChar(char c) {
    return isAsciiLetter(c) || (c >= '0' && c <= '9') || c == '_' || c == '-';
  }
}
