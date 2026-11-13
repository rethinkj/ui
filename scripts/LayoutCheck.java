import java.lang.foreign.AddressLayout;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.PaddingLayout;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Compares the handwritten wgpu-native bindings in core's {@code com.rethinkj.ui.gpu} with what
 * jextract derives from the headers on this platform: every struct layout in {@code
 * Structs.ALL} (size, alignment, and the name, offset, size and type of every member), and the
 * descriptor of every function in {@code Wgpu.FUNCTIONS} and every callback in {@code
 * Wgpu.CALLBACKS}. It prints one line each and exits with status 1 if anything differs that is
 * not listed in {@link #KNOWN}.
 *
 * <p>A developer tool, not part of the build: jextract and its output stay out of the repository.
 * From the repository root, in a POSIX shell (Git Bash on Windows), with jextract 25 from
 * jdk.java.net/jextract unpacked at {@code $JEXTRACT}:
 *
 * <pre>
 * mvn -q -pl core -am compile
 * J=target/layout-check
 * mkdir -p $J
 * # The headers and the library of the release the natives module packages. On Linux, the same
 * # version's wgpu-linux-x86_64-release.zip from github.com/gfx-rs/wgpu-native/releases.
 * A=$PWD/natives-windows-x64/fetched/archives/wgpu-windows-x86_64-msvc-release.zip
 * (cd $J &amp;&amp; jar xf $A include lib)
 * $JEXTRACT/bin/jextract --dump-includes $J/includes.txt -I $J/include/webgpu \
 *     $J/include/webgpu/wgpu.h
 * grep -E '^--include-[a-z]+ +(wgpu|WGPU)' $J/includes.txt | grep -v WGPUProc &gt; $J/wgpu.txt
 * $JEXTRACT/bin/jextract --output $J/src --target-package jextract.wgpu --header-class-name Wgpu \
 *     -l wgpu_native --use-system-load-library -I $J/include/webgpu @$J/wgpu.txt \
 *     $J/include/webgpu/wgpu.h
 * javac -d $J/classes $(find $J/src -name '*.java')
 * # Windows; on Linux the class path separator is a colon.
 * java --enable-native-access=ALL-UNNAMED -Djava.library.path=$J/lib \
 *     -cp "core/target/classes;$J/classes" scripts/LayoutCheck.java
 * </pre>
 *
 * <p>jextract's descriptor of a function sits in a holder that also looks up the symbol, so the
 * check loads the library, though it calls nothing in it.
 *
 * <p>The bindings' fields are package-private; this tool reads them by reflection, which works
 * because core's classes are on the class path here, not the module path.
 */
public final class LayoutCheck {

  private LayoutCheck() {}

  private static final String BINDINGS = "com.rethinkj.ui.gpu.";
  private static final String JEXTRACT = "jextract.wgpu.";

  /** One handwritten layout stands for the five callback info structs that have a mode. */
  private static final List<String> CALLBACK_INFOS = List.of(
      "WGPURequestAdapterCallbackInfo",
      "WGPURequestDeviceCallbackInfo",
      "WGPUPopErrorScopeCallbackInfo",
      "WGPUDeviceLostCallbackInfo",
      "WGPUBufferMapCallbackInfo");

  /** Where the bindings differ from the header on purpose, and why. */
  private static final Map<String, String> KNOWN = Map.of(
      "wgpuDevicePoll", "returns a one-byte Rust bool, though wgpu.h declares a uint32_t WGPUBool");

  public static void main(String[] args) throws ReflectiveOperationException {
    List<StructLayout> layouts = field("Structs", "ALL");
    Map<String, FunctionDescriptor> functions = field("Wgpu", "FUNCTIONS");
    Map<String, FunctionDescriptor> callbacks = field("Wgpu", "CALLBACKS");
    Class<?> header = Class.forName(JEXTRACT + "Wgpu");

    int compared = 0;
    int different = 0;
    int known = 0;
    for (StructLayout written : layouts) {
      String name = written.name().orElseThrow();
      for (String target : name.equals("WGPU*CallbackInfo") ? CALLBACK_INFOS : List.of(name)) {
        GroupLayout generated =
            (GroupLayout) Class.forName(JEXTRACT + target).getMethod("layout").invoke(null);
        compared++;
        List<String> mine = members(written);
        List<String> theirs = members(generated);
        boolean same = written.byteSize() == generated.byteSize()
            && written.byteAlignment() == generated.byteAlignment()
            && mine.equals(theirs);
        System.out.printf(
            "struct   %-38s %3d bytes, %2d members  %s%n",
            target, written.byteSize(), mine.size(), same ? "same" : "DIFFERENT");
        if (!same) {
          different++;
          System.out.printf(
              "    handwritten: %d bytes, align %d, %s%n",
              written.byteSize(), written.byteAlignment(), mine);
          System.out.printf(
              "    jextract:    %d bytes, align %d, %s%n",
              generated.byteSize(), generated.byteAlignment(), theirs);
        }
      }
    }
    for (Map.Entry<String, FunctionDescriptor> function : functions.entrySet()) {
      FunctionDescriptor generated = (FunctionDescriptor)
          header.getMethod(function.getKey() + "$descriptor").invoke(null);
      int outcome = compare("function", function.getKey(), function.getValue(), generated);
      compared++;
      different += outcome == 1 ? 1 : 0;
      known += outcome == 2 ? 1 : 0;
    }
    for (Map.Entry<String, FunctionDescriptor> callback : callbacks.entrySet()) {
      FunctionDescriptor generated = (FunctionDescriptor)
          Class.forName(JEXTRACT + callback.getKey()).getMethod("descriptor").invoke(null);
      int outcome = compare("callback", callback.getKey(), callback.getValue(), generated);
      compared++;
      different += outcome == 1 ? 1 : 0;
      known += outcome == 2 ? 1 : 0;
    }
    System.out.printf(
        "%d compared: %d struct layouts, %d functions, %d callbacks; %d different,"
            + " %d known differences%n",
        compared,
        compared - functions.size() - callbacks.size(),
        functions.size(),
        callbacks.size(),
        different,
        known);
    if (different > 0) {
      System.exit(1);
    }
  }

  /** 0 if the same, 1 if different, 2 if different as {@link #KNOWN} says. */
  private static int compare(
      String what, String name, FunctionDescriptor written, FunctionDescriptor generated) {
    String mine = shape(written);
    String theirs = shape(generated);
    if (mine.equals(theirs)) {
      System.out.printf("%-8s %-38s same%n", what, name);
      return 0;
    }
    String reason = KNOWN.get(name);
    System.out.printf(
        "%-8s %-38s %s%n",
        what, name, reason == null ? "DIFFERENT" : "different as intended: " + reason);
    System.out.printf("    handwritten: %s%n    jextract:    %s%n", mine, theirs);
    return reason == null ? 1 : 2;
  }

  /** "(int, ptr, WGPUStringView{16/8}) -> void". */
  private static String shape(FunctionDescriptor descriptor) {
    return descriptor.argumentLayouts().stream()
            .map(LayoutCheck::type)
            .collect(Collectors.joining(", ", "(", ")"))
        + " -> " + descriptor.returnLayout().map(LayoutCheck::type).orElse("void");
  }

  private static String type(MemoryLayout layout) {
    return switch (layout) {
      case AddressLayout address -> "ptr";
      case ValueLayout value -> value.carrier().getSimpleName() + value.byteSize();
      case GroupLayout group ->
        structName(group) + "{" + group.byteSize() + "/" + group.byteAlignment() + " "
            + members(group) + "}";
      default -> layout.toString();
    };
  }

  /** The five callback info structs with a mode share one handwritten layout. */
  private static String structName(GroupLayout group) {
    String name = group.name().orElse("?");
    return CALLBACK_INFOS.contains(name) ? "WGPU*CallbackInfo" : name;
  }

  /** "path@offset+size:type" for every member that is not padding; unions are one member. */
  private static List<String> members(GroupLayout layout) {
    List<String> out = new ArrayList<>();
    collect(layout, "", 0, out);
    return out;
  }

  private static void collect(GroupLayout layout, String prefix, long base, List<String> out) {
    long offset = base;
    for (MemoryLayout member : layout.memberLayouts()) {
      if (!(member instanceof PaddingLayout)) {
        String path = prefix + member.name().orElse("?");
        if (member instanceof StructLayout struct) {
          collect(struct, path + ".", offset, out);
        } else {
          String type = member instanceof ValueLayout
              ? type(member)
              : member instanceof java.lang.foreign.UnionLayout ? "union" : member.toString();
          out.add(path + "@" + offset + "+" + member.byteSize() + ":" + type);
        }
      }
      offset += member.byteSize();
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T field(String className, String name) throws ReflectiveOperationException {
    Field field = Class.forName(BINDINGS + className).getDeclaredField(name);
    field.setAccessible(true);
    return (T) field.get(null);
  }
}
