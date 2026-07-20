(function (global) {
    'use strict';

    function createGenerationControls(options) {
        var deps = Object.assign({}, options || {});
        var tasks = new Map();

        function fetchImpl() {
            return deps.fetch || global.fetch.bind(global);
        }

        function notify(taskId) {
            var task = taskId ? tasks.get(taskId) : null;
            if (typeof deps.onStateChange === 'function') {
                deps.onStateChange({
                    taskId: taskId || null,
                    active: !!task,
                    stopping: !!(task && task.stopping),
                    activeTaskIds: Array.from(tasks.keys())
                });
            }
        }

        function activate(taskId) {
            if (!taskId) return;
            if (!tasks.has(taskId)) {
                tasks.set(taskId, { stopping: false, stopRequest: null });
            }
            notify(taskId);
        }

        function complete(taskId) {
            if (!taskId) return;
            tasks.delete(taskId);
            notify(taskId);
        }

        function stopTask(taskId) {
            var task = tasks.get(taskId);
            if (!task) return Promise.resolve(null);
            if (task.stopRequest) return task.stopRequest;

            task.stopping = true;
            notify(taskId);
            task.stopRequest = fetchImpl()('/api/task/' + encodeURIComponent(taskId) + '/stop', { method: 'POST' })
                .then(function (response) {
                    return response.json().then(function (data) {
                        if (!response.ok || !data.success) {
                            throw new Error(data.error || '停止任务失败');
                        }
                        return data;
                    });
                })
                .catch(function (error) {
                    task.stopping = false;
                    task.stopRequest = null;
                    notify(taskId);
                    throw error;
                });
            return task.stopRequest;
        }

        return {
            activate: activate,
            complete: complete,
            stopTask: stopTask,
            isActive: function (taskId) { return taskId ? tasks.has(taskId) : tasks.size > 0; },
            isStopping: function (taskId) {
                if (taskId) return !!(tasks.get(taskId) && tasks.get(taskId).stopping);
                return Array.from(tasks.values()).some(function (task) { return task.stopping; });
            },
            activeTaskIds: function () { return Array.from(tasks.keys()); }
        };
    }

    var defaultControls = createGenerationControls();
    global.AiStudioGenerationControls = {
        create: createGenerationControls,
        activate: defaultControls.activate,
        complete: defaultControls.complete,
        stopTask: defaultControls.stopTask,
        isActive: defaultControls.isActive,
        isStopping: defaultControls.isStopping,
        activeTaskIds: defaultControls.activeTaskIds
    };
})(window);
