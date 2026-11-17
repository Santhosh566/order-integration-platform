package com.example.orders.controller;

import com.example.orders.exception.InvalidOrderException;
import com.example.orders.graphql.CatalogueModels;
import com.example.orders.graphql.CatalogueSearch;
import com.example.orders.service.CatalogAdminService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The shop window, open to everyone: products on sale with their prices, without stock counts.
 */
@RestController
public class PublicCatalogController {

    private final CatalogAdminService catalog;
    private final CatalogueSearch search;

    public PublicCatalogController(CatalogAdminService catalog, CatalogueSearch search) {
        this.catalog = catalog;
        this.search = search;
    }

    /** ?category=pens narrows it to one category (a main category includes its sub-categories). */
    @GetMapping("/api/public/catalog")
    public List<CatalogAdminService.PublicProduct> catalog(@RequestParam(required = false) String category) {
        return catalog.publicCatalog(category);
    }

    @GetMapping("/api/public/catalog/{sku}")
    public CatalogAdminService.PublicProductDetail product(@PathVariable String sku) {
        return catalog.publicProduct(sku);
    }

    /**
     * Name and main photo of each product asked for, ?sku=A&sku=B (at most 100), including products no
     * longer sold: lines on orders and returns show them. Unknown SKUs are left out.
     */
    @GetMapping("/api/public/thumbnails")
    public List<CatalogueModels.Thumbnail> thumbnails(@RequestParam(name = "sku", required = false) List<String> skus) {
        if (skus == null || skus.isEmpty()) {
            return List.of();
        }
        if (skus.size() > 100) {
            throw new InvalidOrderException("At most 100 products at a time");
        }
        return search.thumbnails(new java.util.LinkedHashSet<>(skus));
    }
}
