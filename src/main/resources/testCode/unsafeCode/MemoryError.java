import java.util.ArrayList;
import java.util.List;

/**
 * 无限占用内存
 */
public class Main {
    public static void main(String[] args) throws InterruptedException {
        List<byte[]> bytes = new ArrayList<>();
        while(true){
            bytes.add(new byte[1000]);
        }
    }
}
