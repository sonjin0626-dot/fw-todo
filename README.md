# fw-todo

Spring Boot / Thymeleaf / MyBatis / MySQLで動くToDoアプリです。

## ログイン機能の使い方

1. MySQLを起動し、通常どおりアプリを起動してください。Docker構成の場合は `docker compose up -d --build` で変更を反映できます。
2. `http://localhost:8080/login` を開き、「アカウント登録」からユーザー名・パスワードを登録します。
3. 登録した情報でログインすると `/todos` に移動します。
4. 各画面の「ログアウト」でセッションを終了できます。

既存Todoの作成者として、初回起動時にユーザー名 `sonjin`、パスワード `password` のアカウントを作成します。同名のアカウントがすでにある場合は、そのアカウントと既存のパスワードを保持します。新規登録するユーザー名は半角英数字・ハイフン・アンダースコアの3〜50文字、パスワードは8文字以上かつUTF-8で72バイト以内です。パスワードはBCryptでハッシュ化し、DBにはハッシュだけを保存します。

アプリ起動時に `src/main/resources/db/users.sql` と `db/todos.sql` で、存在しないテーブルを作成します。続いて `TodoOwnershipSchemaInitializer` が既存の `todos` に `user_id` 列と `users(id)` への外部キーを追加し、所有者未設定のTodoを `sonjin` に紐付けます。削除済み・完了済みも対象です。所有者がすでにあるTodoや既存のパスワードは再起動時も変更しません。DockerのDBボリューム削除やデータの再作成は不要です。

## 変更したファイルと役割

| ファイル | 変更内容 |
| --- | --- |
| `pom.xml` | Spring Security・ThymeleafのSecurity拡張を追加。テスト用にSecurity Test・H2を追加。バージョンは既存のSpring Boot 4.0.8の依存管理に合わせる。 |
| `SecurityConfig.java` | ログイン・登録・静的リソースを公開し、それ以外を認証必須にする。フォーム認証、ログアウト、BCryptを設定。 |
| `User.java` / `UserMapper.java` | ユーザーのデータ構造と、MyBatisによる取得・登録を追加。 |
| `CustomUserDetailsService.java` | DBのユーザーをSpring Securityの認証用データに変換。 |
| `RegistrationForm.java` / `AuthController.java` | ログイン画面の表示、登録の入力検証、パスワードのハッシュ化、ユーザー名重複の処理。登録時の権限は常に `USER`。 |
| `db/users.sql` / `application.properties` | ユーザーテーブルの自動作成と、30分のセッション有効期限、HttpOnly・SameSite Cookieを設定。 |
| `templates/login.html` / `register.html` | ログイン・登録画面、認証失敗・登録完了・ログアウトのメッセージを追加。 |
| `templates/fragments/account.html` / 既存8画面 | 共通部品でログイン中のユーザー名とログアウトボタンを表示。 |
| `static/css/style.css` | 認証フォームとアカウント欄のスタイルを追加。 |
| `SecurityIntegrationTests.java` / `application-test.properties` | H2のテストDBで実際のDB認証、登録、CSRF、セッション変更・破棄を検証。 |
| `AuthenticatedMvcTestConfig.java` / 既存の画面テスト4ファイル / `TodoappApplicationTests.java` | 既存の画面テストにログイン済みユーザーとCSRFトークンを設定。起動テストにはテストDBを使用。 |

CSRF保護は有効です。Thymeleafの `th:action` を使ったPOSTフォームにはCSRFトークンが自動で追加されます。ログアウトもPOSTで実行します。セッションIDはログイン成功時に変更され、ログアウト時にはセッションを無効化します。

## ユーザー別Todo管理

一般ユーザー（USER）は自分のTodoだけを表示・編集できます。一覧、検索、件数、ページ送り、カレンダー用API、CSV、ゴミ箱、MCPの一覧・集計にもユーザー条件を適用します。管理者（ADMIN）は同じ画面で全ユーザーのTodoを扱えます。

新規登録時の `user_id` は `TodoService` がSpring Securityの認証情報と `UserMapper` から取得します。フォームやJSONで別の `userId` を送っても所有者は変更できません。更新・削除・復元・印の切替・確認画面では所有者を確認し、一般ユーザーが他ユーザーのIDを直接指定した場合は403を返します。管理者が他人のTodoを編集しても元の所有者を保持します。存在しないIDに対するAPIは従来どおり404です。一括削除は管理者だけが利用できます。

