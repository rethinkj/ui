// No `requires`: the compiler rejects anything outside java.base.
module com.rethinkj.ui {
  exports com.rethinkj.ui;
  exports com.rethinkj.ui.layout;
  exports com.rethinkj.ui.motion;
  exports com.rethinkj.ui.render;
  // Not exported: com.rethinkj.ui.natives, which loads the native libraries, and
  // com.rethinkj.ui.gpu, the bindings to wgpu-native.
}
