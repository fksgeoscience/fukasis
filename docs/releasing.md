# PC 用ツール (web / cli) のリリース手順

`web/` と `cli/` は、GitHub Release と npm / crates.io で配布できるように準備してあります。
**npm と crates.io にはまだ公開していません。** 公開するかどうかを決めたら、この手順で進めます。

アプリ本体 (APK) のリリースは `v*` タグ、PC 用ツールは `pc-v*` タグで、別々に行います。

## 配布物

| 配布先 | 名前 | 中身 |
|---|---|---|
| GitHub Release | `fukasis-<バージョン>-<ターゲット>.zip` | コマンドライン版の実行ファイル (Linux / macOS / Windows) |
| GitHub Release | `fukasis-web-<バージョン>.zip` | web 版一式 (展開して `index.html` を開く) |
| crates.io | [`fukasis`](../cli/Cargo.toml) | コマンドライン版 (`cargo install fukasis`) とライブラリ |
| npm | [`fukasis-web`](../web/package.json) | web 版 (`npx fukasis-web --open`) と、Node.js から使える計算部分 (`require('fukasis-web')`) |

どちらの名前も 2026-10-08 の時点では空いていました。公開する直前にもう一度確かめてください。

## 公開する前に決めること

- **誰の名前で公開するか。** crates.io も npm も、最初に公開した人が所有者になります。チームで管理するなら、公開後に所有者を追加します (`cargo owner --add`, `npm owner add`)。
- **リポジトリの URL。** `cli/Cargo.toml` と `web/package.json` には `https://github.com/legrs/fukasis` を書いてあります。別のリポジトリを正とするなら書き換えます。
- **名前。** npm は組織の名前を付けた `@<組織>/fukasis-web` にもできます。変えるなら `web/package.json` の `name` と README を直します。
- **取り消せないこと。** crates.io に公開したバージョンは削除できません (`cargo yank` で新規の利用を止められるだけ)。npm も公開から 72 時間を過ぎると原則として削除できません。同じバージョン番号は二度と使えません。

## リリースの流れ

1. `cli/Cargo.toml` と `web/package.json` の `version` を同じ値に上げ、`cli/` で `cargo build` して `Cargo.lock` を更新します。
2. master に入れ、「PC tools」ワークフローが通っていることを確かめます (テストのほか、`cargo package` と `npm pack --dry-run` で配布物の中身も確かめています)。
3. タグを付けて push します。

   ```bash
   git tag pc-v0.1.0
   ```

   ```bash
   git push origin pc-v0.1.0
   ```

4. 「PC tools release」ワークフローが、実行ファイルと web 版の zip を付けた GitHub Release の**下書き**を作ります。内容を確かめて、GitHub の画面から公開します。

タグを付けずに試したいときは、「PC tools release」を手動で実行します。Release も公開もせず、配布物を実行結果 (Artifacts) に残すだけです。

## crates.io / npm への公開

既定では公開しません。有効にするには、リポジトリの Settings → Secrets and variables → Actions で次を設定します。

| 公開先 | 変数 (Variables) | シークレット (Secrets) |
|---|---|---|
| crates.io | `PUBLISH_CRATES_IO` = `true` | `CARGO_REGISTRY_TOKEN` (crates.io の API トークン) |
| npm | `PUBLISH_NPM` = `true` | `NPM_TOKEN` (npm の Automation トークン) |

設定してあると、タグを push したときに実行ファイルのビルドが済んでから公開します。

手元から公開することもできます。

```bash
cd cli && cargo publish --dry-run
```

```bash
cd web && npm publish --dry-run
```

`--dry-run` を外すと実際に公開されます。
