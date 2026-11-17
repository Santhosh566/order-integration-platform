package com.example.orders.repository;

import com.example.orders.model.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findAllByOrderBySortOrderAscNameAsc();

    Optional<Category> findBySlug(String slug);

    boolean existsBySlug(String slug);

    long countByParentId(Long parentId);

    @Query("select count(i) from InventoryItem i where i.categoryId = :categoryId")
    long countProducts(Long categoryId);
}
