package com.rethinkj.ui.examples.docs;

import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.FieldState;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Work;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.Checkbox;
import com.rethinkj.ui.kit.DataGrid;
import com.rethinkj.ui.kit.Dialog;
import com.rethinkj.ui.kit.Editing;
import com.rethinkj.ui.kit.EmptyState;
import com.rethinkj.ui.kit.Form;
import com.rethinkj.ui.kit.Format;
import com.rethinkj.ui.kit.GridTable;
import com.rethinkj.ui.kit.NavList;
import com.rethinkj.ui.kit.Part;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.TextField;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Toast;
import com.rethinkj.ui.kit.ValueField;
import com.rethinkj.ui.render.GpuPresenter;
import com.rethinkj.ui.window.Window;
import com.rethinkj.ui.window.WindowLoop;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The app of the guide that builds a whole app: customers in a grid, loaded off the app's thread,
 * edited in a dialog and saved to a made-up server, and notes in a second view.
 */
public final class Customers {

  // docs:start customers-record
  record Customer(long id, String name, String email, boolean company, String vatId) {
    static final Part<Customer, String> NAME = Part.of(Customer::name, Customer::withName);
    static final Part<Customer, String> EMAIL = Part.of(Customer::email, Customer::withEmail);
    static final Part<Customer, Boolean> COMPANY =
        Part.of(Customer::company, Customer::withCompany);
    static final Part<Customer, String> VAT_ID = Part.of(Customer::vatId, Customer::withVatId);

    static final Customer NEW = new Customer(0, "", "", false, ""); // id 0: not saved yet

    Customer withName(String name) {
      return new Customer(id, name, email, company, vatId);
    }

    Customer withEmail(String email) {
      return new Customer(id, name, email, company, vatId);
    }

    Customer withCompany(boolean company) {
      return new Customer(id, name, email, company, vatId);
    }

    Customer withVatId(String vatId) {
      return new Customer(id, name, email, company, vatId);
    }
  }

  // docs:end customers-record

  // docs:start customers-state
  enum View {
    CUSTOMERS(Symbol.PEOPLE, "Customers"),
    NOTES(Symbol.DOCUMENT, "Notes");

    final Symbol icon;
    final String title;

    View(Symbol icon, String title) {
      this.icon = icon;
      this.title = title;
    }
  }

  Theme t = Theme.DARK;
  App<Customers> app; // set by app(), which makes it
  final Repository repository = new Repository();
  View view = View.CUSTOMERS;

  // The customers view
  List<Customer> customers; // null until the first load has arrived
  String loadError; // why the last load failed, null if it did not
  Work loading; // the running load, null for none
  final DataGrid.Selection selection = new DataGrid.Selection(DataGrid.Mode.ROWS); // and scroll

  // The notes view
  String notes = "";
  final FieldState notesField = new FieldState(); // caret, selection and undo

  // The dialog and the toast
  Editing<Customer> editing; // the open dialog's edit, null while none is open
  Work saving; // the running save, null for none
  String toast; // the toast's message, null for none

  // docs:end customers-state

  // docs:start customers-view
  static El view(Customers s) {
    Theme t = s.t;
    El views = NavList.navList(
            t, List.of(View.values()), s.view, v -> NavList.item(v.icon, v.title), s::show)
        .key("views")
        .label("Views")
        .grow(1);
    El root = row()
        .wFull()
        .hFull()
        .fill(t.surface().window())
        .padding(t.space().m())
        .gap(t.space().m())
        .children(
            Card.flush(t, Card.inset(t, views)).w(180).shrink(0),
            switch (s.view) {
              case CUSTOMERS -> customers(t, s);
              case NOTES -> notes(t, s);
            });
    return root.overlay(s.editing == null ? null : dialog(t, s))
        .overlay(
            s.toast == null ? null : Toast.toast(t, s.toast, null, null, () -> s.toast = null));
  }

  static El notes(Theme t, Customers s) {
    El area = TextField.textArea(t, s.notes, "Notes for the team", v -> s.notes = v)
        .fieldState(s.notesField) // the caret and undo come back with the view
        .key("notes")
        .wFull()
        .grow(1);
    return Card.card(t, area).grow(1);
  }

  // docs:end customers-view

