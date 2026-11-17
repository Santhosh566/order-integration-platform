package com.example.orders.graphql;

import java.util.List;

/** The shapes of the GraphQL catalogue (src/main/resources/graphql/catalogue.graphqls). */
public final class CatalogueModels {

    private CatalogueModels() {}

    public record ProductFilter(String text, List<String> categories, List<String> brands, Double minPrice,
                                Double maxPrice, Boolean inStockOnly, Integer minRating, String offer) {}

    /** A live coupon on a product; see the Offer type. */
    public record Offer(String code, String description, String discountType, double discountValue,
                        Double maxDiscount, double minOrderValue, String endsAt, boolean firstOrderOnly,
                        boolean targeted) {}

    public enum ProductSort { RELEVANCE, PRICE_LOW_HIGH, PRICE_HIGH_LOW, NEWEST, TOP_RATED }

    /** @param sku whose rating it is, so the breakdown can be fetched; not in the schema */
    public record Rating(double average, int count, String sku) {}

    /** A product on sale; images, specs, category and related products are fetched only when asked for. */
    public record Product(String sku, String name, String brand, double price, double gstRate, String currency,
                          String stockStatus, String description, String imageKey, String imageAlt, Rating rating,
                          Integer maxPerOrder, Long categoryId) {}

    public record ProductPage(List<Product> items, int totalItems, int totalPages, int page, int size, Facets facets) {}

    public record Facets(List<CategoryCount> categories, List<BrandCount> brands, PriceRange price) {}

    public record CategoryCount(String slug, String name, String parentSlug, int count) {}

    public record BrandCount(String brand, int count) {}

    public record PriceRange(double min, double max) {}

    /**
     * @param imageKey     a photo for the category's tile: its best product's (in stock, best rated); null if none
     * @param productCount products on sale in it and its sub-categories; null where not worked out
     */
    public record Category(Long id, String slug, String name, String parentSlug, String parentName,
                           List<Category> children, String imageKey, Integer productCount) {}

    public record CategoryStats(int productCount, String imageKey) {}

    /** A product's name and main photo, for lines on orders and returns; also for products no longer sold. */
    public record Thumbnail(String sku, String name, String imageKey) {}

    public record Image(Long id, String largeKey, String thumbKey, String altText, int width, int height) {}

    public record Spec(String name, String value) {}

    public enum SuggestionKind { PRODUCT, CATEGORY, BRAND }

    public record Suggestion(SuggestionKind kind, String label, String sku, String slug) {}
}
