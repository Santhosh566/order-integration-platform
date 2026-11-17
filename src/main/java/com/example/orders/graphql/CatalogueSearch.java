package com.example.orders.graphql;

import com.example.orders.graphql.CatalogueModels.BrandCount;
import com.example.orders.graphql.CatalogueModels.CategoryCount;
import com.example.orders.graphql.CatalogueModels.Facets;
import com.example.orders.graphql.CatalogueModels.PriceRange;
import com.example.orders.graphql.CatalogueModels.Product;
import com.example.orders.graphql.CatalogueModels.ProductFilter;
import com.example.orders.graphql.CatalogueModels.ProductPage;
import com.example.orders.graphql.CatalogueModels.ProductSort;
import com.example.orders.graphql.CatalogueModels.Rating;
import com.example.orders.graphql.CatalogueModels.Suggestion;
import com.example.orders.graphql.CatalogueModels.SuggestionKind;
import com.example.orders.model.Category;
import com.example.orders.repository.CategoryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Search over the products on sale, in Postgres: full-text search (stemmed, accents ignored) for
 * whole words, and trigram word similarity for typos and the start of a word ("hedphone", "kurt").
 * Fine for thousands of products; a search engine would only be worth it far beyond that.
 */
@Service
public class CatalogueSearch {

    public static final int MAX_PAGE_SIZE = 48;
    static final int FEW_LEFT = 5;
    /** How close a misspelt or partial word must be (0..1). */
    static final double WORD_SIMILARITY = 0.45;

    private final NamedParameterJdbcTemplate jdbc;
    private final CategoryRepository categories;
    private final com.example.orders.service.ProductOffers offers;
    private final String currency;

    public CatalogueSearch(NamedParameterJdbcTemplate jdbc, CategoryRepository categories,
                           com.example.orders.service.ProductOffers offers,
                           @Value("${orders.currency:INR}") String currency) {
        this.jdbc = jdbc;
        this.categories = categories;
        this.offers = offers;
        this.currency = currency;
    }

    private static final String COLUMNS = "i.sku, i.product_name, i.brand, i.unit_price, i.gst_rate, i.available_quantity, "
            + "i.description, i.rating_avg, i.rating_count, i.max_per_order, i.category_id, "
            + "(select p.thumb_key from product_images p where p.sku = i.sku order by p.position, p.image_id limit 1) as image_key, "
            + "(select coalesce(p.alt_text, i.product_name) from product_images p where p.sku = i.sku "
            + "  order by p.position, p.image_id limit 1) as image_alt";

    /** The facet a query leaves out, so its counts show what choosing another value would give. */
    private enum Ignore { NONE, CATEGORY, BRAND, PRICE }

    @Transactional(readOnly = true)
    public ProductPage search(ProductFilter filter, ProductSort sort, int page, int size) {
        ProductFilter f = filter == null ? new ProductFilter(null, null, null, null, null, null, null, null) : filter;
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int pageNumber = Math.max(page, 0);
        String text = f.text() == null ? "" : f.text().strip();
        if (text.length() > 100) {
            text = text.substring(0, 100);
        }

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("q", text).addValue("similarity", WORD_SIMILARITY)
                .addValue("limit", pageSize).addValue("offset", pageNumber * pageSize);
        String where = where(f, text, params, Ignore.NONE);

        Integer total = jdbc.queryForObject("select count(*) from inventory i where " + where, params, Integer.class);
        List<Product> items = jdbc.query("select " + COLUMNS + " from inventory i where " + where
                + " order by " + orderBy(sort, text) + " limit :limit offset :offset", params, this::toProduct);

        int totalItems = total == null ? 0 : total;
        return new ProductPage(items, totalItems, (totalItems + pageSize - 1) / pageSize, pageNumber, pageSize,
                facets(f, text));
    }

