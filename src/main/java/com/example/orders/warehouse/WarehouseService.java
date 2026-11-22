package com.example.orders.warehouse;

import com.example.orders.exception.NotFoundException;
import com.example.orders.model.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.List;

/**
 * The warehouses stock is kept in (V29). Each has its own GST registration and a position on the map, so an
 * order can be shipped from the nearest one that has its goods (phase 2). Stock itself changes only through
 * StockLedger.
 */
@Service
public class WarehouseService {

    /**
     * @param latitude, longitude where it is (PostGIS point)
     * @param cutoffTime orders packed by then leave the same day (India time)
     * @param isDefault  where stock goes when nobody says which
     */
    public record Warehouse(long id, String code, String name, String address, String pin, String stateCode,
                            String gstin, double latitude, double longitude, LocalTime cutoffTime, boolean active,
                            boolean isDefault, String invoicePrefix, String creditNotePrefix) {

        public com.example.orders.money.IndianState state() {
            return com.example.orders.money.IndianState.find(stateCode)
                    .orElseThrow(() -> new IllegalStateException("Warehouse " + code + " has no Indian state " + stateCode));
        }
    }

    /** A warehouse with how much it holds, for the admin's list. */
    public record Summary(Warehouse warehouse, int products, long available, long reserved) {}

    private final JdbcTemplate jdbc;

    public WarehouseService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<Warehouse> list() {
        return jdbc.query(SELECT + "order by w.is_default desc, w.name", ROW);
    }

    @Transactional(readOnly = true)
    public Warehouse get(long id) {
        return jdbc.query(SELECT + "where w.warehouse_id = ?", ROW, id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("No warehouse " + id));
    }

    /** The warehouse stock goes to when nobody says which. */
    public long defaultId() {
        Long id = jdbc.queryForObject("select warehouse_id from warehouses where is_default", Long.class);
        if (id == null) {
            throw new IllegalStateException("No default warehouse");
        }
        return id;
    }

    /** The warehouse a driver works from; one not given a warehouse works from the default one. */
    public long homeOf(String userId) {
        Long home = jdbc.queryForList("select home_warehouse_id from app_users where user_id = ?", Long.class, userId)
                .stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
        return home != null ? home : defaultId();
    }

    /** Moves a driver to another warehouse (admin). Deliveries they hold stay with them. */
    @Transactional
    public Warehouse setHome(String userId, long warehouseId) {
        Warehouse warehouse = get(warehouseId);
        if (!warehouse.active()) {
            throw new com.example.orders.exception.InvalidOrderException(warehouse.name() + " is closed");
        }
        Boolean driver = jdbc.queryForObject("select exists (select 1 from user_roles where user_id = ? and role = 'DELIVERY')",
                Boolean.class, userId);
        if (!Boolean.TRUE.equals(driver)) {
            throw new com.example.orders.exception.InvalidOrderException(userId + " is not a delivery user");
        }
        jdbc.update("update app_users set home_warehouse_id = ? where user_id = ?", warehouseId, userId);
        return warehouse;
    }

    public String codeOf(long warehouseId) {
        return jdbc.queryForList("select code from warehouses where warehouse_id = ?", String.class, warehouseId)
                .stream().findFirst().orElse(String.valueOf(warehouseId));
    }

    /** The warehouse an order ships from; orders from before it was recorded used the default one. */
    public long of(Order order) {
        return order.getWarehouseId() != null ? order.getWarehouseId() : defaultId();
    }

    @Transactional(readOnly = true)
    public List<Summary> summaries() {
        return list().stream().map(w -> jdbc.queryForObject("select count(*) filter (where available_quantity > 0 or reserved_quantity > 0), "
                + "coalesce(sum(available_quantity), 0), coalesce(sum(reserved_quantity), 0) from warehouse_stock where warehouse_id = ?",
                (rs, n) -> new Summary(w, rs.getInt(1), rs.getLong(2), rs.getLong(3)), w.id())).toList();
    }

    /** One product's stock in one warehouse. */
    public record Stock(long warehouseId, String code, String name, boolean active, int available, int reserved, int reorderLevel) {}

    /** A product's stock in every warehouse, the empty ones included, default first. */
    @Transactional(readOnly = true)
    public List<Stock> stockOf(String sku) {
        return jdbc.query("select w.warehouse_id, w.code, w.name, w.active, coalesce(s.available_quantity, 0) as available, "
                + "coalesce(s.reserved_quantity, 0) as reserved, coalesce(s.reorder_level, 0) as reorder_level from warehouses w "
                + "left join warehouse_stock s on s.warehouse_id = w.warehouse_id and s.sku = ? order by w.is_default desc, w.name",
                (rs, n) -> new Stock(rs.getLong("warehouse_id"), rs.getString("code"), rs.getString("name"), rs.getBoolean("active"),
                        rs.getInt("available"), rs.getInt("reserved"), rs.getInt("reorder_level")), sku);
    }

    private static final String SELECT = "select w.*, ST_Y(w.location::geometry) as latitude, ST_X(w.location::geometry) as longitude "
            + "from warehouses w ";

    private static final RowMapper<Warehouse> ROW = (rs, n) -> new Warehouse(rs.getLong("warehouse_id"), rs.getString("code"),
            rs.getString("name"), rs.getString("address"), rs.getString("pin"), rs.getString("state_code"), rs.getString("gstin"),
            rs.getDouble("latitude"), rs.getDouble("longitude"), rs.getObject("cutoff_time", LocalTime.class),
            rs.getBoolean("active"), rs.getBoolean("is_default"), rs.getString("invoice_prefix"),
            rs.getString("credit_note_prefix"));
}
