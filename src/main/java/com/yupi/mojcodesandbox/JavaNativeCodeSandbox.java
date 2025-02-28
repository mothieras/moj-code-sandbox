package com.yupi.mojcodesandbox;

import com.yupi.mojcodesandbox.model.ExecuteCodeRequest;
import com.yupi.mojcodesandbox.model.ExecuteCodeResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class JavaNativeCodeSandbox extends JavaCodeSandboxTemplate {
    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        log.info("--------代码沙箱开始执行----------");
        ExecuteCodeResponse executeCodeResponse = super.executeCode(executeCodeRequest);
        log.info("--------代码沙箱执行结束----------");
        return executeCodeResponse;
    }
}
