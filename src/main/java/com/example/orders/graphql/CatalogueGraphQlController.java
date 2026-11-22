package com.example.orders.graphql;

import com.example.orders.graphql.CatalogueModels.Category;
import com.example.orders.graphql.CatalogueModels.Image;
import com.example.orders.graphql.CatalogueModels.Product;
import com.example.orders.graphql.CatalogueModels.ProductFilter;
import com.example.orders.graphql.CatalogueModels.ProductPage;
import com.example.orders.graphql.CatalogueModels.ProductSort;
import com.example.orders.graphql.CatalogueModels.Spec;
import com.example.orders.graphql.CatalogueModels.Suggestion;
import com.example.orders.delivery.DeliveryEstimator;
import com.example.orders.repository.CategoryRepository;
import com.example.orders.repository.ProductImageRepository;
import com.example.orders.repository.ProductSpecRepository;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The shop's GraphQL catalogue. Photos, specifications and categories are fetched for a whole page of
 * products in one query each (@BatchMapping), never once per product.
 */
@Controller
public class CatalogueGraphQlController {

    private final CatalogueSearch search;
    private final CategoryRepository categories;
    private final ProductImageRepository images;
    private final ProductSpecRepository specs;
    private final DeliveryEstimator estimator;
    private final com.example.orders.service.ReviewService reviews;
    private final com.example.orders.service.ProductOffers offers;
    private final com.example.orders.warehouse.WarehouseAllocator allocator;

    public CatalogueGraphQlController(CatalogueSearch search, CategoryRepository categories,
                                      ProductImageRepository images, ProductSpecRepository specs,
                                      DeliveryEstimator estimator, com.example.orders.service.ReviewService reviews,
                                      com.example.orders.service.ProductOffers offers,
                                      com.example.orders.warehouse.WarehouseAllocator allocator) {
        this.offers = offers;
        this.allocator = allocator;
        this.estimator = estimator;
        this.reviews = reviews;
        this.search = search;
        this.categories = categories;
        this.images = images;
        this.specs = specs;
    }

    @QueryMapping
    public ProductPage products(@Argument ProductFilter filter, @Argument ProductSort sort,
                                @Argument Integer page, @Argument Integer size) {
        // A client may send null for an argument that has a default
        return search.search(filter, sort, page == null ? 0 : page, size == null ? 24 : size);
    }

    @QueryMapping
    public Product product(@Argument String sku) {
        return search.product(sku);
    }

    @QueryMapping
    public List<Category> categories() {
        List<com.example.orders.model.Category> all = categories.findAllByOrderBySortOrderAscNameAsc().stream()
                .filter(com.example.orders.model.Category::isActive).toList();
        Map<Long, com.example.orders.model.Category> byId = all.stream()
                .collect(Collectors.toMap(com.example.orders.model.Category::getId, Function.identity()));
        Map<Long, CatalogueModels.CategoryStats> stats = search.categoryStats();
        var none = new CatalogueModels.CategoryStats(0, null);
        return all.stream().filter(c -> c.getParentId() == null)
                .map(main -> {
                    var mainStats = stats.getOrDefault(main.getId(), none);
                    return new Category(main.getId(), main.getSlug(), main.getName(), null, null,
                            all.stream().filter(sub -> main.getId().equals(sub.getParentId()))
                                    .map(sub -> {
                                        var subStats = stats.getOrDefault(sub.getId(), none);
                                        Category plain = toCategory(sub, byId);
                                        return new Category(plain.id(), plain.slug(), plain.name(), plain.parentSlug(),
                                                plain.parentName(), List.of(), subStats.imageKey(), subStats.productCount());
                                    }).toList(),
                            mainStats.imageKey(), mainStats.productCount());
                })
                .toList();
    }

    @QueryMapping
    public List<Suggestion> suggestions(@Argument String text, @Argument Integer limit) {
        return search.suggestions(text, limit == null ? 8 : limit);
    }

    public record HolidayNote(String date, String name) {}

    public record DeliveryEstimateView(String outcome, String pin, String district, String state, String earliest,
                                       String latest, boolean approximate, Integer minutesToCutOff,
                                       List<HolidayNote> holidays, String shipsFrom) {}

    @QueryMapping
    public DeliveryEstimateView deliveryEstimate(@Argument String sku, @Argument String pin) {
        Product product = search.product(sku);
        // From the nearest warehouse that has it: its distance and cut-off time set the days
        DeliveryEstimator.Estimate e = estimator.estimate(pin,
                product == null ? null : allocator.nearestHolding(sku, pin == null ? "" : pin.replaceAll("\\s", "")).orElse(null));
        return new DeliveryEstimateView(e.outcome().name(), e.pin(), e.district(), e.state(),
                e.earliest() == null ? null : e.earliest().toString(), e.latest() == null ? null : e.latest().toString(),
                e.approximate(), e.dispatchCountdown() == null ? null : (int) e.dispatchCountdown().toMinutes(),
                e.holidays().stream().map(h -> new HolidayNote(h.day().toString(), h.name())).toList(), e.shipsFrom());
    }

