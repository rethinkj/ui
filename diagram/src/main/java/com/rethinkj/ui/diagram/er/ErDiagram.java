package com.rethinkj.ui.diagram.er;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.El.text;

import com.rethinkj.ui.Colors;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Shadow;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.WindowState;
import com.rethinkj.ui.diagram.Canvas;
import com.rethinkj.ui.diagram.Canvas.Edge;
import com.rethinkj.ui.diagram.Canvas.Emphasis;
import com.rethinkj.ui.diagram.Canvas.Marker;
import com.rethinkj.ui.diagram.Canvas.Node;
import com.rethinkj.ui.diagram.Canvas.Port;
import com.rethinkj.ui.diagram.DiagramTexts;
import com.rethinkj.ui.diagram.LayeredLayout;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Segmented;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.TextField;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Tooltip;
import com.rethinkj.ui.text.Font;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;

/**
 * An entity relationship diagram of a database's schemas on a {@link Canvas}, below a bar with a
 * filter, the mode of all tables and the actions that fit and arrange them. The app fills plain
 * records from its catalog and keeps a {@link State}:
 *
 * <pre>{@code
 * erDiagram(state.diagram, state.schemas, state::savePins).grow(1);
 * }</pre>
 *
 * <p>Each table is a card: a header with its name and a chevron that switches its mode, and a row for
 * each column with the key icon in the warning color for the primary key, the link icon for a
 * foreign key, {@code UQ} for a unique column, and the type, muted, with {@code ?} after it where
 * the column may be null. A row's tooltip shows its default and its foreign keys with their ON
 * DELETE. A table shows all columns, only its keys with a row {@code +n columns} that shows the
 * rest, or only its header ({@link Mode}). Zoomed out below {@link ErStyle#skeleton}, the rows are
 * bars, and below {@link ErStyle#names} a table shows only its name. Each named schema has a frame
 * around its tables, and the filter hides the tables whose names do not contain it.
 *
 * <p>Each foreign key is an edge from the row of its first column to the row of the first column
 * it refers to, or to the header of a table that does not show that row. Its crow's foot at the
 * table that refers is {@code ||} where the key is unique, as it holds the whole primary key or a
 * unique column, and {@code |<} otherwise; at the table referred to, it is {@code ||} where the key is not
 * null and {@code o|} where it may be.
 *
 * <p>A press on a table selects it, a press on empty space clears the selection: the selected table
 * is outlined in the accent, its edges and the rows at their ends stand out, and the tables it is
 * not related to are dimmed by mixing their colors towards the canvas's. Dragging a table moves and
 * pins it. While the canvas has the focus, the arrow keys select the nearest table in their
 * direction, Shift and an arrow move the selected table by {@link ErStyle#nudge} and pin it, Enter
 * switches its mode as the chevron does, Escape clears the selection, and {@code ]} and {@code [} step forward
 * and back through the tables it is related to; {@code +}, {@code -}, {@code 0}, the wheel and
 * Control+wheel pan and zoom as on any canvas.
 *
 * <p>Tables that are not pinned are placed by {@link LayeredLayout}, each schema on its own: at
 * first, when a table comes, goes or changes its height, as with its mode or its columns, and with
 * the action Arrange; the filter does not move them. Arrange all unpins every table first. Pins are plain positions: the app gives
 * them with {@link State#pins(Map)} and is told each change.
 *
 * <p>In a tree dump the canvas is {@code role=graphics-document} labeled {@link
 * DiagramTexts#erDiagram}, each table {@code role=graphics-object} with its key, {@code
 * schema.table}, and a label such as {@code order, 6 columns, references customer, address}, each
 * column {@code role=listitem} with a label such as {@code id, bigint, primary key}, each schema's
 * frame {@code role=group} with the key {@code schema/}, and each foreign key {@code
 * role=graphics-symbol} with its name.
 *
 * <p>Limits: tables are {@link ErStyle#width} wide, and a long name or type is shortened with an
 * ellipsis. A pinned table stays where it is, also where a table beside it grows over it.
 */
public final class ErDiagram {

  private ErDiagram() {}

  /** How much of a table shows. */
  public enum Mode {
    /** Every column. */
    ALL,
    /** The columns of its primary and foreign keys, and a row that counts the others. */
    KEYS,
    /** Only its header, with the number of columns. */
    COLLAPSED
  }

  /** Where the user put a table, its top left in diagram coordinates. */
  public record Pin(double x, double y) {

    public Pin {
      if (!Double.isFinite(x) || !Double.isFinite(y)) {
        throw new IllegalArgumentException("a pin must be finite, was " + x + ", " + y);
      }
    }
  }

