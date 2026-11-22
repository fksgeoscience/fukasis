// SPDX-License-Identifier: MIT
// 実行: node --test web/test/solar.test.js
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const core = require('../js/core.js');
const solar = require('../js/solar.js');

// 温度 T の黒体に, 吸収線と雑音を乗せた合成スペクトル (最大値 1 に正規化)
function synth(T, options = {}) {
  const { noise = 0, step = 0.4, from = 400, to = 720, blueTilt = 0, seed = 1 } = options;
  let state = seed;
  const rand = () => {
    state = (state * 16807) % 2147483647;
    return state / 2147483647 - 0.5;
  };
  const x = [];
  const y = [];
  for (let nm = from; nm <= to + 1e-9; nm += step) {
    let v = solar.planckShape(nm, T);
    for (const m of solar.MASKS) v *= 1 - 0.6 * Math.exp(-Math.pow((nm - m.nm) / 1.2, 2));
    // 青い側の感度校正のずれ (短い波長ほど強度を下げる)
    if (blueTilt && nm < 500) v *= 1 - blueTilt * ((500 - nm) / 50);
    x.push(nm);
    y.push(v * (1 + noise * rand()));
  }
  const max = Math.max(...y);
  return { x, y: y.map((v) => v / max) };
}

test('雑音の無い黒体なら, 当てはめた温度が元の温度に一致する', () => {
  for (const T of [3500, 4500, 5778, 7000, 9000]) {
    const { x, y } = synth(T);
    const r = solar.estimate(x, y);
    assert.ok(Math.abs(r.full.temperature - T) < 1, `${T}: ${r.full.temperature}`);
    assert.ok(Math.abs(r.red.temperature - T) < 1, `${T} (red): ${r.red.temperature}`);
    assert.ok(Math.abs(r.systematic) < 1);
    assert.equal(r.full.atBoundary, false);
  }
});

test('吸収線のまわりは当てはめに使わない', () => {
  const { x, y } = synth(5778);
  const r = solar.estimate(x, y);
  r.points.x.forEach((nm, i) => {
    const inMask = solar.MASKS.some((m) => nm >= m.from && nm <= m.to);
    assert.equal(r.points.used[i], !inMask, `${nm} nm`);
  });
  // 範囲は 450 - 670 nm
  assert.ok(r.points.x[0] >= 450 && r.points.x[r.points.x.length - 1] <= 670);
});

test('雑音があっても温度が近く, 誤差が出る', () => {
  const { x, y } = synth(5778, { noise: 0.03 });
  const r = solar.estimate(x, y);
  assert.ok(Math.abs(r.full.temperature - 5778) < 60, String(r.full.temperature));
  assert.ok(r.full.sigma > 0 && r.full.sigma < 100, String(r.full.sigma));
  // 元の温度は, 誤差の数倍の中に入る
  assert.ok(Math.abs(r.full.temperature - 5778) < 5 * r.full.sigma + 5);
});

test('青い側の校正がずれていると, 青側を除いた当てはめとの差に出る', () => {
  const { x, y } = synth(5778, { blueTilt: 0.1 });
  const r = solar.estimate(x, y);
  // 青い側が暗いと全域の当てはめは低めになり, 青側を除いた当てはめは元の温度に近い
  assert.ok(Math.abs(r.red.temperature - 5778) < 5, String(r.red.temperature));
  assert.ok(r.full.temperature < 5778 - 20, String(r.full.temperature));
  assert.ok(r.systematic > 20, String(r.systematic));
});

test('ウィーンの変位則: 山の位置から温度が出る', () => {
  for (const T of [5000, 5500, 5778]) {
    const { x, y } = synth(T);
    const w = solar.estimate(x, y).wien;
    const expected = solar.WIEN_B / T;
    assert.ok(Math.abs(w.lambdaMax - expected) < 1.5, `${T}: ${w.lambdaMax} != ${expected}`);
    assert.equal(w.atEdge, false);
  }
  // 山が範囲より短い波長にあるときは, 端にあると知らせる
  const { x, y } = synth(8000);
  assert.equal(solar.estimate(x, y).wien.atEdge, true);
});

test('Savitzky-Golay の係数は和が 1 で, 2 次式をそのまま通す', () => {
  for (const m of [2, 5, 12]) {
    const c = solar.savitzkyGolayCoefficients(m);
    assert.ok(Math.abs(c.reduce((s, v) => s + v, 0) - 1) < 1e-12);
    const smoothed = c.reduce((s, v, k) => s + v * Math.pow(k - m, 2), 0);
    assert.ok(Math.abs(smoothed) < 1e-9); // t² の t = 0 での値
  }
});

test('点が足りないときはエラーにする', () => {
  assert.throws(() => solar.estimate([500, 510, 520], [1, 1, 1]), (e) => e.code === 'tooFewPoints');
  const { x, y } = synth(5778);
  assert.throws(() => solar.estimate(x, y, { minNm: 600, maxNm: 500 }), (e) => e.code === 'badRange');
  assert.throws(
    () => solar.estimate(x, y.map(() => 0)),
    (e) => e.code === 'noSignal'
  );
});

test('観測の情報から ISO と露出を読む', () => {
  assert.deepEqual(solar.parseExposure('testseq, 2026-10-08T00:00:00Z,  ISO 800, fd -1.700000, 100 msec * 3 '), {
    iso: 800,
    exposureMs: 100,
    frames: 3,
    gain: null,
  });
  assert.deepEqual(solar.parseExposure(''), { iso: null, exposureMs: null, frames: null, gain: null });
  assert.equal(solar.parseExposure('a, ISO 100, 0.5 msec, gain 2.5').gain, 2.5);
});

test('結果の csv: 撮影条件と温度が入り, 波長の表は Graph タブでも読める', () => {
  const { x, y } = synth(5778);
  const header = 'sun, 2026-11-22T00:00:00Z,  ISO 100, fd -1.700000, 2 msec * 10 ';
  const r = solar.estimate(x, y);
  const text = solar.toCsv(r, Object.assign(solar.parseExposure(header), { source: 'sun', header, gain: '1.0' }));
  const rows = text.trimEnd().split('\n');
  const value = (key) => rows.find((row) => row.startsWith(key + ',')).split(',').slice(1).join(',');
  assert.equal(value('iso'), '100');
  assert.equal(value('exposure_ms'), '2');
  assert.equal(value('frames'), '10');
  assert.equal(value('gain'), '1.0');
  assert.equal(value('observation'), '"' + header.trim() + '"');
  assert.ok(Math.abs(Number(value('planck_T_K')) - 5778) < 1);
  assert.equal(value('reference_T_K'), '5778');
  // 波長の表だけが数値の行として読まれる
  const parsed = core.parseCsv(text);
  assert.equal(parsed.x.length, r.points.x.length);
  assert.ok(Math.abs(parsed.x[0] - r.points.x[0]) < 1e-3);
});
