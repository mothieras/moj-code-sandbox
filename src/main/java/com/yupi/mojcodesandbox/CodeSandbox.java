package com.yupi.mojcodesandbox;


import com.yupi.mojcodesandbox.model.ExecuteCodeRequest;
import com.yupi.mojcodesandbox.model.ExecuteCodeResponse;

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
