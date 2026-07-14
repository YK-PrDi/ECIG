package com.elebusiness.service;

public class ActiveGenerationTaskException extends IllegalStateException {

    public ActiveGenerationTaskException() {
        super("已有生成任务正在处理，请先等待完成或停止当前任务");
    }
}
