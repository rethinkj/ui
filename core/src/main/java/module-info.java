// No `requires`: the compiler rejects anything outside java.base.
module com.rethinkj.ui {
  exports com.rethinkj.ui;
  exports com.rethinkj.ui.layout;
  exports com.rethinkj.ui.motion;
  exports com.rethinkj.ui.render;
  exports com.rethinkj.ui.text;
  exports com.rethinkj.ui.window;
// Not exported: com.rethinkj.ui.natives, which loads the native libraries,
// com.rethinkj.ui.gpu, the bindings to wgpu-native, and com.rethinkj.ui.text.ffi, the bindings
// to FreeType and HarfBuzz.
}
