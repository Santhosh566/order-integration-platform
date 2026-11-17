package com.example.orders.service;

import com.example.orders.exception.InvalidOrderException;
import com.example.orders.model.Category;
import com.example.orders.repository.CategoryRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What a coupon is for. A coupon without targets is for the whole order; with targets, only for the
 * products listed and the products in the categories listed (a main category includes its
 * sub-categories, also ones added later).
 */
@Component
public class CouponTargets {

    /** At most this many products and categories on one coupon. */
    static final int MAX_TARGETS = 100;

    /** A product (sku) or a category (categoryId), with its name for people. */
    public record Target(String sku, Long categoryId, String name) {}

    private final JdbcTemplate jdbc;
    private final CategoryRepository categories;

    public CouponTargets(JdbcTemplate jdbc, CategoryRepository categories) {
        this.jdbc = jdbc;
        this.categories = categories;
    }

    /** The targets of each coupon asked for; a coupon for the whole order maps to an empty list. */
    public Map<String, List<Target>> of(Collection<String> codes) {
        Map<String, List<Target>> result = new LinkedHashMap<>();
        codes.forEach(code -> result.put(code, new ArrayList<>()));
        if (codes.isEmpty()) {
            return result;
        }
        jdbc.query("select t.coupon_code, t.sku, t.category_id, coalesce(i.product_name, c.name) as name "
                + "from coupon_targets t left join inventory i on i.sku = t.sku "
                + "left join categories c on c.category_id = t.category_id "
                + "where t.coupon_code in (" + marks(codes.size()) + ") order by t.category_id nulls last, name",
                rs -> {
                    Object category = rs.getObject("category_id");
                    result.computeIfAbsent(rs.getString("coupon_code"), k -> new ArrayList<>()).add(new Target(
                            rs.getString("sku"), category == null ? null : ((Number) category).longValue(),
                            rs.getString("name")));
                }, codes.toArray());
        return result;
    }

    public List<Target> of(String code) {
        return of(List.of(code)).get(code);
    }

    /**
     * Sets a coupon's targets, replacing any it had. Both empty or null: the whole order.
     * Products must exist (on sale or not: a coupon may be ready before a launch); categories must be shown.
     */
    public void replace(String code, List<String> skus, List<Long> categoryIds) {
        Set<String> products = new LinkedHashSet<>();
        if (skus != null) {
            skus.stream().filter(s -> s != null && !s.isBlank()).map(String::strip).forEach(products::add);
        }
        Set<Long> cats = new LinkedHashSet<>();
        if (categoryIds != null) {
            categoryIds.stream().filter(java.util.Objects::nonNull).forEach(cats::add);
        }
        if (products.size() + cats.size() > MAX_TARGETS) {
            throw new InvalidOrderException("A coupon can be for at most " + MAX_TARGETS + " products and categories; "
                    + "choose a category instead of many products");
        }
        if (!products.isEmpty()) {
            Set<String> known = new HashSet<>(jdbc.queryForList("select sku from inventory where sku in ("
                    + marks(products.size()) + ")", String.class, products.toArray()));
            List<String> missing = products.stream().filter(s -> !known.contains(s)).toList();
            if (!missing.isEmpty()) {
                throw new InvalidOrderException("No product " + String.join(", ", missing));
            }
        }
        if (!cats.isEmpty()) {
            Map<Long, Category> byId = categories.findAllById(cats).stream()
                    .collect(Collectors.toMap(Category::getId, Function.identity()));
            for (Long id : cats) {
                Category category = byId.get(id);
                if (category == null || !category.isActive()) {
                    throw new InvalidOrderException("No category " + id + " in the shop");
                }
            }
        }
        jdbc.update("delete from coupon_targets where coupon_code = ?", code);
        products.forEach(sku -> jdbc.update("insert into coupon_targets (coupon_code, sku) values (?, ?)", code, sku));
        cats.forEach(id -> jdbc.update("insert into coupon_targets (coupon_code, category_id) values (?, ?)", code, id));
    }

    /**
     * Which of these products the coupon takes money off.
     *
     * @return null when the coupon is for the whole order; otherwise the SKUs among those given it is for
     */
    public Set<String> eligible(String code, Collection<String> skus) {
        List<Target> targets = of(code);
        if (targets.isEmpty()) {
            return null;
        }
        if (skus.isEmpty()) {
            return Set.of();
        }
        Map<String, Long> categoryOf = categoriesOf(skus);
        Map<Long, Long> parents = parents();
        Set<String> result = new HashSet<>();
        for (String sku : skus) {
            if (covers(targets, sku, categoryOf.get(sku), parents)) {
                result.add(sku);
            }
        }
        return result;
    }

    /** True when one of the targets lists the product, its category, or its category's main category. */
    static boolean covers(List<Target> targets, String sku, Long categoryId, Map<Long, Long> parents) {
        Long parent = categoryId == null ? null : parents.get(categoryId);
        for (Target t : targets) {
            if (t.sku() != null && t.sku().equals(sku)) {
                return true;
            }
            if (t.categoryId() != null && (t.categoryId().equals(categoryId) || t.categoryId().equals(parent))) {
                return true;
            }
        }
        return false;
    }

    /** Each product's category; products without one are left out. */
    public Map<String, Long> categoriesOf(Collection<String> skus) {
        Map<String, Long> result = new HashMap<>();
        if (skus.isEmpty()) {
            return result;
        }
        jdbc.query("select sku, category_id from inventory where category_id is not null and sku in ("
                + marks(skus.size()) + ")", rs -> {
            result.put(rs.getString("sku"), rs.getLong("category_id"));
        }, skus.toArray());
        return result;
    }

    /** Each sub-category's main category, for shown categories only: a hidden category passes nothing on. */
    public Map<Long, Long> parents() {
        Map<Long, Long> result = new HashMap<>();
        Set<Long> shown = new HashSet<>();
        List<Category> all = categories.findAll();
        all.stream().filter(Category::isActive).forEach(c -> shown.add(c.getId()));
        all.stream().filter(c -> c.getParentId() != null && c.isActive() && shown.contains(c.getParentId()))
                .forEach(c -> result.put(c.getId(), c.getParentId()));
        return result;
    }

    /** "Electronics and 2 more", for messages. */
    static String describe(List<Target> targets) {
        if (targets.isEmpty()) {
            return "any product";
        }
        String first = targets.get(0).name();
        return targets.size() == 1 ? first : first + " and " + (targets.size() - 1) + " more";
    }

    private static String marks(int n) {
        return String.join(", ", java.util.Collections.nCopies(n, "?"));
    }
}
