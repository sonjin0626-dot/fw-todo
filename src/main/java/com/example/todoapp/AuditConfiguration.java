package com.example.todoapp;

import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** 認可(200) → トランザクション(250) → 監査(300)の順に適用する。 */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(order = 250)
public class AuditConfiguration {
}
