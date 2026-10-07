# API設計書：簡易自由律じゃんけん

## 1. 基本情報

### 1.1 ユーザー識別

パスワード認証は行わない。

表示用ユーザー名のみで簡易ログインする。

ユーザー名重複を許可するため、HTTPセッション等によりユーザーを内部識別する。

### 1.2 レスポンス形式

| 用途 | 形式 |
|---|---|
| 画面表示・フォーム処理 | HTML（Thymeleaf） |
| 状態確認 | JSON |

### 1.3 データ保持

DBは使用しない。

データはアプリケーション実行中のメモリ上で管理する。

### 1.4 共通アクセス制御と応答

ユーザーIDはセッションから取得する。送信されたユーザーIDで操作者を切り替えない。

GET / は未ログインでログイン画面を表示する。それ以外の画面GETの未ログインは `/` へ302リダイレクトする。ログイン済みユーザーの正規画面は、①なら `/rooms`、②・③なら `/room`、④なら現在対戦の状態に合う `/play?matchId=...` または `/round-result?matchId=...` とする。

POST /loginを除く状態変更POSTは、未ログインなら `401 / LOGIN_REQUIRED` でログイン画面を表示し、変更しない。ログイン済みでは、要求対象・現在状態・権限を共通ロック内で検証してから入力値と更新処理を扱う。

現在所属するルームを対象とするPOST（`/room/*`、`/match-result/return`、returnPageがROOMまたはMATCH_RESULTのオリジナル手保存・削除）には `roomId` を必須とし、現在の `currentRoomId` と一致することを検証する。古いルーム画面からの要求で別ルームを操作させない。不一致は `409 / INVALID_STATE` で拒否する。入室の `POST /rooms/enter` は①・未所属でroomNameを指定する操作とし、roomIdを要求しない。ログアウトはセッション全体への操作として、この対象指定を要求しない。

正常POSTは302リダイレクトとする。入力不正は `400 / VALIDATION_ERROR`、権限不足は `403 / FORBIDDEN`、状態・対象不一致は `409 / INVALID_STATE`。業務エラーのコードは各節に従う。

POSTのエラー画面はHTMLとし、次のModelを用いる。

- `errorCode`：主エラーコード。
- `errorMessages`：表示する全メッセージの一覧。
- `fieldErrors`：項目名とメッセージの一覧。
- `form`：再表示可能な入力値。
- 当該画面の通常Model。

入力不正では許可された元画面を同じHTTPステータスで再表示し、フォームを展開したまま入力値とエラーを表示する。returnPage等が不正で元画面を確定できない場合、または状態・所属が変わり元画面を表示できない場合は、現在の正規画面を同じエラーステータスで表示し、変更を行わない。

formは再表示する対象フォームの値を表す共通名であり、各テンプレートでは下記の個別フォーム名にも同じ再表示用入力値を設定する。

名前重複が複数ある場合は `errorMessages` に両方を入れる。主コードはユーザー名重複を優先する。

`GET /api/status` のエラーはHTMLや302ではなくJSONとする。未ログインは401、形式不正は400で、`code`・`message`・`errors`（項目とメッセージの配列）を返す。

GETのModelと状態JSONには、確定前の他人の選択手や公開前の他人のオリジナル手相性を含めない。本人の現在オリジナル手設定は、許可された作成・編集フォームのModelへ本人用として渡す。オリジナル手の選択肢は内部ID・名前・選択可能かを表す値だけを渡す。

### 1.5 共通Modelと基本画面

ログイン後画面の共通ヘッダーにはセッションの現在ユーザー名をusernameとして渡す。userStateも現在状態の表示用コピーとする。

GET / の未ログインModelはloginForm。ログイン済みなら29節の正規画面へ誘導する。

GET /roomsは①のみ表示し、userState・currentOriginalHand・roomEnterForm・originalHandForm・originalHandDeleteFormを渡す。オリジナル手フォームはreturnPage=ROOMSとし、roomId・resultMatchIdを空にする。

各画面の送信用内部IDはサーバーが検証済みModelからhidden項目等へ設定し、利用者向けの表示・入力にしない。

### 1.6 文字列の共通判定基準

ユーザー名・ルーム名・オリジナル手名の前後空白除去、保存、重複判定、文字数判定には、次の共通基準を適用する。

- サーバーでJava `String.strip()`の規則により前後空白を除去する。本書の「前後空白」「空白だけ」の判定も、この規則を基準とする。
- 保存・表示する値と重複判定に使用する値は、除去後の値とする。
- 文字数は除去後のUnicodeコードポイント数（Unicodeの文字を表す符号位置の個数）で数える。Java `String.length()`ではなく、`value.codePointCount(0, value.length())`を使用する。
- 「1～20文字」は、除去後のUnicodeコードポイント数が1～20であることを意味する。未入力または除去後に空になる入力は拒否する。
- JavaScript側のチェックは補助とし、最終判定はサーバーを正とする。

