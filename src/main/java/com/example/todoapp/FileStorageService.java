package com.example.todoapp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.example.todoapp.exception.BusinessException;
import com.example.todoapp.exception.TodoNotFoundException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@Transactional(readOnly = true)
public class FileStorageService {
    public static final long MAX_FILE_SIZE = 10 * 1024 * 1024;
    // クライアント申告のMIME型を信用せず、許可した拡張子から決定する。
    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("pdf", "application/pdf"), Map.entry("txt", "text/plain"),
            Map.entry("csv", "text/csv"), Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"),
            Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"));

    private final Path uploadPath;
    private final AttachmentMapper attachments;
    private final TodoService todos;
    private final UserMapper users;

    public FileStorageService(@Value("${app.upload.dir:./uploads}") String uploadDir,
            AttachmentMapper attachments, TodoService todos, UserMapper users) throws IOException {
        this.uploadPath = Path.of(uploadDir).toAbsolutePath().normalize();
        this.attachments = attachments;
        this.todos = todos;
        this.users = users;
        Files.createDirectories(uploadPath);
    }

    public List<TodoAttachment> findByTodoId(Long todoId) {
        requireActiveTodo(todoId);
        return attachments.findByTodoId(todoId);
    }

    public TodoAttachment findById(Long id) {
        TodoAttachment attachment = attachments.findById(id);
        if (attachment == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "添付ファイルが見つかりません");
        }
        requireActiveTodo(attachment.getTodoId());
        return attachment;
    }

    @Transactional(rollbackFor = Exception.class)
    public TodoAttachment store(Long todoId, MultipartFile file) throws IOException {
        Todo todo = requireActiveTodo(todoId);
        if (file == null || file.isEmpty()) {
            throw new BusinessException("EMPTY_ATTACHMENT", "ファイルを選択してください");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException("ATTACHMENT_TOO_LARGE", "ファイルは10MB以下にしてください");
        }
        String filename = validateFilename(file.getOriginalFilename());
        int dot = filename.lastIndexOf('.');
        String extension = dot > 0 ? filename.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        String contentType = CONTENT_TYPES.get(extension);
        if (contentType == null) {
            throw new BusinessException("UNSUPPORTED_ATTACHMENT_TYPE", "この種類のファイルは添付できません");
        }
        // ユーザー物理削除も同じ所有者行をロックする。
        // 削除対象ファイルの取得後に添付が増え、実ファイルだけ残る競合を防ぐ。
        if (users.findByIdForUpdate(todo.getUserId()) == null) throw new TodoNotFoundException(todoId);
        String storedFilename = UUID.randomUUID() + "." + extension;
        Path target = resolve(storedFilename);
        // コピー失敗・DB保存失敗・外側のトランザクションのロールバックでも実ファイルを残さない。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) deleteStoredFile(target);
            }
        });
        try (InputStream input = file.getInputStream()) {
            Files.copy(input, target);
        }
        if (Files.size(target) > MAX_FILE_SIZE) {
            throw new BusinessException("ATTACHMENT_TOO_LARGE", "ファイルは10MB以下にしてください");
        }
        TodoAttachment attachment = new TodoAttachment();
        attachment.setTodoId(todoId);
        attachment.setOriginalFilename(filename);
        attachment.setStoredFilename(storedFilename);
        attachment.setContentType(contentType);
        attachment.setFileSize(Files.size(target));
        attachment.setUploadedAt(LocalDateTime.now());
        attachments.insert(attachment);
        return attachment;
    }

    public Resource load(TodoAttachment attachment) throws IOException {
        // 呼び出し元が渡したメタ情報ではなく、DBと所有者を再確認する。
        TodoAttachment existing = findById(attachment.getId());
        Path path = resolve(existing.getStoredFilename());
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "添付ファイルが見つかりません");
        }
        return new UrlResource(path.toUri());
    }

    @Transactional(rollbackFor = Exception.class)
    public Long delete(Long id) {
        TodoAttachment attachment = findById(id);
        attachments.delete(id);
        deleteAfterCommit(List.of(attachment));
        return attachment.getTodoId();
    }

    /** ユーザー削除のトランザクションが確定した場合だけ、関連する実ファイルも削除する。 */
    @Transactional(rollbackFor = Exception.class)
    @PreAuthorize("hasRole('ADMIN')")
    public void deleteFilesForUser(Long userId) {
        deleteAfterCommit(attachments.findByUserId(userId));
    }

    private Todo requireActiveTodo(Long todoId) {
        Todo todo = todos.findById(todoId);
        if (todo == null) throw new TodoNotFoundException(todoId);
        return todo;
    }

    private String validateFilename(String filename) {
        if (filename == null || filename.isBlank() || filename.length() > 255
                || filename.contains("/") || filename.contains("\\") || filename.contains(":")
                || filename.contains("..") || filename.endsWith(".") || filename.endsWith(" ")
                || filename.codePoints().anyMatch(Character::isISOControl)) {
            throw new BusinessException("INVALID_ATTACHMENT_NAME", "ファイル名が正しくありません");
        }
        return filename;
    }

    private Path resolve(String storedFilename) {
        Path target = uploadPath.resolve(storedFilename).normalize();
        if (!uploadPath.equals(target.getParent())) {
            throw new BusinessException("INVALID_ATTACHMENT_PATH", "ファイルの保存先が正しくありません");
        }
        return target;
    }

    private void deleteAfterCommit(List<TodoAttachment> files) {
        List<Path> paths = files.stream().map(file -> resolve(file.getStoredFilename())).toList();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                paths.forEach(FileStorageService.this::deleteStoredFile);
            }
        });
    }

    private void deleteStoredFile(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.error("添付ファイルの削除に失敗しました: {}", path, exception);
        }
    }
}
