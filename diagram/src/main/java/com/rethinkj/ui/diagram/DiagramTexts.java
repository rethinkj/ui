package com.rethinkj.ui.diagram;

import com.rethinkj.ui.Context;

/**
 * The texts the diagram's components write themselves, in one language, as the kit's {@link
 * com.rethinkj.ui.kit.Texts} are for its components. Components read the texts of {@link
 * #CURRENT}, {@link #ENGLISH} unless the app gives others: {@code app.provide(DiagramTexts.CURRENT,
 * Settings::diagramTexts)}. The module comes with {@link #ENGLISH} and {@link GermanDiagramTexts};
 * another language is an implementation of this interface, and a single text an override of its
 * method. A method's default is the English text.
 */
public interface DiagramTexts {

  /** The English texts, the module's default. */
  DiagramTexts ENGLISH = new DiagramTexts() {};

  /** The texts the diagram's components read, {@link #ENGLISH} unless the app gives others. */
  Context<DiagramTexts> CURRENT = new Context<>(ENGLISH);

  /** The label of a {@link Canvas} that the app gives none. */
  default String diagram() {
    return "Diagram";
  }

  /** A canvas's zoom, as its value in a tree dump: {@code Zoom 100%}. */
  default String zoom(int percent) {
    return "Zoom " + percent + "%";
  }

  /** The value of a node that the user moved, which a layout leaves where it is. */
  default String pinned() {
    return "Pinned";
  }

  /** The value of an edge that stands out, {@link Canvas.Emphasis#HIGHLIGHTED}. */
  default String highlighted() {
    return "Highlighted";
  }

  /** The value of an edge that steps back, {@link Canvas.Emphasis#DIMMED}. */
  default String dimmed() {
    return "Dimmed";
  }
}