  /**
   * What a diagram shows apart from its schemas: the viewport, the modes, the filter, the selection
   * and where each table lies. The app keeps one per diagram and may change it, as the diagram does
   * on input. Tables are named by their keys, {@code schema.table}, or the table's name alone in a
   * schema without a name. A part that contains a {@code .} or starts with {@code "} is written in
   * double quotes, with any {@code "} inside doubled, as in {@code a."b.c"}.
   */
  public static final class State {

    private final Canvas.Viewport viewport = new Canvas.Viewport();
    private Mode mode = Mode.ALL;
    private final Map<String, Mode> modes = new HashMap<>();
    private String filter = "";
    private String selected;
    // Where each table lies, pinned or placed by the layout.
    private final Map<String, Place> places = new HashMap<>();
    private boolean arrangeDue = true;
    // The height of each table and their width when they were last arranged, to arrange them
    // again when one changes.
    private Map<String, Float> arranged = Map.of();
    private float width;
    // Whether the next build pans to the first table shown, after the filter changed.
    private boolean revealFirst;
    // The table that ] and [ step around, and the index of the one they stepped to.
    private String followed;
    private int follow;

    /** A state that fits the whole diagram in. */
    public State() {
      viewport.fit();
    }

    /** The part of the diagram the canvas shows. */
    public Canvas.Viewport viewport() {
      return viewport;
    }

    /** The mode of all tables, unless one has its own. */
    public Mode mode() {
      return mode;
    }

    /** Shows all tables in this mode, also those that had their own. */
    public void mode(Mode mode) {
      this.mode = Objects.requireNonNull(mode, "mode");
      modes.clear();
    }

    /** The mode of the table with this key. */
    public Mode mode(String table) {
      return modes.getOrDefault(table, mode);
    }

    /** Shows the table with this key in this mode. */
    public void mode(String table, Mode mode) {
      Objects.requireNonNull(mode, "mode");
      if (mode == this.mode) {
        modes.remove(table);
      } else {
        modes.put(table, mode);
      }
    }

    /** The text that the names of the tables shown contain, ignoring case; empty for all. */
    public String filter() {
      return filter;
    }

    /** Shows only the tables whose names contain this text, and pans to the first of them. */
    public void filter(String filter) {
      if (!filter.equals(this.filter)) {
        this.filter = filter;
        revealFirst = true;
      }
    }

    /** The key of the selected table, null for none. */
    public String selected() {
      return selected;
    }

    /** Selects the table with this key, or none for null. */
    public void select(String table) {
      selected = table;
      followed = null;
    }

    /** The tables the user put somewhere, by their keys. */
    public Map<String, Pin> pins() {
      Map<String, Pin> pins = new LinkedHashMap<>();
      places.forEach((table, place) -> {
        if (place.pinned) {
          pins.put(table, new Pin(place.x, place.y));
        }
      });
      return pins;
    }

    /**
     * Pins the tables to these places, as the app kept them, and unpins the others, which are
     * arranged again.
     */
    public void pins(Map<String, Pin> pins) {
      places.values().removeIf(place -> place.pinned);
      pins.forEach((table, pin) -> places.put(table, new Place(pin.x(), pin.y(), true)));
      arrangeDue = true;
    }

    /** Places the tables that are not pinned anew, in the next build. */
    public void arrange() {
      arrangeDue = true;
    }

    /** Unpins all tables and places them anew, in the next build. */
    public void arrangeAll() {
      places.clear();
      arrangeDue = true;
    }
  }

  private record Place(double x, double y, boolean pinned) {}

  /** A table as the diagram shows it. */
  private static final class Shown {
    final String key;
    final String schema;
    final Table table;
    final Mode mode;
    final List<Column> rows;
    final int hidden;
    final float height;
    final List<Relation> out = new ArrayList<>();
    final List<Relation> in = new ArrayList<>();

    Shown(String key, String schema, Table table, Mode mode, ErStyle style) {
      this.key = key;
      this.schema = schema;
      this.table = table;
      this.mode = mode;
      rows = switch (mode) {
        case ALL -> table.columns();
        case KEYS -> table.columns().stream().filter(c -> key(table, c)).toList();
        case COLLAPSED -> List.of();
      };
      hidden = mode == Mode.KEYS ? table.columns().size() - rows.size() : 0;
      height = style.header() + (rows.size() + (hidden > 0 ? 1 : 0)) * style.row();
    }

    /** The middle of the column's row, or of the header where it shows none. */
    float y(String column, ErStyle style) {
      for (int i = 0; i < rows.size(); i++) {
        if (rows.get(i).name().equals(column)) {
          return style.header() + i * style.row() + style.row() / 2;
        }
      }
      return style.header() / 2;
    }

    /** The other tables of its relations: those it refers to, then those that refer to it. */
    List<Shown> related() {
      Set<Shown> related = new LinkedHashSet<>();
      out.forEach(relation -> related.add(relation.parent));
      in.forEach(relation -> related.add(relation.child));
      related.remove(this);
      return List.copyOf(related);
    }
  }

