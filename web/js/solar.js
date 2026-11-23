// SPDX-License-Identifier: MIT
// 太陽 (光球) のスペクトルから温度を推定する. web 版だけの機能 (アプリと共通コアには無い).
//   - 主: プランク関数の当てはめ I(λ) = A × B(λ, T). T ごとに A は線形最小二乗で決まるので, T だけを 1 次元で探す.
//         誤差は (A, T) のヤコビアンから作った共分散で出す.
//   - 確認用: ウィーンの変位則. 平滑化 (Savitzky-Golay) したスペクトルの山に放物線を当てて λmax を出す.
//   - 吸収線 (Hβ, Mg b, Na D, Hα) のあたりは除いて計算する.
//   - 青い側は感度の校正が弱いので, 短い波長を除いて当てはめた温度との差を「系統誤差の目安」として出す.
// ブラウザでは window.FukasisSolar, Node では require() で使える.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) {
    module.exports = factory();
  } else {
    root.FukasisSolar = factory();
  }
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  // 第 2 放射定数 hc/k (nm・K) とウィーンの変位則の定数 (nm・K)
  const C2 = 1.438776877e7;
  const WIEN_B = 2.897771955e6;
  // 太陽の有効温度 (K). 比べるための目安
  const SUN_TEFF = 5778;

  const DEFAULTS = {
    minNm: 450, // 使う波長の範囲
    maxNm: 670,
    blueCutNm: 500, // 青い側を除いた当てはめでは, これより長い波長だけを使う
    tMin: 2000, // 温度を探す範囲 (K)
    tMax: 10000,
    smoothNm: 25, // ウィーンの変位則: 平滑化の窓の幅 (nm)
    peakHalfNm: 30, // ウィーンの変位則: 山の頂上の両側この幅に放物線を当てる (nm)
  };

  // 除外する吸収線のまわり [下端, 上端] (nm). スマートフォンの分光器の分解能 (数 nm) を見込んで少し広めにとる
  const MASKS = [
    { label: 'Hβ', nm: 486.1, from: 483, to: 489.5 },
    { label: 'Mg b', nm: 517.3, from: 514.5, to: 520.5 },
    { label: 'Na D', nm: 589.3, from: 586.5, to: 592.5 },
    { label: 'Hα', nm: 656.3, from: 653, to: 659.5 },
  ];

  function solarError(code, detail) {
    const e = new Error(code + (detail ? ': ' + detail : ''));
    e.code = code;
    e.detail = detail;
    return e;
  }

  // プランク関数 B(λ, T) の形 (定数倍は A に入るので省く). λ は nm.
  // 2hc²/λ⁵ / (exp(hc/λkT) − 1) を, 値が極端にならないよう 500 nm で割った波長で書いたもの
  function planckShape(nm, T) {
    const u = nm / 500;
    return 1 / (u * u * u * u * u * Math.expm1(C2 / (nm * T)));
  }

  // ∂B/∂T / B = (x e^x / (e^x − 1)) / T, x = hc/λkT
  function planckLogDerivT(nm, T) {
    const x = C2 / (nm * T);
    return (x / -Math.expm1(-x)) / T;
  }

  function isMasked(nm, masks) {
    return masks.some((m) => nm >= m.from && nm <= m.to);
  }

  // T を決めたときの最小二乗: A = Σ y B / Σ B², χ² = Σ (y − A B)²
  function residualAt(x, y, T) {
    let sbb = 0;
    let sby = 0;
    let syy = 0;
    for (let i = 0; i < x.length; i++) {
      const b = planckShape(x[i], T);
      sbb += b * b;
      sby += b * y[i];
      syy += y[i] * y[i];
    }
    const A = sby / sbb;
    return { A, chi2: Math.max(0, syy - A * sby) };
  }

  // プランク関数を当てはめる. x (nm), y は当てはめに使う点だけ.
  // 返すのは {temperature, sigma, amplitude, chi2, rms, n, atBoundary}
  function fitPlanck(x, y, options) {
    const o = Object.assign({}, DEFAULTS, options);
    const n = x.length;
    if (n < 5) throw solarError('tooFewPoints', String(n));

    // 粗く探す (log T で等間隔)
    const steps = 400;
    const lo = Math.log(o.tMin);
    const hi = Math.log(o.tMax);
    let best = -1;
    let bestChi = Infinity;
    for (let k = 0; k <= steps; k++) {
      const chi = residualAt(x, y, Math.exp(lo + ((hi - lo) * k) / steps)).chi2;
      if (chi < bestChi) {
        bestChi = chi;
        best = k;
      }
    }
    // 黄金分割で詰める
    let a = lo + ((hi - lo) * Math.max(0, best - 1)) / steps;
    let b = lo + ((hi - lo) * Math.min(steps, best + 1)) / steps;
    const g = (Math.sqrt(5) - 1) / 2;
    let c = b - g * (b - a);
    let d = a + g * (b - a);
    let fc = residualAt(x, y, Math.exp(c)).chi2;
    let fd = residualAt(x, y, Math.exp(d)).chi2;
    for (let it = 0; it < 80 && b - a > 1e-10; it++) {
      if (fc < fd) {
        b = d;
        d = c;
        fd = fc;
        c = b - g * (b - a);
        fc = residualAt(x, y, Math.exp(c)).chi2;
      } else {
        a = c;
        c = d;
        fc = fd;
        d = a + g * (b - a);
        fd = residualAt(x, y, Math.exp(d)).chi2;
      }
    }
    const T = Math.exp((a + b) / 2);
    const { A, chi2 } = residualAt(x, y, T);

    // 共分散: s² (JᵀJ)⁻¹. J の列は ∂I/∂A = B, ∂I/∂T = A ∂B/∂T
    let jaa = 0;
    let jat = 0;
    let jtt = 0;
    for (let i = 0; i < n; i++) {
      const bA = planckShape(x[i], T);
      const bT = A * bA * planckLogDerivT(x[i], T);
      jaa += bA * bA;
      jat += bA * bT;
      jtt += bT * bT;
    }
    const det = jaa * jtt - jat * jat;
    const s2 = chi2 / (n - 2);
    const sigma = det > 0 ? Math.sqrt((s2 * jaa) / det) : NaN;
    const edge = (o.tMax - o.tMin) * 0.002;
    return {
      temperature: T,
      sigma,
      amplitude: A,
      chi2,
      rms: Math.sqrt(chi2 / n),
      n,
      atBoundary: T - o.tMin < edge || o.tMax - T < edge,
    };
  }

  // Savitzky-Golay (2 次) の平滑化の係数. 窓は 2m + 1 点
  function savitzkyGolayCoefficients(m) {
    const norm = (2 * m - 1) * (2 * m + 1) * (2 * m + 3);
    const out = [];
    for (let j = -m; j <= m; j++) out.push((3 * (3 * m * m + 3 * m - 1) - 15 * j * j) / norm);
    return out;
  }

  // 小さい順に並んだ xs の上で線形補間する
  function interpolate(xs, ys, v) {
    let lo = 0;
    let hi = xs.length - 1;
    if (v <= xs[0]) return ys[0];
    if (v >= xs[hi]) return ys[hi];
    while (hi - lo > 1) {
      const mid = (lo + hi) >> 1;
      if (xs[mid] <= v) lo = mid;
      else hi = mid;
    }
    return ys[lo] + ((v - xs[lo]) * (ys[hi] - ys[lo])) / (xs[hi] - xs[lo]);
  }

  // ウィーンの変位則. x (nm, 昇順), y は吸収線を除いた点. 除いたところは線形補間でつなぐ.
  // 返すのは {lambdaMax, temperature, atEdge, smoothed: {x, y}}
  function wienPeak(x, y, options) {
    const o = Object.assign({}, DEFAULTS, options);
    const n = x.length;
    if (n < 5) throw solarError('tooFewPoints', String(n));
    // 等間隔に並べ直す (間隔は元の点の間隔の中央値)
    const gaps = [];
    for (let i = 1; i < n; i++) gaps.push(x[i] - x[i - 1]);
    gaps.sort((p, q) => p - q);
    const step = Math.max(0.05, gaps[gaps.length >> 1] || 1);
    const gx = [];
    const gy = [];
    for (let v = x[0]; v <= x[n - 1] + 1e-9; v += step) {
      gx.push(v);
      gy.push(interpolate(x, y, v));
    }
    const m = Math.max(2, Math.round(o.smoothNm / step / 2));
    if (gx.length < 2 * m + 5) throw solarError('tooFewPoints', String(n));
    const coef = savitzkyGolayCoefficients(m);
    const sx = [];
    const sy = [];
    for (let i = m; i < gx.length - m; i++) {
      let s = 0;
      for (let j = -m; j <= m; j++) s += coef[j + m] * gy[i + j];
      sx.push(gx[i]);
      sy.push(s);
    }
    let top = 0;
    for (let i = 1; i < sy.length; i++) if (sy[i] > sy[top]) top = i;

    // 山の頂上のまわりに放物線 y = a t² + b t + c (t = λ − λtop) を最小二乗で当てる
    const half = Math.max(2, Math.round(o.peakHalfNm / step));
    const from = Math.max(0, top - half);
    const to = Math.min(sy.length - 1, top + half);
    let lambdaMax = sx[top];
    let s0 = 0, s1 = 0, s2 = 0, s3 = 0, s4 = 0, t0 = 0, t1 = 0, t2 = 0; // prettier-ignore
    for (let i = from; i <= to; i++) {
      const tt = sx[i] - sx[top];
      const v = sy[i];
      s0 += 1;
      s1 += tt;
      s2 += tt * tt;
      s3 += tt * tt * tt;
      s4 += tt * tt * tt * tt;
      t0 += v;
      t1 += v * tt;
      t2 += v * tt * tt;
    }
    // 3x3 の正規方程式をクラメルの公式で解く
    const det3 = (m3) =>
      m3[0][0] * (m3[1][1] * m3[2][2] - m3[1][2] * m3[2][1]) -
      m3[0][1] * (m3[1][0] * m3[2][2] - m3[1][2] * m3[2][0]) +
      m3[0][2] * (m3[1][0] * m3[2][1] - m3[1][1] * m3[2][0]);
    const M = [
      [s4, s3, s2],
      [s3, s2, s1],
      [s2, s1, s0],
    ];
    const D = det3(M);
    if (to - from >= 3 && D !== 0) {
      const pa = det3([[t2, s3, s2], [t1, s2, s1], [t0, s1, s0]]) / D; // prettier-ignore
      const pb = det3([[s4, t2, s2], [s3, t1, s1], [s2, t0, s0]]) / D; // prettier-ignore
      if (pa < 0) {
        const vertex = sx[top] - pb / (2 * pa);
        if (vertex >= sx[from] && vertex <= sx[to]) lambdaMax = vertex;
      }
    }
    // 山が範囲の端に張り付いているときは, 本当の山は範囲の外にあるかもしれない
    const atEdge = top < half / 2 || top > sy.length - 1 - half / 2;
    return { lambdaMax, temperature: WIEN_B / lambdaMax, atEdge, smoothed: { x: sx, y: sy } };
  }

  // 温度を推定する. wavelength (nm), intensity は csv の値 (順不同でよい. NaN は除く).
  // 返すのは
  //   {full, red, systematic, wien, points: {x, y, used}, model(nm), options, masks}
  //   full: 全域 (minNm - maxNm) での当てはめ, red: blueCutNm より長い波長だけでの当てはめ,
  //   systematic: red.temperature − full.temperature (青い側の校正の影響の目安)
  function estimate(wavelength, intensity, options) {
    const o = Object.assign({}, DEFAULTS, options);
    const masks = (options && options.masks) || MASKS;
    if (!(o.maxNm > o.minNm)) throw solarError('badRange');
    const order = [];
    for (let i = 0; i < Math.min(wavelength.length, intensity.length); i++) {
      const nm = wavelength[i];
      if (Number.isFinite(nm) && Number.isFinite(intensity[i]) && nm >= o.minNm && nm <= o.maxNm) order.push(i);
    }
    order.sort((p, q) => wavelength[p] - wavelength[q]);
    const px = [];
    const py = [];
    const used = [];
    for (const i of order) {
      if (px.length && wavelength[i] === px[px.length - 1]) continue;
      px.push(wavelength[i]);
      py.push(intensity[i]);
      used.push(!isMasked(wavelength[i], masks));
    }
    const fx = [];
    const fy = [];
    for (let i = 0; i < px.length; i++) {
      if (!used[i]) continue;
      fx.push(px[i]);
      fy.push(py[i]);
    }
    if (fx.length < 10) throw solarError('tooFewPoints', String(fx.length));
    if (!fy.some((v) => v > 0)) throw solarError('noSignal');

    const full = fitPlanck(fx, fy, o);
    const rx = [];
    const ry = [];
    for (let i = 0; i < fx.length; i++) {
      if (fx[i] < o.blueCutNm) continue;
      rx.push(fx[i]);
      ry.push(fy[i]);
    }
    let red = null;
    if (rx.length >= 10 && o.blueCutNm > o.minNm) red = fitPlanck(rx, ry, o);
    const wien = wienPeak(fx, fy, o);
    return {
      full,
      red,
      systematic: red ? red.temperature - full.temperature : NaN,
      wien,
      points: { x: px, y: py, used },
      model: (nm) => full.amplitude * planckShape(nm, full.temperature),
      modelRed: red ? (nm) => red.amplitude * planckShape(nm, red.temperature) : null,
      options: o,
      masks,
    };
  }

  // 観測の情報 (metadata.csv の 1 行目) から ISO と露出を読む. 例: "seq, 2026-...,  ISO 800, fd -1.7, 100 msec * 3 "
  // 読めない項目は null
  function parseExposure(header) {
    const line = (header || '').split(/\r?\n/)[0];
    const iso = /\bISO\s*([0-9]+(?:\.[0-9]+)?)/i.exec(line);
    const exp = /([0-9]+(?:\.[0-9]+)?)\s*msec(?:\s*\*\s*([0-9]+))?/i.exec(line);
    const gain = /\bgain\s*([-+]?[0-9]+(?:\.[0-9]+)?)/i.exec(line);
    return {
      iso: iso ? Number(iso[1]) : null,
      exposureMs: exp ? Number(exp[1]) : null,
      frames: exp && exp[2] ? Number(exp[2]) : null,
      gain: gain ? Number(gain[1]) : null,
    };
  }

  const csvField = (v) => {
    const s = v === null || v === undefined ? '' : String(v);
    return /[",\r\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
  };
  const num = (v, digits) => (Number.isFinite(v) ? v.toFixed(digits) : '');

  // 結果を csv にする. 前半は「項目,値」, 空行のあとに波長ごとの 実測値, 当てはめ (全域), 当てはめに使ったか.
  // 波長の表は 1 列目が数値なので, そのまま Graph タブで開くと実測値が描ける.
  // info は {source, header, iso, exposureMs, frames, gain}
  function toCsv(result, info) {
    info = info || {};
    const o = result.options;
    const rows = [
      ['# FUKASIS solar photosphere temperature estimate'],
      ['item', 'value'],
      ['source', info.source],
      ['observation', (info.header || '').split(/\r?\n/)[0].trim()],
      ['iso', info.iso],
      ['exposure_ms', info.exposureMs],
      ['frames', info.frames],
      ['gain', info.gain],
      ['range_nm', o.minNm + '-' + o.maxNm],
      ['masked_nm', result.masks.map((m) => m.label + ' ' + m.from + '-' + m.to).join('; ')],
      ['planck_T_K', num(result.full.temperature, 1)],
      ['planck_T_sigma_K', num(result.full.sigma, 1)],
      ['planck_points', result.full.n],
      ['planck_rms', result.full.rms.toPrecision(4)],
      ['planck_red_from_nm', o.blueCutNm],
      ['planck_red_T_K', result.red ? num(result.red.temperature, 1) : ''],
      ['planck_red_T_sigma_K', result.red ? num(result.red.sigma, 1) : ''],
      ['blue_systematic_K', num(result.systematic, 1)],
      ['wien_lambda_max_nm', num(result.wien.lambdaMax, 2)],
      ['wien_T_K', num(result.wien.temperature, 1)],
      ['wien_peak_at_edge', result.wien.atEdge ? 'yes' : 'no'],
      ['reference_T_K', SUN_TEFF],
      [],
      ['wavelength/nm', 'measured', 'planck_fit', 'used_in_fit'],
    ];
    const p = result.points;
    for (let i = 0; i < p.x.length; i++) {
      rows.push([p.x[i].toFixed(3), p.y[i].toPrecision(6), result.model(p.x[i]).toPrecision(6), p.used[i] ? 1 : 0]);
    }
    return rows.map((r) => r.map(csvField).join(',')).join('\n') + '\n';
  }

  return {
    C2,
    WIEN_B,
    SUN_TEFF,
    DEFAULTS,
    MASKS,
    planckShape,
    fitPlanck,
    savitzkyGolayCoefficients,
    wienPeak,
    estimate,
    parseExposure,
    toCsv,
  };
});
