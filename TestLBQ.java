import java.util.concurrent.*;
public class TestLBQ {
  public static void main(String[] args) {
    LinkedBlockingQueue<Integer> q = new LinkedBlockingQueue<>();
    q.add(1);
    q.add(2);
    var it = q.iterator();
    while (it.hasNext()) {
      System.out.println("next=" + it.next());
      it.remove();
    }
    System.out.println("size=" + q.size());
  }
}