この基準はPOST /loginのusername、POST /rooms/enterのroomName、POST /original-hand/saveのnameに共通適用する。既存の状態・対象・権限の検証順序は変更しない。

## 2. エンドポイント一覧

### 認証

| Method | Path | 内容 |
|---|---|---|
| GET | `/` | ログイン画面 |
| POST | `/login` | 簡易ログイン |
| POST | `/logout` | ログアウト |

### ルーム

| Method | Path | 内容 |
|---|---|---|
| GET | `/rooms` | ルーム作成・参加画面 |
| POST | `/rooms/enter` | ルーム作成または参加 |
| GET | `/room` | 現在のルーム |
| POST | `/room/leave` | 退出 |
| POST | `/room/rules` | ルール変更 |
| POST | `/room/ready` | 準備完了 |
| POST | `/room/ready/cancel` | 準備取消 |
| POST | `/room/start` | 対戦開始 |

### オリジナル手

| Method | Path | 内容 |
|---|---|---|
| POST | `/original-hand/save` | 作成または更新 |
| POST | `/original-hand/delete` | 削除 |

専用GET画面は設けない。

### 対戦

| Method | Path | 内容 |
|---|---|---|
| GET | `/play` | 対戦・観戦 |
| POST | `/play` | 手確定 |
| GET | `/round-result` | ラウンド結果 |
| GET | `/match-result` | 対戦結果 |
| POST | `/match-result/return` | ルームへ戻る |

### 状態

| Method | Path | 内容 |
|---|---|---|
| GET | `/api/status` | 状態確認 |

## 3. `POST /login`

入力：

- `username`

条件：

- 必須
- String.strip()後1～20コードポイント（1.6節）

正常時：

- セッションへユーザーを関連付ける
- 状態①
- `302 Redirect → /rooms`

同一ユーザー名を許可する。

入力不正は400でログイン画面を再表示する。既にログイン済みの同一セッションからの再ログインは409で拒否し、既存ユーザー・所属・セッションの関連付けを変更しない。

## 4. `POST /logout`

- ルーム参加中なら退出処理。
- ④なら対戦途中退出処理。
- ホストならホスト移譲。
- セッションを終了。
- `/` へリダイレクト。

## 5. `POST /rooms/enter`

入力：

- `roomName`

条件：

- 必須
- String.strip()後1～20コードポイント（1.6節）

### 5.1 既存ルームなし

新規作成。

- 操作者をホスト。
- 操作者を②。
- `targetWins = 3`
- `preventConsecutiveSameOriginalHand = false`

### 5.2 既存ルームあり

既存ルームへ参加し、操作者を②へ変更する。

対戦中のルームにも参加可能。その対戦の参加者には追加せず、②として観戦可能とする。

8人参加済みの場合：

```text
409 Conflict
ROOM_FULL
```

同名ルームを新規作成しない。

### 5.3 排他

同時に同じ存在しないルーム名へ入室要求が来ても、同名ルームを2つ作らない。

「①・未所属確認→ルーム名の整形・検証→検索→必要なら作成→8人未満の確認→参加登録→②」を、削除・退出とも共通の排他処理で行う。同一ユーザーの別ルームへの同時入室も1要求だけ成立させる。

未ログインの401を最優先とし、ログイン済みなら①・未所属の確認をroomNameの入力値検証より先に行う。②・③・④、または所属ありの要求は、roomNameが未入力・空白のみ・文字数超過でも `409 / INVALID_STATE`。①・未所属の要求でroomNameが不正な場合だけ `400 / VALIDATION_ERROR` とする。フォーム検証エラーがあっても、この応答順序を変えない。

①・未所属でルーム名が不正な場合は400で `/rooms` の画面を再表示する。満員は409で同画面を再表示し、①を維持する。

正常時は `lastSeenAt` を入室時刻に初期化し、`302 → /room`。①以外の要求は409で拒否する。

ルーム削除と入室も同じ排他処理に含め、削除済みルームへの参加登録や名前索引の不一致を発生させない。

## 6. 内部ルームID

実装内部ではUUID等の内部IDを保持してよい。

ただしユーザーには表示せず、入室操作やコピー機能には使用しない。

## 7. `GET /room`

主なModel：

- `roomId`（制御用内部ID）
- `currentMatchId`（進行中対戦がなければnull）
- `roomName`
- `members`
- `readyMembers`
- `playingMembers`
- `targetWins`
- `preventConsecutiveSameOriginalHand`
- `isHost`
- `userState`
- `matchRunning`
- `currentOriginalHand`
- `roomActionForm`（roomId）
- `roomRuleForm`（roomIdと現在ルール）
- `originalHandForm`・`originalHandDeleteForm`（returnPage=ROOM、roomId）

②・③だけを表示対象とし、他状態は29節へ誘導する。進行中対戦があっても②・③を自動的に観戦へ移動しない。