  /** A foreign key between two tables of the diagram. */
  private record Relation(String key, Shown child, ForeignKey foreignKey, Shown parent) {}

  private static boolean key(Table table, Column column) {
    return column.primaryKey()
        || table.foreignKeys().stream().anyMatch(f -> f.columns().contains(column.name()));
  }

  private static String key(String schema, String table) {
    return schema.isEmpty() ? quote(table) : quote(schema) + "." + quote(table);
  }

  /** Quotes a schema or table name as SQL does where a dot or leading quote would collide. */
  private static String quote(String name) {
    if (name.indexOf('.') >= 0 || name.startsWith("\"")) {
      return "\"" + name.replace("\"", "\"\"") + "\"";
    }
    return name;
  }

  /** All tables of the schemas, by their keys, with their relations among them. */
  private static Map<String, Shown> model(List<Schema> schemas, State state, ErStyle style) {
    Map<String, Shown> tables = new LinkedHashMap<>();
    for (Schema schema : schemas) {
      for (Table table : schema.tables()) {
        String key = key(schema.name(), table.name());
        Shown shown = new Shown(key, schema.name(), table, state.mode(key), style);
        if (tables.put(key, shown) != null) {
          throw new IllegalArgumentException("two tables are named " + key);
        }
      }
    }
    for (Shown child : tables.values()) {
      List<ForeignKey> foreignKeys = child.table.foreignKeys();
      for (int i = 0; i < foreignKeys.size(); i++) {
        ForeignKey foreignKey = foreignKeys.get(i);
        Shown parent = tables.get(key(
            foreignKey.schema() == null ? child.schema : foreignKey.schema(), foreignKey.table()));
        if (parent != null) {
          Relation relation = new Relation(
              child.key + "/" + (foreignKey.name() == null ? i : foreignKey.name()),
              child,
              foreignKey,
              parent);
          child.out.add(relation);
          parent.in.add(relation);
        }
      }
    }
    return tables;
  }

  /**
   * Places the tables that are not pinned, when that is due, a table came, went or changed its
   * height, as with its mode or its columns, or the style changed their width.
   */
  private static void arrange(State state, Map<String, Shown> tables, ErStyle style) {
    Map<String, Float> heights = new HashMap<>();
    tables.values().forEach(table -> heights.put(table.key, table.height));
    if (!state.arrangeDue && heights.equals(state.arranged) && style.width() == state.width) {
      return;
    }
    state.arranged = heights;
    state.width = style.width();
    List<Node> nodes = new ArrayList<>();
    List<LayeredLayout.Edge> edges = new ArrayList<>();
    for (Shown table : tables.values()) {
      Place place = state.places.get(table.key);
      boolean pinned = place != null && place.pinned;
      nodes.add(new Node(
          table.key,
          null,
          pinned ? place.x : 0,
          pinned ? place.y : 0,
          style.width(),
          table.height,
          pinned));
      table.out.forEach(r -> edges.add(new LayeredLayout.Edge(r.child.key, r.parent.key)));
    }
    for (Node node : LayeredLayout.arrange(
        nodes, edges, LayeredLayout.options().cluster(node -> tables.get(node.key()).schema))) {
      state.places.put(node.key(), new Place(node.x(), node.y(), node.pinned()));
    }
    state.arrangeDue = false;
  }

