package com.example.orders.service;

import com.example.orders.exception.InvalidOrderException;
import com.example.orders.exception.InvalidOrderStateException;
import com.example.orders.exception.ProductNotFoundException;
import com.example.orders.model.InventoryItem;
import com.example.orders.model.ProductPrice;
import com.example.orders.model.StockMovement;
import com.example.orders.model.ProductImage;
import com.example.orders.money.GstRules;
import com.example.orders.repository.CategoryRepository;
import com.example.orders.repository.InventoryRepository;
import com.example.orders.repository.ProductImageRepository;
import com.example.orders.repository.ProductPriceRepository;
import com.example.orders.repository.StockMovementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Catalogue and stock administration.
 *
 * Product edits and price changes carry the version the admin was looking at; if someone else
 * changed the product meanwhile, the edit is refused instead of silently overwriting theirs.
 * Stock changes are amounts added or taken away, so they need no version: two receipts both count.
 */
@Service
public class CatalogAdminService {

    private static final Logger log = LoggerFactory.getLogger(CatalogAdminService.class);

    private static final Pattern SKU = Pattern.compile("[A-Z0-9][A-Z0-9-]{1,63}");
    // HSN codes are 4, 6 or 8 digits
    private static final Pattern HSN = Pattern.compile("\\d{4}(\\d{2}){0,2}");
    private static final Set<BigDecimal> GST_RATES = Set.of(
            new BigDecimal("0"), new BigDecimal("0.25"), new BigDecimal("3"), new BigDecimal("5"),
            new BigDecimal("12"), new BigDecimal("18"), new BigDecimal("28"), new BigDecimal("40"));
    private static final BigDecimal MAX_PRICE = new BigDecimal("9999999999.99");
    // The shop shows "only a few left" at or below this
    private static final int FEW_LEFT = 5;

    public record NewProduct(String sku, String name, String hsnCode, BigDecimal unitPrice, BigDecimal gstRate,
                             Integer openingStock, Integer reorderLevel, String description, Long categoryId,
                             String brand, Integer maxPerOrder) {}

    /** Fields left out are not changed. categoryId 0 takes the product out of its category; description "" clears it. */
    public record ProductEdit(Integer expectedVersion, String name, String hsnCode, Integer reorderLevel, Boolean active,
                              String description, Long categoryId, String brand, Integer maxPerOrder) {}

    public record PriceChange(Integer expectedVersion, BigDecimal unitPrice, BigDecimal gstRate,
                              LocalDateTime effectiveFrom, String reason) {}

    /** @param warehouseId the warehouse whose stock changes; the default warehouse when left out */
    public record StockChange(StockMovement.Type type, Integer quantity, String reason, Long warehouseId) {}

    public record ProductView(String sku, String name, String hsnCode, BigDecimal unitPrice, BigDecimal gstRate,
                              String currency, int available, int reserved, int reorderLevel, boolean lowStock,
                              boolean active, int version, LocalDateTime updatedAt, String updatedBy,
                              String description, Long categoryId, String categoryName, String brand,
                              Integer maxPerOrder, BigDecimal ratingAvg, int ratingCount, String gstRule) {}

    /** imageKey is the main photo's thumbnail, served at /api/public/images/{key}; null when it has none. */
    public record PublicProduct(String sku, String name, BigDecimal unitPrice, BigDecimal gstRate, String currency,
                                String stockStatus, String description, Long categoryId, String imageKey,
                                String imageAlt, String brand, BigDecimal ratingAvg, int ratingCount,
                                Integer maxPerOrder) {}

    /** The product page: everything on the card plus every photo. */
    public record PublicProductDetail(PublicProduct product, String categoryName, String categorySlug,
                                      List<PublicImage> images) {}

    public record PublicImage(Long id, String largeKey, String thumbKey, String altText, int width, int height) {}

