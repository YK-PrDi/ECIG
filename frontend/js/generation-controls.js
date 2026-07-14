(function (global) {
    'use strict';

    function createGenerationControls(options) {
        var deps = Object.assign({}, options || {});
        var activeTaskId = null;
        var stopping = false;
        var stopRequest = null;

        function fetchImpl() {
            return deps.fetch || global.fetch.bind(global);
        }

        function notify() {
            if (typeof deps.onStateChange === 'function') {
                deps.onStateChange({ taskId: activeTaskId, active: !!activeTaskId, stopping: stopping });
            }
        }

        function activate(taskId) {
            activeTaskId = taskId || null;
            stopping = false;
            stopRequest = null;
            notify();
        }

        function complete(taskId) {
            if (taskId && taskId !== activeTaskId) return;
            activeTaskId = null;
            stopping = false;
            stopRequest = null;
            notify();
        }

        function refresh() {
            notify();
        }

        function stopActiveTask() {
            if (!activeTaskId) return Promise.resolve(null);
            if (stopRequest) return stopRequest;

            stopping = true;
            notify();
            var taskId = activeTaskId;
            stopRequest = fetchImpl()('/api/task/' + encodeURIComponent(taskId) + '/stop', { method: 'POST' })
                .then(function (response) {
                    return response.json().then(function (data) {
                        if (!response.ok || !data.success) {
                            throw new Error(data.error || '停止任务失败');
                        }
                        return data;
                    });
                })
                .catch(function (error) {
                    stopping = false;
                    stopRequest = null;
                    notify();
                    throw error;
                });
            return stopRequest;
        }

        return {
            activate: activate,
            complete: complete,
            refresh: refresh,
            stopActiveTask: stopActiveTask,
            isActive: function () { return !!activeTaskId; },
            isStopping: function () { return stopping; },
            activeTaskId: function () { return activeTaskId; }
        };
    }

    var defaultControls = createGenerationControls();
    global.AiStudioGenerationControls = {
        create: createGenerationControls,
        activate: defaultControls.activate,
        complete: defaultControls.complete,
        refresh: defaultControls.refresh,
        stopActiveTask: defaultControls.stopActiveTask,
        isActive: defaultControls.isActive,
        isStopping: defaultControls.isStopping,
        activeTaskId: defaultControls.activeTaskId
    };
})(window);
