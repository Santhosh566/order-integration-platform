package com.example.orders.service;

import com.example.orders.exception.InvalidOrderException;
import com.example.orders.exception.InvalidOrderStateException;
import com.example.orders.exception.NotFoundException;
import com.example.orders.model.Category;
import com.example.orders.repository.CategoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shop categories, two levels deep: main categories and their sub-categories.
 */
@Service
public class CategoryService {

    private static final Logger log = LoggerFactory.getLogger(CategoryService.class);

    /** gstRule: "APPAREL_2500" for clothing and footwear, "" to remove it; left out: unchanged. */
    public record CategoryRequest(String name, String slug, Long parentId, Integer sortOrder, Boolean active,
                                  String gstRule) {}

    /** A category with its sub-categories; productCount counts products on sale in it (admin only). */
    public record CategoryView(Long id, String slug, String name, Long parentId, int sortOrder, boolean active,
                               Long productCount, String gstRule, List<CategoryView> children) {}

    private final CategoryRepository categories;
    private final StockLedger stockLedger;

    public CategoryService(CategoryRepository categories, StockLedger stockLedger) {
        this.categories = categories;
        this.stockLedger = stockLedger;
    }

    /** The shop's tree: active categories only, without counts. */
    @Transactional(readOnly = true)
    public List<CategoryView> publicTree() {
        return tree(false);
    }

    /** The admin's tree: every category, with how many products each holds. */
    @Transactional(readOnly = true)
    public List<CategoryView> adminTree() {
        return tree(true);
    }

    private List<CategoryView> tree(boolean admin) {
        List<Category> all = categories.findAllByOrderBySortOrderAscNameAsc().stream()
                .filter(c -> admin || c.isActive()).toList();
        Map<Long, List<CategoryView>> children = new LinkedHashMap<>();
        for (Category c : all) {
            if (c.getParentId() != null) {
                children.computeIfAbsent(c.getParentId(), k -> new ArrayList<>()).add(view(c, admin, List.of()));
            }
        }
        return all.stream().filter(c -> c.getParentId() == null)
                .map(c -> view(c, admin, children.getOrDefault(c.getId(), List.of())))
                .toList();
    }

    private CategoryView view(Category c, boolean admin, List<CategoryView> children) {
        return new CategoryView(c.getId(), c.getSlug(), c.getName(), c.getParentId(), c.getSortOrder(), c.isActive(),
                admin ? categories.countProducts(c.getId()) : null, admin ? c.getGstRule() : null, children);
    }

    @Transactional
    public CategoryView create(CategoryRequest request) {
        String name = requireName(request.name());
        String slug = request.slug() == null || request.slug().isBlank() ? slugOf(name) : checkSlug(request.slug());
        if (categories.existsBySlug(slug)) {
            throw new InvalidOrderStateException("A category with the address '" + slug + "' already exists");
        }
        Long parentId = checkParent(request.parentId(), null);
        Category fresh = new Category(slug, name, parentId, request.sortOrder() == null ? 0 : request.sortOrder());
        fresh.setGstRule(checkGstRule(request.gstRule()));
        Category category = categories.saveAndFlush(fresh);
        log.info("[CATALOG] {} created category {} ({})", stockLedger.actor(), name, slug);
        return view(category, true, List.of());
    }

    @Transactional
    public CategoryView edit(Long id, CategoryRequest request) {
        Category category = find(id);
        if (request.name() != null) {
            category.setName(requireName(request.name()));
        }
        if (request.slug() != null) {
            String slug = checkSlug(request.slug());
            if (!slug.equals(category.getSlug()) && categories.existsBySlug(slug)) {
                throw new InvalidOrderStateException("A category with the address '" + slug + "' already exists");
            }
            category.setSlug(slug);
        }
        if (request.parentId() != null) {
            // 0 makes it a main category
            category.setParentId(request.parentId() == 0 ? null : checkParent(request.parentId(), category));
        }
        if (request.sortOrder() != null) {
            category.setSortOrder(request.sortOrder());
        }
        if (request.active() != null) {
            category.setActive(request.active());
        }
        if (request.gstRule() != null) {
            // Applies to prices set from now on; existing prices keep their rate until changed
            category.setGstRule(checkGstRule(request.gstRule()));
        }
        categories.flush();
        return view(category, true, List.of());
    }

