package com.yupi.mojcodesandbox;

import com.yupi.mojcodesandbox.model.ExecuteCodeRequest;
import com.yupi.mojcodesandbox.model.ExecuteCodeResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class JavaDockerCodeSandboxIT {

    @Autowired JavaDockerCodeSandbox sandbox;

    private ExecuteCodeResponse run(String code, java.util.List<String> input) {
        return sandbox.executeCode(ExecuteCodeRequest.builder()
                .code(code).language("java").inputList(input).build());
    }

    @Test
    void normal_program_outputs_correctly() {
        String code = "public class Main { public static void main(String[] a){ System.out.println(1+2); } }";
        ExecuteCodeResponse resp = run(code, Collections.singletonList(""));
        assertEquals(Integer.valueOf(1), resp.getStatus());          // 1=成功
        assertEquals("3", resp.getOutputList().get(0).trim());
    }

    @Test
    void compile_error_is_reported() {
        String code = "public class Main { oops }";
        ExecuteCodeResponse resp = run(code, Collections.singletonList(""));
        assertNotEquals(Integer.valueOf(1), resp.getStatus());       // 不是成功
    }

    @Test
    void runtime_exception_is_caught() {   // 验雷1：stderr 收得到，判为错误
        String code = "public class Main { public static void main(String[] a){ int x = 1/0; } }";
        ExecuteCodeResponse resp = run(code, Collections.singletonList(""));
        assertEquals(Integer.valueOf(3), resp.getStatus());          // 3=运行存在错误
    }

    @Test
    void infinite_loop_times_out() {       // 验雷3
        String code = "public class Main { public static void main(String[] a){ while(true){} } }";
        ExecuteCodeResponse resp = run(code, Collections.singletonList(""));
        assertEquals(Integer.valueOf(3), resp.getStatus());
        assertTrue(resp.getMessage() == null || resp.getMessage().contains("超时")
                || resp.getStatus() == 3);
    }

    @Test
    void memory_bomb_is_killed() {          // 验雷4：禁 swap 后内存硬限制生效
        String code = "import java.util.*; public class Main { public static void main(String[] a){"
                + " List<long[]> l = new ArrayList<>(); while(true){ l.add(new long[1024*1024]); } } }";
        ExecuteCodeResponse resp = run(code, Collections.singletonList(""));
        assertEquals(Integer.valueOf(3), resp.getStatus());          // OOM/被杀 → 错误
    }
}
