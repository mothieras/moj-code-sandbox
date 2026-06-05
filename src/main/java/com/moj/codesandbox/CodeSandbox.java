package com.moj.codesandbox;


import com.moj.codesandbox.model.ExecuteCodeRequest;
import com.moj.codesandbox.model.ExecuteCodeResponse;

/**
 * 代码沙箱
 */
public interface CodeSandbox {
    /**
     * 执行代码
     * @param executeCodeRequest
     * @return
     */
    ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest);
}
