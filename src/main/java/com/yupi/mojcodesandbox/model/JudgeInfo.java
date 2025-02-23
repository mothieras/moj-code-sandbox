package com.yupi.mojcodesandbox.model;

import lombok.Data;

import java.io.Serializable;

/**
 * 题目判题信息
 */
@Data
public class JudgeInfo implements Serializable {
    private static final long serialVersionUID = -5005633071251474395L;
    /**
     * 程序执行信息 ms
     */
    private String message;
    /**
     * 消耗内存  KB
     */
    private Long memory;
    /**
     * 消耗时间  KB
     */
    private Long time;
}
