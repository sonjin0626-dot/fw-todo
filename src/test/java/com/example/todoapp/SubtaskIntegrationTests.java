package com.example.todoapp;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:subtasks;MODE=MySQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SubtaskIntegrationTests {
    @Autowired TodoService service;
    @Autowired TodoMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    Long parentId;
    @BeforeEach void setup() {
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated("sonjin", "unused",
                org.springframework.security.core.authority.AuthorityUtils.createAuthorityList("ROLE_USER")));
        Todo parent = new Todo();
        parent.setTitle("Parent"); parent.setCategory("資格"); parent.setPriority(2);
        service.create(parent); parentId = parent.getId();
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @Test void duplicatesFieldsAndActiveChildrenWithFreshState() {
        service.addSubtask(parentId, "child");
        Long childId = mapper.findSubtasks(parentId).getFirst().getId();
        service.changeSubtask(parentId, childId, false);
        jdbc.update("UPDATE todos SET detail='memo', due_date='2030-01-02', pinned=true WHERE id=?", parentId);
        Todo copy = service.duplicate(parentId, true);
        Todo saved = mapper.findById(copy.getId());
        assertNotEquals(parentId, saved.getId());
        assertEquals("Parent（コピー）", saved.getTitle());
        assertEquals("memo", saved.getDetail());
        assertEquals("資格", saved.getCategory());
        assertEquals(2, saved.getPriority());
        assertEquals(java.time.LocalDate.of(2030, 1, 2), saved.getDueDate());
        assertFalse(saved.getCompleted());
        assertNull(saved.getCompletedAt());
        assertFalse(saved.getPinned());
        assertNotNull(saved.getCreatedAt());
        var children = mapper.findSubtasks(copy.getId());
        assertEquals(1, children.size());
        assertNotEquals(childId, children.getFirst().getId());
        assertEquals("child", children.getFirst().getTitle());
        assertFalse(children.getFirst().getCompleted());
        assertTrue(mapper.findById(parentId).getCompleted());
        assertTrue(mapper.findSubtasks(service.duplicate(parentId, false).getId()).isEmpty());
        service.changeSubtask(parentId, childId, true);
        assertTrue(mapper.findSubtasks(service.duplicate(parentId, true).getId()).isEmpty());
        service.delete(parentId);
        assertThrows(com.example.todoapp.exception.TodoNotFoundException.class, () -> service.duplicate(parentId, true));
    }

    @Test void duplicateEndpointOptionsAndSecurity() throws Exception {
        service.addSubtask(parentId, "child");
        var result = mvc.perform(post("/todos/" + parentId + "/duplicate").with(user("sonjin")).with(csrf())
                .param("subtaskOption", "true"))
            .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("message")).andReturn();
        String location = result.getResponse().getRedirectedUrl();
        Long copyId = Long.valueOf(location.substring(location.lastIndexOf('/') + 1));
        assertTrue(mapper.findSubtasks(copyId).isEmpty());
        mvc.perform(get(location).with(user("sonjin"))).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("subtaskOption")));
        mvc.perform(post("/todos/" + parentId + "/duplicate").with(user("sonjin")))
            .andExpect(status().isForbidden());
        jdbc.update("INSERT INTO users(username,password,role) VALUES ('duplicate-other','unused','ADMIN')");
        mvc.perform(post("/todos/" + parentId + "/duplicate").with(user("duplicate-other").roles("ADMIN")).with(csrf()))
            .andExpect(status().isForbidden());
        mvc.perform(post("/todo/999999999/duplicate").with(user("sonjin")).with(csrf()))
            .andExpect(status().isNotFound());
    }

    @Test void progressCompletionAndDeletion() {
        service.addSubtask(parentId, "one");
        service.addSubtask(parentId, "two");
        var children = mapper.findSubtasks(parentId);
        assertFalse(service.search("", "", "asc", true).stream().anyMatch(Todo::isSubtask));
        service.changeSubtask(parentId, children.get(0).getId(), false);
        assertEquals(50, service.findByIdWithSubtasks(parentId).getProgress());
        Todo listed = service.searchPage("", "", "asc", true, 1).stream().filter(t -> t.getId().equals(parentId)).findFirst().orElseThrow();
        assertEquals(50, listed.getProgress());
        assertEquals(2, listed.getSubtaskCount());
        service.changeSubtask(parentId, children.get(1).getId(), false);
        assertTrue(mapper.findById(parentId).getCompleted());
        assertNotNull(mapper.findById(parentId).getCompletedAt());
        service.addSubtask(parentId, "three");
        assertFalse(mapper.findById(parentId).getCompleted());
        assertNull(mapper.findById(parentId).getCompletedAt());
        service.changeSubtask(parentId, mapper.findSubtasks(parentId).get(2).getId(), true);
        assertTrue(mapper.findById(parentId).getCompleted());
        service.changeSubtask(parentId, children.get(0).getId(), false);
        assertFalse(mapper.findById(parentId).getCompleted());
    }
    @Test void validatesTitleAndHierarchy() {
        assertThrows(com.example.todoapp.exception.BusinessException.class, () -> service.addSubtask(parentId, " "));
        assertThrows(com.example.todoapp.exception.BusinessException.class, () -> service.addSubtask(parentId, "x".repeat(256)));
        service.addSubtask(parentId, "child");
        Long childId = mapper.findSubtasks(parentId).getFirst().getId();
        assertThrows(com.example.todoapp.exception.BusinessException.class, () -> service.addSubtask(childId, "grandchild"));
        assertThrows(com.example.todoapp.exception.TodoNotFoundException.class, () -> service.changeSubtask(parentId, parentId, true));
    }
    @Test void cascadesDeletionAndRestoresOnlyChildrenDeletedTogether() {
        service.addSubtask(parentId, "active");
        service.addSubtask(parentId, "deleted separately");
        var children = mapper.findSubtasks(parentId);
        service.changeSubtask(parentId, children.get(1).getId(), true);

        service.delete(parentId);
        assertNull(mapper.findById(children.get(0).getId()));
        assertTrue(service.restore(parentId));
        assertNotNull(mapper.findById(children.get(0).getId()));
        assertNull(mapper.findById(children.get(1).getId()));
    }
    @Test void rendersDetailAndSecuresMutations() throws Exception {
        mvc.perform(post("/todos/" + parentId + "/subtasks").with(user("sonjin")).with(csrf()).param("title", "Web child"))
            .andExpect(status().is3xxRedirection());
        mvc.perform(get("/todos/" + parentId).with(user("sonjin")))
            .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Web child")));
        mvc.perform(post("/todos/" + parentId + "/subtasks").with(user("sonjin")).param("title", "No CSRF"))
            .andExpect(status().isForbidden());
        jdbc.update("INSERT INTO users(username,password,role) VALUES ('subtask-other','unused','USER')");
        mvc.perform(post("/todos/" + parentId + "/subtasks").with(user("subtask-other")).with(csrf()).param("title", "Forbidden"))
            .andExpect(status().isForbidden());
    }
}