## 8. `POST /room/leave`

現在ルームから退出する。

④なら対戦途中退出処理も行う。

ホストなら必要に応じてホスト移譲する。

入力は `roomId`。②・③・④のみ。退出前の状態・ルームID・対戦IDを保持してから退出処理を行う。

正常時は①とし、`302 → /rooms`。拒否時は所属・ユーザー状態・対戦を変更しない。

手動退出ではセッションのログイン状態、本人のユーザーID・ユーザー名・現在のオリジナル手を保持する。退出ボタンは②・③のルーム画面と④の対戦・ラウンド結果画面に表示し、各画面のroomActionFormで表示中のroomIdを送信する。④の手確定後の待機中と最終ラウンド結果表示中も受け付け、対戦への影響は20節に従う。

## 9. `POST /room/rules`

入力：

| 項目 | 条件 |
|---|---|
| `roomId` | 現在所属ルームの内部ID |
| `targetWins` | 必須、整数1～99 |
| `preventConsecutiveSameOriginalHand` | boolean |

ホストのみ。

対戦中は変更不可。

変更しても③は維持する。

非ホストによる要求は `403 Forbidden / FORBIDDEN`。

ホストでも進行中対戦がある場合は `409 Conflict / INVALID_STATE`。

拒否時はルールとユーザー状態を変更しない。

正常時は `302 → /room`。入力不正は400でルーム画面のルールフォームを再表示する。非ホストは403、対戦中は409でルーム画面または現在の正規画面にエラーを表示する。

## 10. `POST /room/ready`

入力は `roomId`。状態②のみ実行可能。

検証順：

1. オリジナル手あり
2. 同一ルームの③とのユーザー名非重複
3. 同一ルームの③とのオリジナル手名非重複

### オリジナル手なし

```text
409 Conflict
ORIGINAL_HAND_REQUIRED
```

### ユーザー名重複

```text
409 Conflict
READY_USERNAME_CONFLICT
```

### 手名重複

```text
409 Conflict
READY_HAND_NAME_CONFLICT
```

前回対戦から設定が更新されたかどうかは検証しない。

両方の名前が重複した場合は、`errorCode = READY_USERNAME_CONFLICT` とし、`errorMessages` にユーザー名・手名の両メッセージを順に含める。③との比較は途中で打ち切らず、両条件を確認する。

拒否時は409でルーム画面を再表示し、②を維持する。

正常時③へ変更し、`302 → /room`。

③一覧の取得・重複検証・②→③を、ルーム単位の同一排他処理で行う。同名ユーザーまたは同名手の同時要求でも、後続要求は先行要求反映後の③一覧で検証する。

## 11. `POST /room/ready/cancel`

入力は `roomId`。③のみ。

③→②。正常時 `302 → /room`。状態不一致は409で変更しない。

## 12. `POST /room/start`

入力は `roomId`。

条件：

- ホスト
- 進行中対戦なし
- ③が2人以上

処理：

1. ③だけを対戦参加者として、内部IDと開始時点のユーザー名を固定。
2. 先取勝数固定。
3. 同一オリジナル手連続使用禁止設定固定。
4. オリジナル手と相性を固定。
5. ③→④。
6. 第1ラウンド開始。

ホストが②なら②のままで `302 → /room`。ホストが参加者④になった場合は `302 → /play?matchId=開始した対戦ID`。

非ホストは403、進行中対戦あり・③が1人以下・状態不一致は409で拒否する。拒否時は対戦・ルーム・ユーザー状態を変更せず、ルーム画面または現在の正規画面にエラーを表示する。

## 13. `POST /original-hand/save`

作成・更新共通。

入力：

- `name`
- `vsRock`
- `vsScissors`
- `vsPaper`
- `vsOriginal`
- `returnPage`
- `roomId`（ROOM・MATCH_RESULTで必須）
- `resultMatchId`（MATCH_RESULTで必須）

`returnPage` の許可値と条件：

| 値 | 操作可能状態・対象 | 正常時のリダイレクト |
|---|---|---|
| `ROOMS` | ①、roomId・resultMatchIdなし | `/rooms` |
| `ROOM` | ②、roomIdが現在所属ルームと一致 | `/room` |
| `MATCH_RESULT` | ②、roomId一致、resultMatchIdの終了済み結果が同じルーム | `/match-result?matchId=resultMatchId` |

許可値以外のreturnPage、形式不正・必要項目不足は400。状態・roomId不一致は409で拒否し、保存しない。他ルームの結果や不存在結果を返却先に指定した場合も409で拒否する。

ほかの状態・返却先条件が有効な要求では、必須のroomId・resultMatchIdの未指定・空文字・UUIDとして読み取れない形式は `400 / VALIDATION_ERROR` とする。UUID形式は正しいがroomIdが現在所属と異なる場合、またはresultMatchIdが不存在・未終了・他ルームの結果を指す場合は `409 / INVALID_STATE` とする。保存・削除の両方でこの区別を用い、拒否要求では現在の手と保存済み結果を変更しない。