    @Transactional(readOnly = true)
    public Product product(String sku) {
        List<Product> found = jdbc.query("select " + COLUMNS + " from inventory i where i.sku = :sku and "
                + onSale(), new MapSqlParameterSource("sku", sku), this::toProduct);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * For each category, main ones including their sub-categories: how many products are on sale, and
     * the photo of the best one (in stock, best rated) for its tile. One query for the whole tree.
     */
    @Transactional(readOnly = true)
    public java.util.Map<Long, CatalogueModels.CategoryStats> categoryStats() {
        java.util.Map<Long, CatalogueModels.CategoryStats> stats = new java.util.HashMap<>();
        jdbc.query("select k.category_id, count(*) as products, "
                + "(array_agg(img.thumb_key order by (i.available_quantity > 0) desc, i.rating_avg desc, "
                + "  i.rating_count desc, i.sku) filter (where img.thumb_key is not null))[1] as image_key "
                + "from inventory i "
                + "join categories c on c.category_id = i.category_id and c.active "
                + "cross join lateral (values (c.category_id), (c.parent_id)) as k(category_id) "
                + "left join lateral (select p.thumb_key from product_images p where p.sku = i.sku "
                + "  order by p.position, p.image_id limit 1) img on true "
                + "where k.category_id is not null and " + onSale() + " "
                + "group by k.category_id", new MapSqlParameterSource(),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> stats.put(rs.getLong("category_id"),
                        new CatalogueModels.CategoryStats(rs.getInt("products"), rs.getString("image_key"))));
        return stats;
    }

    /** Name and main photo of each SKU that exists, sold or not: lines on old orders and returns need them too. */
    @Transactional(readOnly = true)
    public List<CatalogueModels.Thumbnail> thumbnails(java.util.Collection<String> skus) {
        if (skus.isEmpty()) {
            return List.of();
        }
        return jdbc.query("select i.sku, i.product_name, "
                + "(select p.thumb_key from product_images p where p.sku = i.sku order by p.position, p.image_id limit 1) as image_key "
                + "from inventory i where i.sku in (:skus) order by i.sku",
                new MapSqlParameterSource("skus", skus), (rs, n) -> new CatalogueModels.Thumbnail(
                        rs.getString("sku"), rs.getString("product_name"), rs.getString("image_key")));
    }

    /** Several products by SKU, those still on sale; for lists the shopper keeps, such as the wishlist. */
    @Transactional(readOnly = true)
    public java.util.Map<String, Product> products(java.util.Collection<String> skus) {
        if (skus.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<String, Product> found = new java.util.HashMap<>();
        jdbc.query("select " + COLUMNS + " from inventory i where i.sku in (:skus) and " + onSale(),
                new MapSqlParameterSource("skus", skus), this::toProduct).forEach(p -> found.put(p.sku(), p));
        return found;
    }

    /** Same category, price within half to double, in stock first, best rated, nearest in price. */
    @Transactional(readOnly = true)
    public List<Product> related(Product product, int limit) {
        if (product.categoryId() == null) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("sku", product.sku()).addValue("category", product.categoryId())
                .addValue("price", product.price()).addValue("limit", Math.min(Math.max(limit, 1), 12));
        return jdbc.query("select " + COLUMNS + " from inventory i where " + onSale()
                + " and i.category_id = :category and i.sku <> :sku"
                + " and i.unit_price between :price / 2 and :price * 2"
                + " order by (i.available_quantity > 0) desc, i.rating_avg desc, abs(i.unit_price - :price), i.sku"
                + " limit :limit", params, this::toProduct);
    }

    @Transactional(readOnly = true)
    public List<Suggestion> suggestions(String text, int limit) {
        String q = text == null ? "" : text.strip();
        if (q.length() < 2) {
            return List.of();
        }
        int max = Math.min(Math.max(limit, 1), 10);
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("q", q)
                .addValue("like", "%" + q.toLowerCase(Locale.ROOT).replace("%", "").replace("_", "") + "%")
                .addValue("similarity", WORD_SIMILARITY);
        List<Suggestion> out = new ArrayList<>();
        out.addAll(jdbc.query("select c.slug, c.name from categories c where c.active and "
                + "(lower(c.name) like :like or word_similarity(f_unaccent(lower(:q)), f_unaccent(lower(c.name))) >= :similarity) "
                + "order by word_similarity(f_unaccent(lower(:q)), f_unaccent(lower(c.name))) desc, c.name limit 3", params,
                (rs, n) -> new Suggestion(SuggestionKind.CATEGORY, rs.getString("name"), null, rs.getString("slug"))));
        out.addAll(jdbc.query("select distinct i.brand from inventory i where " + onSale() + " and i.brand is not null "
                + "and lower(i.brand) like :like order by i.brand limit 2", params,
                (rs, n) -> new Suggestion(SuggestionKind.BRAND, rs.getString("brand"), null, null)));
        out.addAll(jdbc.query("select i.sku, i.product_name from inventory i where " + onSale() + " and " + textMatch()
                + " order by " + rank() + " desc, i.product_name limit " + max, params,
                (rs, n) -> new Suggestion(SuggestionKind.PRODUCT, rs.getString("product_name"), rs.getString("sku"), null)));
        return out.size() > max ? out.subList(0, max) : out;
    }

    // ── Facets ────────────────────────────────────────────────────────────────

    private Facets facets(ProductFilter f, String text) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("q", text).addValue("similarity", WORD_SIMILARITY);

        // Per sub-category, then added up into the main categories
        Map<Long, Category> all = new LinkedHashMap<>();
        categories.findAllByOrderBySortOrderAscNameAsc().stream().filter(Category::isActive).forEach(c -> all.put(c.getId(), c));
        Map<Long, Integer> counts = new LinkedHashMap<>();
        jdbc.query("select i.category_id, count(*) as n from inventory i where " + where(f, text, params, Ignore.CATEGORY)
                + " and i.category_id is not null group by i.category_id", params, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            long id = rs.getLong("category_id");
            int n = rs.getInt("n");
            counts.merge(id, n, Integer::sum);
            Category c = all.get(id);
            if (c != null && c.getParentId() != null) {
                counts.merge(c.getParentId(), n, Integer::sum);
            }
        });
        List<CategoryCount> categoryCounts = new ArrayList<>();
        all.values().forEach(c -> {
            Integer n = counts.get(c.getId());
            if (n != null && n > 0) {
                Category parent = c.getParentId() == null ? null : all.get(c.getParentId());
                categoryCounts.add(new CategoryCount(c.getSlug(), c.getName(), parent == null ? null : parent.getSlug(), n));
            }
        });

        MapSqlParameterSource brandParams = new MapSqlParameterSource().addValue("q", text).addValue("similarity", WORD_SIMILARITY);
        List<BrandCount> brands = jdbc.query("select i.brand, count(*) as n from inventory i where "
                + where(f, text, brandParams, Ignore.BRAND) + " and i.brand is not null group by i.brand order by n desc, i.brand",
                brandParams, (rs, n) -> new BrandCount(rs.getString("brand"), rs.getInt("n")));

        MapSqlParameterSource priceParams = new MapSqlParameterSource().addValue("q", text).addValue("similarity", WORD_SIMILARITY);
        PriceRange price = jdbc.query("select min(i.unit_price) as lo, max(i.unit_price) as hi from inventory i where "
                + where(f, text, priceParams, Ignore.PRICE), priceParams, rs -> {
            rs.next();
            return rs.getObject("lo") == null ? null : new PriceRange(rs.getDouble("lo"), rs.getDouble("hi"));
        });
        return new Facets(categoryCounts, brands, price);
    }

