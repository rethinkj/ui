package com.rethinkj.ui.gpu;

/**
 * A colour as wgpu takes it for clearing: four components, usually from 0 to 1. For a render
 * target with an sRGB format these are linear values.
 */
public record Color(double r, double g, double b, double a) {

  public static final Color TRANSPARENT = new Color(0, 0, 0, 0);
  public static final Color BLACK = new Color(0, 0, 0, 1);
}