  /**
   * The diagram with its bar.
   *
   * @param state what it shows, from the app's state
   * @param schemas the schemas with their tables, each table with a key of its own
   * @param onPins told all pins whenever the user moves a table or arranges all, as {@link
   *     State#pins()} gives them; null for none
   * @param actions the app's own actions at the end of the bar, such as an export
   * @throws IllegalArgumentException if two tables have the same key
   */
  public static El erDiagram(
      State state, List<Schema> schemas, Consumer<Map<String, Pin>> onPins, El... actions) {
    Theme theme = Theme.CURRENT.get();
    DiagramTexts texts = DiagramTexts.CURRENT.get();
    ErStyle style = ErStyle.CURRENT.get();
    Map<String, Shown> tables = model(schemas, state, style);
    arrange(state, tables, style);
    Diagram diagram = new Diagram(state, tables, state.selected, style);
    if (state.revealFirst && !diagram.nodes.isEmpty()) {
      state.revealFirst = false;
      state.viewport.reveal(diagram.nodes.getFirst().key());
    }
    Consumer<Map<String, Pin>> told = onPins == null ? pins -> {} : onPins;

    El bar = row()
        .wrap()
        .padding(theme.space().s(), theme.space().m())
        .gap(theme.space().s())
        .align(Style.Align.CENTER)
        .child(TextField.withIcon(
            TextField.textField(state.filter, texts.filterTables(), state::filter), Symbol.SEARCH))
        .child(Segmented.segmented(
            List.of(texts.allColumns(), texts.keysOnly(), texts.collapsed()),
            state.mode.ordinal(),
            index -> state.mode(Mode.values()[index])))
        .child(el().grow(1))
        .child(Button.button(Button.Variant.GHOST, texts.fit(), state.viewport::fit))
        .child(Button.button(Button.Variant.GHOST, texts.arrange(), state::arrange))
        .child(Button.button(Button.Variant.GHOST, texts.arrangeAll(), () -> {
          state.arrangeAll();
          told.accept(state.pins());
        }));
    for (El action : actions) {
      bar.child(action.shrink(0));
    }
    El canvas = Canvas.canvas(
            state.viewport,
            diagram.frames,
            diagram.nodes,
            diagram.edges,
            diagram::content,
            node -> {
              state.places.put(node.key(), new Place(node.x(), node.y(), node.pinned()));
              told.accept(state.pins());
            })
        .label(texts.erDiagram())
        .onPress(() -> state.select(null))
        .onKeyFirst(key -> key(diagram, key, told))
        .onText(typed -> follow(diagram, typed));
    return col()
        .minSize(0, 0)
        .child(bar.shrink(0))
        .child(el().h(theme.size().stroke()).shrink(0).fill(theme.border().divider()))
        .child(canvas.grow(1).minSize(0, 0));
  }

  /**
   * The whole diagram as a PNG image, with no table selected, at a zoom of 100 % in the theme of
   * {@link Theme#CURRENT} where it is called, as {@link Canvas#png} saves a canvas. A handler runs
   * outside the view's theme, so it gives the theme again: {@code Theme.CURRENT.with(state.theme, ()
   * -> ErDiagram.png(state.diagram, state.schemas))}, and an app with a style of its own gives it
   * with {@link ErStyle#CURRENT} as well. It leaves the state as it is.
   *
   * @throws IllegalArgumentException if the image would be too large, as {@link Canvas#png} says
   */
  public static byte[] png(State live, List<Schema> schemas) {
    ErStyle style = ErStyle.CURRENT.get();
    // A copy, as the image may need an arrangement that the view does not, at another style.
    State state = new State();
    state.mode = live.mode;
    state.modes.putAll(live.modes);
    state.filter = live.filter;
    state.places.putAll(live.places);
    state.arrangeDue = live.arrangeDue;
    state.arranged = live.arranged;
    state.width = live.width;
    Map<String, Shown> tables = model(schemas, state, style);
    arrange(state, tables, style);
    Diagram diagram = new Diagram(state, tables, null, style);
    return Canvas.png(
        diagram.frames,
        diagram.nodes,
        diagram.edges,
        viewport -> Canvas.canvas(
            viewport, diagram.frames, diagram.nodes, diagram.edges, diagram::content, null));
  }

  /** What one build shows: the tables the filter lets through, their edges and frames. */
  private static final class Diagram {
    final State state;
    final Map<String, Shown> tables;
    final ErStyle style;
    final Theme theme = Theme.CURRENT.get();
    final DiagramTexts texts = DiagramTexts.CURRENT.get();
    final Map<String, Shown> shown = new LinkedHashMap<>();
    final Shown selected;
    final Set<Shown> related = new HashSet<>();
    // The rows at the ends of the selected table's edges, as key and column.
    final Set<List<String>> lit = new HashSet<>();
    final List<Node> nodes = new ArrayList<>();
    final List<Edge> edges = new ArrayList<>();
    final List<Node> frames = new ArrayList<>();
    final Map<String, Integer> frameSizes = new HashMap<>();
    // The schema of each frame, by its key.
    final Map<String, String> frameSchemas = new HashMap<>();

    Diagram(State state, Map<String, Shown> tables, String selected, ErStyle style) {
      this.state = state;
      this.tables = tables;
      this.style = style;
      String filter = state.filter.strip().toLowerCase(Locale.ROOT);
      for (Shown table : tables.values()) {
        if (table.table.name().toLowerCase(Locale.ROOT).contains(filter)) {
          shown.put(table.key, table);
        }
      }
      this.selected = selected == null ? null : shown.get(selected);
      if (this.selected != null) {
        related.add(this.selected);
        related.addAll(this.selected.related());
      }
      for (Shown table : shown.values()) {
        Place place = state.places.get(table.key);
        nodes.add(new Node(
            table.key, label(table), place.x, place.y, style.width(), table.height, place.pinned));
        for (Relation relation : table.out) {
          if (shown.containsKey(relation.parent.key)) {
            edges.add(edge(relation));
          }
        }
      }
      frames();
    }

