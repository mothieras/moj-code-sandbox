package com.moj.codesandbox.controller;

import com.moj.codesandbox.JavaDockerCodeSandbox;
import com.moj.codesandbox.model.ExecuteCodeRequest;
import com.moj.codesandbox.model.ExecuteCodeResponse;
import com.moj.codesandbox.model.JudgeInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.Set;

@RestController
@RequestMapping("/")
public class MainController {
    private static final String AUTH_REQUEST_HEADER = "auth";
    private static final int MAX_INPUT_LIST_SIZE = 100;
    private static final int MAX_CODE_LENGTH = 64 * 1024;
    private static final int MAX_INPUT_LENGTH = 10000;
    private static final Set<String> ALLOWED_LANGUAGES = Set.of("java");

    @Value("${sandbox.auth-secret:secretKey}")
    private String authSecret;

    @Resource
    private JavaDockerCodeSandbox javaDockerCodeSandbox;

    @GetMapping("/health")
    public String healthCheck() {
        return "ok";
    }

    @PostMapping("/executeCode")
    public ExecuteCodeResponse executeCode(@RequestBody ExecuteCodeRequest executeCodeRequest, HttpServletRequest request, HttpServletResponse response) {
        String authHeader = request.getHeader(AUTH_REQUEST_HEADER);
        if (!authSecret.equals(authHeader)) {
            response.setStatus(403);
            return errorResponse("鉴权失败");
        }
        if (executeCodeRequest == null) {
            return errorResponse("请求参数为空");
        }
        if (executeCodeRequest.getCode() == null || executeCodeRequest.getCode().isBlank()) {
            return errorResponse("代码为空");
        }
        if (executeCodeRequest.getCode().length() > MAX_CODE_LENGTH) {
            return errorResponse("代码长度超过限制(" + MAX_CODE_LENGTH + ")");
        }
        if (executeCodeRequest.getLanguage() == null || executeCodeRequest.getLanguage().isBlank()) {
            return errorResponse("语言为空");
        }
        if (!ALLOWED_LANGUAGES.contains(executeCodeRequest.getLanguage())) {
            return errorResponse("不支持的语言: " + executeCodeRequest.getLanguage());
        }
        if (executeCodeRequest.getInputList() == null) {
            executeCodeRequest.setInputList(Collections.emptyList());
        }
        if (executeCodeRequest.getInputList().size() > MAX_INPUT_LIST_SIZE) {
            return errorResponse("输入用例数量超过限制(" + MAX_INPUT_LIST_SIZE + ")");
        }
        for (String input : executeCodeRequest.getInputList()) {
            if (input != null && input.length() > MAX_INPUT_LENGTH) {
                return errorResponse("单条输入长度超过限制(" + MAX_INPUT_LENGTH + ")");
            }
        }
        return javaDockerCodeSandbox.executeCode(executeCodeRequest);
    }

    private ExecuteCodeResponse errorResponse(String message) {
        return ExecuteCodeResponse.builder()
                .message(message)
                .status(2)
                .judgeInfo(new JudgeInfo())
                .build();
    }
}
