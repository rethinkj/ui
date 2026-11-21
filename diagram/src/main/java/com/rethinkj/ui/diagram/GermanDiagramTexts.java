package com.rethinkj.ui.diagram;

/**
 * The German texts of the diagram module. An app keeps one in a constant, {@code static final
 * DiagramTexts GERMAN = new GermanDiagramTexts();}, and changes a few texts by overriding their
 * methods.
 */
public class GermanDiagramTexts implements DiagramTexts {

  @Override
  public String diagram() {
    return "Diagramm";
  }

  @Override
  public String zoom(int percent) {
    return "Zoom " + percent + " %";
  }

  @Override
  public String pinned() {
    return "Angeheftet";
  }

  @Override
  public String highlighted() {
    return "Hervorgehoben";
  }

  @Override
  public String dimmed() {
    return "Abgeblendet";
  }
}
