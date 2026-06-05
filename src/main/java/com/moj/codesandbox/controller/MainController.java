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

@RestController
@RequestMapping("/")
public class MainController {
    // 鉴权请求头
    private static final String AUTH_REQUEST_HEADER = "auth";
    private static final int MAX_INPUT_LIST_SIZE = 100;

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
        // 基本的认证
        String authHeader = request.getHeader(AUTH_REQUEST_HEADER);
        if (!authSecret.equals(authHeader)) {
            response.setStatus(403);
            return null;
        }
        if (executeCodeRequest == null) {
            return errorResponse("请求参数为空");
        }
        if (executeCodeRequest.getCode() == null || executeCodeRequest.getCode().isBlank()) {
            return errorResponse("代码为空");
        }
        if (executeCodeRequest.getLanguage() == null || executeCodeRequest.getLanguage().isBlank()) {
            return errorResponse("语言为空");
        }
        if (executeCodeRequest.getInputList() == null) {
            executeCodeRequest.setInputList(Collections.emptyList());
        }
        if (executeCodeRequest.getInputList().size() > MAX_INPUT_LIST_SIZE) {
            return errorResponse("输入用例数量超过限制(" + MAX_INPUT_LIST_SIZE + ")");
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