    private final InventoryRepository inventory;
    private final ProductPriceRepository prices;
    private final StockMovementRepository movements;
    private final StockLedger stockLedger;
    private final String currency;

    private final CategoryService categoryService;
    private final CategoryRepository categories;
    private final ProductImageRepository images;
    private final ProductSpecService specService;
    private final WishlistService wishlist;
    private final com.example.orders.warehouse.WarehouseService warehouses;

    public CatalogAdminService(InventoryRepository inventory, ProductPriceRepository prices,
                               StockMovementRepository movements, StockLedger stockLedger,
                               @Value("${orders.currency:INR}") String currency,
                               CategoryService categoryService, CategoryRepository categories,
                               ProductImageRepository images, ProductSpecService specService,
                               WishlistService wishlist, com.example.orders.warehouse.WarehouseService warehouses) {
        this.warehouses = warehouses;
        this.specService = specService;
        this.wishlist = wishlist;
        this.categoryService = categoryService;
        this.categories = categories;
        this.images = images;
        this.inventory = inventory;
        this.prices = prices;
        this.movements = movements;
        this.stockLedger = stockLedger;
        this.currency = currency;
    }

    // ── Reading ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> list(String query, boolean includeInactive, int page, int size) {
        String pattern = "%" + (query == null ? "" : query.trim().toLowerCase(Locale.ROOT)) + "%";
        Page<InventoryItem> result = inventory.search(pattern, includeInactive,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by("sku")));
        return Map.of(
                "items", result.getContent().stream().map(this::view).toList(),
                "page", result.getNumber(),
                "size", result.getSize(),
                "totalItems", result.getTotalElements(),
                "totalPages", result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(String sku) {
        InventoryItem item = inventory.findById(sku).orElseThrow(() -> new ProductNotFoundException(sku));
        return Map.of(
                "product", view(item),
                "prices", prices.findBySkuOrderByEffectiveFromDescIdDesc(sku).stream().map(CatalogAdminService::priceView).toList(),
                "recentMovements", movements.findBySkuOrderByIdDesc(sku, PageRequest.of(0, 20)).getContent(),
                "images", images.findBySkuOrderByPositionAscIdAsc(sku),
                "specs", specService.specs(sku),
                "suggestedSpecs", specService.suggestedNames(item.getCategoryId()));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> movements(String sku, int page, int size) {
        if (!inventory.existsById(sku)) {
            throw new ProductNotFoundException(sku);
        }
        Page<StockMovement> result = movements.findBySkuOrderByIdDesc(sku,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200)));
        return Map.of("items", result.getContent(), "page", result.getNumber(),
                "totalItems", result.getTotalElements(), "totalPages", result.getTotalPages());
    }

    /**
     * The shop window: products on sale, without exact stock counts. With a category, only that
     * category and, for a main category, its sub-categories.
     */
    @Transactional(readOnly = true)
    public List<PublicProduct> publicCatalog(String categorySlug) {
        java.util.Set<Long> inCategory = categorySlug == null || categorySlug.isBlank()
                ? null : new java.util.HashSet<>(categoryService.withChildren(categorySlug.trim()));
        List<InventoryItem> items = inventory.findByActiveTrueAndUnitPriceIsNotNullOrderBySku().stream()
                .filter(item -> inCategory == null || inCategory.contains(item.getCategoryId()))
                .toList();
        // One query for all main photos, not one per product
        Map<String, ProductImage> mainPhoto = new java.util.HashMap<>();
        if (!items.isEmpty()) {
            images.findBySkuInOrderBySkuAscPositionAscIdAsc(items.stream().map(InventoryItem::getSku).toList())
                    .forEach(img -> mainPhoto.putIfAbsent(img.getSku(), img));
        }
        return items.stream().map(item -> publicView(item, mainPhoto.get(item.getSku()))).toList();
    }

    /** One product for its page in the shop; products off sale answer as missing. */
    @Transactional(readOnly = true)
    public PublicProductDetail publicProduct(String sku) {
        InventoryItem item = inventory.findById(sku)
                .filter(i -> i.isActive() && i.getUnitPrice() != null)
                .orElseThrow(() -> new ProductNotFoundException(sku));
        List<ProductImage> photos = images.findBySkuOrderByPositionAscIdAsc(sku);
        com.example.orders.model.Category category = item.getCategoryId() == null ? null
                : categories.findById(item.getCategoryId()).filter(com.example.orders.model.Category::isActive).orElse(null);
        return new PublicProductDetail(publicView(item, photos.isEmpty() ? null : photos.get(0)),
                category == null ? null : category.getName(), category == null ? null : category.getSlug(),
                photos.stream().map(p -> new PublicImage(p.getId(), p.getLargeKey(), p.getThumbKey(), p.getAltText(),
                        p.getWidth(), p.getHeight())).toList());
    }

    private PublicProduct publicView(InventoryItem item, ProductImage photo) {
        return new PublicProduct(item.getSku(), item.getProductName(), item.getUnitPrice(), item.getGstRate(), currency,
                item.getAvailableQuantity() <= 0 ? "OUT_OF_STOCK"
                        : item.getAvailableQuantity() <= FEW_LEFT ? "FEW_LEFT" : "IN_STOCK",
                item.getDescription(), item.getCategoryId(),
                photo == null ? null : photo.getThumbKey(),
                photo == null ? null : (photo.getAltText() != null ? photo.getAltText() : item.getProductName()),
                item.getBrand(), item.getRatingAvg(), item.getRatingCount(), item.getMaxPerOrder());
    }

    // ── Products ───────────────────────────────────────────────────────────────

    @Transactional
    public ProductView create(NewProduct request) {
        String sku = request.sku() == null ? null : request.sku().trim().toUpperCase(Locale.ROOT);
        if (sku == null || !SKU.matcher(sku).matches()) {
            throw new InvalidOrderException("sku must be 2-64 capital letters, digits or dashes");
        }
        if (inventory.existsById(sku)) {
            throw new InvalidOrderStateException("Product " + sku + " already exists");
        }
        String name = requireName(request.name());
        String hsn = checkHsn(request.hsnCode());
        BigDecimal price = checkPrice(request.unitPrice());
        Long categoryId = categoryService.checkProductCategory(request.categoryId());
        // Clothing and footwear: the category's rule sets the rate from the price
        BigDecimal ruleRate = GstRules.rateFor(gstRuleOf(categoryId), price);
        BigDecimal rate = ruleRate != null ? ruleRate : checkRate(request.gstRate());
        int opening = nonNegative(request.openingStock(), "openingStock", 0);
        int reorder = nonNegative(request.reorderLevel(), "reorderLevel", 0);

        InventoryItem item = new InventoryItem(sku, name, price, hsn, rate, 0);
        item.setReorderLevel(reorder);
        if (request.description() != null) {
            item.setDescription(checkDescription(request.description()));
        }
        item.setCategoryId(categoryId);
        item.setBrand(checkBrand(request.brand()));
        item.setMaxPerOrder(checkMaxPerOrder(request.maxPerOrder()));
        String actor = stockLedger.actor();
        item.edited(actor);
        // The SKU is the id, so save() merges: keep working on the managed copy it returns
        item = inventory.saveAndFlush(item);
        prices.save(appliedPrice(sku, price, rate, "New product", actor));
        stockLedger.record(item, warehouses.defaultId(), StockMovement.Type.OPENING, opening, 0, null, "Opening stock");
        log.info("[CATALOG] {} created {} '{}' at {} {} with {} in stock", actor, sku, name, currency, price, opening);
        return view(item);
    }

    @Transactional
    public ProductView edit(String sku, ProductEdit request) {
        InventoryItem item = lockAtVersion(sku, request.expectedVersion());
        if (request.name() != null) {
            item.setProductName(requireName(request.name()));
        }
        if (request.hsnCode() != null) {
            item.setHsnCode(checkHsn(request.hsnCode()));
        }
        if (request.reorderLevel() != null) {
            item.setReorderLevel(nonNegative(request.reorderLevel(), "reorderLevel", 0));
        }
        boolean wasOnSale = item.isActive();
        if (request.active() != null) {
            item.setActive(request.active());
        }
        if (request.description() != null) {
            item.setDescription(checkDescription(request.description()));
        }
        if (request.brand() != null) {
            item.setBrand(checkBrand(request.brand()));
        }
        if (request.maxPerOrder() != null) {
            // 0 removes the limit
            item.setMaxPerOrder(request.maxPerOrder() == 0 ? null : checkMaxPerOrder(request.maxPerOrder()));
        }
        String actor = stockLedger.actor();
        if (request.categoryId() != null) {
            item.setCategoryId(categoryService.checkProductCategory(request.categoryId()));
            // Moving into or out of clothing can change the GST rate at the same price
            BigDecimal ruleRate = GstRules.rateFor(gstRuleOf(item.getCategoryId()), item.getUnitPrice());
            if (ruleRate != null && item.getUnitPrice() != null && ruleRate.compareTo(item.getGstRate()) != 0) {
                apply(item, prices.save(appliedPrice(sku, item.getUnitPrice(), ruleRate,
                        "GST rule of the new category", actor)), actor);
            }
        }
        item.edited(actor);
        if (!wasOnSale && item.isActive()) {
            // Put back on sale with stock: customers waiting for it are emailed
            wishlist.productAvailable(item);
        }
        log.info("[CATALOG] {} edited {} (now version {}, active {})", item.getUpdatedBy(), sku,
                item.getCatalogVersion(), item.isActive());
        return view(item);
    }

    // ── Prices ─────────────────────────────────────────────────────────────────

    /**
     * Sets a new price and GST rate, now or from a future time. Orders already placed keep their price.
     */
    @Transactional
    public Map<String, Object> changePrice(String sku, PriceChange request) {
        InventoryItem item = lockAtVersion(sku, request.expectedVersion());
        BigDecimal price = checkPrice(request.unitPrice());
        BigDecimal ruleRate = GstRules.rateFor(gstRuleOf(item.getCategoryId()), price);
        BigDecimal rate = ruleRate != null ? ruleRate
                : request.gstRate() != null ? checkRate(request.gstRate()) : item.getGstRate();
        String actor = stockLedger.actor();
        LocalDateTime now = LocalDateTime.now();

        if (request.effectiveFrom() != null && request.effectiveFrom().isAfter(now)) {
            ProductPrice scheduled = prices.save(new ProductPrice(sku, price, rate, request.effectiveFrom(),
                    request.reason(), actor));
            log.info("[CATALOG] {} scheduled {} at {} {} from {}", actor, sku, currency, price, request.effectiveFrom());
            return Map.of("product", view(item), "price", priceView(scheduled));
        }
        // A past time would rewrite history: orders already placed were charged the old price
        ProductPrice applied = prices.save(appliedPrice(sku, price, rate, request.reason(), actor));
        apply(item, applied, actor);
        return Map.of("product", view(item), "price", priceView(applied));
    }

    @Transactional
    public Map<String, Object> cancelScheduledPrice(String sku, long priceId) {
        ProductPrice price = prices.findById(priceId)
                .filter(p -> p.getSku().equals(sku))
                .orElseThrow(() -> new ProductNotFoundException(sku + " price " + priceId));
        if (price.getState() != ProductPrice.State.SCHEDULED) {
            throw new InvalidOrderStateException("Price " + priceId + " is " + price.getState() + " and cannot be cancelled");
        }
        price.cancelled(LocalDateTime.now());
        log.info("[CATALOG] {} cancelled the scheduled price {} of {}", stockLedger.actor(), priceId, sku);
        return priceView(price);
    }

    /**
     * Makes due scheduled prices the catalogue price. Run every minute by the price scheduler;
     * the due rows are locked with SKIP LOCKED, so several pods can run it safely.
     *
     * @return how many prices were applied
     */
    @Transactional
    public int applyDuePrices() {
        List<ProductPrice> due = prices.lockDue(LocalDateTime.now());
        for (ProductPrice price : due) {
            inventory.findForUpdate(price.getSku()).ifPresent(item -> apply(item, price, "price-scheduler"));
        }
        return due.size();
    }

    private void apply(InventoryItem item, ProductPrice price, String actor) {
        item.setUnitPrice(price.getUnitPrice());
        item.setGstRate(price.getGstRate());
        item.edited(actor);
        price.applied(LocalDateTime.now());
        log.info("[CATALOG] {} now costs {} {} (GST {}%), set by {}", item.getSku(), currency,
                price.getUnitPrice(), price.getGstRate().stripTrailingZeros().toPlainString(), price.getCreatedBy());
    }

    private static ProductPrice appliedPrice(String sku, BigDecimal price, BigDecimal rate, String reason, String actor) {
        LocalDateTime now = LocalDateTime.now();
        ProductPrice row = new ProductPrice(sku, price, rate, now, reason, actor);
        row.applied(now);
        return row;
    }

    // ── Stock ──────────────────────────────────────────────────────────────────

    /**
     * Goods received, a stock-count correction, or a write-off. Corrections and write-offs need a
     * reason, because they change stock without an order or a delivery behind them.
     */
    @Transactional
    public StockMovement changeStock(String sku, StockChange request) {
        if (request.type() == null || request.quantity() == null) {
            throw new InvalidOrderException("type and quantity are required");
        }
        int quantity = request.quantity();
        String reason = request.reason() == null ? null : request.reason().trim();
        int change = switch (request.type()) {
            case RECEIPT -> positive(quantity);
            case WRITE_OFF -> -positive(quantity);
            case ADJUSTMENT -> {
                if (quantity == 0) {
                    throw new InvalidOrderException("An adjustment must change the stock");
                }
                yield quantity;
            }
            default -> throw new InvalidOrderException(
                    "Only RECEIPT, ADJUSTMENT and WRITE_OFF can be entered by hand; " + request.type() + " comes from orders");
        };
        if (request.type() != StockMovement.Type.RECEIPT && (reason == null || reason.isEmpty())) {
            throw new InvalidOrderException("A reason is required for " + request.type());
        }
        if (reason != null && reason.length() > 500) {
            throw new InvalidOrderException("reason must be at most 500 characters");
        }
        InventoryItem item = inventory.findForUpdate(sku).orElseThrow(() -> new ProductNotFoundException(sku));
        long warehouse = request.warehouseId() != null ? warehouses.get(request.warehouseId()).id() : warehouses.defaultId();
        return stockLedger.record(item, warehouse, request.type(), change, 0, null, reason);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private InventoryItem lockAtVersion(String sku, Integer expectedVersion) {
        if (expectedVersion == null) {
            throw new InvalidOrderException("expectedVersion is required: send the version you are editing");
        }
        InventoryItem item = inventory.findForUpdate(sku).orElseThrow(() -> new ProductNotFoundException(sku));
        if (item.getCatalogVersion() != expectedVersion) {
            throw new InvalidOrderStateException("Product " + sku + " was changed by " + item.getUpdatedBy()
                    + " since you opened it (version " + expectedVersion + ", now " + item.getCatalogVersion()
                    + "). Reload and try again.");
        }
        return item;
    }

    private ProductView view(InventoryItem item) {
        int level = item.getReorderLevel();
        String categoryName = item.getCategoryId() == null ? null
                : categories.findById(item.getCategoryId()).map(com.example.orders.model.Category::getName).orElse(null);
        return new ProductView(item.getSku(), item.getProductName(), item.getHsnCode(), item.getUnitPrice(),
                item.getGstRate(), currency, item.getAvailableQuantity(), item.getReservedQuantity(), level,
                level > 0 && item.getAvailableQuantity() <= level, item.isActive(), item.getCatalogVersion(),
                item.getUpdatedAt(), item.getUpdatedBy(), item.getDescription(), item.getCategoryId(), categoryName,
                item.getBrand(), item.getMaxPerOrder(), item.getRatingAvg(), item.getRatingCount(),
                gstRuleOf(item.getCategoryId()));
    }

    /** The GST rule of a category, or of its main category; null when neither has one. */
    String gstRuleOf(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        com.example.orders.model.Category category = categories.findById(categoryId).orElse(null);
        if (category == null) {
            return null;
        }
        if (category.getGstRule() != null || category.getParentId() == null) {
            return category.getGstRule();
        }
        return categories.findById(category.getParentId()).map(com.example.orders.model.Category::getGstRule).orElse(null);
    }

    private static String checkBrand(String brand) {
        if (brand == null || brand.isBlank()) {
            return null;
        }
        if (brand.strip().length() > 80) {
            throw new InvalidOrderException("brand must be at most 80 characters");
        }
        return brand.strip();
    }

    private static Integer checkMaxPerOrder(Integer max) {
        if (max == null) {
            return null;
        }
        if (max < 1 || max > 1000) {
            throw new InvalidOrderException("maxPerOrder must be between 1 and 1000");
        }
        return max;
    }

    /** Plain text, at most 5,000 characters; an empty one is removed. */
    static String checkDescription(String description) {
        String text = description.replace("\r\n", "\n").strip();
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() > 5000) {
            throw new InvalidOrderException("description must be at most 5000 characters");
        }
        return text;
    }

    private static Map<String, Object> priceView(ProductPrice price) {
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("id", price.getId());
        view.put("unitPrice", price.getUnitPrice());
        view.put("gstRate", price.getGstRate());
        view.put("effectiveFrom", price.getEffectiveFrom());
        view.put("state", price.getState());
        view.put("reason", price.getReason());
        view.put("createdBy", price.getCreatedBy());
        view.put("createdAt", price.getCreatedAt());
        return view;
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new InvalidOrderException("name is required");
        }
        if (name.trim().length() > 255) {
            throw new InvalidOrderException("name must be at most 255 characters");
        }
        return name.trim();
    }

    private static String checkHsn(String hsn) {
        if (hsn == null || hsn.isBlank()) {
            throw new InvalidOrderException("hsnCode is required for the GST invoice");
        }
        if (!HSN.matcher(hsn.trim()).matches()) {
            throw new InvalidOrderException("hsnCode must be 4, 6 or 8 digits");
        }
        return hsn.trim();
    }

    private static BigDecimal checkPrice(BigDecimal price) {
        if (price == null || price.signum() < 0 || price.compareTo(MAX_PRICE) > 0) {
            throw new InvalidOrderException("unitPrice is required and must be between 0 and " + MAX_PRICE);
        }
        if (price.stripTrailingZeros().scale() > 2) {
            throw new InvalidOrderException("unitPrice can have at most 2 decimals");
        }
        return price;
    }

    private static BigDecimal checkRate(BigDecimal rate) {
        if (rate == null || GST_RATES.stream().noneMatch(allowed -> allowed.compareTo(rate) == 0)) {
            throw new InvalidOrderException("gstRate must be one of 0, 0.25, 3, 5, 12, 18, 28 or 40");
        }
        return rate;
    }

    private static int nonNegative(Integer value, String field, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value < 0) {
            throw new InvalidOrderException(field + " cannot be negative");
        }
        return value;
    }

    private static int positive(int quantity) {
        if (quantity <= 0) {
            throw new InvalidOrderException("quantity must be more than 0");
        }
        return quantity;
    }
}
