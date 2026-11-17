package com.example.orders.controller;

import com.example.orders.service.CategoryService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Categories: the shop's tree is public; changes are for admins (see SecurityConfig). */
@RestController
public class CategoryController {

    private final CategoryService categories;
    private final com.example.orders.service.ProductSpecService specs;

    public CategoryController(CategoryService categories, com.example.orders.service.ProductSpecService specs) {
        this.categories = categories;
        this.specs = specs;
    }

    @GetMapping("/api/public/categories")
    public List<CategoryService.CategoryView> publicTree() {
        return categories.publicTree();
    }

    @GetMapping("/api/admin/categories")
    public List<CategoryService.CategoryView> adminTree() {
        return categories.adminTree();
    }

    @PostMapping("/api/admin/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryService.CategoryView create(@RequestBody CategoryService.CategoryRequest request) {
        return categories.create(request);
    }

    @PatchMapping("/api/admin/categories/{id}")
    public CategoryService.CategoryView edit(@PathVariable Long id, @RequestBody CategoryService.CategoryRequest request) {
        return categories.edit(id, request);
    }

    @GetMapping("/api/admin/categories/{id}/spec-keys")
    public List<String> specKeys(@PathVariable Long id) {
        categories.find(id);
        return specs.suggestedNames(id);
    }

    /** The specification names products of this category are offered: ["RAM", "Storage", …] */
    @PutMapping("/api/admin/categories/{id}/spec-keys")
    public List<String> replaceSpecKeys(@PathVariable Long id, @RequestBody List<String> names) {
        categories.find(id);
        return specs.replaceSuggestedNames(id, names);
    }

    @DeleteMapping("/api/admin/categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        categories.delete(id);
    }
}
