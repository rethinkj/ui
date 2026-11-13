package com.rethinkj.ui.examples;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.text.TextRendering;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Every example's own title bar shows Restore in place of Maximize while its window is. */
class TitleBarsTest {

  private static Map<String, App<?>> apps() {
    LightSwitch lights = new LightSwitch();
    lights.app = new App<>(lights, LightSwitch::view);
    ConnectionDialog dialog = new ConnectionDialog();
    dialog.app = new App<>(dialog, ConnectionDialog::view);
    Settings settings = new Settings();
    settings.app = new App<>(settings, Settings::view);
    DataBrowser browser = new DataBrowser();
    browser.app = new App<>(browser, DataBrowser::view);
    Manual manual = new Manual(List.of());
    manual.app = new App<>(manual, Manual::view);
    Workbench workbench = new Workbench();
    workbench.app = new App<>(workbench, Workbench::view);
    Documents documents = new Documents();
    documents.app = new App<>(documents, d -> d.view(d.groups.main(), d.app));
    return Map.of(
        "LightSwitch",
        lights.app,
        "ConnectionDialog",
        dialog.app,
        "Settings",
        settings.app,
        "DataBrowser",
        browser.app,
        "Manual",
        manual.app,
        "Showcase",
        Showcase.app(Showcase.Look.DARK),
        "Workbench",
        workbench.app,
        "Documents",
        documents.app);
  }

  @Test
  void theMaximizeButtonSaysRestoreWhileTheWindowIsMaximized() {
    Snaps.assumeText();
    apps().forEach((name, app) -> {
      app.textRendering(TextRendering.LINUX);
      app.resize(1100, 720, 1);
      app.frame();
      String tree = app.render().tree();
      assertTrue(tree.contains("role=button label=\"Maximize\""), name + ":\n" + tree);
      app.windowMaximized(true);
      app.frame();
      tree = app.render().tree();
      assertTrue(tree.contains("role=button label=\"Restore\""), name + ":\n" + tree);
      app.windowMaximized(false);
      app.frame();
      assertTrue(app.render().tree().contains("role=button label=\"Maximize\""), name);
    });
  }
}