条件：

- 名前必須。前後空白を除去した後に1～20文字。未入力・空文字・空白だけの入力は不可。
- 除去後のnameが「グー」「チョキ」「パー」のいずれかと完全一致する場合は不可。作成・更新の双方に適用する。部分一致では拒否しない。
- 相性4項目必須。値は `WIN / LOSE / DRAW`。
- 最低1項目 `LOSE`。

除去後の名前を保存し、表示・準備完了時の重複判定に使用する。

禁止名は `400 / VALIDATION_ERROR` とし、`fieldErrors` のname項目と `errorMessages` に `オリジナル手の名前に「グー」「チョキ」「パー」は使用できません。` を含める。許可された元画面のフォームを展開して入力値を再表示し、作成・更新を行わない。この禁止をusernameやroomNameの検証には適用しない。

入力不正は `400 / VALIDATION_ERROR` とし、許可された元画面を再表示する。フォームの入力値・項目エラーと通常Modelを渡し、保存しない。不正な更新で既存の名前・相性・内部IDを変更しない。

③・④からは409で拒否する。別タブで準備完了・対戦開始した後に旧フォームを送信しても更新しない。

MATCH_RESULTでは、成功後も入力エラー時もフォームが持つresultMatchIdの結果を表示する。最新結果や他タブの閲覧先を参照して対象を変更しない。

## 14. `POST /original-hand/delete`

入力：

- `returnPage`
- `roomId`（ROOM・MATCH_RESULTで必須）
- `resultMatchId`（MATCH_RESULTで必須）

状態・対象・返却先の条件は13節と同じ。①・②のみ。

正常時は現在のオリジナル手を削除し、returnPageの対応先へ302リダイレクトする。MATCH_RESULTなら対象結果IDを維持する。

未作成時の削除は `409 / INVALID_STATE`。不正な入力・状態・対象では削除しない。

削除後、②から新たに③へ遷移するには再作成が必要。終了済み結果と開始済み対戦のオリジナル手スナップショットは削除しない。

## 15. `GET /play`

入力は任意の `matchId`（対戦内部ID）。省略時は現在の進行中対戦を対象とする。表示ページには対象matchIdとroomIdを制御用データとして保持する。

対象がラウンド結果表示中なら、参加者・観戦者とも `/round-result?matchId=対象ID` へ302リダイレクトする。対象が終了済みで②・③なら、同一ルームの権限確認後に `/match-result?matchId=対象ID` へ進む。

④は常に本人の現在対戦を優先する。不存在・他ルームの対象を表示しない。詳細な遷移は29節に従う。

利用可能：

### 対戦参加者

④。

### 観戦者

同一ルームの②・③で、進行中対戦が存在する場合。

主なModel：

- `roomId`・`matchId`（画面に表示しない制御用データ）
- `matchParticipant`
- `roundNumber`
- `targetWins`
- `scores`
- `availableHands`
- `selectedHand`
- `waitingForOthers`
- `previousHand`
- `preventConsecutiveSameOriginalHand`
- `roundHistory`
- `userState`
- `handSelectionForm`（matchId・roundNumber・typeと選択値）
- `roomActionForm`（roomId、④の「ルームを退出」用）

観戦者には手選択UIを提供しない。

`selectedHand` は本人の選択だけを渡し、観戦者ではnullとする。`availableHands` には相性を含めない。確定前の他人の手はHTML・JSON・制御用データにも含めない。

本人の観戦操作で進行中対戦を表示しても、他タブの結果の対象IDと②・③の状態は変更しない。

## 16. `POST /play`

④のみ。

入力：

- `matchId`（必須、現在参加中の対戦内部ID）
- `roundNumber`（必須、整数1以上、現在ラウンド番号）
- `type`
- `normalHand`
- `originalHandId`

### 16.0 同一ラウンドの二重確定

要求のmatchId・roundNumberが、本人の現在対戦・現在ラウンドと一致することを先に確認する。不一致は `409 / INVALID_STATE` で拒否する。古い要求を現在のラウンドへ流用しない。

現在対戦のactive参加者であり、手選択中かつそのラウンドで未確定であることをサーバー側で確認する。

既に確定済みなら、同じ手の再送も別の手への変更も `409 Conflict / INVALID_STATE` で拒否する。

未確定確認・手の登録・全員確定判定を一連の排他的処理とし、二重要求で提出済み手を上書きしたり、ラウンド判定・勝数加算を二重に行ったりしない。

### 16.1 通常手

`type = NORMAL`

`normalHand = ROCK / SCISSORS / PAPER`

通常手は連続使用制限の対象外。

### 16.2 オリジナル手

`type = ORIGINAL`

`originalHandId` 必須。

対戦開始時に固定された使用可能な手だけ受け付ける。

