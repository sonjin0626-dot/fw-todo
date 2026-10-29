# fw-todo

Spring Boot / Thymeleaf / MyBatis / MySQLで動くToDoアプリです。

## 多言語対応（日本語・英語）

各画面の「日本語」「English」リンクで表示言語を切り替えられます。
URLに `?lang=ja` または `?lang=en` を付けても切り替えられます（例：`/todos?lang=en`）。
既定の表示は日本語です。選択した言語は `SessionLocaleResolver` でセッションに保持し、画面遷移・ログイン後も引き継ぎます。ログアウトやセッション終了後は日本語に戻ります。

`WebConfig.java` にロケール設定と `LocaleChangeInterceptor` を配置しています。
`src/main/resources/messages.properties` は日本語のフォールバック、`messages_ja.properties` と `messages_en.properties` は各言語の画面文言・操作結果・入力エラーです。UTF-8で管理し、メッセージキャッシュは3600秒です。画面はThymeleafの `#{key}`、コントローラーは `MessageSource`、Jakarta Validationは `{validation.*}` で参照します。Spring Boot 4の既存構成を維持しています。

一覧・登録・確認・編集・削除・カレンダー・添付・認証・ユーザー管理・エラー画面を対応しています。
言語切り替え時は検索条件・ページ・カレンダーの表示期間を維持します。ToDo入力・確認画面では保存処理を実行せず、入力値をPOSTで引き継いで再表示します。
ジャンルはDBの既存の日本語値を保持して表示ラベルだけを翻訳します。利用者が入力したタイトル・メモ・ファイル名、および外部API由来の祝日名は元の内容を表示します。REST APIのフィールド名・ジャンル値・CSVの形式は従来どおりです。

`InternationalizationTests` で既定言語、セッション保持、未対応言語、各画面の英語表示、入力検証、登録結果、翻訳キーの整合性を検証します。

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

## 例外ハンドリング

画面用の `exception/GlobalExceptionHandler` とAPI用の `api/RestExceptionHandler` で例外を共通処理します。API用ハンドラーを優先し、画面にはHTML、`/api/**` には従来の `application/problem+json` 形式を返します。URLやHTTPメソッドの解決時に発生するAPIエラーもJSONで返します。

| 状況 | HTTPステータス | 応答 |
| --- | --- | --- |
| ToDoが見つからない | 404 | `TodoNotFoundException` → `error/404.html`、APIは `code: NOT_FOUND`。画面の編集・削除確認は従来の一覧へのリダイレクトから404表示へ変更。 |
| 業務上できない操作 | 400 | `BusinessException` → `error/business.html`、APIは業務エラーコードと説明。管理者自身の削除・降格・無効化や不正なロールなどに使用。 |
| APIの入力チェックエラー | 400 | `code: VALIDATION_ERROR` と既存の項目別 `errors`。 |
| 不正なJSON・日付・IDなど | 400 | 技術的な詳細を含まない説明。対応していないHTTPメソッドは405と `Allow` ヘッダーを保持。 |
| 権限不足 | 403 | 既存の権限制御を維持。コントローラー・サービスで発生した拒否は画面用403ページ、API用JSONへ変換。ログイン・CSRFなどフィルターでの判定はSpring Securityが処理。 |
| 予期しない例外 | 500 | `error/500.html`、APIは `code: INTERNAL_SERVER_ERROR`。例外の詳細・スタックトレースはサーバーログに記録。 |

APIの既存フィールド `type`、`title`、`status`、`detail`、`instance` を保持し、`code` と日時の `timestamp` を追加しています。`BusinessException` のメッセージには利用者に公開できる説明だけを渡してください。

このプロジェクトのSpring Boot 4に合わせて `spring.web.error.include-*` を設定し、Spring Boot標準のエラー応答でも例外名・内部メッセージ・スタックトレース・バインドの詳細を公開しないようにしています。404・500ページには一覧へ戻るリンクを設けています。存在しないToDoへの更新・削除・確認も404となり、成功通知を出しません。復元・印の切替・一括削除の既存の結果通知は維持しています。

想定内の業務エラー・不正なリクエストはWARN、予期しない例外はERRORで記録します。祝日API・天気APIの取得失敗もWARNを出力します。追加の `ExceptionHandlingTests` と所有者管理の統合テストで、ステータス、HTML/JSONの切替、入力チェック、内部情報の非表示、例外ログ、権限制御を検証します。

## AOPによるログ出力

添付教材のログ出力を、既存のSpring Boot 4 / Java 21の構成に合わせて実装しています。Boot 4では教材の `spring-boot-starter-aop` が `spring-boot-starter-aspectj` に改名されているため、後者を `pom.xml` に追加しています（[公式移行ガイド](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)）。

