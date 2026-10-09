package guard.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.Map;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;

/** JDK-21-specific entry probes, with no control-flow or schema changes. */
final class Transformer implements ClassFileTransformer, Opcodes {
    private static final String HELPER = "guard/bootstrap/OfflineGuard";
    private final Map<String, Integer> installed = new HashMap<>();
    private Throwable failure;

    @Override public byte[] transform(Module module, ClassLoader loader, String name, Class<?> redefined,
            ProtectionDomain domain, byte[] bytes) {
        int required = switch (name) {
            case "java/net/URL" -> 2;
            case "sun/net/www/protocol/http/HttpURLConnection" -> 3;
            case "sun/net/www/http/HttpClient" -> 2;
            default -> 0;
        };
        if (required == 0) return null;
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            int[] injected = {0};
            reader.accept(new ClassVisitor(ASM8, writer) {
                @Override public MethodVisitor visitMethod(int access, String method, String descriptor,
                        String signature, String[] exceptions) {
                    MethodVisitor delegate = super.visitMethod(access, method, descriptor, signature, exceptions);
                    int kind = 0;
                    if (name.equals("java/net/URL") && method.equals("openConnection")) {
                        if (descriptor.equals("()Ljava/net/URLConnection;")) kind = 1;
                        if (descriptor.equals("(Ljava/net/Proxy;)Ljava/net/URLConnection;")) kind = 2;
                    } else if (name.equals("sun/net/www/protocol/http/HttpURLConnection")) {
                        if (method.equals("plainConnect") && descriptor.equals("()V")) kind = 3;
                        if (method.equals("URLtoSocketPermission") && descriptor.equals("(Ljava/net/URL;)Ljava/net/SocketPermission;")) kind = 4;
                        if (method.equals("followRedirect0") && descriptor.equals("(Ljava/lang/String;ILjava/net/URL;)Z")) kind = 5;
                    } else if (name.equals("sun/net/www/http/HttpClient") && method.equals("openServer")) {
                        if (descriptor.equals("()V")) kind = 6;
                        if (descriptor.equals("(Ljava/lang/String;I)V")) kind = 7;
                    }
                    if (kind == 0) return delegate;
                    injected[0]++;
                    int hook = kind;
                    return new MethodVisitor(ASM8, delegate) {
                        @Override public void visitCode() {
                            super.visitCode();
                            switch (hook) {
                                case 1 -> { visitVarInsn(ALOAD, 0); url(); }
                                case 2 -> { visitVarInsn(ALOAD, 0); visitVarInsn(ALOAD, 1); urlProxy(); }
                                case 3 -> { field("sun/net/www/protocol/http/HttpURLConnection", "url", "Ljava/net/URL;"); url(); }
                                case 4 -> { visitVarInsn(ALOAD, 1); url(); }
                                case 5 -> { visitVarInsn(ALOAD, 3); url(); }
                                case 6 -> {
                                    field("sun/net/www/http/HttpClient", "url", "Ljava/net/URL;");
                                    field("sun/net/www/http/HttpClient", "proxy", "Ljava/net/Proxy;");
                                    urlProxy();
                                }
                                case 7 -> {
                                    field("sun/net/www/http/HttpClient", "url", "Ljava/net/URL;");
                                    visitVarInsn(ALOAD, 1);
                                    field("sun/net/www/http/HttpClient", "proxy", "Ljava/net/Proxy;");
                                    visitMethodInsn(INVOKESTATIC, HELPER, "checkTransport", "(Ljava/net/URL;Ljava/lang/String;Ljava/net/Proxy;)V", false);
                                }
                                default -> throw new AssertionError();
                            }
                        }
                        private void field(String owner, String field, String type) {
                            visitVarInsn(ALOAD, 0); visitFieldInsn(GETFIELD, owner, field, type);
                        }
                        private void url() { visitMethodInsn(INVOKESTATIC, HELPER, "checkUrl", "(Ljava/net/URL;)V", false); }
                        private void urlProxy() { visitMethodInsn(INVOKESTATIC, HELPER, "checkUrlProxy", "(Ljava/net/URL;Ljava/net/Proxy;)V", false); }
                    };
                }
            }, 0);
            if (injected[0] != required) throw new IllegalStateException("Offline guard unexpected JDK method shape");
            installed.put(name, injected[0]);
            return writer.toByteArray();
        } catch (Throwable error) {
            failure = error;
            // Instrumentation ignores transform exceptions. Premain requireComplete turns this fatal.
            return null;
        }
    }

    void requireComplete() {
        if (failure != null) throw new IllegalStateException("Offline guard transformation failed", failure);
        if (installed.size() != 3 || installed.values().stream().mapToInt(Integer::intValue).sum() != 7)
            throw new IllegalStateException("Offline guard installation incomplete");
    }
}