    private String label(Shown table) {
      StringBuilder label = new StringBuilder(table.table.name())
          .append(", ")
          .append(texts.columns(table.table.columns().size()));
      List<String> references = names(table.out.stream().map(Relation::parent).toList());
      if (!references.isEmpty()) {
        label.append(", ").append(texts.references(String.join(", ", references)));
      }
      List<String> referrers = names(table.in.stream().map(Relation::child).toList());
      if (!referrers.isEmpty()) {
        label.append(", ").append(texts.referencedBy(String.join(", ", referrers)));
      }
      return label.toString();
    }

    private static List<String> names(List<Shown> tables) {
      return tables.stream().map(t -> t.table.name()).distinct().toList();
    }

    private Edge edge(Relation relation) {
      ForeignKey foreignKey = relation.foreignKey;
      Table child = relation.child.table;
      List<String> primaryKey =
          child.columns().stream().filter(Column::primaryKey).map(Column::name).toList();
      // Unique where its columns hold the whole primary key or a column unique by itself.
      boolean unique = !primaryKey.isEmpty() && foreignKey.columns().containsAll(primaryKey)
          || foreignKey.columns().stream()
              .map(child::column)
              .anyMatch(column -> column != null && column.unique());
      boolean nullable = foreignKey.columns().stream()
          .map(child::column)
          .anyMatch(column -> column == null || column.nullable());
      Emphasis emphasis = Emphasis.NORMAL;
      if (selected != null) {
        emphasis = relation.child == selected || relation.parent == selected
            ? Emphasis.HIGHLIGHTED
            : Emphasis.DIMMED;
      }
      if (emphasis == Emphasis.HIGHLIGHTED) {
        foreignKey.columns().forEach(c -> lit.add(List.of(relation.child.key, c)));
        foreignKey.referencedColumns().forEach(c -> lit.add(List.of(relation.parent.key, c)));
      }
      String label = foreignKey.name() != null
          ? foreignKey.name()
          : child.name() + " → " + relation.parent.table.name();
      return new Edge(
          relation.key,
          label,
          new Port(relation.child.key, relation.child.y(foreignKey.columns().getFirst(), style)),
          new Port(
              relation.parent.key,
              relation.parent.y(foreignKey.referencedColumns().getFirst(), style)),
          unique ? Marker.ONE : Marker.ONE_OR_MANY,
          nullable ? Marker.ZERO_OR_ONE : Marker.ONE,
          emphasis,
          foreignKey.onDelete() == null ? null : texts.onDelete(foreignKey.onDelete()));
    }

    /** A frame around the tables shown of each schema with a name. */
    private void frames() {
      Map<String, double[]> bounds = new LinkedHashMap<>();
      for (Node node : nodes) {
        String schema = shown.get(node.key()).schema;
        if (schema.isEmpty()) {
          continue;
        }
        double[] box = bounds.computeIfAbsent(schema, s -> new double[] {
          Double.POSITIVE_INFINITY,
          Double.POSITIVE_INFINITY,
          Double.NEGATIVE_INFINITY,
          Double.NEGATIVE_INFINITY
        });
        box[0] = Math.min(box[0], node.x());
        box[1] = Math.min(box[1], node.y());
        box[2] = Math.max(box[2], node.x() + node.width());
        box[3] = Math.max(box[3], node.y() + node.height());
        frameSizes.merge(schema, 1, Integer::sum);
      }
      float pad = style.frame();
      // Keyed schema/, and apart from every table and relation, as a table without a schema may be
      // named so: from all of them, so that the filter does not change a frame's key.
      Set<String> taken = new HashSet<>(tables.keySet());
      tables.values().forEach(table -> table.out.forEach(relation -> taken.add(relation.key)));
      Map<String, String> keys = new HashMap<>();
      tables.values().forEach(table -> {
        if (!table.schema.isEmpty() && !keys.containsKey(table.schema)) {
          String key = table.schema + "/";
          while (!taken.add(key)) {
            key += "/";
          }
          keys.put(table.schema, key);
        }
      });
      bounds.forEach((schema, box) -> {
        String key = keys.get(schema);
        frameSchemas.put(key, schema);
        frames.add(new Node(
            key,
            schema + ", " + texts.tables(frameSizes.get(schema)),
            box[0] - pad,
            box[1] - pad - style.frameLabel(),
            (float) (box[2] - box[0] + 2 * pad),
            (float) (box[3] - box[1] + 2 * pad + style.frameLabel())));
      });
    }

    /** What a table or a frame shows at this zoom. */
    El content(Node node, float zoom) {
      Shown table = shown.get(node.key());
      return table == null ? frame(node, zoom) : card(table, zoom);
    }

    /** A size of text in the diagram, which stays readable where the tables show less. */
    private float readable(float size, float zoom) {
      return size * Math.max(zoom, style.skeleton());
    }

