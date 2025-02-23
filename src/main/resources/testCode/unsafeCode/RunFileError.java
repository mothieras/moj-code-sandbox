
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * 运行其他程序
 */
public class Main {
    public static void main(String[] args) throws InterruptedException, IOException {
        String userDir = System.getProperty("user.dir");
        String filePath = userDir + File.separator + "src/main/resources/muma.bat";
        Process process = Runtime.getRuntime().exec(filePath);
        process.waitFor();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String compileMsgLine;
        StringBuilder compileStringBuilder = new StringBuilder();
        // 逐行读取编译输出
        while ((compileMsgLine = reader.readLine()) != null) {
            compileStringBuilder.append(compileMsgLine);
        }
        System.out.println(compileStringBuilder);

        System.out.println("执行异常程序成功");
    }
}