### 16.3 同一オリジナル手連続使用禁止

`preventConsecutiveSameOriginalHand = false`

→ 検証しない。

`true` の場合でも現在手が通常手なら検証しない。

現在手がオリジナル手の場合：

1. 直前ラウンドの本人の選択を取得。
2. 直前もオリジナル手か確認。
3. 同一 `originalHandId` なら拒否。

違反時：

```text
400 Bad Request
VALIDATION_ERROR
```

手は確定しない。

NORMALではnormalHandを必須、originalHandIdを空とする。ORIGINALではoriginalHandIdを必須、normalHandを空とする。typeと値の不整合は400で拒否する。

正常時は `302 → /play?matchId=対象ID` とし、GET側で現在の進行状態に合う画面を表示する。

入力エラーは400、二重確定・古い要求・状態不一致は409。現在も対象ラウンドを選択可能なら対戦画面を再表示し、それ以外は現在の正規画面でエラーを表示する。いずれも拒否要求で手・勝数・対戦状態を変更しない。

## 17. `GET /round-result`

入力は任意の `matchId`。省略時は現在の進行中対戦を対象とする。

閲覧可能：

- 現在の対戦参加者。
- 同一ルームの②・③観戦者。

手選択中なら `/play?matchId=対象ID`、終了済みなら権限確認後に `/match-result?matchId=対象ID` へ302リダイレクトする。④は本人の現在対戦を優先する。不存在・他ルームの対象は29節に従う。

Model：

- `roomId`・`matchId`（表示しない制御用データ）
- `roundNumber`
- `results`：確定済みRoundResultEntryの表示用コピー（userId・username・handName・wonRound）。
- `scores`：resultsに含まれる各参加者のuserId・開始時のusername・その時点の累積勝数。
- `hasRoundWinner`
- `transitionAt`：サーバーが決めた遷移時刻。
- `matchFinished`：正常最終勝者確定済みか。
- `userState`：④は参加者、②・③は観戦用の操作表示。
- `roomActionForm`（roomId、④の「ルームを退出」用）

scoresはMatchParticipantから共通ロック内で表示用にコピーする。RoundResultEntry・roundHistory自体に累積勝数を追加しない。

手動次ラウンドAPIは設けない。カウント表示だけで終了を判定せず、対象matchIdのサーバー状態を確認する。

観戦表示でも他タブの結果対象と②・③の状態を変更しない。

## 18. ラウンド自動遷移

ラウンド結果確定時にサーバーで `transitionAt` を設定する。

### 勝者あり

結果確定＋10秒。

### 勝者なし

結果確定＋5秒。

期限到達後の次回MatchTransitionScheduler実行時：

- 未決着 → 次ラウンドを生成し `SELECTING_HAND`
- 正常決着確定済み → `MATCH_RESULT`

最終勝者確定後は、10秒の期限到達後の正常終了処理が完了するまで、切断で結果を `ABORTED` に変更しない。

### 18.1 時間仕様の共通基準

5秒・10秒・30秒はサーバー上の期限として扱い、実時間での画面切替との完全一致を要求しない。

- ラウンド結果の期限は結果確定時刻＋5秒または＋10秒、通信切断の期限は最後の正常状態確認時刻＋30秒とする。入室直後は入室時刻を通信監視の基準とする。
- 期限より前に、時間経過を理由とする状態遷移・切断処理を行ってはならない。
- MatchTransitionScheduler（対戦の自動遷移を確認する定期処理）は500ms間隔、DisconnectMonitor（切断を確認する定期処理）は1秒間隔で確認し、期限到達後の次回実行時に、共通ロック内で最新の状態と時刻を確認して更新する。
- ブラウザーの状態確認は2秒間隔とし、サーバー状態変更後の次回状態確認で表示へ反映されればよい。カウントが0になったことだけで画面側が状態を確定しない。
- 時間依存ロジックはJava `Clock`等（現在時刻を取得する仕組み）を利用し、テストで現在時刻を制御可能にする。期限前の非遷移と期限到達後のScheduler実行による遷移を分けて検証し、実時間の完全一致を合否条件にしない。

人数不足による即時中止や手動退出等、時間経過によらない既存の処理はこの待機期限の対象ではなく、従来どおり実行する。最終勝者確定後は、期限到達後の正常終了処理が完了するまで正常結果の保護と残存参加者の④を維持する。

## 19. ラウンド履歴データ

`roundHistory` の各要素：

- `roundNumber`
- `results`

各 `results`：

- `userId`
- `username`
- `handName`
- `wonRound`

実装のRoundResult.entriesを表示用resultsへコピーし、roundNumberと組み合わせて返す。

履歴データには累積勝数・個別相性・対戦相手ごとの勝敗を含めない。

## 20. 途中退出

退出者のMatchParticipantを削除せずactive=falseとする。操作全体は共通ロック内で行う。

