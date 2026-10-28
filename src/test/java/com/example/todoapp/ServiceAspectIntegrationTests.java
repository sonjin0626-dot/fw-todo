package com.example.todoapp;

import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.todoapp.aspect.LoggingAspect;
import com.example.todoapp.aspect.PerformanceAspect;
import com.example.todoapp.exception.TodoNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:aspect-test;MODE=MySQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@Transactional
@WithMockUser(username = "aop-admin", roles = "ADMIN")
class ServiceAspectIntegrationTests {
    @Autowired TodoService todos;
    @Autowired AdminUserService users;
    @Autowired CustomUserDetailsService userDetails;
    @Autowired UserMapper userMapper;
    @Autowired JdbcTemplate jdbc;

    private final Logger logging = (Logger) LoggerFactory.getLogger(LoggingAspect.class);
    private final Logger performance = (Logger) LoggerFactory.getLogger(PerformanceAspect.class);
    private ListAppender<ILoggingEvent> appender;
    private Level previousLoggingLevel;
    private Level previousPerformanceLevel;
    private Long userId;

    @BeforeEach
    void setUp() {
        jdbc.update("INSERT INTO users (username, password, role) VALUES ('aop-admin', 'admin-secret-hash', 'ADMIN'),"
                + " ('aop-user', 'user-secret-hash', 'USER')");
        userId = jdbc.queryForObject("SELECT id FROM users WHERE username = 'aop-user'", Long.class);
        appender = new ListAppender<>();
        appender.start();
        previousLoggingLevel = logging.getLevel();
        previousPerformanceLevel = performance.getLevel();
        logging.setLevel(Level.INFO);
        performance.setLevel(Level.INFO);
        logging.addAppender(appender);
        performance.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logging.detachAppender(appender);
        performance.detachAppender(appender);
        logging.setLevel(previousLoggingLevel);
        performance.setLevel(previousPerformanceLevel);
        appender.stop();
    }

    @Test
    void springProxiesLogArgumentsResultAndElapsedTimeForExistingTodoService() {
        assertThat(AopUtils.isAopProxy(todos)).isTrue();
        Todo todo = new Todo();
        todo.setTitle("AOPの動作確認");
        todo.setCategory("仕事");
        todo.setPriority(2);
        todos.create(todo);
        // voidメソッドでも正常終了を記録し、実際の登録処理が実行される。
        assertThat(todo.getId()).isNotNull();
        assertThat(messages()).anySatisfy(message -> assertThat(message)
                .contains("【開始】TodoService.create(..)", "AOPの動作確認"));
        assertThat(messages()).contains("【終了】TodoService.create(..) 戻り値: null");
        appender.list.clear();

        Todo found = todos.findById(todo.getId());

        assertThat(found.getTitle()).isEqualTo(todo.getTitle());
        assertThat(messages()).hasSize(3);
        assertThat(messages().get(0)).isEqualTo("【開始】TodoService.findById(..) 引数: [" + todo.getId() + "]");
        assertThat(messages().get(1)).matches("TodoService\\.findById\\(\\.\\.\\) 実行時間: \\d+ms");
        assertThat(messages().get(2)).contains("【終了】TodoService.findById(..) 戻り値: Todo(", "AOPの動作確認");
        assertThat(appender.list).allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.INFO));
    }

    @Test
    void exceptionsKeepTheirTypeAndMessageAndAreTimedWithoutASuccessLog() {
        assertThatThrownBy(() -> todos.checkAccess(Long.MAX_VALUE))
                .isInstanceOf(TodoNotFoundException.class)
                .hasMessage("ID " + Long.MAX_VALUE + " のやることは見つかりませんでした。");

        assertThat(messages()).hasSize(3);
        assertThat(messages().get(1)).matches("TodoService\\.checkAccess\\(\\.\\.\\) 実行時間: \\d+ms \\(例外発生\\)");
        assertThat(messages().get(2)).contains("【例外】TodoService.checkAccess(..) エラー:", Long.toString(Long.MAX_VALUE));
        assertThat(appender.list.subList(1, 3))
                .allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.ERROR));
        assertThat(messages()).noneMatch(message -> message.contains("【終了】"));
    }

    @Test
    void internalOverloadCallsDoNotCreateDuplicateAdvice() {
        todos.search("aop-keyword", "仕事", "dueDate");

        assertThat(messages()).hasSize(3);
        assertThat(messages().get(0)).contains("【開始】TodoService.search(..)", "[aop-keyword, 仕事, dueDate]");
        assertThat(messages().get(2)).isEqualTo("【終了】TodoService.search(..) 戻り値: []");
    }

    @Test
    void adminServiceMasksPasswordInputsAndFormatsUserResultsIncludingCollections() {
        UserEditForm form = new UserEditForm();
        form.setUsername("aop-user");
        form.setRole("USER");
        form.setEnabled(true);
        form.setNewPassword("private-new-password");
        users.updateByAdmin(userId, form);
        users.findById(userId);
        users.findAll();

        assertThat(messages()).anySatisfy(message -> assertThat(message)
                .contains("【開始】AdminUserService.updateByAdmin(..)", "newPassword=[PROTECTED]"));
        assertThat(messages()).anySatisfy(message -> assertThat(message)
                .contains("【終了】AdminUserService.findById(..)", "username=aop-user", "password=[PROTECTED]"));
        assertThat(messages()).anySatisfy(message -> assertThat(message)
                .contains("【終了】AdminUserService.findAll()", "戻り値: [User("));
        assertThat(String.join("\n", messages())).doesNotContain("private-new-password", "user-secret-hash", "admin-secret-hash", "$2a$");
    }

    @Test
    void authenticationServiceLogsUserDetailsWithoutPasswordAndMappersAreExcluded() {
        userMapper.findByUsername("aop-user");
        assertThat(messages()).isEmpty();

        var principal = userDetails.loadUserByUsername("aop-user");

        assertThat(principal.getPassword()).isEqualTo("user-secret-hash");
        assertThat(messages()).hasSize(3);
        assertThat(messages().get(0)).contains("【開始】CustomUserDetailsService.loadUserByUsername(..)", "[aop-user]");
        assertThat(messages().get(2)).contains("【終了】CustomUserDetailsService.loadUserByUsername(..)",
                "username=aop-user", "ROLE_USER", "password=[PROTECTED]");
        assertThat(String.join("\n", messages())).doesNotContain("user-secret-hash");
    }

    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
