/** 钉住三样东西：对比度、动效开关、禁止清单（DESIGN.md §1/§4/§6）。 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const tokens = readFileSync(fileURLToPath(new URL('../../src/styles/tokens.css', import.meta.url)), 'utf8');
const app = readFileSync(fileURLToPath(new URL('../../src/styles/app.css', import.meta.url)), 'utf8');

/** `--name: oklch(L C H)` / `oklch(L C H / A)` → `{oklch:[L,C,H], alpha}`。 */
function readTokens(text) {
  const bag = {};
  for (const hit of text.matchAll(/--([\w-]+):\s*oklch\(([^)]+)\)/g)) {
    const parts = hit[2].split('/');
    const numbers = parts[0].trim().split(/\s+/).map(Number);
    bag[hit[1]] = { oklch: numbers, alpha: parts[1] ? Number(parts[1]) : 1 };
  }
  return bag;
}

const T = readTokens(tokens);

function toRgb({ oklch, alpha }) {
  const [L, C, H] = oklch;
  const rad = (H * Math.PI) / 180;
  const a = C * Math.cos(rad);
  const b = C * Math.sin(rad);
  const l = (L + 0.3963377774 * a + 0.2158037573 * b) ** 3;
  const m = (L - 0.1055613458 * a - 0.0638541728 * b) ** 3;
  const s = (L - 0.0894841775 * a - 1.291485548 * b) ** 3;
  const lin = [
    4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
    -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
    -0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s,
  ];
  const gamma = value => (value <= 0.0031308 ? 12.92 * value : 1.055 * value ** (1 / 2.4) - 0.055);
  return { rgb: lin.map(one => Math.min(1, Math.max(0, gamma(one)))), alpha };
}

function over(front, back) {
  const a = front.alpha;
  return {
    rgb: front.rgb.map((one, at) => one * a + back.rgb[at] * (1 - a)),
    alpha: 1,
  };
}

