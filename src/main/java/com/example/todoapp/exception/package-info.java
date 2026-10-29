/**
 * 業務例外と、画面・APIの共通エラー処理を提供します。
 *
 * <ul>
 *   <li>{@link com.example.todoapp.exception.BusinessException} — 利用者へ説明できる業務エラー</li>
 *   <li>{@link com.example.todoapp.exception.TodoNotFoundException} — 対象ToDoが存在しないエラー</li>
 *   <li>{@link com.example.todoapp.exception.GlobalExceptionHandler} — 画面とAPIのエラー応答</li>
 * </ul>
 *
 * @author fw-todo contributors
 * @version 0.0.1
 * @since 0.0.1
 */
package com.example.todoapp.exception;
