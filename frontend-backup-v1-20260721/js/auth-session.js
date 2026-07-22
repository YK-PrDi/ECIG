(function (global) {
    'use strict';

    var config = {};
    var heartbeatTimer = null;
    var heartbeatInFlight = false;
    var sessionExpiredShown = false;
    var visibilityBound = false;

    function doc() {
        return config.document || global.document;
    }

    function fetchImpl() {
        return config.fetch || global.fetch.bind(global);
    }

    function api() {
        return config.api || global.AiStudioApi;
    }

    function setOverlayVisible(visible) {
        if (typeof config.setLoginOverlayVisible === 'function') {
            config.setLoginOverlayVisible(visible);
            return;
        }
        var overlay = doc().getElementById('loginOverlay');
        if (overlay) overlay.style.display = visible ? 'flex' : 'none';
    }

    function clearPassword() {
        var pass = doc().getElementById('loginPass');
        if (pass) pass.value = '';
    }

    function loginUsername() {
        if (typeof config.getUsername === 'function') return config.getUsername();
        var input = doc().getElementById('loginUser');
        var value = input && input.value ? String(input.value).trim() : 'admin';
        return value || 'admin';
    }

    function loginPassword() {
        if (typeof config.getPassword === 'function') return config.getPassword();
        var input = doc().getElementById('loginPass');
        return input ? input.value : '';
    }

    function updateUser(user) {
        if (typeof config.updateCurrentUser === 'function') {
            config.updateCurrentUser(user);
        } else if (typeof global.updateCurrentUser === 'function') {
            global.updateCurrentUser(user);
        }
    }

    function startApp() {
        if (typeof config.initApp === 'function') {
            config.initApp();
        } else if (typeof global.initApp === 'function') {
            global.initApp();
        }
    }

    function notify(message) {
        if (!message) return;
        if (typeof config.alert === 'function') {
            config.alert(message);
        } else if (typeof global.alert === 'function') {
            global.alert(message);
        }
    }

    function stopHeartbeat() {
        if (heartbeatTimer) {
            (config.clearInterval || global.clearInterval)(heartbeatTimer);
            heartbeatTimer = null;
        }
    }

    function showLoginOverlay(message) {
        stopHeartbeat();
        global.CURRENT_USER = null;
        clearPassword();
        setOverlayVisible(true);
        if (message && !sessionExpiredShown) {
            sessionExpiredShown = true;
            notify(message);
        }
    }

    async function sendHeartbeat() {
        if (heartbeatInFlight) return;
        heartbeatInFlight = true;
        try {
            var response = await fetchImpl()('/api/auth/heartbeat', { cache: 'no-store' });
            var data = await api().readJson(response, '登录心跳接口');
            if (!response.ok || !data.authenticated) {
                showLoginOverlay('登录已过期，请重新登录');
                return;
            }
            sessionExpiredShown = false;
            if (data.user) updateUser(data.user);
        } catch (e) {
            (config.console || global.console).warn('session heartbeat failed:', e.message);
        } finally {
            heartbeatInFlight = false;
        }
    }

    function startHeartbeat() {
        if (heartbeatTimer) return;
        sessionExpiredShown = false;
        sendHeartbeat();
        heartbeatTimer = (config.setInterval || global.setInterval)(sendHeartbeat, 60000);
    }

    async function handleLogin() {
        try {
            var response = await fetchImpl()('/api/auth/login', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    username: loginUsername(),
                    password: loginPassword()
                })
            });
            var data = await api().readJson(response, '登录接口');
            if (data.success) {
                updateUser(data.user);
                setOverlayVisible(false);
                startHeartbeat();
                startApp();
            } else {
                notify('验证失败');
            }
        } catch (e) {
            notify('登录请求失败：' + e.message);
        }
    }

    async function handleLogout() {
        try {
            await fetchImpl()('/api/auth/logout', { method: 'POST' });
        } catch (_) {}
        stopHeartbeat();
        global.CURRENT_USER = null;
        clearPassword();
        setOverlayVisible(true);
    }

    async function checkSession() {
        try {
            var response = await fetchImpl()('/api/auth/check');
            var data = await api().readJson(response, '登录状态接口');
            if (data.authenticated) {
                updateUser(data.user);
                setOverlayVisible(false);
                startHeartbeat();
                startApp();
            }
        } catch (_) {}
    }

    function bindVisibilityHeartbeat() {
        if (visibilityBound || !doc() || typeof doc().addEventListener !== 'function') return;
        visibilityBound = true;
        doc().addEventListener('visibilitychange', function () {
            if (doc().visibilityState === 'visible' && heartbeatTimer) {
                sendHeartbeat();
            }
        });
    }

    function configure(options) {
        config = Object.assign(config, options || {});
        bindVisibilityHeartbeat();
    }

    var auth = {
        configure: configure,
        checkSession: checkSession,
        handleLogin: handleLogin,
        handleLogout: handleLogout,
        startHeartbeat: startHeartbeat,
        stopHeartbeat: stopHeartbeat,
        sendHeartbeat: sendHeartbeat,
        showLoginOverlay: showLoginOverlay
    };

    global.AiStudioAuth = auth;
    global.handleLogin = handleLogin;
    global.handleLogout = handleLogout;
    global.showLoginOverlay = showLoginOverlay;
    global.startSessionHeartbeat = startHeartbeat;
    global.stopSessionHeartbeat = stopHeartbeat;

    configure();
})(window);
