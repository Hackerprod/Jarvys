import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.Permission;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.runner.*;
import org.junit.runner.notification.*;

/** Local test launcher; denies standard JVM network, not native-code network. */
@SuppressWarnings("removal")
public final class OfflineJUnit {
  static final AtomicInteger denials = new AtomicInteger();
  static final java.util.concurrent.atomic.AtomicReference<Throwable> firstDenial = new java.util.concurrent.atomic.AtomicReference<>();
  static final class Row {
    final Description d; final long start=System.nanoTime(); long elapsed;
    final List<Failure> failures=new ArrayList<>(); String skipped;
    Row(Description d){this.d=d;}
  }
  static String xml(String s){ if(s==null)return ""; StringBuilder b=new StringBuilder();
    for(char c:s.toCharArray()) { if(c=='&')b.append("&amp;");else if(c=='<')b.append("&lt;");else if(c=='>')b.append("&gt;");else if(c=='\"')b.append("&quot;");else if(c=='\'')b.append("&apos;");else if(c=='\n'||c=='\r'||c=='\t'||c>=32)b.append(c); } return b.toString(); }
  public static void main(String[] args) {
    if(args.length!=2) throw new IllegalArgumentException("class and XML path required");
    List<Row> rows=Collections.synchronizedList(new ArrayList<>());
    Map<Description,Row> active=Collections.synchronizedMap(new HashMap<>());
    boolean ok=false; long begin=System.nanoTime();
    try {
      SecurityManager guard=new SecurityManager(){
        private void deny(){denials.incrementAndGet();SecurityException denied=new SecurityException("Offline test transport denied");firstDenial.compareAndSet(null,denied);throw denied;}
        public void checkConnect(String h,int p){deny();}
        public void checkConnect(String h,int p,Object c){deny();}
        public void checkAccept(String h,int p){deny();}
        public void checkListen(int p){deny();}
        public void checkExec(String cmd){
          boolean permissionQuery=StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(frames ->
              frames.skip(1).findFirst().map(frame -> frame.getDeclaringClass()==File.class && frame.getMethodName().equals("canExecute")).orElse(false));
          if(permissionQuery)return; // Pure JDK permission query, not process creation.
          deny();
        }
        public void checkPermission(Permission p){ if(p instanceof FilePermission && Arrays.asList(p.getActions().split(",")).contains("execute"))deny(); }
        public void checkPermission(Permission p,Object c){checkPermission(p);}
      };
      System.setSecurityManager(guard);
      if(System.getSecurityManager()!=guard)throw new AssertionError("Offline guard unavailable");
      JUnitCore core=new JUnitCore();
      core.addListener(new RunListener(){
        Row row(Description d){ synchronized(active){Row r=active.get(d); if(r==null){r=new Row(d);active.put(d,r);rows.add(r);}return r;} }
        public void testStarted(Description d){row(d);}
        public void testFailure(Failure f){row(f.getDescription()).failures.add(f);}
        public void testAssumptionFailure(Failure f){row(f.getDescription()).skipped=f.getMessage();}
        public void testIgnored(Description d){row(d).skipped="ignored";}
        public void testFinished(Description d){row(d).elapsed=System.nanoTime()-row(d).start;}
      });
      Result result=core.run(Class.forName(args[0]));
      ok=result.wasSuccessful() && result.getRunCount()>0 && System.getSecurityManager()==guard && denials.get()==0;
      if(rows.size()!=result.getRunCount()+result.getIgnoreCount())throw new AssertionError("JUnit event/count reconciliation failed");
      if(result.getRunCount()==0)throw new AssertionError("No tests executed");
      if(denials.get()!=0)throw new AssertionError("Unexpected offline transport or subprocess attempt",firstDenial.get());
      if(System.getSecurityManager()!=guard)throw new AssertionError("Offline guard replaced");
    } catch(Throwable t) {
      Row r=new Row(Description.createTestDescription(args[0],"launcherFailure"));r.failures.add(new Failure(r.d,t));rows.add(r);ok=false;
    }
    try {
      int failures=0,skips=0;for(Row r:rows){if(!r.failures.isEmpty())failures++;if(r.skipped!=null)skips++;}
      StringBuilder b=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
      b.append("<testsuite name=\"").append(xml(args[0])).append("\" tests=\"").append(rows.size()).append("\" failures=\"").append(failures).append("\" errors=\"0\" skipped=\"").append(skips).append("\" time=\"").append((System.nanoTime()-begin)/1e9).append("\">\n");
      for(Row r:rows){b.append("<testcase classname=\"").append(xml(r.d.getClassName())).append("\" name=\"").append(xml(r.d.getMethodName()==null?r.d.getDisplayName():r.d.getMethodName())).append("\" time=\"").append(r.elapsed/1e9).append("\">");
        if(r.skipped!=null)b.append("<skipped message=\"").append(xml(r.skipped)).append("\"/>");
        for(Failure f:r.failures)b.append("<failure type=\"").append(xml(f.getException().getClass().getName())).append("\" message=\"").append(xml(f.getMessage())).append("\">").append(xml(f.getTrace())).append("</failure>");
        b.append("</testcase>\n");}
      b.append("</testsuite>\n");Path out=Paths.get(args[1]);Files.createDirectories(out.toAbsolutePath().getParent());Files.write(out,b.toString().getBytes(StandardCharsets.UTF_8));
    }catch(Throwable t){ok=false;System.err.println("Offline XML output failed");}
    // Do not restore the guard: background work is contained until JVM termination.
    System.exit(ok && denials.get()==0 ? 0 : 1);
  }
}