    // ── SQL pieces ────────────────────────────────────────────────────────────

    private static String onSale() {
        return "i.active and i.unit_price is not null";
    }

    private static String textMatch() {
        return "(i.search_vector @@ websearch_to_tsquery('english', f_unaccent(:q)) or word_similarity(f_unaccent(lower(:q)), "
                + "i.search_text) >= :similarity)";
    }

    private static String rank() {
        return "(ts_rank_cd(i.search_vector, websearch_to_tsquery('english', f_unaccent(:q))) + word_similarity("
                + "f_unaccent(lower(:q)), i.search_text))";
    }

    private String where(ProductFilter f, String text, MapSqlParameterSource params, Ignore ignore) {
        List<String> conditions = new ArrayList<>();
        conditions.add(onSale());
        if (!text.isEmpty()) {
            conditions.add(textMatch());
        }
        if (ignore != Ignore.CATEGORY && f.categories() != null && !f.categories().isEmpty()) {
            Set<Long> ids = categoryIds(f.categories());
            params.addValue("categoryIds", ids.isEmpty() ? List.of(-1L) : ids);
            conditions.add("i.category_id in (:categoryIds)");
        }
        if (ignore != Ignore.BRAND && f.brands() != null && !f.brands().isEmpty()) {
            params.addValue("brands", f.brands().stream().map(b -> b.toLowerCase(Locale.ROOT)).toList());
            conditions.add("lower(i.brand) in (:brands)");
        }
        if (ignore != Ignore.PRICE && f.minPrice() != null) {
            params.addValue("minPrice", f.minPrice());
            conditions.add("i.unit_price >= :minPrice");
        }
        if (ignore != Ignore.PRICE && f.maxPrice() != null) {
            params.addValue("maxPrice", f.maxPrice());
            conditions.add("i.unit_price <= :maxPrice");
        }
        if (Boolean.TRUE.equals(f.inStockOnly())) {
            conditions.add("i.available_quantity > 0");
        }
        if (f.minRating() != null && f.minRating() > 0) {
            params.addValue("minRating", Math.min(f.minRating(), 5));
            conditions.add("i.rating_count > 0 and i.rating_avg >= :minRating");
        }
        if (f.offer() != null && !f.offer().isBlank()) {
            conditions.add(offerCondition(f.offer(), params));
        }
        return String.join(" and ", conditions);
    }

