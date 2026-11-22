package com.example.orders.warehouse;

import com.example.orders.model.Order;
import com.example.orders.model.OrderItem;
import com.example.orders.money.IndianState;
import com.example.orders.service.GstService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Which warehouse an order ships from: the nearest active one that holds every line, so the order travels as one
 * parcel with one tax invoice. "Nearest" is PostGIS's distance on the globe from the warehouse to the centre of the
 * delivery PIN code; without a centre, a warehouse in the delivery state comes first, then the default one.
 *
 * Orders are never split between warehouses. When the shop has enough of everything but no single warehouse does,
 * the customer is asked to order the items separately (problem), rather than finding out after paying.
 *
 * Reading stock here takes no locks: a quote and the placing of an order only choose. InventoryService.reserve
 * chooses again under the products' row locks, which every stock change takes first, so what it reads there holds.
 */
@Service
public class WarehouseAllocator {

    /**
     * @param warehouse the nearest warehouse with everything, or null when none has
     * @param problem   why the order cannot ship together though the shop has enough, or null
     */
    public record Choice(WarehouseService.Warehouse warehouse, String problem) {}

    private final JdbcTemplate jdbc;
    private final WarehouseService warehouses;
    private final GstService gst;

    public WarehouseAllocator(JdbcTemplate jdbc, WarehouseService warehouses, GstService gst) {
        this.jdbc = jdbc;
        this.warehouses = warehouses;
        this.gst = gst;
    }

    /** The nearest active warehouse that holds every line of the order now, if any. */
    public Optional<Long> nearestWithAll(Order order) {
        Map<String, Integer> units = units(order);
        if (units.isEmpty()) {
            return Optional.empty();
        }
        String pin = order.getShippingAddress() == null ? null : order.getShippingAddress().getPostalCode();
        String state = deliveryState(order).map(IndianState::gstCode).orElse("");
        List<Long> found = jdbc.queryForList("select w.warehouse_id from warehouses w left join pin_codes p on p.pin = ? "
                + "where w.active and not exists (select 1 from unnest(?::varchar[], ?::int[]) as want(sku, qty) "
                + "  where coalesce((select s.available_quantity from warehouse_stock s "
                + "                  where s.warehouse_id = w.warehouse_id and s.sku = want.sku), 0) < want.qty) "
                + "order by ST_Distance(w.location, p.centre) nulls last, (w.state_code = ?) desc, w.is_default desc, w.warehouse_id "
                + "limit 1", Long.class, pin, array(units.keySet()), array(units.values()), state);
        return found.stream().findFirst();
    }

    /**
     * The nearest active warehouse with this product in stock, for the product page's delivery date: its name, its
     * cut-off time and its distance to the centre of the PIN code (null when the PIN has no position).
     */
    public Optional<com.example.orders.delivery.DeliveryEstimator.ShipFrom> nearestHolding(String sku, String pin) {
        return jdbc.query("select w.name, w.cutoff_time, ST_Distance(w.location, p.centre) / 1000 as km from warehouses w "
                + "join warehouse_stock s on s.warehouse_id = w.warehouse_id and s.sku = ? and s.available_quantity > 0 "
                + "left join pin_codes p on p.pin = ? where w.active "
                + "order by ST_Distance(w.location, p.centre) nulls last, w.is_default desc, w.warehouse_id limit 1",
                (rs, n) -> new com.example.orders.delivery.DeliveryEstimator.ShipFrom(rs.getString("name"),
                        rs.getObject("cutoff_time", java.time.LocalTime.class), (Double) rs.getObject("km")),
                sku, pin).stream().findFirst();
    }

    /** For a quote or a new order: the warehouse to ship from, or why the items cannot ship together. */
    public Choice choose(Order order) {
        Optional<Long> best = nearestWithAll(order);
        if (best.isPresent()) {
            return new Choice(warehouses.get(best.get()), null);
        }
        return new Choice(null, splitProblem(order));
    }

    /**
     * Sets the order's warehouse and works its GST out from that warehouse's registration: CGST + SGST when it
     * delivers in the warehouse's own state, IGST otherwise. Without a warehouse (nothing in stock), the default one.
     */
    public void shipFrom(Order order, Long warehouseId) {
        WarehouseService.Warehouse from = warehouses.get(warehouseId != null ? warehouseId : warehouses.defaultId());
        order.setWarehouseId(from.id());
        gst.apply(order, from.state(), from.gstin());
    }

    /**
     * When every line is in stock somewhere but not all in one warehouse: which lines are missing from the warehouse
     * that holds the most of them. Null when the shop as a whole lacks something (then it is simply out of stock).
     */
    String splitProblem(Order order) {
        Map<String, Integer> units = units(order);
        if (units.isEmpty()) {
            return null;
        }
        Map<String, Integer> total = new TreeMap<>();
        jdbc.query("select s.sku, sum(s.available_quantity)::int as n from warehouse_stock s join warehouses w using (warehouse_id) "
                + "where w.active and s.sku = any(?::varchar[]) group by s.sku", rs -> {
            total.put(rs.getString("sku"), rs.getInt("n"));
        }, array(units.keySet()));
        if (units.entrySet().stream().anyMatch(e -> total.getOrDefault(e.getKey(), 0) < e.getValue())) {
            return null;
        }
        // The warehouse holding the most lines, and what it lacks
        List<String> missing = new ArrayList<>();
        Long most = jdbc.queryForList("select w.warehouse_id from warehouses w where w.active order by "
                + "(select count(*) from unnest(?::varchar[], ?::int[]) as want(sku, qty) join warehouse_stock s "
                + " on s.warehouse_id = w.warehouse_id and s.sku = want.sku and s.available_quantity >= want.qty) desc, "
                + "w.is_default desc, w.warehouse_id limit 1", Long.class, array(units.keySet()), array(units.values()))
                .stream().findFirst().orElse(null);
        if (most != null) {
            units.forEach((sku, qty) -> {
                Integer there = jdbc.queryForList("select available_quantity from warehouse_stock where warehouse_id = ? and sku = ?",
                        Integer.class, most, sku).stream().findFirst().orElse(0);
                if (there < qty) {
                    missing.add(nameOf(order, sku));
                }
            });
        }
        return "These items are kept in different warehouses and cannot come in one parcel: "
                + String.join(", ", missing) + ". Please order them separately.";
    }

    private static Map<String, Integer> units(Order order) {
        Map<String, Integer> units = new TreeMap<>();
        if (order.getItems() != null) {
            for (OrderItem item : order.getItems()) {
                int active = item.getActiveQuantity();
                if (item.getSku() != null && active > 0) {
                    units.merge(item.getSku(), active, Integer::sum);
                }
            }
        }
        return units;
    }

    private static Optional<IndianState> deliveryState(Order order) {
        try {
            return order.getShippingAddress() == null ? Optional.empty()
                    : Optional.of(GstService.placeOfSupply(order.getShippingAddress()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String nameOf(Order order, String sku) {
        return order.getItems().stream().filter(i -> sku.equals(i.getSku()) && i.getProductName() != null)
                .map(OrderItem::getProductName).findFirst().orElse(sku);
    }

    /** A Postgres array literal: {"a","b"} with quotes and backslashes escaped. */
    static String array(java.util.Collection<?> values) {
        return values.stream().map(v -> "\"" + v.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(",", "{", "}"));
    }
}
