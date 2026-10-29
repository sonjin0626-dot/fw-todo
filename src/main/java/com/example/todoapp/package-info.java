/**
 * ToDoアプリケーションの画面・業務処理・永続化・設定を提供します。
 *
 * <p>既存構成に合わせ、コントローラー・サービス・Mapper・モデルをこのパッケージに配置します。
 * HTML入力には{@link com.example.todoapp.Todo}を使い、独立したフォームクラスは設けていません。</p>
 *
 * <h2>主要クラス</h2>
 * <ul>
 *   <li>{@link com.example.todoapp.HomeController} — ToDoの画面とフォーム処理</li>
 *   <li>{@link com.example.todoapp.TodoService} — CRUD・所有者確認・ゴミ箱・印の切り替え</li>
 *   <li>{@link com.example.todoapp.TodoMapper} — MyBatisによるToDoの永続化</li>
 *   <li>{@link com.example.todoapp.Todo} — ToDoデータとHTML入力値</li>
 *   <li>{@link com.example.todoapp.MailService} — 作成・期限通知メール</li>
 *   <li>{@link com.example.todoapp.AuditService} — 操作の試行と結果の記録</li>
 * </ul>
 *
 * @author fw-todo contributors
 * @version 0.0.1
 * @since 0.0.1
 */
package com.example.todoapp;
