package com.yupi.mojcodesandbox.utils;

import cn.hutool.core.util.StrUtil;
import com.yupi.mojcodesandbox.model.ExecuteMessage;
import org.springframework.util.StopWatch;

import java.io.*;

/**
 * 进程工具类
 */
public class ProcessUtils {
    /**
     * 执行进程并获取信息
     *
     * @param runProcess
     * @return
     */
    public static ExecuteMessage runProcessAndGetMessage(Process runProcess, String optName) {
        ExecuteMessage executeMessage = new ExecuteMessage();
        // 等待程序执行，获取错误码
        try {
            StopWatch stopWatch = new StopWatch();
            stopWatch.start();
            int exitVal = runProcess.waitFor();
            executeMessage.setExitVal(exitVal);

            // 正常退出
            if (exitVal == 0) {
                System.out.println(optName + "成功");
                // 分批获取进程的正常输出流
                BufferedReader reader = new BufferedReader(new InputStreamReader(runProcess.getInputStream()));
                String compileMsgLine;
                StringBuilder compileStringBuilder = new StringBuilder();
                // 逐行读取编译输出
                while ((compileMsgLine = reader.readLine()) != null) {
                    compileStringBuilder.append(compileMsgLine);
                }
                executeMessage.setMessage(compileStringBuilder.toString());
            } else {
                System.out.println(optName + "失败，错误码：" + exitVal);

                // 分批获取进程的错误输出流
                BufferedReader reader = new BufferedReader(new InputStreamReader(runProcess.getErrorStream()));
                String compileMsgLine;
                StringBuilder compileStringBuilder = new StringBuilder();
                // 逐行读取编译输出
                while ((compileMsgLine = reader.readLine()) != null) {
                    compileStringBuilder.append(compileMsgLine);
                }
                executeMessage.setErrorMessage(compileStringBuilder.toString());

            }
            stopWatch.stop();
            executeMessage.setTime(stopWatch.getLastTaskTimeMillis());
        } catch (InterruptedException | IOException e) {
            e.printStackTrace();
        }

        return executeMessage;
    }

    /**
     * 执行交互式进程并获取信息
     * todo: 自行优化交互式
     *
     * @param runProcess
     * @return
     */
    public static ExecuteMessage runInteractProcessAndGetMessage(Process runProcess, String args) {
        ExecuteMessage executeMessage = new ExecuteMessage();
        try {


            OutputStream outputStream = runProcess.getOutputStream();
            OutputStreamWriter outputStreamWriter = new OutputStreamWriter(outputStream);

            // 向控制台输入
            String[] s = args.split(" ");
            outputStreamWriter.write(StrUtil.join("\n", s) + "\n");
            // flush相当于按回车，执行输入
            outputStreamWriter.flush();
            // 分批获取进程的正常输出流
            InputStream inputStream = runProcess.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            String compileMsgLine;
            StringBuilder compileStringBuilder = new StringBuilder();
            // 逐行读取编译输出
            while ((compileMsgLine = reader.readLine()) != null) {
                compileStringBuilder.append(compileMsgLine);
            }
            executeMessage.setMessage(compileStringBuilder.toString());
            // 资源回收
            outputStreamWriter.close();
            outputStream.close();
            inputStream.close();
            runProcess.destroy();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return executeMessage;
    }


}
