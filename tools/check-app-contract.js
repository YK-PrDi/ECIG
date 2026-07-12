const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const htmlPath = path.join(root, 'frontend', 'index.html');
const html = fs.readFileSync(htmlPath, 'utf8');
const renderedHtml = html.replace(/<!--[\s\S]*?-->/g, '');
const firstInlineScript = renderedHtml.indexOf('<script>');
const staticMarkup = firstInlineScript >= 0 ? renderedHtml.slice(0, firstInlineScript) : renderedHtml;
const frontendJsDir = path.join(root, 'frontend', 'js');
const frontendScripts = fs.readdirSync(frontendJsDir)
    .filter(name => name.endsWith('.js'))
    .map(name => fs.readFileSync(path.join(frontendJsDir, name), 'utf8'));
const source = [html, ...frontendScripts].join('\n');

function fail(message) {
    console.error(message);
    process.exitCode = 1;
}

function localAssetExists(asset) {
    if (asset.startsWith('http://') || asset.startsWith('https://') || asset.startsWith('//')) return true;
    const normalized = asset.split(/[?#]/, 1)[0];
    if (!normalized || normalized.startsWith('/')) return true;
    return fs.existsSync(path.join(root, 'frontend', normalized));
}

for (const match of staticMarkup.matchAll(/<(?:script|link)\b[^>]+(?:src|href)\s*=\s*(["'])([^"']+)\1/gi)) {
    if (!localAssetExists(match[2])) fail(`缺少前端资源: ${match[2]}`);
}

const declaredFunctions = new Set();
for (const match of source.matchAll(/(?:function\s+|(?:window|global)\.)([A-Za-z_$][\w$]*)\s*(?:=\s*(?:async\s*)?function|\()/g)) {
    declaredFunctions.add(match[1]);
}
for (const match of source.matchAll(/(?:window|global)\.([A-Za-z_$][\w$]*)\s*=\s*(?:async\s*)?\(?/g)) {
    declaredFunctions.add(match[1]);
}

const ignoredCalls = new Set([
    'if', 'for', 'while', 'switch', 'catch', 'return', 'var', 'let', 'const',
    'setTimeout', 'setInterval', 'alert', 'confirm', 'fetch', 'encodeURIComponent',
    'JSON', 'String', 'Number', 'Date', 'Array', 'Object', 'Math', 'Promise',
    'requestAnimationFrame', 'stopPropagation', 'preventDefault', 'remove', 'click'
]);
const eventFunctions = new Set();
for (const match of renderedHtml.matchAll(/\bon(?:click|change|input|keydown|keyup|blur|submit|contextmenu|dragstart)\s*=\s*(["'])([\s\S]*?)\1/gi)) {
    for (const call of match[2].matchAll(/(?:^|[^.\w$])([A-Za-z_$][\w$]*)\s*\(/g)) {
        if (!ignoredCalls.has(call[1])) eventFunctions.add(call[1]);
    }
}
for (const name of [...eventFunctions].sort()) {
    if (!declaredFunctions.has(name)) fail(`内联事件引用了不存在的函数: ${name}`);
}

const controllerDir = path.join(root, 'src', 'main', 'java', 'com', 'elebusiness', 'controller');
const backendRoutes = new Set();
for (const file of fs.readdirSync(controllerDir).filter(name => name.endsWith('.java'))) {
    const controller = fs.readFileSync(path.join(controllerDir, file), 'utf8');
    const classIndex = controller.indexOf('class ');
    const preamble = classIndex >= 0 ? controller.slice(0, classIndex) : '';
    const classRoute = [...preamble.matchAll(/@RequestMapping\(\s*["']([^"']*)["']\s*\)/g)].at(-1)?.[1] || '';
    for (const match of controller.matchAll(/@(Get|Post|Put|Delete|Patch)Mapping(?:\(\s*(?:value\s*=\s*)?["']([^"']*)["'][^)]*\))?/g)) {
        const fullRoute = `${classRoute}${match[2] || ''}`.replace(/\/$/, '');
        if (fullRoute.startsWith('/api/')) backendRoutes.add(fullRoute);
    }
}

function matchesBackendRoute(route) {
    return [...backendRoutes].some(template => {
        const pattern = `^${template
            .replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
            .replace(/\\\{[^}]+\\\}/g, '[^/]+')}$`;
        return new RegExp(pattern).test(route);
    });
}

const frontendRoutes = new Set();
for (const match of source.matchAll(/["'](\/api\/[A-Za-z0-9_./-]+)(?:\?|["'])/g)) {
    const route = match[1].replace(/\/$/, '');
    if (route) frontendRoutes.add(route);
}
for (const route of [...frontendRoutes].sort()) {
    // 动态拼接的路径前缀会在运行时带上 ID，静态分析无法得到最后一段。
    if (!matchesBackendRoute(route) && !route.endsWith('/refs') && !route.endsWith('/task')
            && !route.endsWith('/payment-orders') && !route.endsWith('/generations')) {
        fail(`前端调用的 API 没有后端路由: ${route}`);
    }
}

if (!process.exitCode) {
    console.log(`app contract checks passed: assets, ${eventFunctions.size} event handlers, ${frontendRoutes.size} API routes`);
}
