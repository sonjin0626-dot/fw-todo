package com.example.todoapp;

import java.util.ArrayList;
import java.util.List;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 永続化モデルから分離し、所有者やIDの入力を受け付けない。 */
@Data
public class TemplateForm {
    @NotBlank(message = "{template.nameRequired}")
    @Size(max = 100, message = "{template.nameSize}")
    private String name;
    @Size(max = 500, message = "{template.descriptionSize}")
    private String description;
    @NotNull(message = "{template.itemsSize}")
    @Size(min = 1, max = 100, message = "{template.itemsSize}")
    private List<@NotBlank(message = "{template.itemRequired}") @Size(max = 200, message = "{template.itemSize}") String> itemTitles = new ArrayList<>(List.of(""));
}
