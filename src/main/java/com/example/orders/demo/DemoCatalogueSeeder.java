package com.example.orders.demo;

import com.example.orders.model.Category;
import com.example.orders.repository.CategoryRepository;
import com.example.orders.repository.InventoryRepository;
import com.example.orders.repository.ProductImageRepository;
import com.example.orders.service.CatalogAdminService;
import com.example.orders.service.CategoryService;
import com.example.orders.service.ProductImageService;
import com.example.orders.service.ProductSpecService;
import com.example.orders.storage.ImageStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fills a development shop with 50 categories and 160 products (demo/catalogue.json), through the
 * same services as the admin screens: prices, opening stock, specifications and, when photo storage
 * is set up, a drawn photo each. Adds only what is missing, so it is safe on every start; off in
 * production (ORDERS_DEMO_CATALOGUE=false).
 */
@Component
@Order(20)
public class DemoCatalogueSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoCatalogueSeeder.class);

    record DemoCategory(String slug, String name, String parent, int sortOrder, String colour, String gstRule,
                        List<String> specKeys) {}

    record DemoProduct(String sku, String name, String brand, String category, String hsn, BigDecimal gstRate,
                       BigDecimal price, int stock, int reorderLevel, String description,
                       List<ProductSpecService.Spec> specs, Integer maxPerOrder) {}

    record DemoCatalogue(String note, List<DemoCategory> categories, List<DemoProduct> products,
                         Map<String, String> existing) {}

    private final boolean enabled;
    private final CategoryService categoryService;
    private final CategoryRepository categories;
    private final CatalogAdminService catalog;
    private final ProductSpecService specs;
    private final ProductImageService photos;
    private final ProductImageRepository images;
    private final InventoryRepository inventory;
    private final ImageStorage storage;

    public DemoCatalogueSeeder(@Value("${orders.demo.catalogue}") boolean enabled, CategoryService categoryService,
                               CategoryRepository categories, CatalogAdminService catalog, ProductSpecService specs,
                               ProductImageService photos, ProductImageRepository images,
                               InventoryRepository inventory, ImageStorage storage) {
        this.enabled = enabled;
        this.categoryService = categoryService;
        this.categories = categories;
        this.catalog = catalog;
        this.specs = specs;
        this.photos = photos;
        this.images = images;
        this.inventory = inventory;
        this.storage = storage;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!enabled) {
            return;
        }
        DemoCatalogue demo;
        try (InputStream in = new ClassPathResource("demo/catalogue.json").getInputStream()) {
            demo = JsonMapper.shared().readValue(in, DemoCatalogue.class);
        }
        Map<String, Long> ids = seedCategories(demo.categories());
        Map<String, DemoCategory> bySlug = new HashMap<>();
        demo.categories().forEach(c -> bySlug.put(c.slug(), c));
        int created = seedProducts(demo.products(), ids);
        placeExisting(demo.existing(), ids);
        log.info("[DEMO] Catalogue ready: {} categories, {} new products", ids.size(), created);

        // Photos take a while and need MinIO; the app is usable meanwhile
        if (storage.bucketReachable()) {
            Thread.ofVirtual().name("demo-photos").start(() -> seedPhotos(demo.products(), bySlug));
        } else {
            log.info("[DEMO] Photo storage not reachable: demo products keep their placeholders");
        }
    }

    private Map<String, Long> seedCategories(List<DemoCategory> list) {
        Map<String, Long> ids = new HashMap<>();
        for (DemoCategory c : list) {
            Category existing = categories.findBySlug(c.slug()).orElse(null);
            Long id = existing != null ? existing.getId()
                    : categoryService.create(new CategoryService.CategoryRequest(c.name(), c.slug(),
                            c.parent() == null ? null : ids.get(c.parent()), c.sortOrder(), true, c.gstRule())).id();
            ids.put(c.slug(), id);
            if (!c.specKeys().isEmpty() && specs.suggestedNames(id).isEmpty()) {
                specs.replaceSuggestedNames(id, c.specKeys());
            }
        }
        return ids;
    }

    private int seedProducts(List<DemoProduct> list, Map<String, Long> ids) {
        int created = 0;
        for (DemoProduct p : list) {
            if (inventory.existsById(p.sku())) {
                continue;
            }
            catalog.create(new CatalogAdminService.NewProduct(p.sku(), p.name(), p.hsn(), p.price(), p.gstRate(),
                    p.stock(), p.reorderLevel(), p.description(), ids.get(p.category()), p.brand(), p.maxPerOrder()));
            specs.replace(p.sku(), p.specs());
            created++;
        }
        return created;
    }

    /** The products the older demo seeder created go into fitting categories, once. */
    private void placeExisting(Map<String, String> existing, Map<String, Long> ids) {
        existing.forEach((sku, slug) -> inventory.findById(sku)
                .filter(item -> item.getCategoryId() == null)
                .ifPresent(item -> catalog.edit(sku, new CatalogAdminService.ProductEdit(item.getCatalogVersion(),
                        null, null, null, null, null, ids.get(slug), null, null))));
    }

    private void seedPhotos(List<DemoProduct> list, Map<String, DemoCategory> bySlug) {
        int added = 0;
        for (DemoProduct p : list) {
            try {
                if (images.countBySku(p.sku()) > 0) {
                    continue;
                }
                DemoCategory category = bySlug.get(p.category());
                byte[] tile = DemoTiles.render(p.brand(), p.name(), category.name(), category.colour());
                photos.upload(p.sku(), tile, p.name());
                added++;
            } catch (RuntimeException e) {
                log.warn("[DEMO] No photo for {}: {}", p.sku(), e.getMessage());
                return;
            }
        }
        log.info("[DEMO] {} demo photos added", added);
    }

}