  // docs:start customers-load
  /** Shows another view; leaving the customers cancels their load, coming back starts one. */
  void show(View next) {
    view = next;
    if (next != View.CUSTOMERS && loading != null) {
      loading.cancel(); // nobody waits for the list now: neither handler runs
      loading = null;
    }
    if (next == View.CUSTOMERS && customers == null && loading == null) {
      load();
    }
  }

  /** Loads the list again; a load that still runs is cancelled. The old list shows meanwhile. */
  void load() {
    if (loading != null) {
      loading.cancel();
    }
    Repository repository = this.repository; // only what the work needs, never the state
    loadError = null;
    loading = app.background(
        "load customers",
        repository::list, // on a virtual thread
        list -> { // back on the app's thread
          customers = new ArrayList<>(list);
          loading = null;
        },
        e -> {
          loadError = e.getMessage();
          loading = null;
        });
  }

  // docs:end customers-load

  // docs:start customers-list
  static El customers(Theme t, Customers s) {
    List<Customer> rows = s.customers;
    int row = s.selection.row();
    Customer selected = rows != null && row >= 0 && row < rows.size() ? rows.get(row) : null;
    El reload = Button.button(t, SECONDARY, Symbol.ARROW_CLOCKWISE, "Reload", s::load);
    El edit = Button.button(
        t, SECONDARY, Symbol.EDIT, "Edit", selected == null ? null : () -> s.edit(selected));
    El add = Button.button(
        t, PRIMARY, Symbol.ADD, "New customer", rows == null ? null : () -> s.edit(Customer.NEW));
    El body;
    if (rows == null && s.loadError != null) {
      body = EmptyState.emptyState(
          t,
          Symbol.ERROR_CIRCLE,
          "The customers did not load",
          s.loadError,
          Button.button(t, SECONDARY, "Try again", s::load));
    } else if (rows == null) {
      body = EmptyState.emptyState(t, Symbol.PEOPLE, "Loading the customers…", null, null);
    } else if (rows.isEmpty()) {
      body = EmptyState.emptyState(
          t, Symbol.PEOPLE, "No customers yet", "New customer adds the first.", null);
    } else {
      GridTable<Customer> table = DataGrid.table(rows::get);
      body = DataGrid.dataGrid(
              t,
              List.of(
                  table.column("Name", 180, Format.TEXT, Customer.NAME),
                  table.column("Email", 200, Format.TEXT, Customer.EMAIL),
                  table.column("VAT ID", 120, Format.TEXT, Customer.VAT_ID)),
              rows.size(),
              DataGrid.options().select(s.selection)) // the state keeps selection and scroll
          .key("customers")
          .label("Customers")
          .grow(1);
    }
    String count = rows == null ? null : rows.size() + " customers";
    El loading = s.loading == null ? null : Progress.line(t).label("Loading customers");
    return Card.flush(
            t, Card.toolbar(t, "Customers", count, reload, edit, add), loading, body.grow(1))
        .grow(1);
  }

  // docs:end customers-list

  // docs:start customers-edit
  /** A name: any text but a blank one, which is an error once edited or Save was pressed. */
  static final Format<String> NAME = Format.TEXT.where(n -> !n.isBlank(), "A name is required");

  /** Opens the dialog over a customer, or over {@link Customer#NEW} for a new one. */
  void edit(Customer customer) {
    editing = Editing.of(customer)
        .rule(
            Customer.VAT_ID, // a rule across two fields, shown at the VAT ID
            c -> !c.company() || !c.vatId().isBlank(),
            "A company needs a VAT ID");
  }

  /** Closes the dialog, unless its save runs. */
  void close() {
    if (saving == null) {
      editing = null;
    }
  }

