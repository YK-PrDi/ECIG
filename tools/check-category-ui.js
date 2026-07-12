const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const indexHtml = fs.readFileSync(path.join(root, 'frontend', 'index.html'), 'utf8');
const categoryTree = fs.readFileSync(path.join(root, 'frontend', 'data', 'categories.js'), 'utf8');
const publicApiPaths = fs.readFileSync(path.join(root, 'src', 'main', 'java', 'com', 'elebusiness', 'config', 'PublicApiPaths.java'), 'utf8');

function fail(message) {
  console.error(message);
  process.exit(1);
}

function expect(source, pattern, message) {
  if (!pattern.test(source)) fail(message);
}

expect(indexHtml, /<script\s+src=["']data\/categories\.js["']><\/script>/, 'index.html must load the category tree script');
expect(indexHtml, /function\s+openCustomCatModal\s*\(/, 'custom product category modal function is missing');
expect(indexHtml, /id=["']customCatModal["']/, 'custom product category modal markup is missing');
expect(indexHtml, /function\s+openEcommerceModal\s*\(/, 'ecommerce settings modal function is missing');
expect(indexHtml, /id=["']ec-cat-cascader["']/, 'ecommerce category cascader markup is missing');
expect(indexHtml, /fetch\(["']\/api\/categories\/index["']\)/, 'dynamic category index fetch is missing');
expect(indexHtml, /fetch\(["']\/api\/prompts["']\)/, 'prompt tree fetch used by category prompts is missing');

expect(categoryTree, /window\.EC_CATEGORY_TREE\s*=\s*\[/, 'category tree global is missing');
expect(categoryTree, /display\s*:/, 'category tree appears to be empty');

expect(publicApiPaths, /\/api\/prompts/, 'public API whitelist must include prompt tree');
expect(publicApiPaths, /\/api\/categories\/index/, 'public API whitelist must include category index');

console.log('category UI checks passed');
