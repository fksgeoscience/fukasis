# CLAUDE.md

このリポジトリで作業する Claude (や他のエージェント) 向けのメモです。人が読む説明は `README.md` と `docs/` にあります。

## リポジトリの構成

| 場所 | 中身 |
|---|---|
| `app/` | Android アプリ (Java + C++)。本体 |
| `core/` | Android と iPhone で共有するスペクトル処理 (C++) と Swift パッケージ |
| `ios/` | iPhone 版 (SwiftUI) |
| `web/` | PC 用の web 版 (JavaScript。ビルド不要) |
| `cli/` | PC 用のコマンドライン版 (Rust。外部クレートなし) |
| `npm/` | CLI を npm で配るための組み立て |
| `testdata/` | `web/` と `cli/` のテストの正解データ (`core/` の出力から作る) |
| `docs/` | 手順書。[platforms.md](docs/platforms.md) (各版の違い)、[development.md](docs/development.md) (開発の流れ)、[releasing.md](docs/releasing.md) (公開)、[porting.md](docs/porting.md) (他の機種・iPhone) |

## どのフォークでも共通の決まり

- **マージは「Create a merge commit」で行う。squash と rebase はしない。** 同じコミットを複数のリポジトリ
  (legrs / fksgeoscience / igarinpiano) で共有しているので、コミットを作り直すと履歴が食い違い、
  次の PR に関係のない差分が出たり、マージできなくなったりする。
- **スペクトルの計算は 4 か所で一致させている。** 本体は `core/Sources/FukasisCoreC/` (C++)。`web/js/core.js` と
  `cli/src/` は同じ計算の書き直しで、`testdata/` の正解データと一致することをテストで確かめている。
  計算を変えたら、C++ を直す → `sh testdata/tools/regenerate.sh` で正解データを作り直す → web と cli を合わせる。
  波長校正の当てはめは `CalibrationValidator.java` にも、自動校正は Java と Swift の両方にある。
- **Android の画面の文言を足したら、`values/strings.xml` と `values-ja/strings.xml` の両方に入れる。**
  CI の lint が片方だけの文言をエラーにする。
- **Android アプリは手元でビルドできない環境が多い。** ビルド・単体テスト・lint は GitHub Actions の `CI`
  (`./gradlew testDebugUnitTest lintDebug assembleDebug`) で確かめる。
- 手元で確かめられるテスト:

  ```bash
  node --test web/test/core.test.js web/test/server.test.js npm/test/npm.test.js
  (cd cli && cargo test --locked)
  ```

  `core/` の C++ のテストは `.github/workflows/ci.yml` の「Build & run (shared core)」と同じコマンドで動く (C++17 のコンパイラだけで足りる)。
- npm と crates.io に公開したバージョンは取り消せない。公開は、利用者にはっきり頼まれたときだけ行う。

## igarinpiano/fukasis に限った決まり

ここから下は、**igarinpiano/fukasis (igarinpiano のフォーク) で作業するとき**だけの運用です。
ほかのフォークでは、そのフォークの持ち主のやり方に従ってください。
詳しい手順は [docs/development.md](docs/development.md) にあります。

### 3 つのリポジトリとリモート名

| リポジトリ | 役割 | 手元のリモート名 |
|---|---|---|
| `igarinpiano/fukasis` | 作業用のフォーク。PR はここのブランチから出し、PC 用ツールの公開もここから行う | `origin` |
| `fksgeoscience/fukasis` | チームのリポジトリ。開発の起点 | `fks` |
| `legrs/fukasis` | フォーク元 | `upstream` |

### ブランチの扱い

- **`release` (既定のブランチ)**: 公開用。npm / crates.io / GitHub Releases はここから出し、push すると web 版
  (GitHub Pages, `pages` ワークフロー) が更新される。既定のブランチは `release` のままにする
  (`publish-all` などの手動実行のワークフローは、既定のブランチに無いと実行できないため)。
- **`master`**: `legrs/fukasis` の master と同じ状態に保つだけ。ここで開発しない。
- **作業ブランチ (`feature/○○` `fix/○○` `docs/○○` など)**: 変更ごとに 1 本。**必ず `fks/master` から作る。**
  `release` から作ると、`release` にしかないコミット (バージョンの更新や merge commit) が fksgeoscience 宛の PR に混ざり、衝突する。

### 変更の流れ

1. `git fetch --all` のあと、`git switch -c feature/○○ fks/master`
2. コミットして `git push -u origin feature/○○`。CI (`CI`、`PC tools`) が通るのを待つ
3. 同じブランチから PR を出す: `gh pr create -R fksgeoscience/fukasis --base master --head igarinpiano:feature/○○`
4. 同じブランチを `release` に入れる (`git switch release && git merge feature/○○ && git push`、または `release` 宛の PR)
5. fksgeoscience で PR がマージされたら、`fks/master` を `release` にマージしておく (履歴をつないでおくため)

- **legrs へ**: legrs の PR 9 (`feature/brightness-rotation-i18n`) がマージされるまでは、新しい PR を出さず、
  そのブランチに同じコミットを足す。legrs でマージしてもらうのは PR 9 だけ (各 PR の先頭にそう書いてある)。
  PR 9 がマージされたら、この項目は消してよい。
- PR の宛先の master を取り込んでも直らない「マージできない」は、履歴の食い違いが原因のことが多い。
  機能のコミットだけを `fks/master` の上に `cherry-pick` し直し、`git push --force-with-lease` で同じブランチに上書きすると直る。
  上書きしてよいのは作業ブランチだけで、`release` と `master` は上書きしない。

### 公開 (PC 用ツール)

- `cli/` や `npm/` を変えたら、`cli/Cargo.toml`、`cli/Cargo.lock`、`web/package.json` のバージョンを同じ番号に上げ、
  `release` で CI が通ってから Actions の `publish-all` を実行する (手順は [docs/releasing.md](docs/releasing.md))。
- web 版だけの変更なら、`release` に入れた時点で公開ページに反映される。バージョンを上げる必要はない。
- `publish-all` と `pages` は `igarinpiano/fukasis` 以外では動かない作りになっている。
