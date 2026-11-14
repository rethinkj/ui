package com.rethinkj.ui.examples.docs;

import com.rethinkj.ui.kit.MarkdownDocument;
import com.rethinkj.ui.kit.MarkdownDocument.Block;
import com.rethinkj.ui.kit.MarkdownDocument.CodeBlock;
import com.rethinkj.ui.kit.MarkdownDocument.ListBlock;
import com.rethinkj.ui.kit.MarkdownDocument.Quote;
import java.util.ArrayList;
import java.util.List;

/** The example of reading a Markdown document's blocks, for the Markdown page. */
final class Outlines {

  private Outlines() {}

  // docs:start markdown-model
  /** The SQL of a document's code blocks, those in quotes and lists too: to run them. */
  static List<String> sql(List<Block> blocks) {
    List<String> out = new ArrayList<>();
    for (Block block : blocks) {
      switch (block) {
        case CodeBlock code when code.language().equals("sql") -> out.add(code.code());
        case Quote quote -> out.addAll(sql(quote.blocks()));
        case ListBlock list -> list.items().forEach(item -> out.addAll(sql(item.blocks())));
        default -> {}
      }
    }
    return out;
  }

  /** The headings as an indented outline, in their plain text. */
  static List<String> outline(MarkdownDocument document) {
    return document.headings().stream()
        .map(h -> "  ".repeat(h.level() - 1) + MarkdownDocument.plainText(h.content()))
        .toList();
  }
  // docs:end markdown-model
}
