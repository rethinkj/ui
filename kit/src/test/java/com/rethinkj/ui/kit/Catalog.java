package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.kit.Icon.Variant;
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
 * until opening it asks for them, on a thread of the app's work ({@link App#background}), as a
 * connection to the server would; a script's {@code await} lets the answer in. The schema
 * localhost/shop/public has a few hundred tables, or as many as the test asks for, and
 * localhost/postgres has the system's schema pg_catalog, in muted text. The view puts the tree in a panel with a margin, which leaves room for the focus
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
    "id",
    "created_at",
    "updated_at",
    "customer_id",
    "status",
    "amount",
    "currency",
    "email",
    "note",
    "deleted_at",
    "version"
  };

  // The type of each of COLUMNS, shown after its name.
  private static final String[] TYPES = {
    "bigint",
    "timestamptz",
    "timestamptz",
    "bigint",
    "text",
    "numeric(12,2)",
    "char(3)",
    "varchar(320)",
    "text",
    "timestamptz",
    "integer"
  };

  final Theme theme;
  final List<Node> roots = new ArrayList<>();
  final Set<Node> open = new HashSet<>();
  Node selected;
  final List<String> log = new ArrayList<>();
  final List<Node> loading = new ArrayList<>(); // opened, children asked for, not there yet
  int walks; // calls of roots(), one per rebuild of the view
  final List<Node> labelled = new ArrayList<>(); // the nodes whose label was asked for
  final List<Node> detailed = new ArrayList<>(); // the nodes whose detail was asked for
  final List<Node> mutedAsked = new ArrayList<>(); // the nodes asked whether they are muted
  App<Catalog> app; // which runs the work, made by app()

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
    Node postgres = new Node(Kind.DATABASE, "postgres", localhost);
    tables(new Node(Kind.SCHEMA, "pg_catalog", postgres), List.of("pg_class", "pg_index"));
    new Node(Kind.SCHEMA, "public", postgres);
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
        new Node(
            Kind.COLUMN, c < COLUMNS.length ? column : column + "_" + c / COLUMNS.length, table);
      }
    }
    Node prod = new Node(Kind.SERVER, "prod-eu", null);
    for (String database : List.of("billing", "shop")) {
      tables(
          new Node(Kind.SCHEMA, "public", new Node(Kind.DATABASE, database, prod)),
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
      found = level.stream()
          .filter(n -> n.name.equals(name))
          .findFirst()
          .orElseThrow(() -> new IllegalArgumentException("no " + name + " in " + path));
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

  /**
   * What a server answers when it is asked for its databases, on the work's thread: names only,
   * since the nodes are the app's state.
   */
  static List<String> databases(String server) {
    return List.of("scratch", "shop");
  }

  /** The databases of a server arrive, on the app's thread. */
  void arrive(Node server, List<String> names) {
    loading.remove(server);
    server.children = new ArrayList<>();
    for (String database : names) {
      new Node(Kind.DATABASE, database, server);
    }
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

  /** A column's type, and a schema's number of tables; none for the others. */
  @Override
  public String detail(Node node) {
    detailed.add(node);
    return switch (node.kind) {
      case COLUMN -> TYPES[List.of(COLUMNS).indexOf(node.name.replaceAll("_\\d+$", ""))];
      case SCHEMA -> node.children.size() == 1 ? "1 table" : node.children.size() + " tables";
      default -> null;
    };
  }

  /** The system's schemas, as pg_catalog. */
  @Override
  public boolean muted(Node node) {
    mutedAsked.add(node);
    return node.name.startsWith("pg_");
  }

  @Override
  public El icon(Node node) {
    return switch (node.kind) {
      // The colour says whether the server is connected.
      case SERVER ->
        Icon.icon(theme, Symbol.SERVER, node.children == null ? Variant.DISABLED : Variant.SUCCESS);
      case DATABASE -> Icon.icon(theme, Symbol.DATABASE, Variant.MUTED);
      case SCHEMA -> Icon.icon(theme, Symbol.FOLDER, Variant.MUTED);
      case TABLE -> Icon.icon(theme, Symbol.TABLE, Variant.MUTED);
      case COLUMN -> Icon.icon(theme, Symbol.COLUMN_SINGLE, Variant.MUTED);
    };
  }

  // The view

  El view() {
    return col()
        .wFull()
        .hFull()
        .padding(theme.space().m())
        .fill(theme.surface().panel())
        .child(tree().key("schema").label("Schema").grow(1));
  }

  El tree() {
    return Tree.tree(
        theme,
        this,
        open::contains,
        selected,
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
            loading.add(node);
            String name = node.name; // the work gets the name, not the node
            app.background(
                "databases of " + name,
                () -> databases(name),
                names -> arrive(node, names),
                failure -> log.add("failed " + node + ": " + failure.getMessage()));
          }
        },
        node -> log.add("activate " + node));
  }

  App<Catalog> app() {
    app = new App<>(this, Catalog::view);
    app.textRendering(TextRendering.LINUX);
    app.focusRing(theme.accent().edge());
    return app;
  }
}
