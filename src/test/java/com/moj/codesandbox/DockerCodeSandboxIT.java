package com.moj.codesandbox;

import com.moj.codesandbox.model.ExecuteCodeRequest;
import com.moj.codesandbox.model.ExecuteCodeResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 语言无关沙箱集成测试：覆盖 Java / Python / C++ 三语言的正常输出、编译错误、
 * 运行时异常、无限循环超时。需本机 Docker（首次会拉取对应语言镜像）。
 */
@SpringBootTest
class DockerCodeSandboxIT {

    @Autowired
    DockerCodeSandbox sandbox;

    private ExecuteCodeResponse run(String code, String language, List<String> input) {
        return sandbox.executeCode(ExecuteCodeRequest.builder()
                .code(code).language(language).inputList(input).build());
    }

    // ===== Java =====

    @Test
    void java_normal_outputs_correctly() {
        String code = "public class Main { public static void main(String[] a){ System.out.println(1+2); } }";
        ExecuteCodeResponse resp = run(code, "java", Collections.singletonList(""));
        assertEquals(Integer.valueOf(1), resp.getStatus());
        assertEquals("3", resp.getOutputList().get(0).trim());
    }

    @Test
    void java_compile_error_is_reported() {
        String code = "public class Main { oops }";
        ExecuteCodeResponse resp = run(code, "java", Collections.singletonList(""));
        assertNotEquals(Integer.valueOf(1), resp.getStatus());
    }

    @Test
    void java_runtime_exception_is_caught() {
        String code = "public class Main { public static void main(String[] a){ int x = 1/0; } }";
        ExecuteCodeResponse resp = run(code, "java", Collections.singletonList(""));
        assertEquals(Integer.valueOf(3), resp.getStatus());
    }

    @Test
    void java_infinite_loop_times_out() {
        String code = "public class Main { public static void main(String[] a){ while(true){} } }";
        ExecuteCodeResponse resp = run(code, "java", Collections.singletonList(""));
        assertEquals(Integer.valueOf(3), resp.getStatus());
    }

    // ===== Python =====

    @Test
    void python_normal_outputs_correctly() {
        String code = "print(1 + 2)";
        ExecuteCodeResponse resp = run(code, "python", Collections.singletonList(""));
        assertEquals(Integer.valueOf(1), resp.getStatus());
        assertEquals("3", resp.getOutputList().get(0).trim());
    }

    @Test
    void python_runtime_error_is_caught() {
        String code = "print(1/0)";
        ExecuteCodeResponse resp = run(code, "python", Collections.singletonList(""));
        assertEquals(Integer.valueOf(3), resp.getStatus());
    }

    @Test
    void python_infinite_loop_times_out() {
        String code = "while True:\n    pass";
        ExecuteCodeResponse resp = run(code, "python", Collections.singletonList(""));
        assertEquals(Integer.valueOf(3), resp.getStatus());
    }

    // ===== C++ =====

    @Test
    void cpp_normal_outputs_correctly() {
        String code = "#include <iostream>\nusing namespace std;\nint main(){ cout << (1+2) << endl; }";
        ExecuteCodeResponse resp = run(code, "cpp", Collections.singletonList(""));
        assertEquals(Integer.valueOf(1), resp.getStatus());
        assertEquals("3", resp.getOutputList().get(0).trim());
    }

    @Test
    void cpp_compile_error_is_reported() {
        String code = "#include <iostream>\nint main(){ oops }";
        ExecuteCodeResponse resp = run(code, "cpp", Collections.singletonList(""));
        assertNotEquals(Integer.valueOf(1), resp.getStatus());
    }

    @Test
    void cpp_infinite_loop_times_out() {
        String code = "#include <iostream>\nint main(){ while(true){} }";
        ExecuteCodeResponse resp = run(code, "cpp", Collections.singletonList(""));
        assertEquals(Integer.valueOf(3), resp.getStatus());
    }
}
