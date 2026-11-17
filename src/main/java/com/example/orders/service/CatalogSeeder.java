package com.example.orders.service;

import com.example.orders.repository.InventoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Fills an empty catalogue with the demo products, through the same service the admin screen
 * uses, so they start with a price history and an opening stock entry like any other product.
 */
@Component
public class CatalogSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CatalogSeeder.class);
    private static final BigDecimal GST_18 = new BigDecimal("18");

    private final InventoryRepository inventory;
    private final CatalogAdminService catalog;

    public CatalogSeeder(InventoryRepository inventory, CatalogAdminService catalog) {
        this.inventory = inventory;
        this.catalog = catalog;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (inventory.count() > 0) {
            return;
        }
        log.info("[CATALOG] Empty catalogue: adding the demo products");
        // Prices in rupees, GST included; HSN codes as printed on the tax invoice
        add("WIDGET-A", "Ergonomic Office Chair", "8499.00", "9401", 100, 10);
        add("WIDGET-B", "Mechanical Desk Keyboard", "3299.00", "8471", 20, 5);
        add("GADGET-X", "Dual 4K Monitor Arm", "4999.00", "8302", 2, 1);
        add("SERVER-PRO", "Enterprise Server Rack", "84999.00", "9403", 5, 1);
        add("OUT-OF-STOCK", "Discontinued Item", "999.00", "9403", 0, 0);
    }

    private void add(String sku, String name, String price, String hsn, int stock, int reorderLevel) {
        catalog.create(new CatalogAdminService.NewProduct(sku, name, hsn, new BigDecimal(price), GST_18, stock, reorderLevel, null, null, null, null));
    }
}
