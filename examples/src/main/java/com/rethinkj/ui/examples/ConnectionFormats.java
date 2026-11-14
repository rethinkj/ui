package com.rethinkj.ui.examples;

import com.rethinkj.ui.kit.Format;

/** Value formats shared by the connection forms. */
final class ConnectionFormats {

  static final Format<Integer> PORT =
      Format.INTEGER.where(port -> port >= 1 && port <= 65535, "1 to 65535");

  private ConnectionFormats() {}
}
