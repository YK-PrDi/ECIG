package com.elebusiness.service;

public class ActiveGenerationTaskException extends IllegalStateException {

    public ActiveGenerationTaskException() {
        super("已有生成任务正在处理，请先等待完成或停止当前任务");
    }

    public ActiveGenerationTaskException(int limit) {
        super("当前账号同时最多运行 " + limit + " 个生成任务，请等待已有任务完成或先停止任务");
    }
}
