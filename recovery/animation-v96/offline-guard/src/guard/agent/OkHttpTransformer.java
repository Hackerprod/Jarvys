package guard.agent;

import guard.bootstrap.OfflineGuard;
import java.lang.instrument.ClassFileTransformer;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import java.util.Map;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.Type;

/** Reviewed OkHttp 5.2.3 JVM/Android class bytes only. Runs for each actual defining loader. */
final class OkHttpTransformer implements ClassFileTransformer, Opcodes {
    private static final String HELPER = "guard/bootstrap/OfflineGuard";
    private static final Map<String, String> HASHES = Map.of(
        "okhttp3/internal/connection/RealCall", "0176c14ab01bf0c9298f04a80deaacbba58c97b9218685079864ed90c8376bda",
        "okhttp3/internal/http/RealInterceptorChain", "018f61e04026e443e8895fd285a0e9023feb36cb3154d7d402cad834a8ec652a",
        "okhttp3/internal/connection/RouteSelector", "ab2735d376c66f03b47aca78fa0df88060a166f97e307fe034bae7d6c2b72fee",
        "okhttp3/internal/connection/ConnectPlan", "ceb883ef2f0ce8590bb1711dace629ac77cfebd9b9f241c595e749677b5d2723");

    @Override public byte[] transform(Module module, ClassLoader loader, String name, Class<?> redefined,
            ProtectionDomain domain, byte[] bytes) {
        if (name == null) return null;
        String expected = HASHES.get(name);
        if (expected == null) return null;
        try {
            if (loader == null || (module != null && module.isNamed()))
                throw new IllegalStateException("Offline guard requires classpath OkHttp");
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!expected.equals(actual)) throw new IllegalStateException("Offline guard unsupported OkHttp bytes");
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            int[] injected = {0};
            reader.accept(new ClassVisitor(ASM8, writer) {
                @Override public MethodVisitor visitMethod(int access, String method, String descriptor,
                        String signature, String[] exceptions) {
                    MethodVisitor delegate = super.visitMethod(access, method, descriptor, signature, exceptions);
                    int kind = 0;
                    if (name.equals("okhttp3/internal/connection/RealCall")) {
                        if (method.equals("execute") && descriptor.equals("()Lokhttp3/Response;")) kind = 1;
                        if (method.equals("enqueue") && descriptor.equals("(Lokhttp3/Callback;)V")) kind = 1;
                    } else if (name.equals("okhttp3/internal/http/RealInterceptorChain") && method.equals("proceed") &&
                            descriptor.equals("(Lokhttp3/Request;)Lokhttp3/Response;")) kind = 2;
                    else if (name.equals("okhttp3/internal/connection/RouteSelector") && method.equals("resetNextInetSocketAddress") &&
                            descriptor.equals("(Ljava/net/Proxy;)V")) kind = 3;
                    else if (name.equals("okhttp3/internal/connection/ConnectPlan") && method.equals("connectSocket") &&
                            descriptor.equals("()V")) kind = 4;
                    if (kind == 0) return delegate;
                    if ((access & (ACC_ABSTRACT | ACC_NATIVE | ACC_STATIC)) != 0)
                        throw new IllegalStateException("Offline guard unsupported OkHttp method shape");
                    injected[0]++;
                    int hook = kind;
                    return new MethodVisitor(ASM8, delegate) {
                        @Override public void visitCode() {
                            super.visitCode();
                            visitLdcInsn(Type.getObjectType(name));
                            visitMethodInsn(INVOKESTATIC, HELPER, "verifyOkHttpClass", "(Ljava/lang/Class;)V", false);
                            switch (hook) {
                                case 1 -> { field("originalRequest", "Lokhttp3/Request;"); requestHost(); checkHost(); }
                                case 2 -> { visitVarInsn(ALOAD, 1); requestHost(); checkHost(); }
                                case 3 -> {
                                    field("address", "Lokhttp3/Address;");
                                    invoke("okhttp3/Address", "url", "()Lokhttp3/HttpUrl;");
                                    invoke("okhttp3/HttpUrl", "host", "()Ljava/lang/String;");
                                    visitVarInsn(ALOAD, 1);
                                    visitMethodInsn(INVOKESTATIC, HELPER, "checkOkHttpProxy", "(Ljava/lang/String;Ljava/net/Proxy;)V", false);
                                }
                                case 4 -> {
                                    route(); invoke("okhttp3/Route", "address", "()Lokhttp3/Address;");
                                    invoke("okhttp3/Address", "url", "()Lokhttp3/HttpUrl;");
                                    invoke("okhttp3/HttpUrl", "host", "()Ljava/lang/String;");
                                    route(); invoke("okhttp3/Route", "proxy", "()Ljava/net/Proxy;");
                                    route(); invoke("okhttp3/Route", "socketAddress", "()Ljava/net/InetSocketAddress;");
                                    visitMethodInsn(INVOKESTATIC, HELPER, "checkOkHttpTransport", "(Ljava/lang/String;Ljava/net/Proxy;Ljava/net/InetSocketAddress;)V", false);
                                }
                                default -> throw new AssertionError();
                            }
                        }
                        private void field(String field, String type) {
                            visitVarInsn(ALOAD, 0); visitFieldInsn(GETFIELD, name, field, type);
                        }
                        private void invoke(String owner, String method, String descriptor) {
                            visitMethodInsn(INVOKEVIRTUAL, owner, method, descriptor, false);
                        }
                        private void requestHost() {
                            invoke("okhttp3/Request", "url", "()Lokhttp3/HttpUrl;");
                            invoke("okhttp3/HttpUrl", "host", "()Ljava/lang/String;");
                        }
                        private void checkHost() {
                            visitMethodInsn(INVOKESTATIC, HELPER, "checkOkHttpHost", "(Ljava/lang/String;)V", false);
                        }
                        private void route() { field("route", "Lokhttp3/Route;"); }
                    };
                }
            }, 0);
            int required = name.endsWith("/RealCall") ? 2 : 1;
            if (injected[0] != required) throw new IllegalStateException("Offline guard unexpected OkHttp method shape");
            byte[] transformed = writer.toByteArray();
            OfflineGuard.recordOkHttpHooks(loader, name.replace('/', '.'), injected[0]);
            return transformed;
        } catch (Throwable failure) {
            OfflineGuard.recordOkHttpFailure();
            // The JVM ignores transformer exceptions/null. Invalid bytes instead prevent definition
            // (or reject retransformation while retaining the previously guarded definition).
            return new byte[] {0};
        }
    }
}
