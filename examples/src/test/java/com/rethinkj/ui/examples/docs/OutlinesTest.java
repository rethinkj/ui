package com.rethinkj.ui.examples.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rethinkj.ui.kit.Markdown;
import com.rethinkj.ui.kit.MarkdownDocument;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The Markdown page's walk over a document's blocks, without native libraries. */
class OutlinesTest {

  private static final MarkdownDocument DOCUMENT = Markdown.parse("""
      # Orders

      ```sql
      SELECT 1;
      ```

      ## The `paid` ones

      - Count them:

        ```sql
        SELECT count(*) FROM orders;
        ```

      > ```text
      > not SQL
      > ```
      """);

  @Test
  void theSqlOfEveryCodeBlockInOrder() {
    assertEquals(
        List.of("SELECT 1;\n", "SELECT count(*) FROM orders;\n"), Outlines.sql(DOCUMENT.blocks()));
  }

  @Test
  void theOutlineIndentsByLevel() {
    assertEquals(List.of("Orders", "  The paid ones"), Outlines.outline(DOCUMENT));
  }
}
