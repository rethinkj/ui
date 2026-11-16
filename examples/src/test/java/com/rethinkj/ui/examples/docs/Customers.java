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
import java.util.Objects;

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

  Theme theme = Theme.DARK;
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
  static El view(Customers state) {
    Theme theme = state.theme;
    El views = NavList.navList(
            theme,
            List.of(View.values()),
            state.view,
            view -> NavList.item(view.icon, view.title),
            state::show)
        .key("views")
        .label("Views")
        .grow(1);
    El root = row()
        .wFull()
        .hFull()
        .fill(theme.surface().window())
        .padding(theme.space().m())
        .gap(theme.space().m())
        .children(
            Card.flush(theme, Card.inset(theme, views)).w(180).shrink(0),
            switch (state.view) {
              case CUSTOMERS -> customers(theme, state);
              case NOTES -> notes(theme, state);
            });
    return root.overlay(state.editing == null ? null : dialog(theme, state))
        .overlay(
            state.toast == null
                ? null
                : Toast.toast(theme, state.toast, null, null, state::dismissToast));
  }

  void dismissToast() {
    toast = null;
  }

  static El notes(Theme theme, Customers state) {
    El area = TextField.textArea(theme, state.notes, "Notes for the team", state::setNotes)
        .fieldState(state.notesField) // the caret and undo come back with the view
        .key("notes")
        .wFull()
        .grow(1);
    return Card.card(theme, area).grow(1);
  }

  void setNotes(String value) {
    notes = value;
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
        this::loaded,
        this::notLoaded);
  }

  void loaded(List<Customer> list) { // back on the app's thread
    customers = new ArrayList<>(list);
    loading = null;
  }

  void notLoaded(Exception error) {
    loadError = error.getMessage();
    loading = null;
  }

  // docs:end customers-load

  // docs:start customers-list
  static El customers(Theme theme, Customers state) {
    List<Customer> rows = state.customers;
    int row = state.selection.row();
    Customer selected = rows != null && row >= 0 && row < rows.size() ? rows.get(row) : null;
    El reload = Button.button(theme, SECONDARY, Symbol.ARROW_CLOCKWISE, "Reload", state::load);
    El edit = Button.button(
        theme,
        SECONDARY,
        Symbol.EDIT,
        "Edit",
        selected == null ? null : () -> state.edit(selected));
    El add = Button.button(
        theme, PRIMARY, Symbol.ADD, "New customer", rows == null ? null : state::newCustomer);
    El body;
    if (rows == null && state.loadError != null) {
      body = EmptyState.emptyState(
          theme,
          Symbol.ERROR_CIRCLE,
          "The customers did not load",
          state.loadError,
          Button.button(theme, SECONDARY, "Try again", state::load));
    } else if (rows == null) {
      body = EmptyState.emptyState(theme, Symbol.PEOPLE, "Loading the customers…", null, null);
    } else if (rows.isEmpty()) {
      body = EmptyState.emptyState(
          theme, Symbol.PEOPLE, "No customers yet", "New customer adds the first.", null);
    } else {
      GridTable<Customer> table = DataGrid.table(rows::get);
      body = DataGrid.dataGrid(
              theme,
              List.of(
                  table.column("Name", 180, Format.TEXT, Customer.NAME),
                  table.column("Email", 200, Format.TEXT, Customer.EMAIL),
                  table.column("VAT ID", 120, Format.TEXT, Customer.VAT_ID)),
              rows.size(),
              DataGrid.options().select(state.selection)) // the state keeps selection and scroll
          .key("customers")
          .label("Customers")
          .grow(1);
    }
    String count = rows == null ? null : rows.size() + " customers";
    El loading = state.loading == null ? null : Progress.line(theme).label("Loading customers");
    return Card.flush(
            theme,
            Card.toolbar(theme, "Customers", count, reload, edit, add),
            loading,
            body.grow(1))
        .grow(1);
  }

  // docs:end customers-list

  // docs:start customers-edit
  /** A name: any text but a blank one, which is an error once edited or Save was pressed. */
  static final Format<String> NAME =
      Format.TEXT.where(name -> !name.isBlank(), "A name is required");

  /** Opens the dialog over a customer, or over {@link Customer#NEW} for a new one. */
  void edit(Customer customer) {
    editing = Editing.of(customer)
        .rule(
            Customer.VAT_ID, // a rule across two fields, shown at the VAT ID
            edited -> !edited.company() || !edited.vatId().isBlank(),
            "A company needs a VAT ID");
  }

  void newCustomer() {
    edit(Customer.NEW);
  }

  /** Closes the dialog, unless its save runs. */
  void close() {
    if (saving == null) {
      editing = null;
    }
  }

  static El dialog(Theme theme, Customers state) {
    Editing<Customer> editing = state.editing;
    El form = Form.form(
        theme,
        Form.Labels.BESIDE,
        Form.field(
                "Name",
                ValueField.valueField(theme, NAME, editing.at(Customer.NAME))
                    .key("name")
                    .w(240))
            .required(),
        Form.field(
            "Email",
            TextField.textField(theme, editing.at(Customer.EMAIL)).key("email").w(240)),
        Form.field(
            "Company",
            Checkbox.checkbox(theme, editing.at(Customer.COMPANY), null).key("company")),
        editing.value().company() // only for a company; a null field is left out
            ? Form.field(
                "VAT ID",
                TextField.textField(theme, editing.at(Customer.VAT_ID))
                    .key("vat")
                    .w(160))
            : null);
    boolean busy = state.saving != null;
    El save =
        Button.button(theme, PRIMARY, null, "Save", busy, editing.changed() ? state::save : null);
    El cancel = Button.button(theme, SECONDARY, "Cancel", busy ? null : state::close);
    String title = editing.original().id() == 0
        ? "New customer"
        : "Edit " + editing.original().name();
    return Dialog.dialog(theme, title, form, List.of(cancel, save), save, state::close);
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
        saved -> stored(customer, saved),
        error -> notSaved(customer, error));
  }

  /** The save of {@code customer} came back as {@code saved}. */
  void stored(Customer customer, Customer saved) {
    saving = null;
    int at = indexOf(saved.id());
    if (at >= 0) {
      customers.set(at, saved);
    } else {
      customers.add(saved);
    }
    Customer now = editing.value();
    if (now.equals(customer)) {
      editing = null; // closes the dialog
      toast = "Saved " + saved.name();
    } else { // edited while the save ran: keep those edits, now over the saved customer
      edit(saved);
      editing
          .root()
          .set(new Customer(saved.id(), now.name(), now.email(), now.company(), now.vatId()));
    }
  }

  void notSaved(Customer customer, Exception error) {
    saving = null;
    if (error instanceof Repository.Refused refused) {
      Part<Customer, ?> field = refused.field;
      if (Objects.equals(field.get(editing.value()), field.get(customer))) {
        editing.at(field).error(refused.getMessage()); // until the field is edited
      } // else the user already changed what the server refused
    } else {
      toast = "Not saved: " + error.getMessage(); // the dialog stays open to try again
    }
  }

  int indexOf(long id) {
    for (int index = 0; index < customers.size(); index++) {
      if (customers.get(index).id() == id) {
        return index;
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

    synchronized Customer save(Customer customer) throws Refused, InterruptedException {
      Thread.sleep(Duration.ofMillis(200));
      for (Customer other : rows) {
        if (other.id() != customer.id() && other.email().equalsIgnoreCase(customer.email())) {
          throw new Refused(Customer.EMAIL, other.name() + " has this email");
        }
      }
      if (customer.id() != 0) {
        rows.replaceAll(other -> other.id() == customer.id() ? customer : other); // keeps its place
        return customer;
      }
      Customer saved = new Customer(
          nextId++, customer.name(), customer.email(), customer.company(), customer.vatId());
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
