package com.rethinkj.ui.kit;

/**
 * The colours the default themes are made of, named for what they are: a hue and a step, as {@code
 * BLUE_496}. The step is the colour's CIELAB lightness L* times ten ({@link
 * com.rethinkj.ui.Colors#lightness}), from 0 for black to 1000 for white, so that two names say how
 * far apart two colours are: text and its background want about 40 steps for 4.5:1 near the middle
 * of the scale, and a dark theme's surfaces lie below 130, a light theme's above 900. What a colour
 * is for is named by the tokens of {@link Theme}, whose source shows which tokens share a colour
 * and which are made from another.
 *
 * <p>The greys are a little blue, as the themes' surfaces are; every colour is opaque, and the
 * themes write translucent ones as one of these at an alpha ({@link
 * com.rethinkj.ui.Colors#alpha(int, float)}). Black and white are {@link
 * com.rethinkj.ui.Colors#BLACK} and {@link com.rethinkj.ui.Colors#WHITE}.
 *
 * <p>Each hue has a fill at about step 490 that white text reads on at 4.5:1 and that stands out
 * 3:1 from a dark theme's surfaces (an {@link Avatar}'s fills, and the accents of {@link
 * Theme.Hue} in a dark theme), and a lighter tone at step 700 for lines and outlines on dark
 * surfaces (a {@link Theme.Hue}'s edge there).
 *
 * <p>The palette has no rules of its own: the rules are the themes', which {@code ThemeTest} holds
 * them to. This file is the only one in the kit with colour literals.
 */
public final class Palette {

  private Palette() {}

  // Grey
  public static final int GREY_047 = 0xFF0F1013;
  public static final int GREY_083 = 0xFF17181C;
  public static final int GREY_084 = 0xFF141824;
  public static final int GREY_099 = 0xFF1A1B20;
  public static final int GREY_108 = 0xFF1B1D22;
  public static final int GREY_122 = 0xFF1E2025;
  public static final int GREY_157 = 0xFF25272D;
  public static final int GREY_208 = 0xFF2E323D;
  public static final int GREY_226 = 0xFF33363D;
  public static final int GREY_298 = 0xFF45464C;
  public static final int GREY_311 = 0xFF464952;
  public static final int GREY_314 = 0xFF464A54;
  public static final int GREY_327 = 0xFF4A4D54;
  public static final int GREY_375 = 0xFF555863;
  public static final int GREY_386 = 0xFF575B63;
  public static final int GREY_395 = 0xFF5A5D67;
  public static final int GREY_407 = 0xFF5C606B;
  public static final int GREY_423 = 0xFF60646E;
  public static final int GREY_425 = 0xFF62646D;
  public static final int GREY_477 = 0xFF6E717B;
  public static final int GREY_501 = 0xFF747780;
  public static final int GREY_547 = 0xFF7F838C;
  public static final int GREY_568 = 0xFF858892;
  public static final int GREY_602 = 0xFF8E9199;
  public static final int GREY_648 = 0xFF9A9DA9;
  public static final int GREY_655 = 0xFF9C9FA8;
  public static final int GREY_700 = 0xFFA9ABB2;
  public static final int GREY_747 = 0xFFB4B8C0;
  public static final int GREY_759 = 0xFFB9BBC1;
  public static final int GREY_773 = 0xFFBDBFC5;
  public static final int GREY_805 = 0xFFC4C8CF;
  public static final int GREY_844 = 0xFFCFD3DA;
  public static final int GREY_853 = 0xFFD3D5DB;
  public static final int GREY_883 = 0xFFDADEE4;
  public static final int GREY_912 = 0xFFE3E6EB;
  public static final int GREY_916 = 0xFFE6E7EA;
  public static final int GREY_927 = 0xFFE8EAEF;
  public static final int GREY_968 = 0xFFF4F6F8;
  public static final int GREY_993 = 0xFFFDFDFE;

  // Blue
  public static final int BLUE_180 = 0xFF1C2B4D;
  public static final int BLUE_335 = 0xFF2048A8;
  public static final int BLUE_366 = 0xFF1F4FB8;
  public static final int BLUE_391 = 0xFF2755C0;
  public static final int BLUE_403 = 0xFF2557C9;
  public static final int BLUE_430 = 0xFF2F5ED0;
  public static final int BLUE_447 = 0xFF2E62D9;
  public static final int BLUE_469 = 0xFF3768DD;
  public static final int BLUE_490 = 0xFF3578B6;
  public static final int BLUE_496 = 0xFF3D6FE6;
  public static final int BLUE_694 = 0xFF86A8F8;
  public static final int BLUE_782 = 0xFFA9C1FA;
  public static final int BLUE_918 = 0xFFDFE8FB;

  // Violet
  public static final int VIOLET_362 = 0xFF6A28CF;
  public static final int VIOLET_478 = 0xFF765FD1;
  public static final int VIOLET_700 = 0xFFACA1F4;
  public static final int VIOLET_782 = 0xFFD2B4FF;

  // Purple
  public static final int PURPLE_490 = 0xFFAF45C9;
  public static final int PURPLE_702 = 0xFFD991EB;

  // Pink
  public static final int PINK_491 = 0xFFC84182;
  public static final int PINK_702 = 0xFFEF8EB5;

  // Red
  public static final int RED_345 = 0xFF9E1E14;
  public static final int RED_377 = 0xFFAC2116;
  public static final int RED_395 = 0xFFB42318;
  public static final int RED_436 = 0xFFC42B1C;
  public static final int RED_457 = 0xFFC6373A;
  public static final int RED_485 = 0xFFC8483B;
  public static final int RED_493 = 0xFFCA4949;
  public static final int RED_649 = 0xFFF2797A;
  public static final int RED_702 = 0xFFF0928C;
  public static final int RED_803 = 0xFFFAB6B6;

  // Orange
  public static final int ORANGE_380 = 0xFF95400C;
  public static final int ORANGE_488 = 0xFFA86331;
  public static final int ORANGE_702 = 0xFFD4A17F;
  public static final int ORANGE_806 = 0xFFF5BD8C;

  // Amber
  public static final int AMBER_323 = 0xFF5C4A12;
  public static final int AMBER_327 = 0xFF694600;
  public static final int AMBER_384 = 0xFF7A5300;
  public static final int AMBER_467 = 0xFF8A6A1F;
  public static final int AMBER_491 = 0xFF887328;
  public static final int AMBER_622 = 0xFFC98A00;
  public static final int AMBER_701 = 0xFFB9AB7A;
  public static final int AMBER_720 = 0xFFE8A33A;
  public static final int AMBER_735 = 0xFFE9A92F;
  public static final int AMBER_745 = 0xFFE0B04C;
  public static final int AMBER_768 = 0xFFF2B300;
  public static final int AMBER_835 = 0xFFF0CC7A;

  // Green
  public static final int GREEN_333 = 0xFF135A31;
  public static final int GREEN_378 = 0xFF1A6633;
  public static final int GREEN_389 = 0xFF17693A;
  public static final int GREEN_452 = 0xFF1F7A47;
  public static final int GREEN_492 = 0xFF278636;
  public static final int GREEN_702 = 0xFF7DBA81;
  public static final int GREEN_735 = 0xFF5CC98A;
  public static final int GREEN_823 = 0xFFA6DB94;
  public static final int GREEN_826 = 0xFF86E0AB;

  // Teal
  public static final int TEAL_490 = 0xFF268272;
  public static final int TEAL_700 = 0xFF7EB6A9;

  // Cyan
  public static final int CYAN_363 = 0xFF0A5D75;
  public static final int CYAN_843 = 0xFF9ADCF5;
}