| ファイル | 役割 |
| --- | --- |
| `aspect/LoggingAspect.java` | `@Before` でメソッド名・引数、`@AfterReturning` で戻り値、`@AfterThrowing` でエラーメッセージをSLF4Jで出力。開始・終了はINFO、例外はERROR。 |
| `aspect/PerformanceAspect.java` | `@Around` と `System.nanoTime()` で経過時間を計測し、ミリ秒で出力。正常時はINFO、例外時はERROR。戻り値と例外を呼び出し元へそのまま返す。 |
| `ServiceAspectIntegrationTests.java` | 実際のSpring BeanとH2で、ログの内容・順序・実行時間・例外の伝播・パスワードの非表示を確認。 |

対象は `com.example.todoapp` 以下にある `@Service` クラスのpublicメソッドです。既存の `TodoService`、`AdminUserService`、`CustomUserDetailsService` に適用され、ControllerやMapperは対象に含みません。教材の `com.example.service.*` をそのまま使うと既存Serviceに一致しないため、次のPointcutを両Aspectで共有しています。

```java
@within(org.springframework.stereotype.Service) && execution(public * com.example.todoapp..*(..))
```

`@Order` によって開始 → 実行時間 → 正常終了（または例外）の順で記録します。例外時に正常終了のログは出しません。認証情報を含む `User`、`UserEditForm`、`UserDetails` はパスワードを `[PROTECTED]` に置き換え、ユーザー一覧の要素にも同じ処理を適用します。ToDoの引数・戻り値にはタイトルやメモが含まれます。既存の登録ID・削除件数などの操作ログも引き続き出力します。

```text
INFO  LoggingAspect - 【開始】TodoService.findById(..) 引数: [1]
INFO  PerformanceAspect - TodoService.findById(..) 実行時間: 2ms
INFO  LoggingAspect - 【終了】TodoService.findById(..) 戻り値: Todo(id=1, ...)
```

アプリ起動後に画面・REST API・MCPからToDoを操作すると、起動コンソールにログが出ます。必要に応じて `application.properties` の `logging.level.com.example.todoapp.aspect=OFF` で両Aspectのログを停止できます。

Spring AOPはSpringから注入されたBeanへの呼び出しに作用します。同じクラス内でのメソッド呼び出しや、`new TodoService(...)` で直接生成したオブジェクトには適用されません。例えば `search` のオーバーロードが内部で別の `search` を呼ぶ場合、外側の呼び出しだけを記録します。

## トランザクション管理

添付教材のトランザクション管理を既存の `TodoService` に適用しています。Spring Boot 4 / Java 21 / MyBatisの構成を維持し、既存の画面・REST API・MCPから共通で利用します。追加の依存関係は不要です。

| ファイル | 役割 |
| --- | --- |
| `TodoService.java` | クラスの既定値を `@Transactional(readOnly = true)` とし、検索・件数・詳細・アクセス確認を読み取り専用にする。登録・編集・削除・一括削除・復元・印の切替には `@Transactional(rollbackFor = Exception.class)` を指定。 |
| `HistoryMapper.java` | `todo_history` に対象ToDo ID、操作したユーザーID、操作種別、日時を保存。ToDoと同じトランザクションに参加し、どちらかの保存に失敗したら両方をロールバックする。 |
| `AuditService.java` / `AuditMapper.java` | `audit_logs` に操作の試行を記録。別Beanのpublicメソッドを経由した `REQUIRES_NEW` により、本体がロールバックしても監査ログは残る。 |
| `TodoMapper.java` | 編集・削除の更新件数を返し、実際に行を更新した場合に履歴を保存する。 |
| `db/transactions.sql` / `application.properties` / `initdb/01_create_table.sql` | 既存DB・新規DBに履歴・監査テーブルを追加する。 |
| `TransactionManagementIntegrationTests.java` | 実際のSpring BeanとH2でコミット・ロールバック・独立監査・読み取り専用・認可を検証する。 |

操作履歴の種別は `CREATE`、`UPDATE`、`DELETE`、`BULK_DELETE`、`RESTORE`、`TOGGLE_PIN` です。完了状態の変更は既存の編集処理を経由するため `UPDATE` として記録します。管理者が他人のToDoを操作した場合も、`actor_user_id` には操作した管理者のIDを保存します。復元や印の切替で更新対象がなかった場合は履歴を増やしません。一括削除では、重複ID・存在しないID・削除済みの行を履歴の対象から除外します。一括削除は全体を一つのトランザクションとして扱い、失敗を握りつぶして続行しません。

監査ログの種別は各操作名に `_ATTEMPT` を付けた値です。認可・入力検査を通った操作について、DBの変更に先立って記録します。本体の保存失敗や更新対象なしの場合にも残ります。作成前はIDが未発行なので、`CREATE_ATTEMPT` の従来列 `todo_id` はNULLです。新しい `entity_id` はAOPが作成後のIDを補完します。監査ログ自体の初回保存に失敗した場合は例外を伝播し、本体の変更も確定しません。操作の確定結果は後述する `outcome` で判定します。