    /** Only an empty category can go; one that still has products or sub-categories is deactivated instead. */
    @Transactional
    public void delete(Long id) {
        Category category = find(id);
        if (categories.countProducts(id) > 0) {
            throw new InvalidOrderStateException("Category '" + category.getName() + "' still has products. "
                    + "Move them first, or switch the category off instead.");
        }
        if (categories.countByParentId(id) > 0) {
            throw new InvalidOrderStateException("Category '" + category.getName() + "' still has sub-categories");
        }
        categories.delete(category);
        log.info("[CATALOG] {} deleted category {}", stockLedger.actor(), category.getSlug());
    }

    /** The category a product may be put in: it must exist. Null means none. */
    @Transactional(readOnly = true)
    public Long checkProductCategory(Long categoryId) {
        if (categoryId == null || categoryId == 0) {
            return null;
        }
        return find(categoryId).getId();
    }

    @Transactional(readOnly = true)
    public Category find(Long id) {
        return categories.findById(id).orElseThrow(() -> new NotFoundException("Category " + id + " not found"));
    }

    /** A category and its sub-categories, for filtering the shop by a main category. */
    @Transactional(readOnly = true)
    public List<Long> withChildren(String slug) {
        Category category = categories.findBySlug(slug).filter(Category::isActive)
                .orElseThrow(() -> new NotFoundException("Category " + slug + " not found"));
        List<Long> ids = new ArrayList<>();
        ids.add(category.getId());
        categories.findAllByOrderBySortOrderAscNameAsc().stream()
                .filter(c -> category.getId().equals(c.getParentId()) && c.isActive())
                .forEach(c -> ids.add(c.getId()));
        return ids;
    }

    private Long checkParent(Long parentId, Category self) {
        if (parentId == null || parentId == 0) {
            return null;
        }
        Category parent = find(parentId);
        if (parent.getParentId() != null) {
            throw new InvalidOrderException("Categories go two levels deep: '" + parent.getName()
                    + "' is already a sub-category");
        }
        if (self != null) {
            if (parent.getId().equals(self.getId())) {
                throw new InvalidOrderException("A category cannot be its own parent");
            }
            if (categories.countByParentId(self.getId()) > 0) {
                throw new InvalidOrderException("'" + self.getName() + "' has sub-categories, so it must stay a main category");
            }
        }
        return parent.getId();
    }

    private static String checkGstRule(String rule) {
        if (rule == null || rule.isBlank()) {
            return null;
        }
        if (!com.example.orders.money.GstRules.APPAREL_2500.equals(rule)) {
            throw new InvalidOrderException("gstRule must be APPAREL_2500 or empty");
        }
        return rule;
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new InvalidOrderException("name is required");
        }
        if (name.trim().length() > 120) {
            throw new InvalidOrderException("name must be at most 120 characters");
        }
        return name.trim();
    }

    private static String checkSlug(String slug) {
        String s = slug.trim().toLowerCase(Locale.ROOT);
        if (!s.matches("[a-z0-9]+(-[a-z0-9]+)*") || s.length() > 80) {
            throw new InvalidOrderException("The address may use lower-case letters, digits and single dashes");
        }
        return s;
    }

    /** "Pens & Pencils" → "pens-pencils"; accents are dropped. */
    static String slugOf(String name) {
        String plain = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String slug = plain.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (slug.isEmpty()) {
            throw new InvalidOrderException("Give the category an address of letters or digits");
        }
        return slug.length() > 80 ? slug.substring(0, 80).replaceAll("-$", "") : slug;
    }
}