  static El dialog(Theme t, Customers s) {
    Editing<Customer> e = s.editing;
    El form = Form.form(
        t,
        Form.Labels.BESIDE,
        Form.field(
                "Name",
                ValueField.valueField(t, NAME, e.at(Customer.NAME)).key("name").w(240))
            .required(),
        Form.field(
            "Email", TextField.textField(t, e.at(Customer.EMAIL)).key("email").w(240)),
        Form.field("Company", Checkbox.checkbox(t, e.at(Customer.COMPANY), null).key("company")),
        e.value().company() // only for a company; a null field is left out
            ? Form.field(
                "VAT ID",
                TextField.textField(t, e.at(Customer.VAT_ID)).key("vat").w(160))
            : null);
    boolean busy = s.saving != null;
    El save = Button.button(t, PRIMARY, null, "Save", busy, e.changed() ? s::save : null);
    El cancel = Button.button(t, SECONDARY, "Cancel", busy ? null : s::close);
    String title =
        e.original().id() == 0 ? "New customer" : "Edit " + e.original().name();
    return Dialog.dialog(t, title, form, List.of(cancel, save), save, s::close);
  }

  // docs:end customers-edit

  // docs:start customers-save
  void save() {
    if (!editing.valid()) {
      editing.showErrors(); // also at the fields not edited yet, such as an empty name
      return;
    }
    Customer customer = editing.value();
    Repository repository = this.repository;
    saving = app.background(
        "save " + customer.name(),
        () -> repository.save(customer),
        saved -> {
          saving = null;
          int at = indexOf(saved.id());
          if (at >= 0) {
            customers.set(at, saved);
          } else {
            customers.add(saved);
          }
          editing = null; // closes the dialog
          toast = "Saved " + saved.name();
        },
        e -> {
          saving = null;
          if (e instanceof Repository.Refused refused) {
            editing.at(refused.field).error(refused.getMessage()); // until the field is edited
          } else {
            toast = "Not saved: " + e.getMessage(); // the dialog stays open to try again
          }
        });
  }

  int indexOf(long id) {
    for (int i = 0; i < customers.size(); i++) {
      if (customers.get(i).id() == id) {
        return i;
      }
    }
    return -1;
  }

  // docs:end customers-save

  // docs:start customers-repository
  /**
   * A made-up server. Each call takes a fifth of a second, the first list fails, and a save is
   * refused for an email another customer has. Its methods run on the work's thread.
   */
  static final class Repository {

    /** The server refused the value of one field. */
    static final class Refused extends Exception {
      final Part<Customer, ?> field;

      Refused(Part<Customer, ?> field, String message) {
        super(message);
        this.field = field;
      }
    }

    private final List<Customer> rows = new ArrayList<>(List.of(
        new Customer(1, "Ada Lovelace", "ada@example.com", false, ""),
        new Customer(2, "Analytical Engines Ltd", "orders@engines.example", true, "GB123456789"),
        new Customer(3, "Grace Hopper", "grace@example.com", false, ""),
        new Customer(4, "Alan Turing", "alan@example.com", false, ""),
        new Customer(5, "Hopper Compilers GmbH", "info@compilers.example", true, "DE987654321")));
    private long nextId = 6;
    private boolean answered;

    synchronized List<Customer> list() throws IOException, InterruptedException {
      Thread.sleep(Duration.ofMillis(200)); // cancel() interrupts the wait
      if (!answered) {
        answered = true;
        throw new IOException("The server did not answer in time.");
      }
      return List.copyOf(rows);
    }

    synchronized Customer save(Customer c) throws Refused, InterruptedException {
      Thread.sleep(Duration.ofMillis(200));
      for (Customer other : rows) {
        if (other.id() != c.id() && other.email().equalsIgnoreCase(c.email())) {
          throw new Refused(Customer.EMAIL, other.name() + " has this email");
        }
      }
      Customer saved =
          c.id() == 0 ? new Customer(nextId++, c.name(), c.email(), c.company(), c.vatId()) : c;
      rows.removeIf(other -> other.id() == saved.id());
      rows.add(saved);
      return saved;
    }
  }

  // docs:end customers-repository

  // docs:start customers-main
  static App<Customers> app() {
    Customers state = new Customers();
    App<Customers> app = new App<>(state, Customers::view);
    state.app = app; // the handlers start work with it
    state.load(); // the first load starts with the app
    return app;
  }

  public static void main(String[] args) throws Exception {
    App<Customers> app = app();
    if (Script.handle(args, app)) {
      return; // --script <file> --out <folder>: no window, images and trees instead
    }
    try (Window window = Window.open("Customers", 760, 460, Window.options());
        GpuPresenter presenter = new GpuPresenter(window)) {
      WindowLoop.run(window, app, presenter);
    }
  }
  // docs:end customers-main
}
