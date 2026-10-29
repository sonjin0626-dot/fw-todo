package com.example.todoapp;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:audit-test;MODE=MySQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "audit-admin", roles = "ADMIN")
class AuditLogIntegrationTests {
    @Autowired TodoService todos;
    @Autowired AuditLogService audit;
    @Autowired AuditMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired PlatformTransactionManager transactions;
    Long adminId;
    Long ownerId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM todo_history");
        jdbc.update("DELETE FROM audit_logs");
        jdbc.update("DELETE FROM todos");
        jdbc.update("DELETE FROM users");
        jdbc.update("INSERT INTO users (username, password, role) VALUES"
                + " ('audit-admin', 'secret-hash', 'ADMIN'), ('audit-owner', 'another-secret-hash', 'USER')");
        adminId = jdbc.queryForObject("SELECT id FROM users WHERE username = 'audit-admin'", Long.class);
        ownerId = jdbc.queryForObject("SELECT id FROM users WHERE username = 'audit-owner'", Long.class);
    }

    @Test
    void createsOneEnrichedRecordWithGeneratedIdAndTrustedRequestMetadata() throws Exception {
        mvc.perform(post("/todos").with(user("audit-admin").roles("ADMIN")).with(csrf())
                .with(request -> { request.setRemoteAddr("192.0.2.10"); return request; })
                .header("X-Forwarded-For", "spoofed-address")
                .header("User-Agent", "A".repeat(600))
                .param("title", "日本語のToDo").param("detail", "変更内容")
                .param("category", "資格").param("priority", "2").param("dueDate", "2026-12-31"))
                .andExpect(status().is3xxRedirection());

        AuditLog entry = onlyEntry();
        assertThat(entry.getOperation()).isEqualTo("CREATE");
        assertThat(entry.getOutcome()).isEqualTo("COMMITTED");
        assertThat(entry.getEntityId()).isNotNull();
        assertThat(entry.getOldValue()).isNull();
        assertThat(entry.getUsername()).isEqualTo("audit-admin");
        assertThat(entry.getUserId()).isEqualTo(adminId);
        assertThat(entry.getIpAddress()).isEqualTo("192.0.2.10");
        assertThat(entry.getUserAgent()).hasSize(500);
        var value = json.readTree(entry.getNewValue());
        assertThat(value.get("id").asLong()).isEqualTo(entry.getEntityId());
        assertThat(value.get("title").asString()).isEqualTo("日本語のToDo");
        assertThat(value.get("dueDate").asString()).isEqualTo("2026-12-31");
        assertThat(entry.getNewValue()).doesNotContain("password", "secret-hash", "overdue");
    }

    @Test
    void recordsPersistedBeforeAndAfterForUpdateTrashRestorePinAndBulkDelete() throws Exception {
        Todo todo = seed("変更前", ownerId);
        Todo changed = todos.findById(todo.getId());
        changed.setTitle("変更後");
        changed.setUserId(adminId);
        changed.setCompleted(true);
        todos.update(changed);
        AuditLog update = onlyEntry();
        assertThat(json.readTree(update.getOldValue()).get("title").asString()).isEqualTo("変更前");
        var after = json.readTree(update.getNewValue());
        assertThat(after.get("title").asString()).isEqualTo("変更後");
        assertThat(after.get("userId").asLong()).isEqualTo(ownerId);
        assertThat(after.get("completedAt").isNull()).isFalse();
        assertThat(update.getUserId()).isEqualTo(adminId);

        todos.delete(todo.getId());
        assertThat(json.readTree(latest().getNewValue()).get("deletedAt").isNull()).isFalse();
        assertThat(todos.restore(todo.getId())).isTrue();
        assertThat(json.readTree(latest().getOldValue()).get("deletedAt").isNull()).isFalse();
        assertThat(json.readTree(latest().getNewValue()).get("deletedAt").isNull()).isTrue();
        assertThat(todos.togglePinned(todo.getId())).isTrue();
        assertThat(json.readTree(latest().getNewValue()).get("pinned").asBoolean()).isTrue();
        assertThat(todos.bulkDelete(List.of(todo.getId(), todo.getId(), Long.MAX_VALUE))).isEqualTo(1);
        var entries = audit.search(new AuditSearchCriteria());
        assertThat(entries).hasSize(6);
        assertThat(entries).filteredOn(e -> e.getEntityId().equals(todo.getId()))
                .allSatisfy(e -> assertThat(e.getOutcome()).isEqualTo("COMMITTED"));
        assertThat(entries).filteredOn(e -> e.getEntityId().equals(Long.MAX_VALUE))
                .singleElement().satisfies(e -> assertThat(e.getOutcome()).isEqualTo("NO_CHANGE"));
    }

    @Test
    void keepsSnapshotsAndGeneratedEntityIdWhenAnOuterTransactionRollsBack() {
        Todo todo = sample("ロールバック対象");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            todos.create(todo);
            // 本体の確定前には試行としてのみ保存されている。
            assertThat(onlyEntry().getOutcome()).isEqualTo("ATTEMPTED");
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM todos", Long.class)).isZero();
        AuditLog entry = onlyEntry();
        assertThat(entry.getOutcome()).isEqualTo("ROLLED_BACK");
        assertThat(entry.getEntityId()).isEqualTo(todo.getId());
        assertThat(entry.getNewValue()).contains("ロールバック対象");
        assertThat(AuditContext.current()).isNull();
    }

    @Test
    @WithMockUser(username = "audit-owner")
    void rejectsForeignWritesAndAdminReadsWithoutCreatingAuditRecords() throws Exception {
        Todo todo = seed("管理者のToDo", adminId);
        assertThatThrownBy(() -> todos.delete(todo.getId())).isInstanceOf(AccessDeniedException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs", Long.class)).isZero();
        assertThat(AuditContext.current()).isNull();
        assertThatThrownBy(() -> audit.search(new AuditSearchCriteria())).isInstanceOf(AccessDeniedException.class);
        mvc.perform(get("/admin/audit-logs")).andExpect(status().isForbidden());
        mvc.perform(get("/admin/audit-logs/entity/TODO/{id}", todo.getId())).andExpect(status().isForbidden());
        mvc.perform(get("/todos")).andExpect(content().string(not(containsString("/admin/audit-logs"))));
    }

    @Test
    void searchesAllFiltersIncludesTheWholeEndDateAndPaginatesStably() {
        for (int i = 0; i < 23; i++) insertLog("UPDATE_ATTEMPT", 7L, ownerId, "2026-10-28 23:59:59");
        insertLog("UPDATE_ATTEMPT", 7L, ownerId, "2026-10-29 00:00:00");
        insertLog("DELETE_ATTEMPT", 8L, adminId, "2026-10-28 12:00:00");
        AuditSearchCriteria criteria = new AuditSearchCriteria();
        criteria.setEntityType("TODO");
        criteria.setEntityId(7L);
        criteria.setUserId(ownerId);
        criteria.setAction("UPDATE");
        criteria.setOutcome("COMMITTED");
        criteria.setFromDate(LocalDate.of(2026, 10, 28));
        criteria.setToDate(LocalDate.of(2026, 10, 28));
        assertThat(audit.count(criteria)).isEqualTo(23);
        List<AuditLog> first = audit.search(criteria);
        assertThat(first).hasSize(20);
        assertThat(first).extracting(AuditLog::getId).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        criteria.setPage(1);
        assertThat(audit.search(criteria)).hasSize(3).doesNotContainAnyElementsOf(first);
        criteria.setPage(999);
        assertThat(audit.search(criteria)).isEmpty();
    }

    @Test
    void rendersEscapedSnapshotsEnglishHistoryAndFilterPreservingPageLinks() throws Exception {
        for (int i = 0; i < 21; i++) insertLog("UPDATE_ATTEMPT", 7L, ownerId, "2026-10-28 23:59:59");
        jdbc.update("UPDATE audit_logs SET old_value = ?, new_value = ?", "{\"title\":\"before\"}", "<script>alert(1)</script>");
        mvc.perform(get("/admin/audit-logs").param("entityId", "7").param("action", "UPDATE"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(containsString("&lt;script&gt;alert(1)&lt;/script&gt;")))
                .andExpect(content().string(not(containsString("<script>alert(1)</script>"))))
                .andExpect(content().string(containsString("entityId=7")))
                .andExpect(content().string(containsString("action=UPDATE")))
                .andExpect(content().string(containsString("page=1")));
        mvc.perform(get("/admin/audit-logs/entity/TODO/7").param("lang", "en"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Change history")))
                .andExpect(content().string(containsString("Snapshot after processing")))
                .andExpect(content().string(not(containsString("??audit."))));
        jdbc.update("DELETE FROM todos");
        jdbc.update("DELETE FROM users WHERE id = ?", ownerId);
        mvc.perform(get("/admin/audit-logs/entity/TODO/7"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("audit-owner")));
        mvc.perform(get("/admin/audit-logs").with(anonymous())).andExpect(status().is3xxRedirection());
    }

    @ParameterizedTest
    @ValueSource(strings = {"entityId=-1", "entityId=oops", "userId=0", "fromDate=oops", "page=-1", "page=1000001", "action=HACK", "outcome=HACK", "entityType=USER"})
    void rejectsInvalidSearchConditionsWithAReadablePage(String condition) throws Exception {
        String[] parts = condition.split("=");
        mvc.perform(get("/admin/audit-logs").param(parts[0], parts[1]))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("検索条件を確認")));
    }

    @Test
    void rejectsReversedDateRangeAndInvalidHistoryType() throws Exception {
        mvc.perform(get("/admin/audit-logs").param("fromDate", "2026-10-29").param("toDate", "2026-10-28"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/admin/audit-logs/entity/USER/7")).andExpect(status().isBadRequest());
        mvc.perform(get("/admin/audit-logs/entity/TODO/0")).andExpect(status().isBadRequest());
    }

    private AuditLog onlyEntry() {
        var logs = mapper.search(new AuditSearchCriteria());
        assertThat(logs).hasSize(1);
        return logs.getFirst();
    }

    private AuditLog latest() { return audit.search(new AuditSearchCriteria()).getFirst(); }

    private void insertLog(String action, Long id, Long actor, String time) {
        jdbc.update("INSERT INTO audit_logs (action, todo_id, actor_user_id, entity_type, entity_id, user_id, username, outcome, created_at)"
                + " VALUES (?, ?, ?, 'TODO', ?, ?, 'audit-owner', 'COMMITTED', ?)", action, id, actor, id, actor, time);
    }

    private Todo seed(String title, Long owner) {
        jdbc.update("INSERT INTO todos (user_id, title, category, priority) VALUES (?, ?, '資格', 2)", owner, title);
        Long id = jdbc.queryForObject("SELECT id FROM todos WHERE title = ?", Long.class, title);
        Todo todo = sample(title);
        todo.setId(id);
        return todo;
    }

    private Todo sample(String title) {
        Todo todo = new Todo();
        todo.setTitle(title);
        todo.setCategory("資格");
        todo.setPriority(2);
        return todo;
    }
}
