package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.DataGrid.DataType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Edits a tree of conditions: fields, operators and values in nested AND/OR {@link Clause}s. It
 * filters nothing and knows no query language; the app supplies the {@link Field}s with their
 * {@link Operator}s, keeps the root {@link Clause} and a {@link State}, and turns {@link
 * #applicable} into its own query.
 *
 * <p>Controlled: every edit passes a new immutable root {@link Clause} to {@code onChange},
 * including half-typed values while editing; a null {@code onChange} disables the builder. Values
 * are kept as text (dates and times in ISO 8601); {@link DataType#DATE} and {@link
 * DataType#DATE_TIME} fields without choices use a date field with a calendar picker, while {@link
 * DataType#TIME} uses a plain text field. {@link #applicable} strips switched-off, incomplete,
 * invalid and reversed-range comparisons as well as empty or switched-off clauses.
 *
 * <p>Each clause shows a header with a {@link Segmented} AND/OR switch and buttons to add a
 * condition or a nested group; a nested clause also has a checkbox to switch it off and a remove
 * button. Its conditions sit indented to the right of a thin vertical rail. Switched-off rows and
 * clauses are drawn muted while staying editable.
 *
 * <p>Changing a row's field keeps its operator when the new field offers it (else its first
 * operator) and keeps its values when the field type and operator arity stay the same and the new
 * field offers them (else clears them). Invalid non-empty values are marked with {@link
 * TextField#invalid} and their format's error message; for an operator of {@link Arity#TWO}, a
 * reversed range (both ends valid and start after end) marks both ends invalid with {@link
 * Texts#fromAboveTo()} and shows an alert message below the range; incomplete rows are not marked.
 *
 * <p>Keyboard: Tab moves in reading order and each control keeps its own keys. Adding a condition
 * or a group focuses the new row's field; removing focuses the remove button of the next item in
 * the clause, or the clause's Add condition button when the last item was removed.
 */
public final class ConditionBuilder {

  /** Opacity applied to switched-off rows and clauses so they read muted while remaining editable. */
  private static final float MUTED_OPACITY = 0.5f;

  private static final List<String> BOOLEAN_CHOICES = List.of("true", "false");

  private ConditionBuilder() {}

  /** How many values an {@link Operator} takes: none, one, two (a range), or many. */
  public enum Arity {
    NONE,
    ONE,
    TWO,
    MANY
  }

  /**
   * One operator offered for a {@link Field}, such as {@code =}, {@code between}, {@code in} or
   * {@code is null}.
   *
   * @param id stable identifier stored in {@link Comparison#operator}
   * @param label text shown in the operator selector
   * @param arity how many values this operator takes
   */
  public record Operator(String id, String label, Arity arity) {
    public Operator {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(label, "label");
      Objects.requireNonNull(arity, "arity");
    }
  }

  /**
   * One field the user can filter on, with its data type, optional fixed choices and operators.
   *
   * @param id stable identifier stored in {@link Comparison#field}
   * @param label text shown in the field selector
   * @param type kind of data, supplying the icon and validation format
   * @param choices fixed values for {@link Select} and {@link MultiSelect}; empty for free input.
   *     An operator of {@link Arity#MANY} needs them: without choices its comparison is never
   *     applicable
   * @param operators operators offered for this field, in display order
   */
  public record Field(
      String id, String label, DataType type, List<String> choices, List<Operator> operators) {
    public Field {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(label, "label");
      Objects.requireNonNull(type, "type");
      choices = choices == null ? List.of() : List.copyOf(choices);
      operators = operators == null ? List.of() : List.copyOf(operators);
    }
  }

  /** How a {@link Clause} combines its child conditions. */
  public enum Join {
    AND,
    OR
  }

  /** A node in the condition tree: either a {@link Comparison} row or a nested {@link Clause}. */
  public sealed interface Condition permits Comparison, Clause {
    /** Whether this condition is active; switched-off nodes stay in the tree and remain editable. */
    boolean enabled();
  }

  /**
   * A single condition row comparing a field with an operator and zero or more text values.
   *
   * @param field id of the chosen {@link Field}
   * @param operator id of the chosen {@link Operator}
   * @param values text values (empty for {@link Arity#NONE}, one for {@link Arity#ONE}, two for
   *     {@link Arity#TWO}, zero or more for {@link Arity#MANY})
   * @param enabled whether this row is switched on
   */
  public record Comparison(String field, String operator, List<String> values, boolean enabled)
      implements Condition {
    public Comparison {
      Objects.requireNonNull(field, "field");
      Objects.requireNonNull(operator, "operator");
      values = values == null ? List.of() : List.copyOf(values);
    }
  }

  /**
   * A group of conditions combined with {@link Join#AND} or {@link Join#OR}.
   *
   * @param join how the conditions combine
   * @param conditions child comparisons and nested clauses, in order
   * @param enabled whether this clause is switched on
   */
  public record Clause(Join join, List<Condition> conditions, boolean enabled)
      implements Condition {
    public Clause {
      Objects.requireNonNull(join, "join");
      conditions = conditions == null ? List.of() : List.copyOf(conditions);
    }
  }

  /**
   * UI state of a {@link ConditionBuilder}: open dropdowns, multi-select states, date pickers and
   * pending focus moves. Kept by the app alongside its root {@link Clause}, like {@link
   * MultiSelect.State}.
   */
  public static final class State {
    private final Map<String, Menu.State> menus = new HashMap<>();
    private final Map<String, MultiSelect.State> multiSelects = new HashMap<>();
    private final Map<String, DatePicker.State> calendars = new HashMap<>();
    private String focusKey; // consumed by the next build, so a stale request never fires later
    private int focusSerial;

    Menu.State menu(String key, Set<String> used) {
      used.add(key);
      return menus.computeIfAbsent(key, _ -> new Menu.State());
    }

    MultiSelect.State multiSelect(String key, Set<String> used) {
      used.add(key);
      return multiSelects.computeIfAbsent(key, _ -> new MultiSelect.State());
    }

    DatePicker.State calendar(String key, Set<String> used) {
      used.add(key);
      return calendars.computeIfAbsent(key, _ -> new DatePicker.State());
    }

    void requestFocus(String key) {
      focusKey = key;
      focusSerial++;
    }

    void closeAll() {
      for (Menu.State menu : menus.values()) {
        menu.close();
      }
      for (MultiSelect.State ms : multiSelects.values()) {
        ms.close();
      }
      for (DatePicker.State calendar : calendars.values()) {
        calendar.close();
      }
    }

    void prune(Set<String> usedMenus, Set<String> usedMulti, Set<String> usedCalendars) {
      menus.keySet().retainAll(usedMenus);
      multiSelects.keySet().retainAll(usedMulti);
      calendars.keySet().retainAll(usedCalendars);
    }
  }

  /**
   * Builds a controlled condition builder for {@code root}.
   *
   * @param fields available fields with their types, choices and operators
   * @param root current root clause from the app's state
   * @param state UI state kept by the app
   * @param onChange receives a new immutable root clause on every edit; null disables the builder
   */
  public static El conditionBuilder(
      List<Field> fields, Clause root, State state, Consumer<Clause> onChange) {
    Objects.requireNonNull(fields, "fields");
    Objects.requireNonNull(root, "root");
    Objects.requireNonNull(state, "state");
    Theme theme = Theme.CURRENT.get();
    Texts texts = Texts.CURRENT.get();
    if (onChange == null) {
      state.closeAll();
    }
    Build build = new Build(theme, texts, fields, state, onChange != null);
    El body = root.conditions().isEmpty()
        ? build.emptyRoot(root, onChange)
        : build.clause(root, "", true, true, onChange, null);
    state.prune(build.usedMenus, build.usedMulti, build.usedCalendars);
    return body.wFull().role("group").label(texts.conditions());
  }

  /**
   * Returns a copy of {@code root} containing only active, complete and valid conditions, without
   * empty or switched-off clauses. A clause that is switched off or ends up empty adds no
   * condition, also under {@link Join#OR}.
   */
  public static Clause applicable(Clause root, List<Field> fields) {
    Objects.requireNonNull(root, "root");
    Objects.requireNonNull(fields, "fields");
    if (!root.enabled()) {
      return new Clause(root.join(), List.of(), false);
    }
    Map<String, Field> byId = fieldsById(fields);
    return new Clause(root.join(), applicableConditions(root.conditions(), byId), true);
  }

  private static List<Condition> applicableConditions(
      List<Condition> conditions, Map<String, Field> byId) {
    List<Condition> kept = new ArrayList<>(conditions.size());
    for (Condition condition : conditions) {
      if (!condition.enabled()) {
        continue;
      }
      switch (condition) {
        case Comparison comparison when isApplicable(comparison, byId) -> kept.add(comparison);
        case Comparison _ -> {}
        case Clause nested -> {
          List<Condition> children = applicableConditions(nested.conditions(), byId);
          if (!children.isEmpty()) {
            kept.add(new Clause(nested.join(), children, true));
          }
        }
      }
    }
    return List.copyOf(kept);
  }

  private static boolean isApplicable(Comparison comparison, Map<String, Field> byId) {
    Field field = byId.get(comparison.field());
    if (field == null) {
      return false;
    }
    Operator operator = findOperator(field, comparison.operator());
    if (operator == null) {
      return false;
    }
    List<String> values = comparison.values();
    return switch (operator.arity()) {
      case NONE -> true;
      case ONE -> values.size() == 1 && isValidSingle(field, values.getFirst());
      case TWO ->
        values.size() == 2
            && isValidSingle(field, values.get(0))
            && isValidSingle(field, values.get(1))
            && !ValueInputs.above(field.type(), null, values.get(0), values.get(1));
      case MANY -> {
        List<String> choices = choicesOf(field);
        if (values.isEmpty() || choices.isEmpty()) {
          yield false;
        }
        for (String v : values) {
          if (v == null || v.isEmpty() || !choices.contains(v)) {
            yield false;
          }
        }
        yield true;
      }
    };
  }

  private static boolean isValidSingle(Field field, String value) {
    if (value == null || value.isEmpty()) {
      return false;
    }
    List<String> choices = choicesOf(field);
    if (!choices.isEmpty()) {
      return choices.contains(value);
    }
    return ValueInputs.check(field.type(), null, value) == null;
  }

  private static boolean isReversedRange(Field field, String from, String to) {
    return isValidSingle(field, from)
        && isValidSingle(field, to)
        && ValueInputs.above(field.type(), null, from, to);
  }

  private static Map<String, Field> fieldsById(List<Field> fields) {
    Map<String, Field> map = HashMap.newHashMap(fields.size());
    for (Field field : fields) {
      map.putIfAbsent(field.id(), field);
    }
    return map;
  }

  private static Operator findOperator(Field field, String id) {
    for (Operator operator : field.operators()) {
      if (operator.id().equals(id)) {
        return operator;
      }
    }
    return null;
  }

  /** Whether {@code field} takes {@code value}: any value, or one of its choices if it has some. */
  private static boolean offers(Field field, String value) {
    List<String> choices = choicesOf(field);
    return choices.isEmpty() || choices.contains(value);
  }

  private static List<String> choicesOf(Field field) {
    if (!field.choices().isEmpty()) {
      return field.choices();
    }
    return field.type() == DataType.BOOLEAN ? BOOLEAN_CHOICES : List.of();
  }

  /** The format whose characters a number input lets through, as {@link ValueField} does. */
  private static Format<?> numberFormat(DataType type) {
    return switch (type) {
      case INTEGER -> Format.BIG_INTEGER;
      case DECIMAL -> Format.DECIMAL;
      default -> null;
    };
  }

  private static final class Build {
    final Theme theme;
    final Texts texts;
    final List<Field> fields;
    final List<String> fieldLabels;
    final List<Icon> fieldIcons;
    final Map<String, Field> byId;
    final State state;
    final boolean interactive;
    final Set<String> usedMenus = new HashSet<>();
    final Set<String> usedMulti = new HashSet<>();
    final Set<String> usedCalendars = new HashSet<>();
    final String focusKey;

    Build(Theme theme, Texts texts, List<Field> fields, State state, boolean interactive) {
      this.focusKey = state.focusKey;
      state.focusKey = null;
      this.theme = theme;
      this.texts = texts;
      this.fields = fields;
      this.fieldLabels = fields.stream().map(Field::label).toList();
      this.fieldIcons = fields.stream().<Icon>map(f -> f.type().symbol()).toList();
      this.byId = fieldsById(fields);
      this.state = state;
      this.interactive = interactive;
    }

    El emptyRoot(Clause root, Consumer<Clause> onChange) {
      El addCondition = addConditionButton(root, "", onChange);
      El addGroup = addGroupButton(root, "", onChange);
      return row()
          .align(Style.Align.CENTER)
          .gap(theme.space().s())
          .children(
              label(Label.Variant.MUTED, texts.noConditions())
                  .key("empty")
                  .margin(0, theme.space().s(), 0, 0),
              addCondition,
              addGroup);
    }

    El clause(
        Clause clause,
        String path,
        boolean root,
        boolean parentActive,
        Consumer<Clause> onChange,
        Runnable onRemove) {
      boolean active = parentActive && clause.enabled();
      boolean dimHere = parentActive && !clause.enabled();
      String suffix = suffix(path);

      El join = Segmented.segmented(
              List.of(Join.AND, Join.OR),
              clause.join(),
              texts::conditionJoin,
              onChange == null
                  ? null
                  : next ->
                      onChange.accept(new Clause(next, clause.conditions(), clause.enabled())))
          .key("join" + suffix)
          .label(texts.conditionJoin());
      applyFocus(join);

      El addCondition = addConditionButton(clause, path, onChange);
      El addGroup = addGroupButton(clause, path, onChange);

      El headerActions = row()
          .align(Style.Align.CENTER)
          .gap(theme.space().s())
          .children(join, addCondition, addGroup);
      if (dimHere) {
        headerActions.opacity(MUTED_OPACITY);
      }

      El header = row().align(Style.Align.CENTER).gap(theme.space().s());
      if (!root) {
        El toggle = Checkbox.checkbox(
                clause.enabled(),
                onChange == null
                    ? null
                    : () -> onChange.accept(
                        new Clause(clause.join(), clause.conditions(), !clause.enabled())))
            .key("enabled" + suffix)
            .label(texts.enableGroup());
        applyFocus(toggle);
        header.child(checkboxSlot(toggle));
      }
      header.child(headerActions);
      if (!root) {
        El remove = Button.icon(Button.Variant.GHOST, Symbol.DISMISS, texts.removeGroup(), onRemove)
            .key("remove" + suffix);
        applyFocus(remove);
        header.child(el().grow(1));
        header.child(remove);
      }

      El items = col().grow(1).minSize(0, 0).gap(theme.space().s());
      List<Condition> children = clause.conditions();
      if (children.isEmpty()) {
        items.child(row()
            .h(theme.size().control())
            .align(Style.Align.CENTER)
            .child(label(Label.Variant.MUTED, texts.noConditions()).key("empty" + suffix)));
      } else {
        for (int i = 0; i < children.size(); i++) {
          int index = i;
          String childPath = childPath(path, index);
          Condition child = children.get(index);
          Consumer<Condition> onChildChange = onChange == null
              ? null
              : updated -> onChange.accept(replaceAt(clause, index, updated));
          Runnable onChildRemove = onChange == null
              ? null
              : () -> {
                state.closeAll();
                Clause next = removeAt(clause, index);
                if (index < next.conditions().size()) {
                  state.requestFocus("remove-" + childPath(path, index));
                } else {
                  state.requestFocus("add-condition" + suffix);
                }
                onChange.accept(next);
              };
          items.child(
              switch (child) {
                case Comparison comparison ->
                  comparisonRow(comparison, childPath, active, onChildChange, onChildRemove);
                case Clause nested ->
                  clause(
                      nested,
                      childPath,
                      false,
                      active,
                      onChildChange == null ? null : onChildChange::accept,
                      onChildRemove);
              });
        }
      }

      El body = row()
          .padding(0, 0, 0, theme.space().m())
          .gap(theme.space().m())
          .children(
              el().w(theme.size().indicator())
                  .shrink(0)
                  .radius(theme.size().stroke())
                  .fill(theme.border().divider())
                  .alignSelf(Style.Align.STRETCH),
              items);
      if (dimHere) {
        body.opacity(MUTED_OPACITY);
      }

      El container = col().gap(theme.space().s()).children(header, body);
      if (!root) {
        container.key("clause" + suffix).role("group").label(texts.conditionGroup());
      }
      return container;
    }

    private El comparisonRow(
        Comparison comparison,
        String path,
        boolean parentActive,
        Consumer<Condition> onChange,
        Runnable onRemove) {
      String suffix = suffix(path);
      boolean dimHere = parentActive && !comparison.enabled();

      El toggle = Checkbox.checkbox(
              comparison.enabled(),
              onChange == null
                  ? null
                  : () -> onChange.accept(new Comparison(
                      comparison.field(),
                      comparison.operator(),
                      comparison.values(),
                      !comparison.enabled())))
          .key("enabled" + suffix)
          .label(texts.enableCondition());
      applyFocus(toggle);

      int fieldIndex = fieldIndex(comparison.field());
      Field field = fieldIndex >= 0 ? fields.get(fieldIndex) : null;
      Menu.State fieldMenu = state.menu("field" + suffix, usedMenus);
      El fieldSelect = Select.select(
              fieldLabels,
              fieldIcons,
              fieldIndex,
              fieldMenu,
              onChange == null
                  ? null
                  : chosen -> {
                    state.closeAll();
                    Field nextField = fields.get(chosen);
                    onChange.accept(changeField(comparison, field, nextField));
                  })
          .key("field" + suffix)
          .w(fieldWidth())
          .minSize(0, 0);
      if (fieldIndex < 0) {
        fieldSelect.label(texts.field());
      }
      applyFocus(fieldSelect);

      List<Operator> operators = field == null ? List.of() : field.operators();
      List<String> operatorLabels = operators.stream().map(Operator::label).toList();
      int operatorIndex = operatorIndex(operators, comparison.operator());
      Operator operator = operatorIndex >= 0 ? operators.get(operatorIndex) : null;
      Menu.State operatorMenu = state.menu("operator" + suffix, usedMenus);
      El operatorSelect = Select.select(
              operatorLabels,
              operatorIndex,
              operatorMenu,
              onChange == null || operators.isEmpty()
                  ? null
                  : chosen -> {
                    state.closeAll();
                    Operator nextOp = operators.get(chosen);
                    List<String> nextValues = operator != null && operator.arity() == nextOp.arity()
                        ? comparison.values()
                        : List.of();
                    onChange.accept(new Comparison(
                        comparison.field(), nextOp.id(), nextValues, comparison.enabled()));
                  })
          .key("operator" + suffix)
          .w(operatorWidth())
          .minSize(0, 0);
      if (operatorIndex < 0) {
        operatorSelect.label(texts.operator());
      }
      applyFocus(operatorSelect);

      // shrinks along with the selects in a narrow builder instead of overflowing into Remove
      El valueArea = valueControl(comparison, field, operator, path, onChange).basis(fieldWidth());

      El controls = row()
          .grow(1)
          .minSize(0, 0)
          .align(Style.Align.START)
          .gap(theme.space().s())
          .children(fieldSelect, operatorSelect, valueArea);
      if (dimHere) {
        controls.opacity(MUTED_OPACITY);
      }

      El remove = Button.icon(
              Button.Variant.GHOST, Symbol.DISMISS, texts.removeCondition(), onRemove)
          .key("remove" + suffix);
      applyFocus(remove);

      return row()
          .key("row" + suffix)
          .align(Style.Align.START)
          .gap(theme.space().s())
          .children(checkboxSlot(toggle), controls, remove);
    }

    private El valueControl(
        Comparison comparison,
        Field field,
        Operator operator,
        String path,
        Consumer<Condition> onChange) {
      if (field == null || operator == null || operator.arity() == Arity.NONE) {
        return el().grow(1);
      }
      String suffix = suffix(path);
      return switch (operator.arity()) {
        case NONE -> el().grow(1);
        case ONE -> {
          String current =
              comparison.values().isEmpty() ? "" : comparison.values().getFirst();
          Consumer<String> onEdit = onChange == null
              ? null
              : next -> onChange.accept(new Comparison(
                  comparison.field(),
                  comparison.operator(),
                  next.isEmpty() ? List.of() : List.of(next),
                  comparison.enabled()));
          yield singleValueInput(field, current, texts.value(), "value" + suffix, null, onEdit);
        }
        case TWO -> {
          String from = !comparison.values().isEmpty() ? comparison.values().get(0) : "";
          String to = comparison.values().size() >= 2 ? comparison.values().get(1) : "";
          boolean reversed = isReversedRange(field, from, to);
          String rangeError = reversed ? texts.fromAboveTo() : null;
          Consumer<String> onFrom = onChange == null
              ? null
              : next -> onChange.accept(new Comparison(
                  comparison.field(),
                  comparison.operator(),
                  next.isEmpty() && to.isEmpty() ? List.of() : List.of(next, to),
                  comparison.enabled()));
          Consumer<String> onTo = onChange == null
              ? null
              : next -> onChange.accept(new Comparison(
                  comparison.field(),
                  comparison.operator(),
                  from.isEmpty() && next.isEmpty() ? List.of() : List.of(from, next),
                  comparison.enabled()));
          El fromInput =
              singleValueInput(field, from, texts.from(), "from" + suffix, rangeError, onFrom);
          El toInput = singleValueInput(field, to, texts.to(), "to" + suffix, rangeError, onTo);
          El range = col()
              .grow(1)
              .minSize(0, 0)
              .gap(theme.space().xs())
              .child(row()
                  .wFull()
                  .wrap() // a date-time range too narrow to show both values breaks onto two lines
                  .align(Style.Align.CENTER)
                  .gap(theme.space().xs())
                  .children(
                      fromInput,
                      label(Label.Variant.MUTED, texts.rangeSeparator()).shrink(0),
                      toInput));
          if (reversed) {
            range.child(label(rangeError)
                .key("error" + suffix)
                .role("alert")
                .label(rangeError)
                .wrapText()
                .color(theme.status().danger()));
          }
          yield range;
        }
        case MANY -> {
          List<String> choices = choicesOf(field);
          MultiSelect.State multiState = state.multiSelect("values" + suffix, usedMulti);
          El multi = MultiSelect.multiSelect(
                  choices,
                  comparison.values(),
                  multiState,
                  onChange == null
                      ? null
                      : next -> onChange.accept(new Comparison(
                          comparison.field(), comparison.operator(), next, comparison.enabled())))
              .key("values" + suffix)
              .label(texts.value());
          multi.style().width = Len.AUTO;
          yield multi.grow(1).minSize(0, 0);
        }
      };
    }

    private El singleValueInput(
        Field field,
        String current,
        String placeholder,
        String key,
        String rangeError,
        Consumer<String> onEdit) {
      List<String> choices = choicesOf(field);
      if (!choices.isEmpty()) {
        int chosen = choices.indexOf(current);
        Menu.State menuState = state.menu(key, usedMenus);
        El select = Select.select(
                choices,
                chosen,
                menuState,
                onEdit == null ? null : i -> onEdit.accept(choices.get(i)))
            .key(key);
        if (chosen < 0) {
          select.label(placeholder);
        }
        if (rangeError != null) {
          select.invalid(true).errorMessage(rangeError);
        }
        select.style().width = Len.AUTO;
        select.grow(1).minSize(0, 0);
        applyFocus(select);
        return select;
      }
      String error = current.isEmpty() ? null : ValueInputs.check(field.type(), null, current);
      if (error == null) {
        error = rangeError;
      }
      if (ValueInputs.dateType(field.type())) {
        DatePicker.State calendar = state.calendar(key, usedCalendars);
        El box = ValueInputs.dateField(
            theme,
            texts,
            field.type(),
            v -> ValueInputs.check(field.type(), null, v) == null,
            current,
            placeholder,
            key,
            false,
            error != null,
            calendar,
            state::closeAll,
            onEdit);
        El input = box.children().getFirst();
        if (error != null) {
          input.errorMessage(error);
        }
        box.style().width = Len.AUTO;
        // a date-time needs the header filter's field width to show its minutes beside the button
        float least = field.type() == DataType.DATE_TIME ? theme.size().field() : 0;
        box.style().minYields = true; // where even one line is narrower, it shrinks like the rest
        box.grow(1).minSize(least, 0);
        applyFocus(input);
        return box;
      }
      Format<?> format = numberFormat(field.type());
      Consumer<String> filtered = onEdit == null
          ? null
          : next -> {
            if (format != null
                && !format.allows(next)
                && (format.allows(current) || next.length() >= current.length())) {
              return;
            }
            onEdit.accept(next);
          };
      El input = TextField.textField(current, placeholder, filtered).key(key);
      if (error != null) {
        TextField.invalid(input).errorMessage(error);
      }
      input.style().width = Len.AUTO;
      input.grow(1).minSize(0, 0);
      applyFocus(input);
      return input;
    }

    private El addConditionButton(Clause clause, String path, Consumer<Clause> onChange) {
      String suffix = suffix(path);
      Runnable press = onChange == null || fields.isEmpty()
          ? null
          : () -> {
            state.closeAll();
            int nextIndex = clause.conditions().size();
            Comparison added = defaultComparison();
            state.requestFocus("field-" + childPath(path, nextIndex));
            onChange.accept(append(clause, added));
          };
      El button = Button.button(Button.Variant.GHOST, Symbol.ADD, texts.condition(), press)
          .key("add-condition" + suffix)
          .label(texts.addCondition());
      applyFocus(button);
      return button;
    }

    private El addGroupButton(Clause clause, String path, Consumer<Clause> onChange) {
      String suffix = suffix(path);
      Runnable press = onChange == null
          ? null
          : () -> {
            state.closeAll();
            int nextIndex = clause.conditions().size();
            String groupPath = childPath(path, nextIndex);
            Join nestedJoin = clause.join() == Join.AND ? Join.OR : Join.AND;
            List<Condition> initial = fields.isEmpty() ? List.of() : List.of(defaultComparison());
            Clause added = new Clause(nestedJoin, initial, true);
            state.requestFocus(
                initial.isEmpty()
                    ? "add-condition-" + groupPath
                    : "field-" + childPath(groupPath, 0));
            onChange.accept(append(clause, added));
          };
      El button = Button.button(Button.Variant.GHOST, Symbol.ADD, texts.group(), press)
          .key("add-group" + suffix)
          .label(texts.addGroup());
      applyFocus(button);
      return button;
    }

    private Comparison defaultComparison() {
      Field first = fields.getFirst();
      String operator =
          first.operators().isEmpty() ? "" : first.operators().getFirst().id();
      return new Comparison(first.id(), operator, List.of(), true);
    }

    private Comparison changeField(Comparison current, Field oldField, Field nextField) {
      if (nextField.id().equals(current.field())) {
        return current;
      }
      Operator oldOp = oldField == null ? null : findOperator(oldField, current.operator());
      Operator keptOp = findOperator(nextField, current.operator());
      Operator nextOp = keptOp != null
          ? keptOp
          : nextField.operators().isEmpty() ? null : nextField.operators().getFirst();
      String nextOpId = nextOp == null ? "" : nextOp.id();
      boolean keepValues = oldField != null
          && oldField.type() == nextField.type()
          && current.values().stream().allMatch(v -> v.isEmpty() || offers(nextField, v))
          && oldOp != null
          && nextOp != null
          && oldOp.arity() == nextOp.arity();
      return new Comparison(
          nextField.id(), nextOpId, keepValues ? current.values() : List.of(), current.enabled());
    }

    private El checkboxSlot(El checkbox) {
      return row().h(theme.size().control()).align(Style.Align.CENTER).shrink(0).child(checkbox);
    }

    private void applyFocus(El el) {
      if (interactive && focusKey != null && focusKey.equals(el.key())) {
        el.takeFocus(state.focusSerial);
      }
    }

    private float fieldWidth() {
      return Math.round(theme.size().field() * 0.7f);
    }

    private float operatorWidth() {
      return Math.round(theme.size().field() * 0.6f);
    }

    private int fieldIndex(String id) {
      for (int i = 0; i < fields.size(); i++) {
        if (fields.get(i).id().equals(id)) {
          return i;
        }
      }
      return -1;
    }

    private static int operatorIndex(List<Operator> operators, String id) {
      for (int i = 0; i < operators.size(); i++) {
        if (operators.get(i).id().equals(id)) {
          return i;
        }
      }
      return -1;
    }

    private static String suffix(String path) {
      return path.isEmpty() ? "" : "-" + path;
    }

    private static String childPath(String parentPath, int index) {
      return parentPath.isEmpty() ? Integer.toString(index) : parentPath + "-" + index;
    }

    private static Clause append(Clause clause, Condition added) {
      List<Condition> next = new ArrayList<>(clause.conditions().size() + 1);
      next.addAll(clause.conditions());
      next.add(added);
      return new Clause(clause.join(), next, clause.enabled());
    }

    private static Clause replaceAt(Clause clause, int index, Condition updated) {
      List<Condition> next = new ArrayList<>(clause.conditions());
      next.set(index, updated);
      return new Clause(clause.join(), next, clause.enabled());
    }

    private static Clause removeAt(Clause clause, int index) {
      List<Condition> next = new ArrayList<>(clause.conditions());
      next.remove(index);
      return new Clause(clause.join(), next, clause.enabled());
    }
  }
}
