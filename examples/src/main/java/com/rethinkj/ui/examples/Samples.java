package com.rethinkj.ui.examples;

import com.rethinkj.ui.kit.DataGrid.Align;
import com.rethinkj.ui.kit.DataGrid.Column;
import com.rethinkj.ui.kit.DataGrid.SortKey;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** The made-up data the showcase shows: a result set of orders and the schema of three servers. */
final class Samples {

  private Samples() {}

  // A result set

  private static final String[] FIRST = {
    "Ada", "Grace", "Linus", "Margaret", "Ken", "Barbara", "Edsger", "Frances", "Donald", "Radia",
    "Niklaus", "Leslie", "Tony"};
  private static final String[] LAST = {
    "Lovelace", "Hopper", "Torvalds", "Hamilton", "Thompson", "Liskov", "Dijkstra", "Allen",
    "Knuth", "Perlman", "Wirth", "Lamport", "Hoare"};
  private static final String[] STATUS = {"paid", "pending", "shipped", "refunded", "shipped"};
  private static final String[] NOTE = {
    null, "Leave the parcel with the neighbour at number 12 if nobody answers the door", null,
    "Gift wrap", null, null, "Invoice to the company address, not the delivery address", null,
    "Call before delivery"};
  private static final long[] CENTS = {
    4990, 22900, 129900, 700, 8900, 1248000, 6900, 99000, 1999, 34550};
  private static final LocalDateTime START = LocalDateTime.of(2026, 3, 2, 8, 14, 5);
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  /** An order joined with its customer. */
  record Order(int id, String customer, String email, long cents, LocalDateTime created,
      LocalDateTime shipped, String status, String note) {}

  static final List<Order> ORDERS = orders(48);

  private static List<Order> orders(int count) {
    List<Order> orders = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      String customer = FIRST[i % FIRST.length] + " " + LAST[i * 7 % LAST.length];
      String status = STATUS[i * 3 % STATUS.length];
      LocalDateTime created = START.plusSeconds(i * 52_117L);
      orders.add(new Order(10_480 + i * 3, customer,
          customer.toLowerCase(Locale.ROOT).replace(' ', '.') + "@" + (i % 3 == 0
              ? "engineering.example.com" : "example.org"),
          CENTS[i * 7 % CENTS.length] * (1 + i % 3), created,
          status.equals("shipped") ? created.plusHours(20 + i % 30) : null, status,
          NOTE[i % NOTE.length]));
    }
    return orders;
  }

  /** The columns of the orders, with each cell's text from an index into {@code rows}. */
  static List<Column> orderColumns(List<Order> rows) {
    return List.of(
        new Column("id", 72, Align.RIGHT, true, i -> String.valueOf(rows.get(i).id())),
        new Column("customer", 150, Align.LEFT, false, i -> rows.get(i).customer()),
        new Column("email", 190, Align.LEFT, false, i -> rows.get(i).email()),
        new Column("amount", 100, Align.RIGHT, true, i -> money(rows.get(i).cents())),
        new Column("created_at", 160, Align.LEFT, true, i -> rows.get(i).created().format(TIME)),
        new Column("shipped_at", 160, Align.LEFT, true, i -> rows.get(i).shipped() == null ? null
            : rows.get(i).shipped().format(TIME)),
        new Column("status", 90, Align.LEFT, false, i -> rows.get(i).status()),
        new Column("note", 240, Align.LEFT, false, i -> rows.get(i).note()));
  }

  static String money(long cents) {
    return String.format(Locale.ROOT, "%d.%02d", cents / 100, cents % 100);
  }

  /** The orders sorted by these keys, NULLs last as PostgreSQL sorts them ascending. */
  static List<Order> sorted(List<SortKey> keys) {
    Comparator<Order> order = (a, b) -> 0;
    for (SortKey key : keys) {
      Comparator<Order> by = switch (key.column()) {
        case 0 -> Comparator.comparingInt(Order::id);
        case 1 -> Comparator.comparing(Order::customer);
        case 2 -> Comparator.comparing(Order::email);
        case 3 -> Comparator.comparingLong(Order::cents);
        case 4 -> Comparator.comparing(Order::created);
        case 5 -> Comparator.comparing(Order::shipped,
            Comparator.nullsLast(Comparator.naturalOrder()));
        case 6 -> Comparator.comparing(Order::status);
        default -> Comparator.comparing(Order::note,
            Comparator.nullsLast(Comparator.naturalOrder()));
      };
      order = order.thenComparing(key.descending() ? by.reversed() : by);
    }
    return ORDERS.stream().sorted(order).toList();
  }

  // A schema

  /** A server, database, schema, table or column, by its level from 0. */
  static final class Node {
    final String name;
    final int level;
    final String type; // a column's type, or null
    List<Node> children; // null while not known

    Node(String name, int level, String type, List<Node> children) {
      this.name = name;
      this.level = level;
      this.type = type;
      this.children = children;
    }

    Node find(String name) {
      return children.stream().filter(n -> n.name.equals(name)).findFirst().orElseThrow();
    }
  }

  private static Node node(String name, int level, Node... children) {
    return new Node(name, level, null, List.of(children));
  }

  private static Node table(String name, String... columns) {
    List<Node> nodes = new ArrayList<>();
    for (int i = 0; i < columns.length; i += 2) {
      nodes.add(new Node(columns[i], 4, columns[i + 1], List.of()));
    }
    return new Node(name, 3, null, nodes);
  }

  /** Three servers: localhost with its databases, staging still loading, prod-eu refusing. */
  static List<Node> catalog() {
    Node shop = node("shop", 1,
        node("public", 2,
            table("customers", "id", "bigint", "name", "text", "email", "text", "created_at",
                "timestamptz"),
            table("orders", "id", "bigint", "customer_id", "bigint", "amount", "numeric(10,2)",
                "status", "order_status", "created_at", "timestamptz", "shipped_at",
                "timestamptz", "note", "text"),
            table("order_items", "order_id", "bigint", "product_id", "bigint", "quantity",
                "integer"),
            table("products", "id", "bigint", "name", "text", "price", "numeric(10,2)"),
            table("invoices", "id", "bigint", "order_id", "bigint", "issued_at", "date"),
            table("payments", "id", "bigint", "invoice_id", "bigint", "paid_at", "timestamptz")),
        node("audit", 2, table("events", "id", "bigint"), table("logins", "id", "bigint")));
    Node analytics = node("analytics", 1,
        node("public", 2, table("sessions", "id", "uuid"), table("page_views", "id", "bigint")));
    return List.of(
        node("localhost", 0, shop, analytics, node("postgres", 1, node("public", 2))),
        new Node("staging", 0, null, null),
        new Node("prod-eu", 0, null, null));
  }

  /** The nodes from a root down a path of names, such as localhost, shop, public, orders. */
  static List<Node> path(List<Node> roots, String... names) {
    List<Node> path = new ArrayList<>();
    Node at = roots.stream().filter(n -> n.name.equals(names[0])).findFirst().orElseThrow();
    path.add(at);
    for (String name : Arrays.asList(names).subList(1, names.length)) {
      at = at.find(name);
      path.add(at);
    }
    return path;
  }
}
