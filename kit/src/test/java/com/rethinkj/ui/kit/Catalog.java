package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.text.TextRendering;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The catalog of three database servers as a database tool shows it in a tree, and the tree's
 * state, which the tree's handlers change and log. localhost and prod-eu are connected: their
 * databases, schemas, tables and columns are known. staging is not: its databases are unknown
 * until {@link #arrive}, which stands for the answer of a connection the app opened when the
 * server was opened. The schema localhost/shop/public has a few hundred tables, or as many as the
 * test asks for. The view puts the tree in a panel with a margin, which leaves room for the focus
 * ring.
 */
final class Catalog implements Tree.Model<Catalog.Node> {

  enum Kind {
    SERVER,
    DATABASE,
    SCHEMA,
    TABLE,
    COLUMN
  }

  /** A node of the catalog, made once and told apart by identity. */
  static final class Node {
    final Kind kind;
    final String name;
    final Node parent;
    List<Node> children; // null while not known

    Node(Kind kind, String name, Node parent) {
      this.kind = kind;
      this.name = name;
      this.parent = parent;
      this.children = kind == Kind.COLUMN ? List.of() : new ArrayList<>();
      if (parent != null) {
        parent.children.add(this);
      }
    }

    String path() {
      return parent == null ? name : parent.path() + "/" + name;
    }

    @Override
    public String toString() {
      return path();
    }
  }

  private static final String[] TABLES = {
    "accounts", "addresses", "carts", "cart_items", "categories", "coupons", "customers",
    "deliveries", "discounts", "employees", "invoices", "invoice_lines", "inventory", "orders",
    "order_items", "payments", "prices", "products", "product_images", "refunds", "reviews",
    "sessions", "shipments", "stock_moves", "suppliers", "taxes", "users", "vouchers",
    "warehouses", "wishlists"
  };
  private static final String[] COLUMNS = {
    "id", "created_at", "updated_at", "customer_id", "status", "amount", "currency", "email",
    "note", "deleted_at", "version"
  };

  final Theme theme;
  final List<Node> roots = new ArrayList<>();
  final Set<Node> open = new HashSet<>();
  Node selected;
  final List<String> log = new ArrayList<>();
  final List<Node> loading = new ArrayList<>(); // opened, children asked for, not there yet
  int walks; // calls of roots(), one per rebuild of the view
  final List<Node> labelled = new ArrayList<>(); // the nodes whose label was asked for

  /** The catalog with 300 tables of 3 to 10 columns in localhost/shop/public. */
  Catalog(Theme theme) {
    this(theme, 300, 0);
  }

  /**
   * @param tables the number of tables in localhost/shop/public
   * @param columns the number of columns of each of them, or 0 for 3 to 10
   */
  Catalog(Theme theme, int tables, int columns) {
    this.theme = theme;
    Node localhost = new Node(Kind.SERVER, "localhost", null);
    Node analytics = new Node(Kind.DATABASE, "analytics", localhost);
    tables(new Node(Kind.SCHEMA, "public", analytics), List.of("events", "page_views", "sessions"));
    new Node(Kind.SCHEMA, "public", new Node(Kind.DATABASE, "postgres", localhost));
    Node shop = new Node(Kind.DATABASE, "shop", localhost);
    tables(new Node(Kind.SCHEMA, "audit", shop), List.of("changes", "logins"));
    Node shopPublic = new Node(Kind.SCHEMA, "public", shop);
    List<String> names = new ArrayList<>();
    for (int i = 0; i < tables; i++) {
      String name = TABLES[i % TABLES.length];
      names.add(i < TABLES.length ? name : name + "_" + String.format("%03d", i / TABLES.length));
    }
    names.sort(null);
    for (int i = 0; i < names.size(); i++) {
      Node table = new Node(Kind.TABLE, names.get(i), shopPublic);
      int count = columns > 0 ? columns : 3 + i % 8;
      for (int c = 0; c < count; c++) {
        String column = COLUMNS[c % COLUMNS.length];
        new Node(Kind.COLUMN, c < COLUMNS.length ? column : column + "_" + c / COLUMNS.length,
            table);
      }
    }
    Node prod = new Node(Kind.SERVER, "prod-eu", null);
    for (String database : List.of("billing", "shop")) {
      tables(new Node(Kind.SCHEMA, "public", new Node(Kind.DATABASE, database, prod)),
          List.of("customers", "invoices", "orders"));
    }
    Node staging = new Node(Kind.SERVER, "staging", null);
    staging.children = null;
    roots.addAll(List.of(localhost, prod, staging));
  }

  private static void tables(Node schema, List<String> names) {
    for (String name : names) {
      Node table = new Node(Kind.TABLE, name, schema);
      for (String column : Arrays.copyOf(COLUMNS, 6)) {
        new Node(Kind.COLUMN, column, table);
      }
    }
  }

  /** The node at a path of names from a server down, as in {@code "localhost/shop/public"}. */
  Node node(String path) {
    List<Node> level = roots;
    Node found = null;
    for (String name : path.split("/")) {
      found = level.stream().filter(n -> n.name.equals(name)).findFirst().orElseThrow(
          () -> new IllegalArgumentException("no " + name + " in " + path));
      level = found.children;
    }
    return found;
  }

  /** Opens the nodes at these paths, as the user would one by one. */
  Catalog opened(String... paths) {
    for (String path : paths) {
      open.add(node(path));
    }
    return this;
  }

  /** The databases of staging arrive from the server. */
  void arrive() {
    for (Node node : loading) {
      node.children = new ArrayList<>();
      for (String database : List.of("scratch", "shop")) {
        new Node(Kind.DATABASE, database, node);
      }
    }
    loading.clear();
  }

  // Tree.Model

  @Override
  public List<Node> roots() {
    walks++;
    return roots;
  }

  @Override
  public List<Node> children(Node node) {
    return node.children;
  }

  @Override
  public boolean isLeaf(Node node) {
    return node.kind == Kind.COLUMN;
  }

  @Override
  public String label(Node node) {
    labelled.add(node);
    return node.name;
  }

  @Override
  public El icon(Node node) {
    if (node.kind != Kind.SERVER) {
      return null;
    }
    // A dot that says whether the server is connected.
    float side = theme.space().m();
    return el().size(side, side).shrink(0).radius(side / 2)
        .fill(node.children == null ? theme.text().disabled() : theme.status().success());
  }

  // The view

  El view() {
    return col().wFull().hFull().padding(theme.space().m()).fill(theme.surface().panel())
        .child(tree().key("schema").label("Schema").grow(1));
  }

  El tree() {
    return Tree.tree(theme, this, open::contains, selected,
        node -> {
          log.add("select " + node);
          selected = node;
        },
        (node, opens) -> {
          log.add((opens ? "open " : "close ") + node);
          if (!opens) {
            open.remove(node);
            return;
          }
          open.add(node);
          if (node.children == null && !loading.contains(node)) {
            loading.add(node); // a real app connects here, on a thread of its own
          }
        },
        node -> log.add("activate " + node));
  }

  App<Catalog> app() {
    App<Catalog> app = new App<>(this, Catalog::view);
    app.textRendering(TextRendering.LINUX);
    app.focusRing(theme.border().focus());
    return app;
  }
}