    /** Products a live coupon takes money off: all of them for a whole-order coupon, none for a dead one. */
    private String offerCondition(String code, MapSqlParameterSource params) {
        var scope = offers.scope(code).orElse(null);
        if (scope == null) {
            return "false";
        }
        if (scope.wholeOrder()) {
            return "true";
        }
        params.addValue("offerSkus", scope.skus().isEmpty() ? List.of("") : scope.skus());
        params.addValue("offerCategories", scope.categoryIds().isEmpty() ? List.of(-1L) : scope.categoryIds());
        return "(i.sku in (:offerSkus) or i.category_id in (:offerCategories))";
    }

    /** The categories named, with the sub-categories of any main category among them. */
    private Set<Long> categoryIds(List<String> slugs) {
        Set<String> wanted = new HashSet<>(slugs);
        Set<Long> ids = new HashSet<>();
        List<Category> all = categories.findAllByOrderBySortOrderAscNameAsc();
        all.stream().filter(c -> c.isActive() && wanted.contains(c.getSlug())).forEach(c -> {
            ids.add(c.getId());
            all.stream().filter(sub -> c.getId().equals(sub.getParentId()) && sub.isActive()).forEach(sub -> ids.add(sub.getId()));
        });
        return ids;
    }

    private static String orderBy(ProductSort sort, String text) {
        return switch (sort == null ? ProductSort.RELEVANCE : sort) {
            case PRICE_LOW_HIGH -> "i.unit_price asc, i.sku";
            case PRICE_HIGH_LOW -> "i.unit_price desc, i.sku";
            case NEWEST -> "i.created_at desc, i.sku";
            case TOP_RATED -> "i.rating_avg desc, i.rating_count desc, i.sku";
            case RELEVANCE -> text.isEmpty()
                    ? "(i.available_quantity > 0) desc, i.rating_avg desc, i.rating_count desc, i.product_name"
                    : rank() + " desc, (i.available_quantity > 0) desc, i.sku";
        };
    }

    private Product toProduct(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        int available = rs.getInt("available_quantity");
        Object max = rs.getObject("max_per_order");
        Object category = rs.getObject("category_id");
        return new Product(rs.getString("sku"), rs.getString("product_name"), rs.getString("brand"),
                rs.getDouble("unit_price"), rs.getDouble("gst_rate"), currency,
                available <= 0 ? "OUT_OF_STOCK" : available <= FEW_LEFT ? "FEW_LEFT" : "IN_STOCK",
                rs.getString("description"), rs.getString("image_key"), rs.getString("image_alt"),
                new Rating(rs.getDouble("rating_avg"), rs.getInt("rating_count"), rs.getString("sku")),
                max == null ? null : ((Number) max).intValue(), category == null ? null : ((Number) category).longValue());
    }
}
