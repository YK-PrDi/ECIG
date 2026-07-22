(function (global) {
    'use strict';

    function defaultFormatSeconds(totalSec) {
        if (!Number.isFinite(totalSec) || totalSec < 0) return '估算中...';
        totalSec = Math.round(totalSec);
        var minutes = Math.floor(totalSec / 60);
        var seconds = totalSec % 60;
        return minutes + ':' + String(seconds).padStart(2, '0');
    }

    function createTaskPolling(options) {
        var deps = Object.assign({}, options || {});
        var timers = deps.timers || new Map();

        function api() {
            return deps.api || global.AiStudioApi;
        }

        function fetchImpl() {
            return deps.fetch || global.fetch.bind(global);
        }

        function stop(taskId) {
            var task = timers.get(taskId);
            if (!task) return;
            if (task.pollTimer) (deps.clearInterval || global.clearInterval)(task.pollTimer);
            if (task.elapsedTimer) (deps.clearInterval || global.clearInterval)(task.elapsedTimer);
            timers.delete(taskId);
        }

        async function fetchStatus(taskId) {
            var response = await fetchImpl()('/api/task/' + encodeURIComponent(taskId));
            if (response.status === 401) {
                var expired = new Error('登录已过期，请重新登录');
                expired.sessionExpired = true;
                throw expired;
            }
            var data = await api().readJson(response, '任务状态接口');
            if (!response.ok || data.error || data.success === false) {
                throw new Error(data.error || ('任务状态接口请求失败：HTTP ' + response.status));
            }
            return data;
        }

        function progressCard(taskId) {
            var documentRef = deps.document || global.document;
            return documentRef && documentRef.querySelector
                ? documentRef.querySelector('.progress-card[data-task-id="' + taskId + '"]')
                : null;
        }

        function fmtSec(value) {
            return (deps.fmtSec || global.fmtSec || defaultFormatSeconds)(value);
        }

        function updateCard(taskId, pct, countText, etaText, currentText) {
            return (deps.updateProgressCard || global.updateProgressCard)(taskId, pct, countText, etaText, currentText);
        }

        function finalize(taskId, data) {
            return (deps.finalizeProgressCard || global.finalizeProgressCard)(taskId, data);
        }

        function renderImages(results) {
            return (deps.renderGeneratedImages || global.renderGeneratedImages)(results);
        }

        function addToBoard(results) {
            return (deps.autoAddToBoard || global.autoAddToBoard)(results);
        }

        function showLogin(message) {
            return (deps.showLoginOverlay || global.showLoginOverlay)(message);
        }

        function logPollingError(error) {
            (deps.console || global.console).warn('task polling failed:', error && error.message ? error.message : error);
        }

        async function pollOnce(taskId, startTime) {
            var data = await fetchStatus(taskId);
            var progress = data.progress || 0;
            var total = data.total || 1;
            var pct = Math.round((progress / total) * 100);
            var elapsedSec = (Date.now() - startTime) / 1000;
            var etaText = '估算中...';
            if (progress > 0 && progress < total) {
                etaText = fmtSec((elapsedSec / progress) * (total - progress));
            } else if (progress >= total) {
                etaText = '收尾中...';
            }

            var current = data.currentProduct || '';
            var currentText = current
                ? '正在处理：' + current
                : (progress < total ? '正在生成第 ' + (progress + 1) + ' 张...' : '');
            updateCard(taskId, pct, progress + '/' + total, etaText, currentText);

            var allResults = data.results || [];
            var task = timers.get(taskId);
            if (task) {
                var newItems = allResults.slice(task.seenCount);
                task.seenCount += newItems.length;
                var newSuccess = newItems.filter(function (result) {
                    return result.status === 'success' && !(result.name || '').startsWith('__');
                });
                if (newSuccess.length > 0) {
                    renderImages(newSuccess);
                    addToBoard(newSuccess);
                }
            }

            if (data.status === 'stopping') {
                updateCard(taskId, pct, progress + '/' + total, '正在停止', '正在中断当前调用...');
            }

            if (data.status !== 'running' && data.status !== 'pending' && data.status !== 'stopping') {
                stop(taskId);
                finalize(taskId, data);
            }
            return data;
        }

        function start(taskId) {
            var existing = timers.get(taskId);
            if (existing) {
                if (existing.pollTimer) (deps.clearInterval || global.clearInterval)(existing.pollTimer);
                if (existing.elapsedTimer) (deps.clearInterval || global.clearInterval)(existing.elapsedTimer);
            }

            var startTime = Date.now();
            var entry = { pollTimer: null, elapsedTimer: null, seenCount: 0 };
            timers.set(taskId, entry);

            entry.elapsedTimer = (deps.setInterval || global.setInterval)(function () {
                var card = progressCard(taskId);
                if (!card) return;
                var elapsed = card.querySelector('[data-role="elapsed"]');
                if (elapsed) elapsed.textContent = fmtSec((Date.now() - startTime) / 1000);
            }, 1000);

            entry.pollTimer = (deps.setInterval || global.setInterval)(async function () {
                try {
                    await pollOnce(taskId, startTime);
                } catch (error) {
                    if (error && error.sessionExpired) {
                        stop(taskId);
                        showLogin(error.message);
                    } else {
                        logPollingError(error);
                    }
                }
            }, 800);

            (deps.setTimeout || global.setTimeout)(function () {
                fetchStatus(taskId).then(function (data) {
                    var progress = data.progress || 0;
                    var total = data.total || 1;
                    updateCard(taskId, Math.round((progress / total) * 100), progress + '/' + total, '估算中...', '');
                }).catch(function (error) {
                    if (error && error.sessionExpired) {
                        stop(taskId);
                        showLogin(error.message);
                    }
                });
            }, 50);
        }

        function configure(nextOptions) {
            deps = Object.assign(deps, nextOptions || {});
            if (nextOptions && nextOptions.timers) timers = nextOptions.timers;
        }

        return {
            configure: configure,
            start: start,
            stop: stop,
            fetchStatus: fetchStatus,
            pollOnce: pollOnce,
            timers: timers
        };
    }

    var defaultPolling = createTaskPolling();

    var exported = {
        create: createTaskPolling,
        configure: defaultPolling.configure,
        startPolling: defaultPolling.start,
        stopPollingTask: defaultPolling.stop,
        fetchTaskStatus: defaultPolling.fetchStatus,
        pollOnce: defaultPolling.pollOnce
    };

    global.AiStudioTaskPolling = exported;
    global.startPolling = defaultPolling.start;
    global.stopPollingTask = defaultPolling.stop;
    global.fetchTaskStatus = defaultPolling.fetchStatus;
})(window);
