package com.moj.codesandbox.controller;

import com.moj.codesandbox.DockerCodeSandbox;
import com.moj.codesandbox.model.ExecuteCodeRequest;
import com.moj.codesandbox.model.ExecuteCodeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MainControllerTest {

    private static final String AUTH_SECRET = "secretKey";
    private static final String WRONG_AUTH = "wrongKey";

    private MainController controller;
    private DockerCodeSandbox mockSandbox;
    private HttpServletRequest mockRequest;
    private HttpServletResponse mockResponse;

    @BeforeEach
    void setUp() {
        mockSandbox = mock(DockerCodeSandbox.class);
        when(mockSandbox.executeCode(any())).thenReturn(ExecuteCodeResponse.builder()
                .message("OK")
                .status(1)
                .outputList(List.of("3"))
                .build());

        mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getHeader("auth")).thenReturn(AUTH_SECRET);

        mockResponse = mock(HttpServletResponse.class);

        controller = new MainController();
        ReflectionTestUtils.setField(controller, "authSecret", AUTH_SECRET);
        ReflectionTestUtils.setField(controller, "dockerCodeSandbox", mockSandbox);
    }

    private ExecuteCodeRequest validRequest() {
        return ExecuteCodeRequest.builder()
                .code("public class Main { public static void main(String[] args) { System.out.println(1 + 2); } }")
                .language("java")
                .inputList(List.of(""))
                .build();
    }

    // ---- 1. auth failure ----

    @Test
    void authFailure_returns403WithErrorBody() {
        when(mockRequest.getHeader("auth")).thenReturn(WRONG_AUTH);

        ExecuteCodeResponse result = controller.executeCode(
                validRequest(), mockRequest, mockResponse);

        verify(mockResponse).setStatus(403);
        assertThat(result.getStatus()).isEqualTo(2);
        assertThat(result.getMessage()).isEqualTo("鉴权失败");
    }

    // ---- 2. auth success ----

    @Test
    void authSuccess_proceedsToSandbox() {
        controller.executeCode(validRequest(), mockRequest, mockResponse);

        verify(mockSandbox).executeCode(any(ExecuteCodeRequest.class));
    }

    // ---- 3. null request body ----

    @Test
    void nullRequestBody_returnsError() {
        ExecuteCodeResponse result = controller.executeCode(null, mockRequest, mockResponse);

        assertThat(result.getStatus()).isEqualTo(2);
        assertThat(result.getMessage()).isEqualTo("请求参数为空");
    }

    // ---- 4. empty code ----

    @Test
    void emptyCode_returnsError() {
        ExecuteCodeRequest request = ExecuteCodeRequest.builder()
                .code("")
                .language("java")
                .inputList(List.of())
                .build();

        ExecuteCodeResponse result = controller.executeCode(request, mockRequest, mockResponse);

        assertThat(result.getStatus()).isEqualTo(2);
        assertThat(result.getMessage()).isEqualTo("代码为空");
    }

    // ---- 5. blank code ----

    @Test
    void blankCode_returnsError() {
        ExecuteCodeRequest request = ExecuteCodeRequest.builder()
                .code("   ")
                .language("java")
                .inputList(List.of())
                .build();

        ExecuteCodeResponse result = controller.executeCode(request, mockRequest, mockResponse);

        assertThat(result.getStatus()).isEqualTo(2);
        assertThat(result.getMessage()).isEqualTo("代码为空");
    }

    // ---- 6. code exceeds max length ----

    @Test
    void codeExceedsMaxLength_returnsError() {
        String longCode = "a".repeat(65537);
        ExecuteCodeRequest request = ExecuteCodeRequest.builder()
                .code(longCode)
                .language("java")
                .inputList(List.of())
                .build();

        ExecuteCodeResponse result = controller.executeCode(request, mockRequest, mockResponse);

        assertThat(result.getStatus()).isEqualTo(2);
        assertThat(result.getMessage()).contains("代码长度");
    }

    // ---- 7. unsupported language (ruby 不在白名单) ----

    @Test
    void unsupportedLanguage_returnsError() {
        ExecuteCodeRequest request = ExecuteCodeRequest.builder()
                .code("puts 'hello'")
                .language("ruby")
                .inputList(List.of())
                .build();

        ExecuteCodeResponse result = controller.executeCode(request, mockRequest, mockResponse);

        assertThat(result.getStatus()).isEqualTo(2);
        assertThat(result.getMessage()).contains("不支持的语言");
    }

    // ---- 8. supported language proceeds (python) ----

    @Test
    void supportedLanguage_proceedsToSandbox() {
        ExecuteCodeRequest request = ExecuteCodeRequest.builder()
                .code("print(1 + 2)")
                .language("python")
                .inputList(List.of(""))
                .build();

        controller.executeCode(request, mockRequest, mockResponse);

        verify(mockSandbox).executeCode(any(ExecuteCodeRequest.class));
    }

    // ---- 9. input list exceeds max ----

    @Test
    void inputListExceedsMax_returnsError() {
        List<String> largeInputList = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            largeInputList.add("input" + i);
        }

        ExecuteCodeRequest request = ExecuteCodeRequest.builder()
                .code("public class Main {}")
                .language("java")
                .inputList(largeInputList)
                .build();

        ExecuteCodeResponse result = controller.executeCode(request, mockRequest, mockResponse);

        assertThat(result.getStatus()).isEqualTo(2);
        assertThat(result.getMessage()).contains("输入用例数量");
    }

    // ---- 10. single input too large ----

    @Test
    void singleInputTooLarge_returnsError() {
        String longInput = "a".repeat(10001);
        ExecuteCodeRequest request = ExecuteCodeRequest.builder()
                .code("public class Main {}")
                .language("java")
                .inputList(List.of("normal", longInput))
                .build();

        ExecuteCodeResponse result = controller.executeCode(request, mockRequest, mockResponse);

        assertThat(result.getStatus()).isEqualTo(2);
        assertThat(result.getMessage()).contains("单条输入长度");
    }

    // ---- 11. null input list defaults to empty and proceeds ----

    @Test
    void nullInputList_defaultsToEmptyAndProceeds() {
        ExecuteCodeRequest request = ExecuteCodeRequest.builder()
                .code("public class Main {}")
                .language("java")
                .inputList(null)
                .build();

        controller.executeCode(request, mockRequest, mockResponse);

        ArgumentCaptor<ExecuteCodeRequest> captor = ArgumentCaptor.forClass(ExecuteCodeRequest.class);
        verify(mockSandbox).executeCode(captor.capture());
        assertThat(captor.getValue().getInputList()).isEmpty();
    }

    // ---- 12. health check ----

    @Test
    void healthCheck_returnsOk() {
        String result = controller.healthCheck();

        assertThat(result).isEqualTo("ok");
    }
}
