(function (global) {
  'use strict';

  const WHITE_BACKGROUND_PATTERNS = [
    /白底(?:图|商品图|产品图|主图|照片|摄影图)?/,
    /纯白(?:色)?(?:无缝)?背景/,
    /白色(?:无缝)?背景/,
    /背景(?:改|换|设|设置|处理|做)?(?:成|为)?纯白(?:色)?/,
    /背景(?:改|换|设|设置|处理|做)?(?:成|为)?白色/
  ];

  const WHITE_BACKGROUND_CONSTRAINTS = [
    '【白底商品图直出】以用户上传图片中的产品为唯一产品主体。',
    '【产品一致性】严格保持产品外形、比例、颜色、材质、结构、接口、按键、纹理和核心识别细节一致，不得改款、增减部件或改变颜色关系。',
    '【背景要求】使用均匀、干净的纯白无缝背景，不使用渐变、纹理、环境布景或彩色光污染。',
    '【光影要求】保留自然、真实、克制的接触阴影，使产品稳定落地、不悬浮；光线中性，边缘和材质清晰。',
    '【构图要求】只展示单一产品主体，采用标准白底商品图构图，完整呈现产品，不生成额外产品。',
    '【禁止场景化】无场景、无道具、无人物、无装饰、无营销氛围、无卖点视觉化表达。',
    '【禁止文字】画面不要出现任何文字、标题、标签、水印或额外 Logo。'
  ].join('\n');

  function hasExplicitWhiteBackgroundIntent(prompt) {
    const value = String(prompt || '').trim();
    return value !== '' && WHITE_BACKGROUND_PATTERNS.some(pattern => pattern.test(value));
  }

  function build(options) {
    const input = options || {};
    const userPrompt = input.userPrompt || '';
    const isWhiteBackgroundDirect = input.styleKey === 'original'
      && input.skipAnalysis === true
      && input.withText === false
      && hasExplicitWhiteBackgroundIntent(userPrompt);

    if (isWhiteBackgroundDirect) {
      return {
        prompt: [userPrompt, WHITE_BACKGROUND_CONSTRAINTS].filter(Boolean).join('\n'),
        isWhiteBackgroundDirect: true
      };
    }

    return {
      prompt: [userPrompt, input.stylePrompt, input.categoryPrompt].filter(Boolean).join('\n') || '自动生成',
      isWhiteBackgroundDirect: false
    };
  }

  global.AiStudioCustomDirectPrompt = {
    hasExplicitWhiteBackgroundIntent,
    build
  };
})(typeof window !== 'undefined' ? window : globalThis);
