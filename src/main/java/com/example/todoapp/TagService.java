package com.example.todoapp;
import java.util.List;
import java.util.ArrayList;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.todoapp.exception.BusinessException;
@Service
@Transactional(readOnly = true)
public class TagService {
    private final TagMapper mapper;
    public TagService(TagMapper mapper) { this.mapper = mapper; }
    public List<Tag> resolve(List<Long> ids) {
        if (ids == null) return List.of();
        List<Tag> tags = new ArrayList<>();
        for (Long id : new java.util.LinkedHashSet<>(ids)) {
            Tag tag = id == null ? null : mapper.findById(id);
            if (tag == null) throw new BusinessException("INVALID_TAG", "選択したタグが存在しません");
            tags.add(tag);
        }
        return tags;
    }
    public List<Long> findTagIds(Long todoId) { return mapper.findTagIds(todoId); }
    @Transactional
    public void save(Long todoId, List<Long> ids) {
        // 未指定のAPI更新では保持し、フォームの空リストでは全解除する。
        if (ids == null) return;
        List<Tag> tags = resolve(ids);
        mapper.deleteTodoTags(todoId);
        for (Tag tag : tags) mapper.insertTodoTag(todoId, tag.id());
    }
}