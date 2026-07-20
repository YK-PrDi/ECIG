const fs = require('fs');
const path = require('path');
const assert = require('assert');

const root = path.resolve(__dirname, '..');
const generateController = fs.readFileSync(
  path.join(root, 'src/main/java/com/elebusiness/controller/GenerateController.java'),
  'utf8'
);
const materialController = fs.readFileSync(
  path.join(root, 'src/main/java/com/elebusiness/controller/KaiPinMaterialController.java'),
  'utf8'
);

function section(source, start, end) {
  const startIndex = source.indexOf(start);
  const endIndex = source.indexOf(end, startIndex + start.length);
  assert.ok(startIndex >= 0 && endIndex > startIndex, `找不到源码区段: ${start}`);
  return source.slice(startIndex, endIndex);
}

function assertPersistenceGuarded(sourceSection, label) {
  assert.match(
    sourceSection,
    /for \(CompletableFuture<String> f : futures\) \{\s*if \(task\.isCancelled\(\)\) break;/,
    `${label} 收集结果前必须检查取消状态`
  );
  assert.match(
    sourceSection,
    /if \(!task\.isCancelled\(\)\) \{[\s\S]*cosService\.upload[\s\S]*historyService\.recordGeneration/,
    `${label} 的 COS 上传和历史记录必须位于未取消保护块内`
  );
}

const excelSection = section(generateController, 'public ResponseEntity<Map<String, Object>> kaipinExcelGenerate(', '@PostMapping("/api/custom_generate")');
const customSection = section(generateController, 'public ResponseEntity<Map<String, Object>> customGenerate(', '@PostMapping("/api/inpaint")');
const materialSection = section(materialController, 'public ResponseEntity<Map<String, Object>> generateWithMaterials(', 'private Map<Long, String> parseMaterialPromptOverrides');
const standardSection = section(generateController, 'public ResponseEntity<Map<String, Object>> generate(', '@PostMapping("/api/product_analyze")');

assertPersistenceGuarded(excelSection, 'Excel 开品');
assertPersistenceGuarded(customSection, '自定义生成');
assertPersistenceGuarded(materialSection, '素材库开品');
assert.match(
  standardSection,
  /generateDetailImages[\s\S]*if \(task\.isCancelled\(\)\) break;[\s\S]*historyService\.recordGeneration/,
  '标准模式必须在写成功结果和历史前再次检查取消状态'
);
assert.match(
  standardSection,
  /if \(!task\.isCancelled\(\)\) \{[\s\S]*__AI_THOUGHT__0/,
  '标准模式取消后不应再写思考信息'
);

console.log('hard cancel persistence guards check passed');
