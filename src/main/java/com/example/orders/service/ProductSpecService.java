package com.example.orders.service;

import com.example.orders.exception.InvalidOrderException;
import com.example.orders.exception.ProductNotFoundException;
import com.example.orders.model.ProductSpec;
import com.example.orders.repository.InventoryRepository;
import com.example.orders.repository.ProductSpecRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A product's specification table, and the names each category suggests for it.
 */
@Service
public class ProductSpecService {

    public static final int MAX_SPECS = 30;

    public record Spec(String name, String value) {}

    private final ProductSpecRepository specs;
    private final InventoryRepository inventory;
    private final JdbcTemplate jdbc;

    public ProductSpecService(ProductSpecRepository specs, InventoryRepository inventory, JdbcTemplate jdbc) {
        this.specs = specs;
        this.inventory = inventory;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<Spec> specs(String sku) {
        return specs.findBySkuOrderByPositionAsc(sku).stream().map(s -> new Spec(s.getName(), s.getValue())).toList();
    }

    /** Replaces the whole table, in the order given; empty values are left out. */
    @Transactional
    public List<Spec> replace(String sku, List<Spec> table) {
        if (!inventory.existsById(sku)) {
            throw new ProductNotFoundException(sku);
        }
        List<Spec> clean = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Spec spec : table == null ? List.<Spec>of() : table) {
            String name = spec.name() == null ? "" : spec.name().strip();
            String value = spec.value() == null ? "" : spec.value().strip();
            if (name.isEmpty() && value.isEmpty()) {
                continue;
            }
            if (name.isEmpty() || value.isEmpty()) {
                throw new InvalidOrderException("Every specification needs a name and a value");
            }
            if (name.length() > 60 || value.length() > 200) {
                throw new InvalidOrderException("Specification names may have 60 characters, values 200");
            }
            if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                throw new InvalidOrderException("'" + name + "' appears twice");
            }
            clean.add(new Spec(name, value));
        }
        if (clean.size() > MAX_SPECS) {
            throw new InvalidOrderException("A product has at most " + MAX_SPECS + " specifications");
        }
        specs.deleteBySku(sku);
        specs.flush();
        for (int i = 0; i < clean.size(); i++) {
            specs.save(new ProductSpec(sku, clean.get(i).name(), clean.get(i).value(), i));
        }
        return clean;
    }

    @Transactional(readOnly = true)
    public List<String> suggestedNames(Long categoryId) {
        if (categoryId == null) {
            return List.of();
        }
        return jdbc.queryForList("select name from category_spec_keys where category_id = ? order by position, name",
                String.class, categoryId);
    }

    @Transactional
    public List<String> replaceSuggestedNames(Long categoryId, List<String> names) {
        jdbc.update("delete from category_spec_keys where category_id = ?", categoryId);
        List<String> clean = (names == null ? List.<String>of() : names).stream()
                .map(String::strip).filter(n -> !n.isEmpty() && n.length() <= 60).distinct().toList();
        for (int i = 0; i < clean.size(); i++) {
            jdbc.update("insert into category_spec_keys (category_id, name, position) values (?, ?, ?)",
                    categoryId, clean.get(i), i);
        }
        return clean;
    }
}