| 退出時の対戦状態 | 残存人数 | 処理 |
|---|---|---|
| 手選択中、最終勝者未確定 | 2人以上 | 退出者の現在選択だけ除外。残存者全員が確定済みなら1回だけ判定 |
| 手選択中、最終勝者未確定 | 1人以下 | ABORTED、winners=[]で即時終了 |
| 未決着ラウンド結果表示中 | 2人以上 | 確定済み結果・履歴・勝数・直前手・transitionAtを維持。再判定しない |
| 未決着ラウンド結果表示中 | 1人以下 | 即時ABORTED。確定済み履歴・獲得済み勝数は保持 |
| 最終勝者確定後の結果表示中 | 0人を含む全人数 | 正常勝者・結果を維持し、結果確定＋10秒の期限到達後の次回MatchTransitionScheduler実行でNORMAL終了 |

未決着結果表示中の退出者は次ラウンドから除外する。確定済みラウンドの選択・表示を消さない。

### 20.1 終了時の状態更新

正常終了では10秒の期限到達後の次回MatchTransitionScheduler実行で終了処理する時点、中止では中止確定時に、当該対戦に参加し現在も同じルームに残っている④のみ②へ変更する。

対戦非参加の②・③は変更しない。退出・切断済みの①を②へ戻さない。

ルームが削除済みでも、対戦の固定情報だけから確定済み正常結果を保存する。ルーム更新は同じ内部roomIdが存在し、currentMatchIdが当該対戦の場合だけ行う。同名で再作成された別ルームを変更しない。

## 21. `GET /match-result`

### 21.1 閲覧条件

次をすべて満たす場合に閲覧できる。

- ログイン済み。
- 現在の状態が②または③。
- 対象対戦の終了済みスナップショットが存在する。
- スナップショットの内部roomIdが現在所属するルームの内部IDと一致する。

参加者だったかどうかは条件に含めない。非参加の②・③も閲覧可能。

未ログインは `/`、①は `/rooms`。④は本人が参加中の新しい対戦の `/play?matchId=...` または `/round-result?matchId=...` へ302リダイレクトする。

指定対象が不存在・未終了・他ルーム・形式不正なら、その結果を公開せず `/room` へ戻す。同名でも再作成された別内部IDのルームは同じルームとみなさない。

### 21.2 閲覧対象の固定

入力は任意の `matchId`（終了済み対戦の内部ID）。

- 指定あり：そのIDの終了済み結果を権限確認して表示する。無効な指定を最新結果へ置き換えない。
- 指定なし：現在ルームのlastCompletedMatchIdを一度だけ解決し、`302 → /match-result?matchId=解決したID` とする。閲覧可能な結果がなければ `/room`。

対戦・観戦画面からの終了時遷移では、必ずその画面で扱っていたmatchIdを指定する。遷移が遅れて次対戦が終了していても、元の対戦結果を表示する。

閲覧対象はタブごとのURL・HTML制御用データ・フォームに保持する。GameUserやHTTPセッションに単一の閲覧先IDを保存しない。

次対戦の開始・進行・終了、lastCompletedMatchIdの更新、別タブの操作で対象を変えない。再読み込み・オリジナル手の作成／更新／削除後も同じIDを使用する。

本人が④になった場合と、退出・切断・ログアウトで閲覧権限を失った場合は、現在のアクセス制御を優先する。

### 21.3 Model

- `roomId`・`matchId`（内部ID、表示しない制御用データ）
- `endType`：NORMAL / ABORTED。
- `winners`：勝者の内部ユーザーID・開始時のユーザー名。
- `finalScores`：開始時の全参加者の内部ユーザーID・開始時のユーザー名・最終勝数。
- `roundHistory`
- `originalHandAffinities`
- `userState`
- `currentOriginalHand`
- `matchResultReturnForm`（roomId・matchId）
- `originalHandForm`・`originalHandDeleteForm`（returnPage=MATCH_RESULT、roomId・resultMatchId=表示中のmatchId）

結果データはMatchResultSnapshotだけから取得する。現在のGameMatchや過去参加者のGameUserで名前を復元しない。

途中退出者と0勝の参加者もfinalScoresに含める。

現在のuserStateとcurrentOriginalHandは編集導線・現在の設定用として別に取得する。現在のオリジナル手更新によって過去結果を変更しない。

## 22. `originalHandAffinities`

各要素：

- `handId`
- `handName`
- `vsRock`
- `vsScissors`
- `vsPaper`
- `vsOriginal`

`handId` は画面に表示しない。

## 23. 相性表の表示切替

新しいAPIは追加しない。

`GET /match-result` で必要データを渡し、JavaScriptで表示／非表示を切り替える。

状態変更は行わない。

## 24. 対戦結果画面からのオリジナル手編集

ユーザー状態②の場合：

- 作成済み → 編集導線
- 未作成 → 作成導線

を表示可能。

保存は既存の `POST /original-hand/save` を使用する。