    private El frame(Node node, float zoom) {
      String schema = frameSchemas.get(node.key());
      Font font = theme.type().small().font().get();
      float size = readable(theme.type().small().size(), zoom);
      float pad = theme.space().m() * zoom;
      float gap = theme.space().xs() * zoom;
      float room = node.width() * zoom - 2 * theme.size().stroke() - 2 * pad - size - gap;
      String full = schema + " · " + texts.tables(frameSizes.get(schema));
      float scale = WindowState.CURRENT.get().scale();
      String title = font.shape(full, size, scale).width() / scale <= room ? full : schema;
      return col()
          .wFull()
          .hFull()
          .radius(theme.radius().large() * zoom)
          .border(theme.size().stroke(), theme.border().subtle())
          .child(row()
              .h(style.frameLabel() * zoom)
              .padding(0, pad)
              .gap(gap)
              .align(Style.Align.CENTER)
              .child(Icon.icon(Symbol.DATABASE, Icon.Variant.MUTED, size))
              .child(text(title)
                  .font(font)
                  .fontSize(size)
                  .color(theme.text().muted())
                  .ellipsis()
                  .minSize(0, 0)));
    }

    private El card(Shown table, float zoom) {
      boolean dimmed = selected != null && !related.contains(table);
      int window = theme.surface().window();
      IntUnaryOperator tone =
          dimmed ? color -> Colors.mix(color, window, style.dimming()) : color -> color;
      El card = col()
          .wFull()
          .hFull()
          .role("list")
          .selected(table == selected)
          .fill(tone.applyAsInt(theme.surface().panel()))
          .radius(theme.radius().medium() * zoom)
          .clip()
          .onPointerDown((clicks, modifiers) -> state.select(table.key));
      if (table == selected) {
        int accent = theme.accent().edge();
        card.border(theme.size().stroke(), accent)
            .shadow(new Shadow(0, 0, 0, theme.size().stroke(), accent));
      } else {
        card.border(theme.size().stroke(), tone.applyAsInt(theme.border().subtle()));
      }
      if (zoom < style.names()) {
        Font font = theme.type().strong().font().get();
        float inner = table.height * zoom - 2 * theme.size().stroke();
        float line = font.ascent() + font.descent() + font.lineGap();
        float size = readable(theme.type().strong().size(), zoom);
        if (inner > 0 && line > 0) {
          size = Math.min(size, inner / line);
        }
        return card.justify(Style.Content.CENTER)
            .padding(0, readable(theme.space().s(), zoom))
            .child(text(table.table.name())
                .font(font)
                .fontSize(size)
                .color(tone.applyAsInt(theme.text().primary()))
                .ellipsis()
                .minSize(0, 0));
      }
      card.child(header(table, zoom, tone));
      boolean skeleton = zoom < style.skeleton();
      for (Column column : table.rows) {
        card.child(
            skeleton ? bar(table, column, zoom, tone) : columnRow(table, column, zoom, tone));
      }
      if (table.hidden > 0) {
        El more = row().h(style.row() * zoom).shrink(0).padding(0, theme.space().s() * zoom);
        if (!skeleton) {
          more.role("button")
              .onPress(() -> switchMode(state, table.key, Mode.ALL))
              .child(text(texts.moreColumns(table.hidden))
                  .fontSize(theme.type().small().size() * zoom)
                  .color(tone.applyAsInt(theme.text().muted())));
        }
        card.child(more);
      }
      return card;
    }

    private El header(Shown table, float zoom, IntUnaryOperator tone) {
      boolean skeleton = zoom < style.skeleton();
      float icon = theme.size().icon() * zoom;
      int divider = tone.applyAsInt(theme.border().divider());
      El header = row()
          .h(style.header() * zoom)
          .shrink(0)
          .padding(0, theme.space().s() * zoom)
          .gap(theme.space().s() * zoom)
          .align(Style.Align.CENTER);
      if (table.mode != Mode.COLLAPSED) {
        header.border(theme.size().stroke(), 0).borderColors(0, 0, divider, 0);
      }
      if (!skeleton) {
        header.child(Icon.icon(Symbol.TABLE, Icon.Variant.MUTED, icon)
            .color(tone.applyAsInt(theme.text().muted())));
      }
      header.child(text(table.table.name())
          .font(theme.type().strong().font().get())
          .fontSize(readable(theme.type().strong().size(), zoom))
          .color(tone.applyAsInt(theme.text().primary()))
          .ellipsis()
          .minSize(0, 0)
          .grow(1));
      if (skeleton) {
        return header;
      }
      boolean collapsed = table.mode == Mode.COLLAPSED;
      if (collapsed) {
        header.child(text(texts.columns(table.table.columns().size()))
            .fontSize(theme.type().small().size() * zoom)
            .color(tone.applyAsInt(theme.text().muted()))
            .shrink(0));
      }
      String next = switch (next(table)) {
        case ALL -> texts.expand();
        case KEYS -> texts.keysOnly();
        case COLLAPSED -> texts.collapse();
      };
      return header.child(Icon.icon(
              collapsed ? Symbol.CHEVRON_RIGHT : Symbol.CHEVRON_DOWN, Icon.Variant.MUTED, icon)
          .color(tone.applyAsInt(theme.text().muted()))
          .role("button")
          .label(next)
          .onPress(() -> switchMode(state, table.key, next(table))));
    }

