package com.example.orders.controller;

import com.example.orders.warehouse.WarehouseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Warehouses and the stock in each, for admins (/api/admin/**, SecurityConfig). */
@RestController
public class AdminWarehouseController {

    private final WarehouseService warehouses;
    private final com.example.orders.warehouse.WarehouseAdminService admin;

    public AdminWarehouseController(WarehouseService warehouses, com.example.orders.warehouse.WarehouseAdminService admin) {
        this.warehouses = warehouses;
        this.admin = admin;
    }

    /** Opens a warehouse; its position defaults to the centre of its PIN code. */
    @org.springframework.web.bind.annotation.PostMapping("/api/admin/warehouses")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public WarehouseService.Warehouse create(@org.springframework.web.bind.annotation.RequestBody
                                             com.example.orders.warehouse.WarehouseAdminService.WarehouseRequest request) {
        return admin.create(request);
    }

    /** Name, address, cut-off, position, open or closed. */
    @org.springframework.web.bind.annotation.PatchMapping("/api/admin/warehouses/{id}")
    public WarehouseService.Warehouse update(@PathVariable long id, @org.springframework.web.bind.annotation.RequestBody
                                             com.example.orders.warehouse.WarehouseAdminService.WarehouseRequest request) {
        return admin.update(id, request);
    }

    /** {"sku": "...", "fromWarehouseId": 1, "toWarehouseId": 2, "quantity": 5, "reason": "..."} */
    @org.springframework.web.bind.annotation.PostMapping("/api/admin/warehouses/transfers")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public com.example.orders.warehouse.WarehouseAdminService.TransferResult transfer(@org.springframework.web.bind.annotation.RequestBody
                                                                                      com.example.orders.warehouse.WarehouseAdminService.Transfer request) {
        return admin.transfer(request);
    }

    /** Where orders ship from, for the shop: id, code and name only. */
    @GetMapping("/api/public/warehouses")
    public List<java.util.Map<String, Object>> publicList() {
        return warehouses.list().stream().map(w -> java.util.Map.<String, Object>of("id", w.id(), "code", w.code(), "name", w.name()))
                .toList();
    }

    /** Every warehouse with how many products and units it holds. */
    @GetMapping("/api/admin/warehouses")
    public List<WarehouseService.Summary> list() {
        return warehouses.summaries();
    }

    /** {"warehouseId": 2}: the warehouse a driver works from, and so whose deliveries they see. */
    @org.springframework.web.bind.annotation.PutMapping("/api/admin/users/{userId}/warehouse")
    public WarehouseService.Warehouse moveDriver(@PathVariable String userId,
                                                 @org.springframework.web.bind.annotation.RequestBody java.util.Map<String, Long> body) {
        Long warehouseId = body == null ? null : body.get("warehouseId");
        if (warehouseId == null) {
            throw new com.example.orders.exception.InvalidOrderException("warehouseId is required");
        }
        return warehouses.setHome(userId, warehouseId);
    }

    /** One product's stock in each warehouse. */
    @GetMapping("/api/admin/catalog/{sku}/warehouses")
    public List<WarehouseService.Stock> stockOf(@PathVariable String sku) {
        return warehouses.stockOf(sku);
    }
}