`returnPage = MATCH_RESULT` とし、表示中のroomIdとresultMatchIdも送信する。作成・更新・削除の成功後と入力エラー時の再表示に同じresultMatchIdを使用する。

更新後も終了済み対戦スナップショットは変更しない。

③の場合は編集不可。

## 25. `POST /match-result/return`

入力：`roomId`・`matchId`（表示中の終了済み対戦）。

②・③では現在所属ルームと対象結果のroomIdを検証し、`302 → /room`。

ユーザー状態は変更しない。②は②、③は③のまま。他タブのURL・結果対象を変更する処理は行わない。

要求処理時に既に④なら、状態を変更せず、現在対戦の `/play?matchId=...` または `/round-result?matchId=...` へ302リダイレクトする。

roomId不一致や無効な対象は409で拒否し、現在の正規画面にエラーを表示する。

## 26. `GET /api/status`

2秒間隔で使用する。正常確認時にlastSeenAtを更新する。

入力：任意の `matchId`。SCR-004・005・006は表示中の対戦IDを指定する。SCR-002・003では指定しない。

| 項目 | 型・値・意味 |
|---|---|
| `serverTime` | UTCのISO日時。カウント表示の基準 |
| `userState` | ROOM_NONE / ROOM_WAITING / READY / PLAYING |
| `currentRoomId` | 現在所属する内部ルームID。①はnull |
| `roomState` | NONE（未所属）/ WAITING（進行中対戦なし）/ PLAYING（進行中対戦あり） |
| `currentMatchId` | 現在ルームの進行中対戦ID。なければnull |
| `matchState` | 現在対戦のSELECTING_HAND / ROUND_RESULT。なければnull |
| `roundNumber` | 現在対戦のラウンド番号。なければnull |
| `transitionAt` | 現在対戦がROUND_RESULTのときだけUTCのISO日時。それ以外null |
| `lastCompletedMatchId` | 現在ルームの最新の終了済み対戦ID。なければnull |
| `displayMatchId` | 指定matchIdが現在ルームの閲覧可能な対戦ならそのID。無指定・無効・他ルームならnull |
| `displayMatchState` | 指定対象のSELECTING_HAND / ROUND_RESULT / MATCH_RESULT。displayMatchIdがnullならnull |
| `displayRoundNumber` | 指定対象が進行中なら現在ラウンド番号。終了済み・無効ならnull |
| `displayTransitionAt` | 指定対象がROUND_RESULTのときの遷移時刻。それ以外null |
| `selfHandConfirmed` | 指定対象が本人の現在対戦の手選択中で、本人が④かつactive参加者ならboolean。本人の手が確定済みならtrue、未確定ならfalse。それ以外null |
| `selfSelectedHand` | selfHandConfirmed=trueのときだけ本人の確定済み手の表示用コピー。それ以外null。構造は下記 |
| `room` | 現在ルームの表示用情報。①はnull。構造は下記 |

roomの構造：

- id・name・hostUserId。
- targetWins・preventConsecutiveSameOriginalHand。
- members：入室順のuserId・username・userState・isHost。

JSONの内部IDはUUID文字列、ラウンド番号・勝数は整数、時刻はUTCのISO日時文字列として扱う。各null条件は上表に従う。

selfHandConfirmed・selfSelectedHandはdisplayMatchId・displayRoundNumberの対象に属する情報とする。指定matchIdがcurrentMatchIdと一致し、対象がSELECTING_HANDで、セッションの本人が④かつその対戦のactive参加者の場合だけ生成する。matchId無指定・無効・他ルーム、②・③の観戦、ROUND_RESULT、終了済み結果では両方nullとする。

正常応答での無効なmatchIdは、UUID形式は正しいが表示可能な対象がない指定を指す。UUID形式不正は1.4節に従って400のJSONエラーとし、正常応答の項目を返さない。

selfSelectedHandの構造：

- `type`：NORMAL / ORIGINAL。
- `normalHand`：NORMALならROCK / SCISSORS / PAPER、ORIGINALならnull。
- `originalHandId`：ORIGINALなら固定済み手のUUID、NORMALならnull。
- `handName`：通常手の表示名、または対戦開始時に固定したオリジナル手名。

本人は送信されたユーザーIDではなくセッションから特定する。他参加者のselectionsやオリジナル手相性はこの応答に含めない。共通ロック内で、対象ID・ラウンド番号・本人の確定状況・本人の手を同じ時点の表示用コピーにする。

roomStateは上記3値を導出して返す表示用値であり、保存用の追加状態を作らない。

終了済みの指定対象はMatchResultSnapshotのroomIdで権限確認してdisplayMatchState=MATCH_RESULTとする。指定対象が他ルーム等の場合、その対戦の情報を返さない。

未ログインは401のJSON。①では現在対戦・指定対戦・roomをすべてnullとし、roomState=NONEとする。