    @SchemaMapping(typeName = "Product")
    public List<Product> related(Product product, @Argument Integer limit) {
        return search.related(product, limit == null ? 8 : limit);
    }

    @SchemaMapping(typeName = "Product")
    public com.example.orders.service.ReviewService.ReviewPage reviews(Product product, @Argument String sort,
                                                                       @Argument Integer page, @Argument Integer size) {
        return reviews.published(product.sku(), sort == null ? null : com.example.orders.service.ReviewService.Sort.valueOf(sort),
                page == null ? 0 : page, size == null ? 10 : size);
    }

    /** Stars and the number of written reviews: only worked out when a page asks (the product page does). */
    @SchemaMapping(typeName = "Rating")
    public List<Integer> distribution(CatalogueModels.Rating rating) {
        return rating.sku() == null ? List.of(0, 0, 0, 0, 0) : reviews.breakdown(rating.sku()).stars();
    }

    @SchemaMapping(typeName = "Rating")
    public int reviewCount(CatalogueModels.Rating rating) {
        return rating.sku() == null ? 0 : reviews.breakdown(rating.sku()).reviewCount();
    }

    @BatchMapping(typeName = "Product")
    public Map<Product, List<Image>> images(List<Product> products) {
        Map<String, List<Image>> bySku = new LinkedHashMap<>();
        images.findBySkuInOrderBySkuAscPositionAscIdAsc(products.stream().map(Product::sku).toList())
                .forEach(i -> bySku.computeIfAbsent(i.getSku(), k -> new ArrayList<>()).add(new Image(i.getId(),
                        i.getLargeKey(), i.getThumbKey(), i.getAltText(), i.getWidth(), i.getHeight())));
        return products.stream().collect(Collectors.toMap(Function.identity(),
                p -> bySku.getOrDefault(p.sku(), List.of()), (a, b) -> a, LinkedHashMap::new));
    }

    @BatchMapping(typeName = "Product")
    public Map<Product, List<Spec>> specs(List<Product> products) {
        Map<String, List<Spec>> bySku = new LinkedHashMap<>();
        specs.findBySkuInOrderBySkuAscPositionAsc(products.stream().map(Product::sku).toList())
                .forEach(s -> bySku.computeIfAbsent(s.getSku(), k -> new ArrayList<>()).add(new Spec(s.getName(), s.getValue())));
        return products.stream().collect(Collectors.toMap(Function.identity(),
                p -> bySku.getOrDefault(p.sku(), List.of()), (a, b) -> a, LinkedHashMap::new));
    }

    /** One look at the live coupons for the whole page of products. */
    @BatchMapping(typeName = "Product")
    public Map<Product, List<CatalogueModels.Offer>> offers(List<Product> products) {
        Map<String, Long> categoryBySku = new LinkedHashMap<>();
        products.forEach(p -> categoryBySku.put(p.sku(), p.categoryId()));
        Map<String, List<com.example.orders.service.ProductOffers.ProductOffer>> bySku = offers.forProducts(categoryBySku);
        return products.stream().collect(Collectors.toMap(Function.identity(),
                p -> bySku.getOrDefault(p.sku(), List.of()).stream().map(o -> new CatalogueModels.Offer(o.code(),
                        o.description(), o.discountType().name(), o.discountValue().doubleValue(),
                        o.maxDiscount() == null ? null : o.maxDiscount().doubleValue(), o.minOrderValue().doubleValue(),
                        o.endsAt() == null ? null : o.endsAt().toString(), o.firstOrderOnly(), o.targeted())).toList(),
                (a, b) -> a, LinkedHashMap::new));
    }

    @BatchMapping(typeName = "Product")
    public Map<Product, Category> category(List<Product> products) {
        Map<Long, com.example.orders.model.Category> byId = categories.findAll().stream()
                .collect(Collectors.toMap(com.example.orders.model.Category::getId, Function.identity()));
        Map<Product, Category> result = new LinkedHashMap<>();
        for (Product p : products) {
            com.example.orders.model.Category c = p.categoryId() == null ? null : byId.get(p.categoryId());
            // A hidden category is not shown, but the product stays on sale
            result.put(p, c == null || !c.isActive() ? null : toCategory(c, byId));
        }
        return result;
    }

    private static Category toCategory(com.example.orders.model.Category c, Map<Long, com.example.orders.model.Category> byId) {
        com.example.orders.model.Category parent = c.getParentId() == null ? null : byId.get(c.getParentId());
        return new Category(c.getId(), c.getSlug(), c.getName(), parent == null ? null : parent.getSlug(),
                parent == null ? null : parent.getName(), List.of(), null, null);
    }
}