| ファイル | 役割 |
| --- | --- |
| `Todo.java` | 所有者の `userId` を保持。 |
| `TodoMapper.java` / `mapper/TodoMapper.xml` | 検索・件数・ゴミ箱・CSV・更新SQLを所有者で制限。作成時に `user_id` を保存。 |
| `TodoService.java` | 認証情報から所有者を決定し、全入口に共通のアクセス制御を適用。 |
| `HomeController.java` / `templates/todos.html` | `@AuthenticationPrincipal` によるユーザー名の取得、件数の表示、編集確認画面のアクセス検査。 |
| `TodoOwnershipSchemaInitializer.java` / `db/todos.sql` / `initdb/01_create_table.sql` | 新規DBの定義と既存DBの移行。 |
| `TodoOwnershipIntegrationTests.java` / `TodoOwnershipSchemaInitializerTests.java` | 2ユーザー間のデータ分離、403、所有者改ざん、一括削除、MCP、既存データ移行を検証。 |

API（`/api/**`）とMCP（`/mcp`）も認証対象です。既存の外部クライアントはログイン済みセッションを扱う必要があり、POST・PUT・DELETEにはCSRFトークンも必要です。外部連携用のOAuthやAPIキー認証は追加していません。

## ロール別権限制御

| 操作 | USER | ADMIN |
| --- | --- | --- |
| ToDoの閲覧・編集・削除・復元 | 自分の分のみ | 全ユーザー分 |
| カレンダー・検索・CSV・MCP | 自分の分のみ | 全ユーザー分 |
| ToDo新規作成 | 自分の所有で作成 | 自分の所有で作成 |
| 一括削除 | 利用不可 | 利用可能 |
| `/admin/users` のユーザー一覧・詳細・編集・削除 | 利用不可 | 利用可能 |

初回の管理者は、登録済みアカウントをDBで昇格して用意してください。例えば `sonjin` を管理者にする場合は、MySQLで次を実行します。アプリがこの昇格を自動実行することはありません。

```sql
UPDATE users SET role = 'ADMIN' WHERE username = 'sonjin';
```

アプリを再起動すると、既存DBには `users.enabled` 列が追加されます。既存ユーザーは有効状態で保持されます。新規登録のロールは引き続きUSER固定です。管理者でログインすると、共通メニューに「ユーザー管理」が表示されます。ロール変更や無効化は、対象ユーザーの次のリクエストから反映されます。無効なアカウントは新規ログインもできません。自分自身の降格・無効化は禁止しています。

## ユーザー管理CRUD

管理者メニューの「ユーザー管理」（`/admin/users`）から、次の操作を行えます。

- 一覧：ID、ユーザー名、ロール、有効状態、登録日を表示します。
- 詳細：`/admin/users/{id}` で登録日時・更新日時を含めて表示します。パスワードやハッシュは表示・取得しません。
- 編集：既存の `/admin/users/{id}/edit` でユーザー名、ロール、有効状態、新しいパスワードを設定できます。ユーザー名は登録時と同じ形式で、重複は禁止です。パスワードは空欄なら保持し、入力する場合は8文字以上・UTF-8で72バイト以内を検証し、BCryptで保存します。旧パスワードは不要です。
- 削除：一覧・詳細の「削除」で確認ダイアログを表示し、`POST /admin/users/{id}/delete` で物理削除します。完了済み・ゴミ箱内を含む関連ToDoを先に削除し、ユーザー削除と同じトランザクションで確定します。失敗した場合は全体をロールバックします。自分自身の削除は禁止です。復元が必要な場合は、有効状態をオフにしてください。

自分のユーザー名・パスワードを変更すると、その操作中のセッションを終了し再ログインを求めます。改名・削除されたアカウントの既存セッションは、次のアクセスで拒否されます。同じユーザー名が再登録されても古いセッションは引き継がれません。

再起動時に既存の `users` へ `updated_at` 列を自動追加します。移行前の更新履歴は存在しないため、既存行の初期値は移行時点の日時です。ユーザー管理で変更した日時を以後記録します。初期アカウント `sonjin` は空のDBまたは所有者未設定ToDoの移行時に用意し、移行が不要なDBでは、改名・削除しても再作成しません。

