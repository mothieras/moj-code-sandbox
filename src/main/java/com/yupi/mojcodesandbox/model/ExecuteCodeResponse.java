package com.yupi.mojcodesandbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecuteCodeResponse {

    /** 编译阶段原始结果（null = 无需编译） */
    private ExecuteMessage compileResult;

    /** 运行阶段原始结果（每条用例一条） */
    private List<ExecuteMessage> runResults;

    // === 兼容字段 ===
    private List<String> outputList;
    /**
     * 任意信息，接口信息
     */
    private String message;
    /**
     * 执行状态
     */
    private Integer status;
    /**
     * 判题信息
     */
    private JudgeInfo judgeInfo;
}
