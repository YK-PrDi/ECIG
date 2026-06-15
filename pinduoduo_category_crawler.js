/**
 * 拼多多分类爬虫脚本
 * 用于批量爬取所有分类数据并写入文件
 */

const fs = require('fs');
const path = require('path');

// 分类数据结构
const categoryTree = {
  "家居生活": {
    completed: true,
    children: {}
  }
};

// 已爬取的分类列表
const crawledCategories = [
  "家居生活"
];

// 待爬取的分类列表
const pendingCategories = [
  "数码电器",
  "美容个护",
  "服饰箱包",
  "家纺家具家装",
  "母婴玩具",
  "医药健康",
  "食品保健",
  "汽配摩托",
  "运动户外",
  "水果生鲜",
  "虚拟商品"
];

// 保存分类数据
function saveCategoryData(level1, level2, level3List) {
  if (!categoryTree[level1]) {
    categoryTree[level1] = {
      completed: false,
      children: {}
    };
  }

  categoryTree[level1].children[level2] = level3List;

  // 写入文件
  const filePath = path.join(__dirname, '拼多多分类数据.json');
  fs.writeFileSync(filePath, JSON.stringify(categoryTree, null, 2), 'utf-8');
}

// 获取下一个待爬取的分类
function getNextCategory() {
  return pendingCategories[0];
}

// 标记分类为已完成
function markCategoryCompleted(level1) {
  if (categoryTree[level1]) {
    categoryTree[level1].completed = true;
  }

  const index = pendingCategories.indexOf(level1);
  if (index > -1) {
    pendingCategories.splice(index, 1);
  }

  crawledCategories.push(level1);
}

// 导出为 Markdown 格式
function exportToMarkdown() {
  let markdown = '# 拼多多商品分类数据\n\n';
  markdown += `# 爬取时间: ${new Date().toISOString().split('T')[0]}\n\n`;
  markdown += '## 一级分类列表\n\n';

  const allCategories = [...crawledCategories, ...pendingCategories];
  allCategories.forEach((cat, index) => {
    const status = crawledCategories.includes(cat) ? '✅' : '⏳';
    markdown += `${index + 1}. ${status} ${cat}\n`;
  });

  markdown += '\n---\n\n## 详细分类树\n\n';

  for (const [level1, data] of Object.entries(categoryTree)) {
    if (data.completed) {
      markdown += `### ${level1}\n\n`;

      for (const [level2, level3List] of Object.entries(data.children)) {
        markdown += `#### ${level2}\n`;
        level3List.forEach(level3 => {
          markdown += `- ${level3}\n`;
        });
        markdown += '\n';
      }
    }
  }

  const mdPath = path.join(__dirname, '拼多多分类数据.md');
  fs.writeFileSync(mdPath, markdown, 'utf-8');
}

module.exports = {
  saveCategoryData,
  getNextCategory,
  markCategoryCompleted,
  exportToMarkdown,
  categoryTree,
  crawledCategories,
  pendingCategories
};