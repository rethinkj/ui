package com.rethinkj.ui.examples.docs;

import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;

import com.rethinkj.ui.El;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Checkbox;
import com.rethinkj.ui.kit.Editing;
import com.rethinkj.ui.kit.Form;
import com.rethinkj.ui.kit.Format;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.NumberField;
import com.rethinkj.ui.kit.Part;
import com.rethinkj.ui.kit.TextField;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Toggle;
import com.rethinkj.ui.kit.ValueField;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/** The examples of the page Editing records: a form over a record, and one over a POJO. */
final class Editings {

  // docs:start editing-record
  record Order(String customer, BigDecimal amount, LocalDate ordered, LocalDate due, boolean paid) {
    static final Part<Order, String> CUSTOMER = Part.of(Order::customer, Order::withCustomer);
    static final Part<Order, BigDecimal> AMOUNT = Part.of(Order::amount, Order::withAmount);
    static final Part<Order, LocalDate> DUE = Part.of(Order::due, Order::withDue);
    static final Part<Order, Boolean> PAID = Part.of(Order::paid, Order::withPaid);

    Order withCustomer(String customer) {
      return new Order(customer, amount, ordered, due, paid);
    }

    Order withAmount(BigDecimal amount) {
      return new Order(customer, amount, ordered, due, paid);
    }

    Order withDue(LocalDate due) {
      return new Order(customer, amount, ordered, due, paid);
    }

    Order withPaid(boolean paid) {
      return new Order(customer, amount, ordered, due, paid);
    }
  }

  // docs:end editing-record

  // docs:start editing-state
  final Editing<Order> editing = Editing.of(
          new Order("Ada", new BigDecimal("12.50"), LocalDate.of(2026, 11, 13), null, false))
      .rule(
          Order.DUE,
          o -> o.due() == null || !o.due().isBefore(o.ordered()),
          "Not before the order, 2026-11-13");
  String status = "";
  // docs:end editing-state

  static El record(Editings s) {
    Theme t = Theme.DARK;
    // docs:start editing-view
    Editing<Order> e = s.editing;
    El form = Form.form(
        t,
        Form.Labels.BESIDE,
        Form.field("Customer", TextField.textField(t, e.at(Order.CUSTOMER)).key("customer")),
        Form.field(
            "Amount",
            ValueField.valueField(t, Format.DECIMAL, e.at(Order.AMOUNT))
                .key("amount")
                .w(120)),
        Form.field(
            "Due",
            ValueField.valueField(t, Format.DATE.optional(), e.at(Order.DUE), "2026-11-30")
                .key("due")
                .w(120)),
        Form.field("Paid", Checkbox.checkbox(t, e.at(Order.PAID), null)));
    El save = Button.button(t, PRIMARY, "Save", e.valid() && e.changed() ? s::save : null);
    El revert = Button.button(t, SECONDARY, "Revert", e.changed() ? e::reset : null);
    // docs:end editing-view
    return Frame.of(
        t, form, Form.buttons(t, revert, save), Label.label(t, Label.Variant.MUTED, s.status));
  }

  // docs:start editing-save
  void save() {
    Order order = editing.value();
    if (order.amount().signum() < 0) { // as a server might answer
      editing.at(Order.AMOUNT).error("The server refused a negative amount");
      return;
    }
    status = "Saved " + order.customer() + ", " + order.amount();
    editing.saved(); // the saved order is the new original: Save waits for the next change
  }

  // docs:end editing-save

  // docs:start editing-pojo
  static final class Settings {
    static final Part<Settings, Integer> FETCH =
        Part.ofSetter(Settings::getFetchSize, Settings::setFetchSize);
    static final Part<Settings, Boolean> AUTO_COMMIT =
        Part.ofSetter(Settings::isAutoCommit, Settings::setAutoCommit);

    private int fetchSize = 100;
    private boolean autoCommit = true;

    Settings() {}

    Settings(Settings other) { // the copy the editing edits
      fetchSize = other.fetchSize;
      autoCommit = other.autoCommit;
    }

    int getFetchSize() {
      return fetchSize;
    }

    void setFetchSize(int fetchSize) {
      this.fetchSize = fetchSize;
    }

    boolean isAutoCommit() {
      return autoCommit;
    }

    void setAutoCommit(boolean autoCommit) {
      this.autoCommit = autoCommit;
    }

    @Override
    public boolean equals(Object o) { // what changed() compares by
      return o instanceof Settings s && s.fetchSize == fetchSize && s.autoCommit == autoCommit;
    }

    @Override
    public int hashCode() {
      return Objects.hash(fetchSize, autoCommit);
    }
  }

  Settings settings = new Settings(); // what the app uses
  final Editing<Settings> editingSettings = Editing.of(settings, Settings::new);

  // docs:end editing-pojo

  static El pojo(Editings s) {
    Theme t = Theme.DARK;
    // docs:start editing-pojo-view
    Editing<Settings> e = s.editingSettings;
    El form = Form.form(
        t,
        Form.Labels.BESIDE,
        Form.field("Fetch size", NumberField.numberField(t, e.at(Settings.FETCH), 10, 1000, 10)),
        Form.field("Commit", Toggle.toggle(t, e.at(Settings.AUTO_COMMIT), "Auto-commit")));
    El apply = Button.button(
        t,
        PRIMARY,
        "Apply",
        e.changed()
            ? () -> {
              s.settings = new Settings(e.value());
              e.saved();
            }
            : null);
    // docs:end editing-pojo-view
    return Frame.of(t, form, Form.buttons(t, apply));
  }
}
