package com.yupi.mojcodesandbox.model;

import lombok.Data;

/**
 * 进程执行信息
 */
@Data
public class ExecuteMessage {
    private Integer exitVal;
    private String message;
    private String errorMessage;
    private Long time;
    private Long memory;
    /** 是否超时 */
    private Boolean timeout;
}
