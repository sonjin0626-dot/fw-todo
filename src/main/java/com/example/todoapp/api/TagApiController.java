package com.example.todoapp.api;
import com.example.todoapp.Tag;
import com.example.todoapp.TagMapper;
import java.util.List;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/tags")
public class TagApiController {
    private final TagMapper mapper;
    public TagApiController(TagMapper mapper) { this.mapper = mapper; }
    @GetMapping("/search")
    public List<Tag> search(@RequestParam(defaultValue = "") String q) {
        return q.isBlank() ? List.of() : mapper.searchByName(q.trim());
    }
}