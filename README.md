# radiko Podcast 文字起こし v0.6

Android Studioを使わず、GitHub ActionsだけでAPKを作れる試作です。

## 目的

1. Androidの公式 `AudioPlaybackCapture` で、再生中のメディア音声を取得
2. Android 13+ の公式 `RecognizerIntent.EXTRA_AUDIO_SOURCE` で、そのPCM音声を音声認識器に渡す
3. 日本語の文字起こし結果をアプリ内に保存

再生側アプリが音声キャプチャを禁止している場合、その音声は取得しません。
制限を回避する処理はありません。

## GitHubでAPKを作る方法

1. このZIPを展開
2. GitHubで新しい空リポジトリを作成
3. 展開した中身をリポジトリへアップロードして commit
4. GitHubの `Actions` タブを開く
5. `Build Android APK` を開く
6. `Run workflow` を押す
7. 完了後、実行結果ページ下部の `Artifacts` から
   `radiko-transcriber-v06-apk` をダウンロード
8. ZIP内の `app-debug.apk` をAndroidへインストール

## アプリの使い方

1. アプリを開く
2. radiko Podcast URLを貼る
3. 「文字起こし開始」
4. Androidの画面共有/録画許可を許可
5. 「radikoでこの回を開く」
6. radikoで再生
7. 戻ると文字起こし結果が表示される
8. 終了時に「文字起こし停止」

## 注意点

- Android 13以上が必要
- 音声認識プロバイダが `EXTRA_AUDIO_SOURCE` をサポートしない端末では認識できないことがあります
- radiko側が `AudioPlaybackCapture` を禁止している場合は入力レベルがほぼ0になります
- どちらの場合も制限回避は行いません