    private El columnRow(Shown table, Column column, float zoom, IntUnaryOperator tone) {
      Table of = table.table;
      List<ForeignKey> foreignKeys = of.foreignKeys().stream()
          .filter(f -> f.columns().contains(column.name()))
          .toList();
      float icon = theme.size().icon() * zoom;
      int fill = lit.contains(List.of(table.key, column.name()))
          ? tone.applyAsInt(theme.grid().selected())
          : 0;
      El mark = el().size(icon, icon).shrink(0);
      if (column.primaryKey()) {
        mark = Icon.icon(Symbol.KEY, Icon.Variant.WARNING, icon)
            .color(tone.applyAsInt(theme.status().warning()));
      } else if (!foreignKeys.isEmpty()) {
        mark = Icon.icon(Symbol.LINK, Icon.Variant.MUTED, icon)
            .color(tone.applyAsInt(theme.text().muted()));
      }
      float small = theme.type().small().size() * zoom;
      int muted = tone.applyAsInt(theme.text().muted());
      El row = row()
          .h(style.row() * zoom)
          .shrink(0)
          .padding(0, theme.space().s() * zoom)
          .gap(theme.space().s() * zoom)
          .align(Style.Align.CENTER)
          .role("listitem")
          .label(rowLabel(column, foreignKeys))
          .fill(fill)
          .whenHovered(hovered -> hovered.fill(Colors.over(
              fill == 0 ? tone.applyAsInt(theme.surface().panel()) : fill,
              theme.surface().hover())))
          .child(mark)
          .child(text(column.name())
              .fontSize(theme.type().body().size() * zoom)
              .color(tone.applyAsInt(theme.text().primary()))
              .ellipsis()
              .minSize(0, 0)
              .grow(1));
      if (column.unique() && !column.primaryKey()) {
        row.child(text("UQ").fontSize(small).color(muted).shrink(0));
      }
      row.child(text(column.type() + (column.nullable() ? "?" : ""))
          .fontSize(small)
          .color(muted)
          .ellipsis()
          .minSize(0, 0)
          .shrink(0.5f));
      String details = details(column, foreignKeys);
      if (details == null) {
        return row;
      }
      return Tooltip.tooltip(
          row, column.name() + " " + column.type(), Tooltip.options().description(details));
    }

    private String rowLabel(Column column, List<ForeignKey> foreignKeys) {
      List<String> parts = new ArrayList<>(List.of(column.name(), column.type()));
      if (column.primaryKey()) {
        parts.add(texts.primaryKey());
      }
      if (!foreignKeys.isEmpty()) {
        parts.add(texts.foreignKey());
      }
      if (column.unique() && !column.primaryKey()) {
        parts.add(texts.unique());
      }
      if (column.nullable()) {
        parts.add(texts.nullable());
      }
      return String.join(", ", parts);
    }

    /** The default and the foreign keys of a column, a line each, or null for neither. */
    private String details(Column column, List<ForeignKey> foreignKeys) {
      List<String> lines = new ArrayList<>();
      if (column.defaultValue() != null) {
        lines.add(texts.defaultValue(column.defaultValue()));
      }
      for (ForeignKey foreignKey : foreignKeys) {
        StringBuilder line = new StringBuilder();
        if (foreignKey.name() != null) {
          line.append(foreignKey.name()).append(' ');
        }
        line.append("→ ")
            .append(foreignKey.schema() == null ? "" : foreignKey.schema() + ".")
            .append(foreignKey.table())
            .append('(')
            .append(String.join(", ", foreignKey.referencedColumns()))
            .append(')');
        if (foreignKey.onDelete() != null) {
          line.append(", ").append(texts.onDelete(foreignKey.onDelete()));
        }
        lines.add(line.toString());
      }
      return lines.isEmpty() ? null : String.join("\n", lines);
    }

    /** A column as a bar, in the warning color for a key, where the zoom leaves text unreadable. */
    private El bar(Shown table, Column column, float zoom, IntUnaryOperator tone) {
      float inner = (style.width() - 2 * theme.space().s()) * zoom;
      float width = Math.min(inner * 0.6f, column.name().length() * 7 * zoom);
      int color =
          key(table.table, column) ? theme.status().warning() : theme.border().strong();
      return row()
          .h(style.row() * zoom)
          .shrink(0)
          .padding(0, theme.space().s() * zoom)
          .align(Style.Align.CENTER)
          .child(el().size(width, Math.max(2, style.row() * zoom / 4))
              .radius(style.row() * zoom / 8)
              .fill(tone.applyAsInt(color)));
    }
  }

