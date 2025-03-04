package com.yupi.mojcodesandbox;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.yupi.mojcodesandbox.model.*;
import com.yupi.mojcodesandbox.utils.ProcessUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Deprecated
public abstract class BaseCodeSandboxTemplate implements CodeSandbox {

    // 通用的保存源码目录
    private static final String GLOBAL_CODE_DIR_NAME = "tmpCode";
    private static final long TIME_OUT = 8000L;


    // 覆盖 executeCode，提供通用模板
    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest request) {
        try {
            // 1) 根据语言获取配置
            LanguageConfig langConfig = LanguageConfig.of(request.getLanguage());

            // 2) 保存代码为文件
            File userCodeFile = saveCodeToFile(request.getCode(), langConfig);

            // 3) 编译 (如果需要)
            ExecuteMessage compileResult = compileFileIfNeeded(userCodeFile, langConfig);
            if (compileResult.getExitVal() != 0) {
                // 编译失败
                return ExecuteCodeResponse.builder()
                        .status(3)
                        .message(compileResult.getErrorMessage())
                        .build();
            }

            // 4) 运行
            List<ExecuteMessage> runMsgs = runFile(userCodeFile, request.getInputList(), langConfig);

            // 5) 收集输出
            ExecuteCodeResponse outputResponse = getOutputResponse(runMsgs);

            // 6) 清理文件
            boolean delOk = deleteFile(userCodeFile);
            if (!delOk) {
                log.error("delete file error, userCodeFilePath = {}", userCodeFile.getAbsolutePath());
            }

            return outputResponse;
        } catch (Exception e) {

            return someErrorResponse(e);
        }
    }

    /**
     * 保存代码文件
     *
     * @param code
     * @param langConfig
     * @return
     */
    protected File saveCodeToFile(String code, LanguageConfig langConfig) {
        // 路径设计
        String userDir = System.getProperty("user.dir");
        String codeDirPath = userDir + File.separator + GLOBAL_CODE_DIR_NAME;
        FileUtil.mkdir(codeDirPath);

        // 随机目录 + 后缀
        String userCodeParent = codeDirPath + File.separator + UUID.randomUUID();
        FileUtil.mkdir(userCodeParent);

        String fileName = langConfig == LanguageConfig.JAVA ? "Main" + langConfig.getFileSuffix()
                : "main" + langConfig.getFileSuffix();
        String userCodePath = userCodeParent + File.separator + fileName;
        return FileUtil.writeString(code, userCodePath, StandardCharsets.UTF_8);
    }

    /**
     * 编译（可选）
     *
     * @param userCodeFile
     * @param langConfig
     * @return
     */
    protected ExecuteMessage compileFileIfNeeded(File userCodeFile, LanguageConfig langConfig) {
        if (langConfig.getCompileCmdTemplate() == null) {
            // Python 等无需编译
            ExecuteMessage msg = new ExecuteMessage();
            msg.setExitVal(0);
            return msg;
        }
        // 拼装编译命令
        String compileCmd = langConfig.getCompileCmdTemplate()
                .replace("{srcFile}", userCodeFile.getAbsolutePath())
                .replace("{srcDir}", userCodeFile.getParent());

        return runLocalCommand(compileCmd, "编译");
    }

    /**
     * 运行
     *
     * @param userCodeFile
     * @param inputList
     * @param langConfig
     * @return
     */
    protected List<ExecuteMessage> runFile(File userCodeFile, List<String> inputList, LanguageConfig langConfig) {
        List<ExecuteMessage> resultList = new ArrayList<>();
        String srcDir = userCodeFile.getParent();
        String srcFile = userCodeFile.getAbsolutePath();

        for (String inputArgs : inputList) {
            // 运行命令：将 {args}, {srcFile}, {srcDir} 替换
            String runCmd = langConfig.getRunCmdTemplate()
                    .replace("{srcDir}", srcDir)
                    .replace("{srcFile}", srcFile)
                    .replace("{args}", inputArgs.trim());

            // 执行
            ExecuteMessage execMsg = runLocalCommand(runCmd, "运行");
            resultList.add(execMsg);
        }
        return resultList;
    }

    /**
     * @param cmd
     * @param stage
     * @return
     */
    protected ExecuteMessage runLocalCommand(String cmd, String stage) {
        try {
            Process process = Runtime.getRuntime().exec(cmd);
            // 超时控制
            new Thread(() -> {
                try {
                    Thread.sleep(TIME_OUT);
                    process.destroy();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }).start();

            return ProcessUtils.runProcessAndGetMessage(process, stage);
        } catch (Exception e) {
            throw new RuntimeException(stage + "出错: " + e.getMessage(), e);
        }
    }

    /**
     * 收集执行信息
     *
     * @param executeMessageList
     * @return
     */
    protected ExecuteCodeResponse getOutputResponse(List<ExecuteMessage> executeMessageList) {
        // 收集最大用时、最大内存、输出列表
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        List<String> outputList = new ArrayList<>();
        // 取用时最大值，便于判断是否超时
        long maxTime = 0;
        long maxMemory = 0;
        for (ExecuteMessage executeMessage : executeMessageList) {
            String errorMessage = executeMessage.getErrorMessage();
            if (StrUtil.isNotBlank(errorMessage)) {
                executeCodeResponse.setMessage(errorMessage);
                //执行中存在错误
                executeCodeResponse.setStatus(3);
                break;
            }
            outputList.add(executeMessage.getMessage());
            Long time = executeMessage.getTime();
            Long memory = executeMessage.getMemory();
            if (time != null) {
                maxTime = Math.max(maxTime, time);
            }
            if (memory != null) {
                maxMemory = Math.max(maxMemory, memory);
            }
        }
        // 正常运行完成
        if (outputList.size() == executeMessageList.size()) {
            executeCodeResponse.setStatus(1);
        }
        executeCodeResponse.setOutputList(outputList);

        JudgeInfo judgeInfo = new JudgeInfo();

        judgeInfo.setTime(maxTime);
        judgeInfo.setMemory(maxMemory);
        executeCodeResponse.setJudgeInfo(judgeInfo);

        return executeCodeResponse;
    }

    /**
     * 删除文件
     *
     * @param userCodeFile
     * @return
     */
    public boolean deleteFile(File userCodeFile) {
        if (userCodeFile.getParentFile() != null) {
            String userCodeParentPath = userCodeFile.getParentFile().getAbsolutePath();
            boolean del = FileUtil.del(userCodeParentPath);
            System.out.println("删除" + (del ? "成功" : "失败"));
            return del;
        }
        return true;
    }

    /**
     * 获取错误响应
     *
     * @param e
     * @return
     */
    private ExecuteCodeResponse someErrorResponse(Exception e) {
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        executeCodeResponse.setOutputList(new ArrayList<>());
        executeCodeResponse.setMessage(e.getMessage());
        // 表示代码沙箱错误
        executeCodeResponse.setStatus(2);
        executeCodeResponse.setJudgeInfo(new JudgeInfo());
        return executeCodeResponse;
    }
}