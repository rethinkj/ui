package com.rethinkj.ui.window;

/**
 * How the user set up the system to look: light or dark, the accent colour, and high contrast.
 * {@link Window#systemAppearance()} reads it, {@link WindowEvent.AppearanceChanged} says that it
 * changed, and {@link WindowLoop#appearance} hands it to an app. Nothing follows it by itself: an
 * app chooses its theme from it, or keeps its own.
 *
 * <p>High contrast, on Windows a setting of its own under Accessibility, asks for the system's few
 * colours instead of an app's: the text, window, highlight, link, disabled text and button
 * colours that {@code GetSysColor} gives, which the user chose for legibility. An app that honours
 * it draws every surface, text and edge in those colours, gives every control an edge, keeps
 * focus and selection visible by those colours rather than by shade, and leaves out shadows,
 * translucency and images behind text. The kit has no theme for that yet.
 *
 * @param scheme light or dark, as the system asks apps to be: on Windows the setting "Choose your
 *     app mode" ({@code AppsUseLightTheme}), on Linux the desktop portal's {@code color-scheme}
 * @param accent the system's accent colour as 0xFFRRGGBB, from which an app may build its theme's
 *     accent; 0 where the system has none or SDL does not say, as on Linux
 * @param highContrast whether high contrast is on; always false on Linux, where SDL does not say
 */
public record SystemAppearance(Scheme scheme, int accent, boolean highContrast) {

  /** Light or dark. */
  public enum Scheme {
    /** The system does not say, as a Linux desktop without the portal. */
    UNKNOWN,
    LIGHT,
    DARK
  }

  public SystemAppearance {
    java.util.Objects.requireNonNull(scheme, "scheme");
  }

  /** Whether the system asks for dark: false where it does not say. */
  public boolean dark() {
    return scheme == Scheme.DARK;
  }
}
