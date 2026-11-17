package com.example.orders.controller;

import com.example.orders.model.StockMovement;
import com.example.orders.service.CatalogAdminService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Catalogue and stock administration. Admins only (see SecurityConfig).
 *
 * PATCH and price changes need expectedVersion, the version shown when the product was opened.
 */
@RestController
@RequestMapping("/api/admin/catalog")
public class AdminCatalogController {

    private final CatalogAdminService catalog;

    private final com.example.orders.service.ProductSpecService specService;

    public AdminCatalogController(CatalogAdminService catalog, com.example.orders.service.ProductSpecService specService) {
        this.catalog = catalog;
        this.specService = specService;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "") String q,
                                    @RequestParam(defaultValue = "true") boolean includeInactive,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "25") int size) {
        return catalog.list(q, includeInactive, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogAdminService.ProductView create(@RequestBody CatalogAdminService.NewProduct request) {
        return catalog.create(request);
    }

    @GetMapping("/{sku}")
    public Map<String, Object> detail(@PathVariable String sku) {
        return catalog.detail(sku);
    }

    @PatchMapping("/{sku}")
    public CatalogAdminService.ProductView edit(@PathVariable String sku,
                                                @RequestBody CatalogAdminService.ProductEdit request) {
        return catalog.edit(sku, request);
    }

    @PostMapping("/{sku}/prices")
    public Map<String, Object> changePrice(@PathVariable String sku,
                                           @RequestBody CatalogAdminService.PriceChange request) {
        return catalog.changePrice(sku, request);
    }

    @DeleteMapping("/{sku}/prices/{priceId}")
    public Map<String, Object> cancelScheduledPrice(@PathVariable String sku, @PathVariable long priceId) {
        return catalog.cancelScheduledPrice(sku, priceId);
    }

    @PostMapping("/{sku}/stock")
    @ResponseStatus(HttpStatus.CREATED)
    public StockMovement changeStock(@PathVariable String sku, @RequestBody CatalogAdminService.StockChange request) {
        return catalog.changeStock(sku, request);
    }

    /** The specification table, replaced as a whole: [{"name": "RAM", "value": "8 GB"}, …] */
    @PutMapping("/{sku}/specs")
    public List<com.example.orders.service.ProductSpecService.Spec> specs(
            @PathVariable String sku, @RequestBody List<com.example.orders.service.ProductSpecService.Spec> table) {
        return specService.replace(sku, table);
    }

    @GetMapping("/{sku}/movements")
    public Map<String, Object> movements(@PathVariable String sku,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "50") int size) {
        return catalog.movements(sku, page, size);
    }
}