currentMatchIdは現在の進行中対戦、displayMatchIdはタブが扱う対象を表す。状態確認でURLの対象IDを変更しない。

### 26.1 画面制御の優先順

1. 未ログイン→ `/`。①→ `/rooms`。
2. ④→本人のcurrentMatchIdを優先。SELECTING_HANDならplay、ROUND_RESULTならround-result。
3. ②・③で表示ページのroomIdとcurrentRoomIdが異なる→現在の `/room`。
4. SCR-006の②・③→表示中の結果を維持。新対戦の状態で切り替えない。
5. SCR-004・005の②・③→displayMatchStateに従い同じ対象のplay・round-result・match-resultへ移動。
6. SCR-003の②・③→ルームに留まり、参加者・ホスト・ルール・ボタンを更新。本人が「対戦を見る」を押したときだけ観戦へ進む。

進行中対象のラウンド番号が変わった場合は対戦画面の該当ラウンドを更新する。5秒／10秒を過ぎてもサーバーが未遷移なら確認を続け、クライアントだけで次ラウンドを確定しない。

SCR-004の④が同じ対戦・ラウンドを表示中なら、selfHandConfirmed・selfSelectedHandで本人の手確定表示を更新する。trueならすべての手選択ボタンを無効化し、本人の選択手と他参加者を待つ状態を反映する。状態確認のたびにページ全体を再読み込みしない。

確定情報の更新にはページのmatchId・roundNumberと一致するdisplay対象だけを使用する。同じ対戦・ラウンドで一度確定済みを反映した後は、遅延したfalseの応答で手選択を再有効化しない。次ラウンドへ移った場合だけ確定表示を初期化し、連続使用制限を含む新しいラウンドの選択条件を適用する。

HTML・JSONに確定前の他人の選択手や公開前の他人の相性を含めない。本人用の許可された編集フォームと終了済み結果の公開相性は、それぞれのModelで扱う。

## 27. 通信切断

最後の正常状態確認＋30秒をサーバー上の切断期限とする。DisconnectMonitorは1秒間隔で確認し、期限到達後の次回実行時に、共通ロック内で最新lastSeenAtと現在状態を再確認して切断処理する。期限前には切断しない。18.1節の時間仕様を適用し、表示はサーバー状態変更後の次回2秒間隔の状態確認で反映する。

②・③：

- ルーム退出
- ①へ変更
- ホストなら移譲
- 0人ならルーム削除

④：

- 対戦からactive除外
- ルームから退出
- 残り人数によって継続または中止

## 28. 主なエラー

| HTTP | コード | 内容 |
|---|---|---|
| 400 | `VALIDATION_ERROR` | 入力不正 |
| 401 | `LOGIN_REQUIRED` | 未ログイン |
| 403 | `FORBIDDEN` | 権限不足 |
| 404 | `ROOM_NOT_FOUND` | ルーム不存在 |
| 409 | `ROOM_FULL` | 8人参加済み |
| 409 | `INVALID_STATE` | 現在状態では操作不可 |
| 409 | `ORIGINAL_HAND_REQUIRED` | オリジナル手未作成 |
| 409 | `READY_USERNAME_CONFLICT` | ③とのユーザー名重複 |
| 409 | `READY_HAND_NAME_CONFLICT` | ③との手名重複 |
| 500 | `INTERNAL_ERROR` | 内部エラー |

## 29. 直接URLアクセス

未ログインのGET / はログイン画面を表示し、それ以外の画面GETは `/` へ302リダイレクトする。ルーム内画面へ①でアクセスしたら `/rooms` へ302リダイレクトする。

| 現在状態・対象 | `/`・`/rooms` | `/room` | `/play`・`/round-result` | `/match-result` |
|---|---|---|---|---|
| ① | `/`は `/rooms`、`/rooms`を表示 | `/rooms` | `/rooms` | `/rooms` |
| ②・③ | `/room` | ルーム表示。進行中でも維持 | 有効な同一ルーム対象の進行状態に合う画面 | 21節の同一ルーム終了結果 |
| ④ | 現在対戦の正規画面 | 現在対戦の正規画面 | 本人の現在対戦を優先 | 本人の現在対戦を優先 |

②・③のplay・round-resultで対象matchIdを解決した後：

- SELECTING_HAND→playを表示、round-resultへの直接アクセスはplayへ誘導。
- ROUND_RESULT→round-resultを表示、playへの直接アクセスはround-resultへ誘導。
- 終了済み→同じmatchIdのmatch-resultへ誘導。
- 無指定かつ進行中なし、無効な指定、他ルームの対象→ `/room`。

④の正規画面は本人の現在対戦の状態で決定し、古い対戦IDで本人の現在対戦を変更しない。

②・③の結果画面は同一ルームの閲覧条件を満たす間、URLの対象を維持する。次対戦の④になった本人は新対戦を優先する。その対戦が終了して②になったら、終了した対戦IDを指定して結果へ移る。