履歴・監査のID列には外部キーを設けていません。管理者によるユーザー・関連ToDoの物理削除後も記録を保持し、独立した監査トランザクションが未コミットの行を参照して待機することを防ぎます。`todo_history` はID・操作のみを保存します。新しい監査ログには変更前後のタイトル・メモを含むToDoのJSONを保存します。パスワード・認証情報は保存しません。

`rollbackFor = Exception.class` によりチェック例外もロールバック対象です。既存の `BusinessException` は不正な操作を拒否するために使われているので、教材の警告用 `noRollbackFor = BusinessException.class` は適用していません。業務エラー時も変更を戻す動作を維持します。将来「警告が出ても変更を確定する」業務を追加する際は、その警告専用の例外型に限定して `noRollbackFor` を設定してください。

通常の更新処理は既定の `REQUIRED` で、呼び出し元にトランザクションがあれば参加します。監査だけが独立して確定します（[Spring公式の伝播の説明](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)）。Springから注入されたBeanへの呼び出しで適用されるため、監査メソッドを `TodoService` 内へ移して直接呼ばないでください。読み取りメソッドのオーバーロードは、外側の読み取り専用トランザクション内で処理します。

アプリを再起動すると `db/transactions.sql` が存在しないテーブルを自動作成します。既存データの削除やDBボリュームの作り直しは不要です。履歴の過去分は生成しません。確認には次のSQLを使えます。

```sql
SELECT id, todo_id, actor_user_id, action, created_at
FROM todo_history ORDER BY id DESC LIMIT 50;

SELECT id, todo_id, actor_user_id, action, created_at
FROM audit_logs ORDER BY id DESC LIMIT 50;
```

## 監査ログの検索・変更履歴

管理者でログインし、画面上部の「監査ログ」（`GET /admin/audit-logs`）を開きます。対象種別・対象ID・操作者ID・操作・結果・開始日・終了日で検索でき、1ページ20件を新しい順に表示します。終了日はその日の23:59:59までを含み、同時刻のログはIDの降順で安定して並びます。検索条件はページ送りでも引き継ぎます。入力不正は説明付きのHTTP 400です。

「詳細」で変更前・処理後のJSON、IPアドレス、User-Agentを開けます。対象ID、またはToDo編集画面の「変更履歴」から `/admin/audit-logs/entity/TODO/{id}` へ進めます。削除・ユーザー名変更後も、記録時点のユーザー名と履歴を表示します。画面と読み取りServiceを `@PreAuthorize("hasRole('ADMIN')")` で制限し、HTTPレスポンスに `Cache-Control: no-store` を付けています。JSONはHTMLエスケープして表示します。日本語・英語に対応します。