追加・拡張した主なファイルは `UserEditForm.java`、`AdminUserController.java`、`AdminUserService.java`、`UserMapper.java`、`TodoMapper.java`、`templates/admin/` です。`AdminUserCrudIntegrationTests` では詳細表示、入力検証、パスワード保持・変更、所有者保持、権限制御、CSRF、自己削除禁止、関連ToDo削除、ロールバック、既存セッションを検証します。

| 変更ファイル | 内容と理由 |
| --- | --- |
| `SecurityConfig.java` | `@EnableMethodSecurity` でメソッド認可を有効化。`/admin/**` をADMIN専用にし、その他の保護URLにはUSERまたはADMINを要求。 |
| `AccountStatusFilter.java` | リクエストごとにDBのロール・有効状態を確認。既存セッションの古い権限による操作を防止。 |
| `User.java` / `UserMapper.java` / `CustomUserDetailsService.java` | `enabled` の保持・取得、ユーザー管理用SQL、無効アカウントのログイン拒否を追加。管理画面向けの取得SQLにはパスワードを含めない。 |
| `AdminUserService.java` / `AdminUserController.java` | `@PreAuthorize("hasRole('ADMIN')")` でユーザー管理を保護。ロールの許可値・対象ユーザーの存在・自分自身の降格や無効化を検査。 |
| `TodoService.java` | 本人のIDを通常の検索条件とし、管理者のときだけ全件を対象にする。編集時は元の所有者を保持。一括削除にもADMIN認可を適用。画面・API・MCPに共通で作用。 |
| `mapper/TodoMapper.xml` | 管理者の検索・更新・削除・復元に対応。`userId` が指定された場合は従来どおり所有者で絞る。 |
| `HomeController.java` | 一括削除のHTTP入口にもADMIN認可を適用。 |
| `templates/todos.html` / `templates/fragments/account.html` | `sec:authorize` で管理者メニュー・一括削除・所有者IDを表示。管理者の一覧は全ユーザー分であることを明示。 |
| `templates/admin/users.html` / `templates/admin/user-edit.html` | ユーザー一覧と、ロール・有効状態の編集画面を追加。POSTにはThymeleafのCSRF保護を使用。 |
| `templates/error/403.html` | 権限不足時の案内を追加。 |
| `TodoOwnershipSchemaInitializer.java` / `db/users.sql` / `initdb/01_create_table.sql` | 既存DB・新規DBの両方で `enabled` 列を利用可能にする。 |
| `RoleAuthorizationIntegrationTests.java` | USERによる管理機能の拒否、ADMINの他人のToDo操作、所有者保持、ロール変更、無効化、既存セッションへの反映、CSRFを検証。 |
| `TodoBulkDeletionTests.java` / `TodoOwnershipIntegrationTests.java` / `SecurityIntegrationTests.java` | 管理者専用の一括削除と、DBに実在するユーザーの認証を前提に既存テストを調整。 |

添付の `/todo` はこのアプリの既存URL `/todos` に、`TodoController` は `HomeController` に対応させています。既存のSpring Boot 4 / Java 21の構成を維持しています。画面を非表示にするだけでなく、URL・メソッド・所有者の検査をサーバー側でも行います。

## テスト

Windowsでは `mvnw.cmd` がプロジェクト指定のMaven 3.10.0を取得・起動します。動作確認は `.\mvnw.cmd -v`、テストは次のコマンドで実行できます。別途Mavenをインストールする必要はありません。

```powershell
.\mvnw.cmd test
```

認証・所有者管理・移行・起動テストはテスト専用のH2を使用します。既存のMySQL統合テストは `COMPLETION_TEST_DB_PASSWORD` が設定された場合だけ実行されます。

### WindowsでのMaven Wrapper起動エラー

同梱のWrapper 3.3.4には、`.m2` のリンク先情報 `Target` が `null` の場合でも `Target[0]` を参照し、`Cannot index into a null array` で停止する処理がありました。この環境のWindows PowerShell 5.1と通常の `.m2` フォルダーで再現を確認しています。

`mvnw.cmd` は通常のキャッシュパスを先に設定し、リンク先情報が存在する場合だけ参照するよう修正しています。`Get-Item` には `-LiteralPath` と `-Force` を指定しています。アプリのRUN起動やJavaコードのエラーではありません。Wrapperを再生成する際は、この修正が上書きされていないか確認してください。

関連するApache側の報告：[maven-wrapper issue #395](https://github.com/apache/maven-wrapper/issues/395)。