  /** Switches a table's mode for the user, and shows it where its new size puts it. */
  private static void switchMode(State state, String table, Mode mode) {
    state.mode(table, mode);
    state.viewport.reveal(table);
  }

  /** The mode after a table's: all columns, its keys where it has others, collapsed, and over. */
  private static Mode next(Shown table) {
    return switch (table.mode) {
      case ALL ->
        table.table.columns().stream().allMatch(column -> key(table.table, column))
            ? Mode.COLLAPSED
            : Mode.KEYS;
      case KEYS -> Mode.COLLAPSED;
      case COLLAPSED -> Mode.ALL;
    };
  }

  private static boolean key(Diagram diagram, Key key, Consumer<Map<String, Pin>> onPins) {
    State state = diagram.state;
    int dx = switch (key.name()) {
      case "ArrowLeft" -> -1;
      case "ArrowRight" -> 1;
      default -> 0;
    };
    int dy = switch (key.name()) {
      case "ArrowUp" -> -1;
      case "ArrowDown" -> 1;
      default -> 0;
    };
    Shown selected = diagram.selected;
    if (key.modifiers() == Key.SHIFT && (dx != 0 || dy != 0)) {
      if (selected == null) {
        return false;
      }
      Place place = state.places.get(selected.key);
      state.places.put(
          selected.key,
          new Place(
              place.x + dx * diagram.style.nudge(), place.y + dy * diagram.style.nudge(), true));
      onPins.accept(state.pins());
      reveal(diagram, selected);
      return true;
    }
    if (key.modifiers() != 0) {
      return false;
    }
    if (dx != 0 || dy != 0) {
      Shown next = nearest(diagram, selected, dx, dy);
      if (next != null) {
        state.select(next.key);
        reveal(diagram, next);
      }
      return true;
    }
    switch (key.name()) {
      case "Enter" -> {
        if (selected == null) {
          return false;
        }
        switchMode(state, selected.key, next(selected));
      }
      case "Escape" -> {
        if (selected == null) {
          return false;
        }
        state.select(null);
      }
      default -> {
        return false;
      }
    }
    return true;
  }

  /**
   * The table nearest to this one in a direction, weighing distance across it twice; the one at
   * the top left where none is selected.
   */
  private static Shown nearest(Diagram diagram, Shown from, int dx, int dy) {
    Shown best = null;
    double bestScore = Double.POSITIVE_INFINITY;
    Node origin = from == null ? null : node(diagram, from);
    for (Node node : diagram.nodes) {
      double score;
      if (origin == null) {
        score = node.x() + node.y();
      } else {
        double x = node.x() + node.width() / 2 - origin.x() - origin.width() / 2;
        double y = node.y() + node.height() / 2 - origin.y() - origin.height() / 2;
        double along = x * dx + y * dy;
        if (along <= 0) {
          continue;
        }
        score = along + 2 * Math.abs(dx != 0 ? y : x);
      }
      if (score < bestScore) {
        bestScore = score;
        best = diagram.shown.get(node.key());
      }
    }
    return best;
  }

  private static Node node(Diagram diagram, Shown table) {
    Place place = diagram.state.places.get(table.key);
    return new Node(
        table.key, null, place.x, place.y, diagram.style.width(), table.height, place.pinned);
  }

  private static void reveal(Diagram diagram, Shown table) {
    diagram.state.viewport.reveal(table.key);
  }

  /** Steps with ] and [ through the tables related to the one where the steps began. */
  private static void follow(Diagram diagram, String typed) {
    int step = switch (typed) {
      case "]" -> 1;
      case "[" -> -1;
      default -> 0;
    };
    State state = diagram.state;
    if (step == 0) {
      return;
    }
    Shown origin = state.followed == null ? null : diagram.shown.get(state.followed);
    if (origin == null) {
      // A round begins at the selected table, also where the filter hid the last one's start.
      state.followed = null;
      origin = diagram.selected;
    }
    if (origin == null) {
      return;
    }
    List<Shown> related = origin.related().stream()
        .filter(table -> diagram.shown.containsKey(table.key))
        .toList();
    if (related.isEmpty()) {
      return;
    }
    int index = state.followed == null
        ? (step > 0 ? 0 : related.size() - 1)
        : Math.floorMod(state.follow + step, related.size());
    Shown next = related.get(index);
    state.selected = next.key;
    state.followed = origin.key;
    state.follow = index;
    reveal(diagram, next);
  }
}
