const fs = require('fs');
const path = require('path');

const html = fs.readFileSync(path.join(__dirname, '..', 'frontend', 'index.html'), 'utf8');
const scripts = Array.from(html.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/gi), m => m[1]);

let ok = 0;
for (const script of scripts) {
  new Function(script);
  ok += 1;
}

console.log(`scripts ${ok} ok`);