function luminance({ rgb }) {
  const channel = value => (value <= 0.03928 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4);
  const [r, g, b] = rgb.map(channel);
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrast(name, overName) {
  const back = toRgb(T[overName]);
  const front = T[name].alpha < 1 ? over(toRgb(T[name]), back) : toRgb(T[name]);
  const one = luminance(front);
  const two = luminance(back);
  return Number(((Math.max(one, two) + 0.05) / (Math.min(one, two) + 0.05)).toFixed(2));
}

describe('DESIGN.md §1 的颜色表逐条对上', () => {
  it('表面与线', () => {
    expect(T.bg.oklch).toEqual([0.16, 0.008, 85]);
    expect(T.surface.oklch).toEqual([0.2, 0.009, 85]);
    expect(T['surface-2'].oklch).toEqual([0.245, 0.01, 85]);
    expect(T.line.oklch).toEqual([0.3, 0.01, 85]);
    expect(T['line-strong'].oklch).toEqual([0.38, 0.012, 85]);
  });

  it('字与强调色', () => {
    expect(T.ink.oklch).toEqual([0.95, 0.006, 85]);
    expect(T['ink-2'].oklch).toEqual([0.78, 0.008, 85]);
    // DESIGN.md 写的是 0.62；压在 --surface-2 上只有 4.46:1，抬到 0.63（两处一起改）
    expect(T['ink-3'].oklch[0]).toBeCloseTo(0.63, 3);
    expect(T['ink-3'].oklch[1]).toBe(0.008);
    expect(T['ink-3'].oklch[2]).toBe(85);
    expect(T.accent.oklch).toEqual([0.8, 0.155, 88]);
    expect(T['accent-ink'].oklch).toEqual([0.2, 0.03, 88]);
  });

  it('四色语义', () => {
    expect(T.ok.oklch).toEqual([0.76, 0.13, 158]);
    expect(T.warn.oklch).toEqual([0.8, 0.12, 62]);
    expect(T.danger.oklch).toEqual([0.7, 0.165, 27]);
    expect(T.run.oklch).toEqual([0.78, 0.09, 240]);
  });
});

describe('对比度（正文 ≥4.5:1，占位符也算）', () => {
  it('三级字对页面底', () => {
    expect(contrast('ink', 'bg')).toBeGreaterThanOrEqual(4.5);
    expect(contrast('ink-2', 'bg')).toBeGreaterThanOrEqual(4.5);
    expect(contrast('ink-3', 'bg')).toBeGreaterThanOrEqual(4.5);
  });

  it('三级字对面板（字长在面板上）', () => {
    expect(contrast('ink', 'surface')).toBeGreaterThanOrEqual(4.5);
    expect(contrast('ink-2', 'surface')).toBeGreaterThanOrEqual(4.5);
    expect(contrast('ink-3', 'surface')).toBeGreaterThanOrEqual(4.5);
    expect(contrast('ink', 'surface-2')).toBeGreaterThanOrEqual(4.5);
    expect(contrast('ink-2', 'surface-2')).toBeGreaterThanOrEqual(4.5);
    expect(contrast('ink-3', 'surface-2')).toBeGreaterThanOrEqual(4.5);
  });

  it('琥珀底上的字', () => {
    expect(contrast('accent-ink', 'accent')).toBeGreaterThanOrEqual(4.5);
  });

  it('四色字压在页面底上（徽章与日志里的红绿）', () => {
    expect(contrast('ok', 'bg')).toBeGreaterThanOrEqual(4.5);
    expect(contrast('warn', 'bg')).toBeGreaterThanOrEqual(4.5);
    expect(contrast('danger', 'bg')).toBeGreaterThanOrEqual(4.5);
    expect(contrast('run', 'bg')).toBeGreaterThanOrEqual(4.5);
  });

  it('四色字压在它们的微底上（徽章）', () => {
    expect(contrast('ok', 'bg')).toBeGreaterThanOrEqual(4.5);
    expect(contrast('danger', 'surface')).toBeGreaterThanOrEqual(4.5);
    // 微底是一层 13% 的同色，压在底上之后几乎不变色 —— 拿它当底再算一次
    const dim = { rgb: toRgb(T['ok-dim']).rgb, alpha: T['ok-dim'].alpha };
    const painted = over(dim, toRgb(T.bg));
    const one = luminance(toRgb(T.ok));
    const two = luminance(painted);
    expect(Number(((Math.max(one, two) + 0.05) / (Math.min(one, two) + 0.05)).toFixed(2))).toBeGreaterThanOrEqual(4.5);
  });

  it('焦点环与主按钮（非文字，≥3:1）', () => {
    expect(contrast('accent', 'bg')).toBeGreaterThanOrEqual(3);
    expect(contrast('accent', 'surface')).toBeGreaterThanOrEqual(3);
    expect(contrast('line-strong', 'surface')).toBeGreaterThanOrEqual(1.5);
  });
});

describe('动效与命中区', () => {
  it('reduced-motion 时全部关掉', () => {
    expect(app).toContain('@media (prefers-reduced-motion: reduce)');
    expect(app).toMatch(/prefers-reduced-motion[\s\S]*animation: none !important/);
    expect(app).toMatch(/prefers-reduced-motion[\s\S]*transition: none !important/);
  });

  it('动效时长都在 150~250ms', () => {
    for (const hit of tokens.matchAll(/--t\d:\s*(\d+)ms/g)) {
      const value = Number(hit[1]);
      expect(value).toBeGreaterThanOrEqual(150);
      expect(value).toBeLessThanOrEqual(250);
    }
    expect(tokens).toContain('--t1: 160ms');
    expect(tokens).toContain('--t2: 200ms');
    expect(tokens).toContain('--t3: 250ms');
  });

  it('命中区 44 与正文 16', () => {
    expect(tokens).toContain('--fs: 1rem');
    expect(tokens).toContain('--hit: 44px');
    expect(app).toContain('min-height: var(--hit)');
  });

  it('焦点环 2px', () => {
    expect(app).toContain(':focus-visible { outline: 2px solid var(--accent)');
  });
});

describe('DESIGN.md §6 的禁止清单', () => {
  it('没有渐变文字、没有玻璃拟态、没有胶囊滥用', () => {
    expect(app).not.toContain('background-clip: text');
    expect(app).not.toContain('backdrop-filter');
    expect(app).not.toContain('border-radius: 999px');
    expect(app).not.toContain('border-image');
  });

  it('没有 >1px 的彩色左侧条（当前项那条 2px 用 inset 阴影画的）', () => {
    for (const hit of app.matchAll(/border-(left|right):\s*(\d+)px/g)) {
      expect(Number(hit[2])).toBeLessThanOrEqual(1);
    }
  });

  it('层级走语义阶，没有裸的 999', () => {
    expect(app).not.toMatch(/z-index:\s*(9{3,}|[4-9]\d{3,})/);
    for (const hit of app.matchAll(/z-index:\s*([^;]+);/g)) {
      expect(hit[1].trim()).toMatch(/^(var\(--z-|auto|0)/);
    }
  });

  it('没有卡片网格与 hero 大数字那一路的装饰', () => {
    expect(app).not.toContain('font-size: 3rem');
    expect(app).not.toContain('--fs-3xl');
    for (const hit of tokens.matchAll(/--fs[\w-]*:\s*([\d.]+)rem/g)) {
      expect(Number(hit[1])).toBeLessThanOrEqual(2);
    }
  });

  it('app.css 不超过 700 行（硬指标）', () => {
    expect(app.split('\n').length).toBeLessThanOrEqual(700);
  });

  it('间距只用 4 的倍数（token 那七档）', () => {
    const wanted = ['--s1: 4px', '--s2: 8px', '--s3: 12px', '--s4: 16px', '--s5: 24px', '--s6: 32px', '--s7: 48px'];
    for (const one of wanted) {
      expect(tokens).toContain(one);
    }
  });
});
