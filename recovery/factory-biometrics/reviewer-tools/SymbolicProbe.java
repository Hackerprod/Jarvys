import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @interface ProbeAnnotation { String value(); }
@ProbeAnnotation("annotated") public class SymbolicProbe {
 public static final String MARK = "marker"; private int hidden = 7;
 public int branch(int n) { try { switch(n) {case 1: return hidden; case 30: return n+1; default: return new int[]{1,2,3,4}[n];} } catch (RuntimeException e) {return -1;} }
 public int dense(int n) {switch(n) {case 1:return 7;case 2:return 9;case 3:return 11;default:return 13;}}
 public int[] array() {return new int[]{1,2,3,4,5,6,7,8,9,10,11,12};}
 public String unicode() {return "🔒\\n é";}
 public Runnable lambda() { return () -> System.out.println("lambda"); }
}