教材の構成を既存のSpring Boot 4 / Java 21、Long型ID、`TodoService` のvoidの戻り値、論理削除に合わせています。既存の `AuditService` / `AuditMapper` / `audit_logs` を拡張し、追加の依存関係はありません。JSONにはBoot 4が標準採用するJackson 3の `JsonMapper` を使用します（[Spring公式](https://docs.spring.io/spring-boot/4.0/reference/features/json.html)）。

- `@Auditable` / `AuditAspect`：作成・編集・削除・一括削除・復元・印の切替の前後で、DB上の実際のToDoをJSONに変換します。削除は論理削除なので、処理後にも `deletedAt` を含むスナップショットがあります。一括削除は重複を除いた対象ごとに記録します。
- `AuditService.log`：従来の認可済み位置で独立した試行ログを保存します。AOPはそのログを同じ呼び出しのスナップショットに関連付けます。認可失敗や一括削除の入力検査失敗ではログを作成しません。新しい監査対象を増やす場合も、アノテーションと認可後の試行記録を組み合わせてください。
- `AuditContext`：呼び出し単位の情報を保持し、例外時を含めて `finally` 相当の処理でスレッドから除去します。別スレッドへは渡しません。
- `AuditService.complete`：本体のトランザクション終了後に、別の `REQUIRES_NEW` でID・処理後の値・結果を補完します。外側のトランザクションに参加した場合も、その終了まで確定扱いにしません。結果補完に失敗した場合は初回の試行ログが残り、サーバーログに監査IDを記録します。

`outcome` は `COMMITTED`（確定）、`ROLLED_BACK`（ロールバック）、`NO_CHANGE`（変更なし）、`ATTEMPTED`（試行・結果未記録）、`FAILED`、`UNKNOWN`、`LEGACY`（拡張前の試行）です。`action` の `_ATTEMPT` は既存仕様として保持し、画面の操作名はCREATE等に読み替えます。ロールバック時の処理後JSONは確定したDB状態を表さないため、画面に説明を表示します。プロセス停止や結果保存失敗では `ATTEMPTED` のまま残る場合があります。

認可のアドバイス（順序200）、`AuditConfiguration` のトランザクション設定（`@EnableTransactionManagement(order = 250)`）、監査AOP（順序300）の順に内側へ実行し、監査用の読み取りを本体のトランザクション内で行います。専用の設定クラスにすることで、DBを使用しないMVCスライステストへトランザクション設定が混入しません。ユーザーID・所有者は既存Serviceの検査を使います。IPは `getRemoteAddr()` を使い、クライアントが送信した転送ヘッダーを直接信用しません。信頼するプロキシがある場合はサーバー側で設定してください。User-Agentは500文字まで保存します。

再起動時に `AuditSchemaInitializer` が既存の `audit_logs` へ列・索引を追加します。従来の `todo_id` / `actor_user_id` を残し、既存ログの `entity_id` / `user_id` を補完します。過去のJSON・ユーザー名・結果を推測して作成することはありません。DBの削除・ボリュームの再作成は不要です。ログにはToDo本文と接続情報が残るため、閲覧は管理者に限定しています。

```sql
SELECT id, action, entity_type, entity_id, user_id, username, outcome, created_at
FROM audit_logs ORDER BY created_at DESC, id DESC LIMIT 50;
```

`AuditLogIntegrationTests` は実際のAOP・DB・Thymeleafで、変更前後、作成ID、接続情報、ロールバック、検索・日付終端・ページ送り、権限制御、HTMLエスケープ、日英表示を検証します。`AuditSchemaInitializerTests` は既存ログの保持と移行の再実行を検証します。

```powershell
.\mvnw.cmd "-Dtest=AuditLogIntegrationTests,AuditSchemaInitializerTests,TransactionManagementIntegrationTests" test
```

## ファイル添付

ToDo一覧または編集画面の「添付ファイル」から、ファイルの追加・一覧表示・ダウンロード・削除を行えます。新しいToDoは先に登録してください。添付画面は `/todos/{id}/attach` です。元の日本語ファイル名を表示・ダウンロードに使用し、保存名はUUIDに置き換えます。同名ファイルを複数添付しても上書きしません。

- 1ファイル10MBまで。1回に1ファイルを送信します。multipart全体の上限はヘッダー分を含めて11MBです。サイズ超過は画面で説明し、Servletによる拒否はHTTP 413を返します。
- 許可する拡張子は `.pdf`、`.txt`、`.csv`、`.png`、`.jpg`、`.jpeg`、`.gif`、`.webp`、`.doc`、`.docx`、`.xls`、`.xlsx`、`.ppt`、`.pptx` です。パスを含む名前、制御文字、不正な拡張子を拒否します。拡張子の検査を行い、ウイルススキャンは実装していません。
- 一般ユーザーは自分のToDoだけ、管理者は全ユーザーのToDoの添付を操作できます。ダウンロード・削除でも所有者を検査し、POSTには既存のCSRF保護を適用します。ダウンロードは `Content-Disposition: attachment` と日本語ファイル名を指定し、キャッシュを禁止します。
- ToDoをゴミ箱に移すと、添付を保持したままアクセスを停止します。復元すると再び利用できます。管理者がユーザーを物理削除すると、ゴミ箱・完了済みを含む関連添付も削除します。

`application.properties` の `app.upload.dir`（環境変数 `APP_UPLOAD_DIR`、既定値 `./uploads`）に実ファイル、`todo_attachments` テーブルにメタ情報を保存します。保存先は静的ファイルとして公開せず、認証済みのダウンロード処理で読み出します。アプリ再起動時に `db/attachments.sql` がテーブルを自動作成するため、既存DBの作り直しは不要です。

Docker Composeでは `fw-upload-data` ボリュームを `/app/uploads` にマウントします。コンテナを作り直しても添付は保持されます。DBと添付保存先の両方をバックアップしてください。ローカル保存先はGitとDockerのビルド対象から除外しています。

アップロードは、コピー・DB登録・呼び出し元のトランザクションに失敗した場合に実ファイルも削除します。ユーザー削除と同じ所有者行をロックし、同時のアップロードによって実ファイルだけが残る競合も防ぎます。削除はDBのコミット後に実ファイルを消すため、DBがロールバックした場合はファイルを保持します。コミット後のファイル削除にOSの権限などで失敗した場合はサーバーログに記録します。

主な実装は `TodoAttachment.java`、`AttachmentMapper.java` / `mapper/AttachmentMapper.xml`、`FileStorageService.java`、`TodoAttachmentController.java`、`templates/attachments.html` です。教材のInteger IDを既存のLong IDへ、`/todo` を `/todos` へ合わせ、Spring Boot 4 / Java 21の構成を維持しています。`TodoAttachmentIntegrationTests` では実際のDB・ファイル保存、権限制御、CSRF、日本語名、サイズ上限、ロールバックを検証します。10MBちょうどの受け付けと超過時の413は、組み込みサーバーへ実際のHTTPリクエストを送って確認します。

## 非同期処理

教材の非同期処理を既存のSpring Boot 4 / Java 21、Long型ID、`TodoService` の認証・所有者管理に合わせています。追加の依存関係やDB変更はありません。

画面上部の「ToDo集計」（`GET /dashboard`）で、合計・完了・未完了・期限切れ・今日から3日以内が期限の件数・ゴミ箱件数を表示します。通常の件数にはゴミ箱を含めません。一般ユーザーは本人のToDo、管理者は全ユーザーが対象です。日本語・英語の表示に対応します。

| ファイル | 役割 |
| --- | --- |
| `TodoappApplication.java` | `@EnableAsync` で非同期処理を有効化。 |
| `AsyncConfig.java` | `ThreadPoolTaskExecutor` を2つ定義。`taskExecutor` は通常5・最大10スレッド・待機25件、`emailExecutor` は通常2・最大5スレッド・待機100件。終了時は最大30秒待機。`void` の非同期メソッドの例外を `AsyncUncaughtExceptionHandler` で記録。 |
| `TodoService.java` / `TodoCreatedEvent.java` | ToDo登録・履歴保存の後に、IDだけを持つ不変イベントを発行。画面・API・MCPに共通で適用。 |
| `TodoCreatedListener.java` / `NotificationService.java` | トランザクションの確定後、別Beanの `@Async("emailExecutor")` を呼ぶ。ロールバック時は通知せず、キューが満杯の場合は通知の受付失敗をログに記録。 |
| `ReportService.java` / `Report.java` / `Statistics.java` | `@Async("taskExecutor")` で集計し、`CompletableFuture` で結果を返す。既存の `TodoService` を通してデータを取得。 |
| `DashboardController.java` / `templates/dashboard.html` | 2つの集計を並列に開始し、`CompletableFuture.allOf(...).get(timeout, TimeUnit.SECONDS)` で結果を待つ。成功時だけ両結果を表示。 |
| `AsyncProcessingIntegrationTests.java` / `DashboardControllerTests.java` | 実際のSpringプロキシとH2で別スレッド・並列実行・所有者・管理者の範囲・コミット後通知・ロールバック・例外ログを検証。Controllerではタイムアウト・中断・キュー満杯・失敗時の表示を確認。 |

登録後の通知は `NotificationService` から `MailService` を呼び出し、所有者のメールアドレスへ送信します。`emailExecutor` を利用し、初期設定では実送信せずログで確認します。詳しくは次の「メール送信通知」を参照してください。待機を再現するための `Thread.sleep` は入れていません。

`taskExecutor` のスレッド名は `Async-`、通知用は `Email-` で始まります。各タスクには呼び出し時の認証を引き継ぎ、実行終了時にワーカースレッドから除去します。これは認証をスレッド単位で保持する既存の `TodoService` を使うためです（[Spring Security公式](https://docs.spring.io/spring-security/reference/servlet/integrations/concurrency.html)）。通知は [Springのトランザクションイベント](https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html) の `AFTER_COMMIT` を使います。同じクラス内で非同期メソッドを直接呼ぶとプロキシを通らないため、Controller・Listenerから別のServiceへ呼び出します。

集計の待機時間は `application.properties` の `app.async.report-timeout-seconds=3` で変更できます。タイムアウト・キュー満杯・中断ではHTTP 503、予期しない集計失敗では500を返し、画面に再集計の案内を表示します。非同期側の認可失敗は既存の403画面へ引き継ぎます。タイムアウトは画面の待機を終えるもので、既に実行中のDB処理は継続する場合があります。

登録通知はメモリ上のキューで処理するため、アプリの強制終了やキュー満杯のときの配信保証はありません。SMTP送信時の再試行は次節の設定に従います。既存AOPの実行時間は各Serviceメソッドの計測で、Controller全体の待機時間ではありません。

```powershell
.\mvnw.cmd "-Dtest=AsyncProcessingIntegrationTests,DashboardControllerTests" test
```

## メール送信通知

教材の `deadline` / `description` を既存の `dueDate` / `detail` に、通知先URLを `/todos/{id}/edit` に合わせ、Spring Boot 4 / Java 21 / Long型IDを維持しています。

- ToDo作成時：DBコミット後に既存の `TodoCreatedListener` → `NotificationService` → `MailService` を呼び、`emailExecutor` でテキストメールを送信します。保存をロールバックした場合は送信しません。SMTP送信失敗は登録結果を変更しません。
- 毎日9時（既定は日本時間）：明日が期限の未完了ToDoを所有者へ通知します。Thymeleafの `templates/mail/reminder.html` を使い、UTF-8のHTMLとプレーンテキストのマルチパートメールを送信します。タイトル・メモはHTMLエスケープされます。
- 期限切れ：起動1分後から1時間間隔で確認します。同じ期限について送信成功済みのToDoは再送しません。完了済み・ゴミ箱内・期限未設定のToDo、メールアドレス未設定・無効・削除済みのユーザーを除外します。
- 通知先は、アカウント登録時の任意項目「メールアドレス」で設定します。既存アカウントは管理者のユーザー編集画面で設定・変更・消去できます。空欄の場合は通知しません。入力はサーバー側でも形式・254文字以内を検証します。

既存DBは起動時に `TodoOwnershipSchemaInitializer` が `users.email`、`todos.reminder_notified_date`、`todos.overdue_notified_date` を追加します。データの削除や既存メールアドレスの上書きは行いません。通知済みの値は対象の期限日です。期限の変更、完了から未完了への変更時にはリセットします。通知済み管理の更新だけではToDoの更新日時を変更しません。

### 開発時の確認

`app.mail.enabled=false` が初期値です。SMTPへ接続せず、形式と対象ToDo IDをログに記録します。宛先・本文・認証情報は通知ログに出しません。プレビューでは通知済みにしないため、実送信を有効にしてから通知できます。定期処理も止める場合は `APP_MAIL_REMINDERS_ENABLED=false` を設定します。

### Gmailで実送信する

Gmailのアプリパスワードを用意し、起動プロセスの環境変数を設定します。通常のGoogleログインパスワードは使いません。送信元はSMTPで認証するアドレスに合わせてください。

```powershell
$env:APP_MAIL_ENABLED='true'
$env:SPRING_MAIL_USERNAME='your-email@gmail.com'
$env:SPRING_MAIL_PASSWORD='your-app-password'
$env:APP_MAIL_FROM='your-email@gmail.com'
$env:APP_BASE_URL='http://localhost:8080'
.\mvnw.cmd spring-boot:run
```

Docker Composeでは `.env.example` を参考に `.env` へ同じ値を設定してアプリを再作成します。`.env` はGit管理対象外です。ローカル起動では `.env` は自動読込されないため、上記の環境変数を使ってください。公開環境では `APP_BASE_URL` を利用者がアクセスできるHTTPS URLへ変更します。メール内のリンクも通常のログイン・所有者確認を通ります。

| 環境変数 | 既定値・用途 |
| --- | --- |
| `APP_MAIL_ENABLED` | `false`。`true` でSMTP実送信 |
| `SPRING_MAIL_HOST` / `SPRING_MAIL_PORT` | `smtp.gmail.com` / `587`。SMTP認証とSTARTTLS必須 |
| `SPRING_MAIL_USERNAME` / `SPRING_MAIL_PASSWORD` | SMTPユーザー名 / アプリパスワード |
| `APP_MAIL_FROM` | ローカルではSMTPユーザー名、未設定なら `noreply@example.com`。Composeでは明示設定 |
| `APP_BASE_URL` | `http://localhost:8080` |
| `APP_MAIL_REMINDERS_ENABLED` | `true`。定期処理を有効化 |
| `APP_MAIL_ZONE` | `Asia/Tokyo`。cron・日付判定の共通タイムゾーン |
| `APP_MAIL_REMINDER_CRON` | `0 0 9 * * *`。`-` で毎日の処理を停止 |
| `APP_MAIL_MAX_ATTEMPTS` | `3`（1〜5）。1件あたりのSMTP送信試行上限 |

SMTPの接続・読込・書込タイムアウトはそれぞれ5秒です。`MailSendException` は上限まで再試行し、認証エラーは即時失敗として記録します。期限切れ通知に失敗した場合は未送信のまま保持し、次の定期チェックでも再試行します。毎日のリマインダーが全試行に失敗した場合、翌日は期限当日なのでリマインダー対象外となり、期限超過後の通知対象になります。

作成通知の永続キュー、停止中の9時の処理の補完、複数アプリ間の排他制御は実装していません。定期通知は単一アプリで実行してください。SMTPが受信した直後の通信切断や、送信成功後のDB更新・アプリ停止によって重複配信が起こる可能性があります。

メール処理の引数・本文が既存AOPログへ流れないよう、`LoggingAspect` から `MailService` を除外しています。実装は [Spring公式のメール機能](https://docs.spring.io/spring-framework/reference/integration/email.html) と [トランザクションイベント](https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html) の仕組みに沿っています。

`MailServiceTests` は実際のMIMEメッセージ・テンプレートで日本語、HTMLエスケープ、代替テキスト、再試行、実送信無効化を検証します。`MailNotificationIntegrationTests` はH2とSMTPモックで通知対象・所有者・日本時間・非同期送信・コミット/ロールバック・通知済み管理・メールアドレスの保存を検証します。テストは外部へメールを送信しません。

```powershell
.\mvnw.cmd "-Dtest=MailServiceTests,MailNotificationIntegrationTests,TodoOwnershipSchemaInitializerTests" test
```

## Javadoc

教材のJavadoc設定を既存のSpring Boot 4 / Java 21構成に合わせています。
`maven-javadoc-plugin` 3.6.0で、ソース・生成HTML・文字セットをUTF-8、ロケールを`ja_JP`に設定しています。
Lombokを使うモデルのフィールド説明やサービスの補助メソッドも読めるよう、`show=private`で生成します。
JDK付属の`javadoc`を使用するため、Java 21のJDKで実行してください。

```powershell
.\mvnw.cmd javadoc:javadoc
Start-Process .\target\site\apidocs\index.html
```

出力先は`target/site/apidocs/index.html`です。`package`実行時にも`prepare-package`フェーズで自動生成します。
生成HTMLは`target/`配下に置き、Gitへは追加しません。

| 教材の対象 | このプロジェクトの対象・記述内容 |
| --- | --- |
| `TodoService` | 全メソッドに引数・戻り値・実際の例外条件を記述。`findById`は対象なしで`null`を返す既存仕様を明記 |
| `TodoController` | `HomeController`の全エンドポイントにHTTPメソッド・パス・画面遷移を記述。`api/TodoApiController`にはJSON・CSVと応答ステータスを記述 |
| `Todo` / `TodoForm` | 画面フォームを兼ねる`Todo`と、REST入力用の`api/TodoRequest`に項目・検証制約・変換処理を記述 |
| パッケージの概要 | 既存の5パッケージに`package-info.java`を追加し、主要クラスを`{@link}`で参照 |

主要クラスに`@author`・`@version`・`@since`・`@see`を付け、コード例と値には`{@code}`を使用しています。
`CustomUserDetailsService.loadUserByUsername`は`{@inheritDoc}`でインターフェースの説明を継承し、DBからの取得仕様を補足しています。
コメントを更新したら同じコマンドで再生成してください。JavadocはJavaコードの開発者向け資料です。

## テスト

Windowsでは `mvnw.cmd` がプロジェクト指定のMaven 3.10.0を取得・起動します。動作確認は `.\mvnw.cmd -v`、テストは次のコマンドで実行できます。別途Mavenをインストールする必要はありません。

```powershell
.\mvnw.cmd test
```

認証・所有者管理・移行・起動テストはテスト専用のH2を使用します。既存のMySQL統合テストは `COMPLETION_TEST_DB_PASSWORD` が設定された場合だけ実行されます。

### 教材「単体テスト」に対応するテスト

教材の例を、既存のSpring Boot 4 / Java 21、Long型ID、`/todos`、`detail`、認証・所有者管理の仕様に合わせています。テスト依存関係は既存のJUnit Jupiter・Mockito・MyBatis Starter Testを利用します。

| ファイル | 検証内容 |
| --- | --- |
| `TodoServiceTest.java` | `@ExtendWith(MockitoExtension.class)`、`@Mock`、`@InjectMocks`を使った単体テスト。検索・作成・取得・更新・論理削除・一括削除・復元・印切替、認証と所有者確認、監査・履歴の呼び出しを検証。SpringやDBは起動しません。 |
| `TodoControllerIntegrationTest.java` | `@SpringBootTest`とMockMvcで一覧・作成・確認・編集・削除、入力エラー・404・CSRFを検証。実際のService・Mapperと専用H2を使用します。 |
| `TodoMapperTest.java` | `@MybatisTest`で本番のMapper XMLとテーブル定義を読み込み、専用H2でCRUD・検索条件・並び順・ページ送り・所有者条件を検証。 |

DBを使用する追加テストは`@Transactional`で各テスト後にロールバックします。Controllerテストの監査記録は既存の`REQUIRES_NEW`によって独立して確定するため、テストのトランザクション終了後に、そのテストユーザーの監査記録だけを削除します。

既存画面では`POST /todos/confirm`で入力を検証するため、空タイトルのテストもこのURLで行います。教材の詳細画面に相当する確認は、既存の`GET /todos/{id}/edit`を使用します。

追加した3クラスだけを実行する場合：

```powershell
.\mvnw.cmd "-Dtest=TodoServiceTest,TodoControllerIntegrationTest,TodoMapperTest" test
```

### WindowsでのMaven Wrapper起動エラー

同梱のWrapper 3.3.4には、`.m2` のリンク先情報 `Target` が `null` の場合でも `Target[0]` を参照し、`Cannot index into a null array` で停止する処理がありました。この環境のWindows PowerShell 5.1と通常の `.m2` フォルダーで再現を確認しています。

`mvnw.cmd` は通常のキャッシュパスを先に設定し、リンク先情報が存在する場合だけ参照するよう修正しています。`Get-Item` には `-LiteralPath` と `-Force` を指定しています。アプリのRUN起動やJavaコードのエラーではありません。Wrapperを再生成する際は、この修正が上書きされていないか確認してください。

関連するApache側の報告：[maven-wrapper issue #395](https://github.com/apache/maven-wrapper/issues/395)。

### サブタスク

一覧のタイトルを押すと、タスク詳細（`/todos/{id}`）でサブタスクを追加・削除・完了切替できます。
サブタスクは1階層までで、タイトルは必須・255文字以内です。所有者・ジャンル・優先度は親から引き継ぎます。
一覧と詳細に進捗率を表示し、全件完了で親を自動完了、未完了の追加・切替で親を未完了に戻します。
子が0件になったときの進捗は0%・親は未完了です。子がある親の完了状態は子の状態から決まります。

親をゴミ箱に移すと有効な子も連動して移動します。親の復元では、一緒に削除された子だけ復元します。
物理削除には自己参照外部キーの `ON DELETE CASCADE` を適用しています。
既存DBには起動時に `parent_id`、復元管理用の `deleted_with_parent`、外部キー・インデックスを追加します。

フォームのPOST先は次のとおりです（認証とCSRFトークンが必要）。

- `/todos/{parentId}/subtasks`：追加（`title`）
- `/todos/{parentId}/subtasks/{subtaskId}/toggle`：完了切替
- `/todos/{parentId}/subtasks/{subtaskId}/delete`：削除
## テンプレートToDo

ログイン後のナビゲーションから「テンプレート一覧」（`/templates`）を開きます。「新規テンプレート作成」で名前・説明とタスク項目を保存できます。項目は画面で追加・削除でき、1〜100件、タイトルは各200文字以内です。テンプレート名は100文字、説明は500文字までです。

一覧はカード形式で、項目数とタイトルを表示します。「編集」で名前・説明・項目を変更し、「タスク作成」で選択したジャンル・優先度・期限を全項目に設定して、未完了のToDoを一括作成します。期限は省略できます。同じテンプレートを繰り返し適用できます。編集・削除しても作成済みのToDoは残ります。

テンプレートは管理者も含めて本人のみ一覧表示・編集・削除・適用できます。所有者は認証情報から決定します。`db/templates.sql` が起動時に既存データを保持して2テーブルを追加するため、既存DBの初期化は不要です。ユーザー削除時は、そのユーザーのテンプレートと項目も削除されます。

`TemplateService` は既存の `TodoService.create` を呼び出して作成履歴・監査・コミット後通知を維持します。一括作成は単一トランザクションで、途中で失敗した場合はToDoと成功履歴を全件ロールバックします。操作の試行を記録する監査ログは従来の仕様で独立して残ります。

`TemplateIntegrationTests` で登録・編集・削除、項目順序、入力エラー、画面表示と英語表示、所有者制御、CSRF、一括適用、途中失敗時のロールバックを検証します。

## ドラッグ＆ドロップ並び替え

一覧の「手動順」を選択し、左端のハンドルをドラッグして、このページ内の親タスクを並び替えます。ハンドルにフォーカスして上下キーでも移動できます。ドロップ後に自動保存し、失敗時は元の順番へ戻してメッセージを表示します。手動順では印・期限より保存した順番を優先します。新規タスクは末尾に追加されます。

- `display_order` 列・インデックスは起動時に既存DBへ追加し、既存行はID順で初期化します。DBの作り直しは不要です。
- `PUT /api/todo/reorder`（`/api/todos/reorder` も可）に `[{"id":2,"displayOrder":1},{"id":1,"displayOrder":2}]` を送信します。順位は送信する行の中で1始まりの連番です。
- 認証ユーザー自身の、削除されていない親タスクのみ更新できます。管理者も他人の並び順は変更できません。CSRFトークンが必要です。
- 検索結果・ページ内で表示した行が占める位置だけを入れ替え、他の行との相対位置を保持します。ページをまたぐドラッグは対象外です。
- タッチ端末では、ハンドルを選択してキーボードの上下キーを利用してください（HTML5 Drag & Drop APIを使用）。

関連テスト: `TodoReorderIntegrationTests`（保存・末尾追加・所有者・削除済み・サブタスク・入力不正・CSRF）。
# タグオートコンプリート

新規登録・編集画面のタグ欄に入力すると、300ms後に既存タグを部分一致検索します。
候補は最大10件で、クリックまたは上下キーとEnterで選択できます。選択済みタグは×で解除でき、確認・書き直し画面でも保持されます。

起動時に `db/tags.sql` で `tags`・`todo_tags` テーブルと初期候補（Java、Spring Boot、MyBatis、MySQL）を追加します。
新しい候補を用意する場合は `tags(name)` に追加してください。入力だけでは新規タグを作成しません。
検索APIはログイン後に `GET /api/tags/search?q=Java` で利用できます。
