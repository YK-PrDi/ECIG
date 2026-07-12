(function (global) {
    'use strict';

    async function readJson(response, label) {
        var contentType = String(response.headers && response.headers.get
            ? response.headers.get('content-type') || ''
            : '').toLowerCase();
        var text = await response.text();
        if (!text) return {};
        if (contentType.indexOf('application/json') === -1) {
            throw new Error((label || '接口') + '返回的不是 JSON，可能访问到了前端 HTML 页面或代理配置错误（HTTP ' + response.status + '）');
        }
        try {
            return JSON.parse(text);
        } catch (e) {
            throw new Error((label || '接口') + '返回 JSON 解析失败：' + e.message);
        }
    }

    async function fetchJson(url, options, label) {
        var response = await global.fetch(url, options || {});
        var data = await readJson(response, label);
        return { response: response, data: data };
    }

    var api = {
        readJson: readJson,
        fetchJson: fetchJson
    };

    global.AiStudioApi = api;
    global.readApiJson = readJson;
})(window);
