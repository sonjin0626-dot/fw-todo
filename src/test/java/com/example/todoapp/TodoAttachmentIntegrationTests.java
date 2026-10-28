package com.example.todoapp;

import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.ContentDisposition;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.mybatis.spring.SqlSessionTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:attachment-test;MODE=MySQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TodoAttachmentIntegrationTests {
    private static final Path STORAGE = Path.of("target", "attachment-tests-" + UUID.randomUUID()).toAbsolutePath();
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired FileStorageService files;
    @Autowired PlatformTransactionManager transactions;
    @Autowired Environment environment;
    @Autowired PasswordEncoder encoder;
    @Autowired AdminUserService adminUsers;
    @Autowired SqlSessionTemplate sqlSession;
    @MockitoSpyBean AttachmentMapper attachments;
    @MockitoSpyBean UserMapper users;
    Long ownerId;
    Long otherId;
    Long todoId;

    @DynamicPropertySource
    static void storageProperty(DynamicPropertyRegistry registry) {
        registry.add("app.upload.dir", STORAGE::toString);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM todos");
        jdbc.update("DELETE FROM users");
        jdbc.update("INSERT INTO users (username, password, role) VALUES"
                + " ('file-owner', 'unused', 'USER'), ('file-other', 'unused', 'USER'), ('file-admin', 'unused', 'ADMIN')");
        ownerId = jdbc.queryForObject("SELECT id FROM users WHERE username = 'file-owner'", Long.class);
        otherId = jdbc.queryForObject("SELECT id FROM users WHERE username = 'file-other'", Long.class);
        jdbc.update("INSERT INTO todos (user_id, title, category) VALUES (?, '添付するToDo', '仕事')", ownerId);
        todoId = jdbc.queryForObject("SELECT id FROM todos", Long.class);
    }

    @AfterEach
    void cleanFiles() throws IOException {
        SecurityContextHolder.clearContext();
        try (var paths = Files.list(STORAGE)) {
            for (Path path : paths.toList()) Files.deleteIfExists(path);
        }
    }

    @AfterAll
    static void cleanDirectory() throws IOException {
        Files.deleteIfExists(STORAGE);
    }

    @Test
    void uploadsListsDownloadsJapaneseNamesAndKeepsDuplicateNamesSeparate() throws Exception {
        byte[] data = "日本語の本文".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 2; i++) {
            mvc.perform(multipart("/todos/{id}/attach", todoId)
                    .file(new MockMultipartFile("file", "資料 メモ.txt", "text/html", data))
                    .with(user("file-owner")).with(csrf()))
                    .andExpect(redirectedUrl("/todos/" + todoId + "/attach"))
                    .andExpect(flash().attribute("message", "ファイルを添付しました"));
        }
        List<TodoAttachment> saved = attachments.findByTodoId(todoId);
        assertThat(saved).hasSize(2);
        assertThat(saved).extracting(TodoAttachment::getStoredFilename).doesNotHaveDuplicates();
        for (TodoAttachment attachment : saved) {
            assertThat(attachment.getStoredFilename()).matches("[0-9a-f-]{36}\\.txt");
            assertThat(attachment.getFileSize()).isEqualTo(data.length);
            assertThat(attachment.getUploadedAt()).isNotNull();
            assertThat(Files.readAllBytes(STORAGE.resolve(attachment.getStoredFilename()))).isEqualTo(data);
            var response = mvc.perform(get("/todos/attachments/{id}/download", attachment.getId())
                    .with(user("file-owner"))).andExpect(status().isOk())
                    .andExpect(content().contentType("text/plain"))
                    .andExpect(content().bytes(data)).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andReturn().getResponse();
            assertThat(ContentDisposition.parse(response.getHeader("Content-Disposition")).getFilename())
                    .isEqualTo("資料 メモ.txt");
            assertThat(response.getHeader("Content-Disposition")).startsWith("attachment;").contains("filename*=UTF-8''");
        }
        mvc.perform(get("/todos/{id}/attach", todoId).with(user("file-owner")))
                .andExpect(status().isOk()).andExpect(view().name("attachments"))
                .andExpect(content().string(containsString("multipart/form-data")))
                .andExpect(content().string(containsString("資料 メモ.txt")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
        mvc.perform(get("/todos").with(user("file-owner"))).andExpect(content().string(containsString("/todos/" + todoId + "/attach")));
        mvc.perform(get("/todos/{id}/edit", todoId).with(user("file-owner"))).andExpect(content().string(containsString("添付ファイル")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../secret.txt", "a/../secret.txt", "..\\secret.txt", "C:\\secret.txt", "bad\r\n.txt",
            "program.exe", "script.html", "script.svg", "macro.docm", "txt", ".txt", "name.txt "})
    void rejectsUnsafeNamesAndUnsupportedExtensionsWithoutSaving(String filename) throws Exception {
        mvc.perform(multipart("/todos/{id}/attach", todoId).file(new MockMultipartFile("file", filename, "text/plain", new byte[]{1}))
                .with(user("file-owner")).with(csrf())).andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("error"));
        assertNoAttachmentsOrFiles();
    }

    @Test
    void rejectsEmptyAndOversizedFilesButAcceptsExactlyTenMegabytes() throws Exception {
        for (byte[] data : new byte[][]{new byte[0], new byte[(int) FileStorageService.MAX_FILE_SIZE + 1]}) {
            mvc.perform(multipart("/todos/{id}/attach", todoId).file(new MockMultipartFile("file", "size.txt", "text/plain", data))
                    .with(user("file-owner")).with(csrf())).andExpect(status().is3xxRedirection())
                    .andExpect(flash().attributeExists("error"));
            assertNoAttachmentsOrFiles();
        }
        mvc.perform(multipart("/todos/{id}/attach", todoId)
                .file(new MockMultipartFile("file", "limit.txt", "text/plain", new byte[(int) FileStorageService.MAX_FILE_SIZE]))
                .with(user("file-owner")).with(csrf())).andExpect(flash().attributeExists("message"));
        assertThat(attachments.findByTodoId(todoId).getFirst().getFileSize()).isEqualTo(FileStorageService.MAX_FILE_SIZE);
    }

    @Test
    void enforcesOwnershipCsrfAndAuthenticationAndAllowsAdministrators() throws Exception {
        TodoAttachment attachment = upload();
        Long id = attachment.getId();
        mvc.perform(get("/todos/{id}/attach", todoId).with(user("file-other"))).andExpect(status().isForbidden());
        mvc.perform(multipart("/todos/{id}/attach", todoId).file(sample()).with(user("file-other")).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/todos/attachments/{id}/download", id).with(user("file-other"))).andExpect(status().isForbidden());
        mvc.perform(post("/todos/attachments/{id}/delete", id).with(user("file-other")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(multipart("/todos/{id}/attach", todoId).file(sample()).with(user("file-owner"))).andExpect(status().isForbidden());
        mvc.perform(post("/todos/attachments/{id}/delete", id).with(user("file-owner"))).andExpect(status().isForbidden());
        mvc.perform(get("/todos/attachments/{id}/download", id)).andExpect(status().is3xxRedirection());
        mvc.perform(get("/todos/{id}/attach", todoId).with(user("file-admin"))).andExpect(status().isOk());
        mvc.perform(get("/todos/attachments/{id}/download", id).with(user("file-admin"))).andExpect(status().isOk());
        mvc.perform(multipart("/todos/{id}/attach", todoId).file(sample()).with(user("file-admin")).with(csrf()))
                .andExpect(flash().attributeExists("message"));
        assertThat(jdbc.queryForObject("SELECT user_id FROM todos WHERE id = ?", Long.class, todoId)).isEqualTo(ownerId);
        mvc.perform(post("/todos/attachments/{id}/delete", id).with(user("file-admin")).with(csrf()))
                .andExpect(redirectedUrl("/todos/" + todoId + "/attach"));
        assertThat(attachments.findById(id)).isNull();
        assertThat(STORAGE.resolve(attachment.getStoredFilename())).doesNotExist();
        authenticate("file-other");
        assertThatThrownBy(() -> files.findById(attachments.findByTodoId(todoId).getFirst().getId())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> files.store(todoId, sample())).isInstanceOf(AccessDeniedException.class);
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> files.findByTodoId(todoId)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void hidesTrashedAttachmentsAndMakesThemAvailableAfterRestore() throws Exception {
        TodoAttachment attachment = upload();
        mvc.perform(post("/todos/{id}/delete", todoId).with(user("file-owner")).with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(get("/todos/{id}/attach", todoId).with(user("file-owner"))).andExpect(status().isNotFound());
        mvc.perform(multipart("/todos/{id}/attach", todoId).file(sample()).with(user("file-owner")).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get("/todos/attachments/{id}/download", attachment.getId()).with(user("file-owner"))).andExpect(status().isNotFound());
        mvc.perform(post("/todos/attachments/{id}/delete", attachment.getId()).with(user("file-owner")).with(csrf())).andExpect(status().isNotFound());
        assertThat(STORAGE.resolve(attachment.getStoredFilename())).exists();
        mvc.perform(post("/todos/{id}/restore", todoId).with(user("file-owner")).with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(get("/todos/attachments/{id}/download", attachment.getId()).with(user("file-owner"))).andExpect(status().isOk());
    }

    @Test
    void missingMetadataTodoOrStoredFileReturns404() throws Exception {
        mvc.perform(get("/todos/999999/attach").with(user("file-owner"))).andExpect(status().isNotFound());
        mvc.perform(multipart("/todos/999999/attach").file(sample()).with(user("file-owner")).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get("/todos/attachments/999999/download").with(user("file-owner"))).andExpect(status().isNotFound());
        mvc.perform(post("/todos/attachments/999999/delete").with(user("file-owner")).with(csrf())).andExpect(status().isNotFound());
        TodoAttachment attachment = upload();
        Files.delete(STORAGE.resolve(attachment.getStoredFilename()));
        mvc.perform(get("/todos/attachments/{id}/download", attachment.getId()).with(user("file-owner"))).andExpect(status().isNotFound());
        mvc.perform(post("/todos/attachments/{id}/delete", attachment.getId()).with(user("file-owner")).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertNoAttachmentsOrFiles();
    }

    @Test
    void escapesUploadedNamesInTheAttachmentPage() throws Exception {
        mvc.perform(multipart("/todos/{id}/attach", todoId)
                .file(new MockMultipartFile("file", "<img onerror=alert(1)>.txt", "text/plain", new byte[]{1}))
                .with(user("file-owner")).with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(get("/todos/{id}/attach", todoId).with(user("file-owner")))
                .andExpect(content().string(containsString("&lt;img onerror=alert(1)&gt;.txt")))
                .andExpect(content().string(not(containsString("<img onerror=alert(1)>"))));
    }

    @Test
    void uploadDatabaseAndIoFailuresAndOuterRollbackRemovePhysicalFiles() throws Exception {
        authenticate("file-owner");
        doAnswer(call -> {
            TodoAttachment attachment = call.getArgument(0);
            jdbc.update("INSERT INTO todo_attachments (todo_id, original_filename, stored_filename, content_type, file_size, uploaded_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?)", attachment.getTodoId(), attachment.getOriginalFilename(),
                    attachment.getStoredFilename(), attachment.getContentType(), attachment.getFileSize(), attachment.getUploadedAt());
            throw new IllegalStateException("metadata failure");
        })
                .when(attachments).insert(any());
        assertThatThrownBy(() -> files.store(todoId, sample())).isInstanceOf(IllegalStateException.class);
        assertNoAttachmentsOrFiles();
        reset(attachments);
        MockMultipartFile broken = new MockMultipartFile("file", "broken.txt", "text/plain", new byte[]{1}) {
            @Override public InputStream getInputStream() {
                return new InputStream() {
                    int reads;
                    @Override public int read() throws IOException {
                        if (reads++ < 3) return 65;
                        throw new IOException("simulated copy failure");
                    }
                };
            }
        };
        assertThatThrownBy(() -> files.store(todoId, broken)).isInstanceOf(IOException.class);
        assertNoAttachmentsOrFiles();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            try { files.store(todoId, sample()); } catch (IOException exception) { throw new IllegalStateException(exception); }
            status.setRollbackOnly();
        });
        assertNoAttachmentsOrFiles();
    }

    @Test
    void attachmentDeleteRollbackPreservesBothFileAndMetadata() throws Exception {
        TodoAttachment attachment = upload();
        authenticate("file-owner");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            files.delete(attachment.getId());
            assertThat(STORAGE.resolve(attachment.getStoredFilename())).exists();
            status.setRollbackOnly();
        });
        assertThat(attachments.findById(attachment.getId())).isNotNull();
        assertThat(STORAGE.resolve(attachment.getStoredFilename())).exists();
        files.delete(attachment.getId());
        assertNoAttachmentsOrFiles();
    }

    @Test
    void userDeletionCleansAllAttachmentsOnlyAfterSuccessfulCommit() throws Exception {
        TodoAttachment active = upload();
        jdbc.update("UPDATE todos SET completed = true, deleted_at = CURRENT_TIMESTAMP WHERE id = ?", todoId);
        doThrow(new IllegalStateException("user deletion failed")).when(users).deleteById(ownerId);
        mvc.perform(post("/admin/users/{id}/delete", ownerId).with(user("file-admin")).with(csrf()))
                .andExpect(status().isInternalServerError());
        assertThat(attachments.findById(active.getId())).isNotNull();
        assertThat(STORAGE.resolve(active.getStoredFilename())).exists();
        reset(users);
        mvc.perform(post("/admin/users/{id}/delete", ownerId).with(user("file-admin")).with(csrf()))
                .andExpect(redirectedUrl("/admin/users"));
        assertNoAttachmentsOrFiles();
    }

    @Test
    void concurrentUserDeletionWaitsForUploadAndLeavesNoOrphanFiles() throws Exception {
        CountDownLatch copying = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        CountDownLatch deleting = new CountDownLatch(1);
        MockMultipartFile slow = new MockMultipartFile("file", "slow.txt", "text/plain", new byte[]{1}) {
            @Override public InputStream getInputStream() throws IOException {
                copying.countDown();
                try {
                    if (!resume.await(10, TimeUnit.SECONDS)) throw new IOException("copy wait timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException(exception);
                }
                return super.getInputStream();
            }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var upload = executor.submit(() -> {
                authenticate("file-owner");
                try { return files.store(todoId, slow); }
                finally { SecurityContextHolder.clearContext(); }
            });
            assertThat(copying.await(10, TimeUnit.SECONDS)).isTrue();
            // コピー中は所有者の行ロックを保持している。削除側のロック取得を実SQLで確認する。
            doAnswer(call -> {
                deleting.countDown();
                return sqlSession.getMapper(UserMapper.class).findByIdForUpdate(ownerId);
            }).when(users).findByIdForUpdate(ownerId);
            var deletion = executor.submit(() -> {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        "file-admin", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
                try {
                    adminUsers.deleteUser(ownerId);
                } finally { SecurityContextHolder.clearContext(); }
            });
            try {
                assertThat(deleting.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> deletion.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally {
                resume.countDown();
            }
            upload.get(10, TimeUnit.SECONDS);
            deletion.get(10, TimeUnit.SECONDS);
        }
        assertThat(users.findById(ownerId)).isNull();
        assertNoAttachmentsOrFiles();
    }

    @Test
    void realServletAcceptsTenMegabytesAndReturns413ForOversizedUploads() throws Exception {
        jdbc.update("UPDATE users SET password = ? WHERE id = ?", encoder.encode("file-password"), ownerId);
        String base = "http://127.0.0.1:" + environment.getRequiredProperty("local.server.port");
        try (HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
            var loginPage = client.send(HttpRequest.newBuilder(URI.create(base + "/login")).GET().build(), HttpResponse.BodyHandlers.ofString());
            String loginForm = "username=file-owner&password=file-password&_csrf="
                    + URLEncoder.encode(csrfToken(loginPage.body()), StandardCharsets.UTF_8);
            var login = client.send(HttpRequest.newBuilder(URI.create(base + "/login"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(loginForm)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(login.statusCode()).isEqualTo(302);
            var page = client.send(HttpRequest.newBuilder(URI.create(base + "/todos/" + todoId + "/attach")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(page.statusCode()).isEqualTo(200);
            String token = csrfToken(page.body());
            for (int extra : new int[]{0, 1}) {
                String boundary = "AttachmentTestBoundary";
                byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"limit.txt\"\r\n"
                        + "Content-Type: text/plain\r\n\r\n").getBytes(StandardCharsets.UTF_8);
                byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
                byte[] payload = new byte[(int) FileStorageService.MAX_FILE_SIZE + extra];
                var request = HttpRequest.newBuilder(URI.create(base + "/todos/" + todoId + "/attach"))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .header("X-CSRF-TOKEN", token)
                        .POST(HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(prefix),
                                HttpRequest.BodyPublishers.ofByteArray(payload), HttpRequest.BodyPublishers.ofByteArray(suffix))).build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                assertThat(response.statusCode()).isEqualTo(extra == 0 ? 302 : 413);
                if (extra > 0) assertThat(response.body()).contains("10MB以下");
            }
        }
        assertThat(attachments.findByTodoId(todoId)).hasSize(1);
    }

    private String csrfToken(String html) {
        var matcher = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private TodoAttachment upload() throws Exception {
        mvc.perform(multipart("/todos/{id}/attach", todoId).file(sample()).with(user("file-owner")).with(csrf()))
                .andExpect(flash().attributeExists("message"));
        return attachments.findByTodoId(todoId).getFirst();
    }

    private MockMultipartFile sample() {
        return new MockMultipartFile("file", "sample.txt", "text/plain", "sample".getBytes(StandardCharsets.UTF_8));
    }

    private void authenticate(String name) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(name, "", List.of()));
    }

    private void assertNoAttachmentsOrFiles() throws IOException {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM todo_attachments", Integer.class)).isZero();
        try (var paths = Files.list(STORAGE)) { assertThat(paths.toList()).isEmpty(); }
    }
}
