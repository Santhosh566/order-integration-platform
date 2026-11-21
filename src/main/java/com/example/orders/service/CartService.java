package com.example.orders.service;

import com.example.orders.exception.InvalidOrderException;
import com.example.orders.graphql.CatalogueModels.Product;
import com.example.orders.graphql.CatalogueSearch;
import com.example.orders.model.Order;
import com.example.orders.model.OrderItem;
import com.example.orders.money.Money;
import com.example.orders.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The signed-in customer's cart, kept on the server so it is the same on every device, and so the shop
 * can remind a customer who left it (CartReminderService).
 *
 * A cart is one row from the first item put in until it is ordered, emptied, or left so long it expires;
 * the next item starts a new one. Placing an order takes its products out of the cart in the order's own
 * transaction (orderPlaced). A cart that had been reminded about counts as RECOVERED by that order.
 */
@Service
public class CartService {

    private static final Logger log = LoggerFactory.getLogger(CartService.class);

    /** Lines in one cart; any real basket fits, a script cannot fill the table. */
    static final int MAX_LINES = 50;
    /** The most units of one product, as in the shop (cart.ts MAX_QUANTITY). */
    static final int MAX_QUANTITY = 20;

    public enum Status { ACTIVE, ORDERED, RECOVERED, EMPTIED, EXPIRED }

    /**
     * @param priceSeen the price the customer has now seen for it; kept only when it is today's catalogue price
     *                  (the cart page's "accept the new prices"). Orders are priced by the server regardless.
     */
    public record Line(String sku, Integer quantity, BigDecimal priceSeen) {}

    /**
     * One line as the shop shows it. product is null when it is no longer sold; name is kept so the cart can
     * say which.
     *
     * @param priceSeen   the price when it was put in the cart
     * @param priceDropped the price is lower now than when it was put in
     */
    public record CartLine(String sku, String name, int quantity, BigDecimal priceSeen, Product product,
                           boolean available, boolean priceDropped) {}

    /**
     * @param itemCount units in the cart, for the badge in the header
     * @param total     what the lines that can be bought cost now, GST included
     */
    public record Cart(List<CartLine> lines, int itemCount, BigDecimal total, LocalDateTime updatedAt) {
        static Cart empty() {
            return new Cart(List.of(), 0, Money.of(BigDecimal.ZERO), null);
        }

        Cart summed() {
            int count = lines.stream().mapToInt(CartLine::quantity).sum();
            BigDecimal sum = lines.stream().filter(CartLine::available)
                    .map(l -> BigDecimal.valueOf(l.product().price()).multiply(BigDecimal.valueOf(l.quantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return new Cart(lines, count, Money.of(sum), updatedAt);
        }
    }

    private final JdbcTemplate jdbc;
    private final CatalogueSearch catalogue;
    private final CurrentUser currentUser;

    public CartService(JdbcTemplate jdbc, CatalogueSearch catalogue, CurrentUser currentUser) {
        this.jdbc = jdbc;
        this.catalogue = catalogue;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public Cart mine() {
        return cartOf(customerId());
    }

    /**
     * Replaces what is in the cart; an empty list empties it. Products no longer sold are left out. A line that
     * was already there keeps the price it was put in at, so a later "cheaper now" is still told.
     */
    @Transactional
    public Cart replace(List<Line> lines) {
        String customer = customerId();
        Map<String, Integer> wanted = check(lines);
        Long cartId = activeCart(customer, !wanted.isEmpty());
        if (cartId == null) {
            return Cart.empty();
        }
        if (wanted.isEmpty()) {
            close(cartId, Status.EMPTIED, null);
            return Cart.empty();
        }
        Map<String, Integer> now = linesOf(cartId);
        if (!now.equals(wanted)) {
            write(cartId, now, wanted);
        }
        acceptPrices(cartId, lines);
        return cartOf(customer);
    }

    /** Lines whose priceSeen is today's catalogue price take it, so the cart stops saying the price changed. */
    private void acceptPrices(long cartId, List<Line> lines) {
        for (Line line : lines) {
            if (line != null && line.sku() != null && line.priceSeen() != null) {
                jdbc.update("update cart_items c set price_seen = i.unit_price from inventory i where c.cart_id = ? and c.sku = ? "
                        + "and i.sku = c.sku and i.unit_price = ? and c.price_seen <> i.unit_price",
                        cartId, line.sku().strip(), Money.of(line.priceSeen()));
            }
        }
    }

    /**
     * Adds the lines from a cart this browser kept before it was signed in or before carts moved to the server.
     * A product in both keeps the larger quantity, so merging twice changes nothing.
     */
    @Transactional
    public Cart merge(List<Line> lines) {
        String customer = customerId();
        Map<String, Integer> extra = check(lines);
        if (extra.isEmpty()) {
            return cartOf(customer);
        }
        Long cartId = activeCart(customer, true);
        Map<String, Integer> now = linesOf(cartId);
        Map<String, Integer> merged = new LinkedHashMap<>(now);
        extra.forEach((sku, quantity) -> merged.merge(sku, quantity, Math::max));
        if (merged.size() > MAX_LINES) {
            throw new InvalidOrderException("A cart holds at most " + MAX_LINES + " products");
        }
        if (!merged.equals(now)) {
            write(cartId, now, merged);
        }
        return cartOf(customer);
    }

    @Transactional
    public void clear() {
        Long cartId = activeCart(customerId(), false);
        if (cartId != null) {
            close(cartId, Status.EMPTIED, null);
        }
    }

    /**
     * An order was placed for the customer: its products leave the cart, in the order's transaction. When the
     * cart had been reminded about, it is closed as RECOVERED by this order and anything not ordered moves to a
     * new cart; otherwise the cart is ORDERED once nothing is left in it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void orderPlaced(Order order) {
        if (order.getCustomerId() == null || order.getItems() == null || order.getItems().isEmpty()) {
            return;
        }
        List<Long> found = jdbc.queryForList("select cart_id from carts where customer_id = ? and status = 'ACTIVE' for update",
                Long.class, order.getCustomerId());
        if (found.isEmpty()) {
            return;
        }
        long cartId = found.get(0);
        Set<String> ordered = order.getItems().stream().map(OrderItem::getSku).collect(Collectors.toSet());
        Map<String, Integer> lines = linesOf(cartId);
        if (lines.keySet().stream().noneMatch(ordered::contains)) {
            return;
        }
        Integer reminded = jdbc.queryForObject("select reminders_sent from carts where cart_id = ?", Integer.class, cartId);
        List<String> left = lines.keySet().stream().filter(sku -> !ordered.contains(sku)).toList();
        if (reminded != null && reminded > 0) {
            close(cartId, Status.RECOVERED, order.getOrderId());
            if (!left.isEmpty()) {
                Long next = jdbc.queryForObject("insert into carts (customer_id) values (?) returning cart_id", Long.class,
                        order.getCustomerId());
                for (String sku : left) {
                    jdbc.update("update cart_items set cart_id = ? where cart_id = ? and sku = ?", next, cartId, sku);
                }
            }
            log.info("[CART] Cart {} of {} recovered by order {} after {} reminder(s)", cartId, order.getCustomerId(),
                    order.getOrderId(), reminded);
            return;
        }
        if (left.isEmpty()) {
            close(cartId, Status.ORDERED, order.getOrderId());
            return;
        }
        for (String sku : ordered) {
            jdbc.update("delete from cart_items where cart_id = ? and sku = ?", cartId, sku);
        }
        // The rest waits again from now, like a cart the customer has just changed
        jdbc.update("update carts set updated_at = now() where cart_id = ?", cartId);
    }

    // ── Shared ─────────────────────────────────────────────────────────────

    /** The customer's open cart with today's prices and stock. */
    Cart cartOf(String customer) {
        List<Long> found = jdbc.queryForList("select cart_id from carts where customer_id = ? and status = 'ACTIVE'",
                Long.class, customer);
        if (found.isEmpty()) {
            return Cart.empty();
        }
        long cartId = found.get(0);
        LocalDateTime updatedAt = jdbc.queryForObject("select updated_at from carts where cart_id = ?", LocalDateTime.class, cartId);
        return new Cart(lines(cartId), 0, BigDecimal.ZERO, updatedAt).summed();
    }

    /** The lines of any cart, oldest first, priced from the catalogue now. */
    List<CartLine> lines(long cartId) {
        List<Object[]> rows = jdbc.query("select c.sku, i.product_name, c.quantity, c.price_seen from cart_items c "
                + "join inventory i on i.sku = c.sku where c.cart_id = ? order by c.added_at, c.sku",
                (rs, n) -> new Object[] {rs.getString("sku"), rs.getString("product_name"), rs.getInt("quantity"),
                        rs.getBigDecimal("price_seen")}, cartId);
        Map<String, Product> products = catalogue.products(rows.stream().map(r -> (String) r[0]).toList());
        List<CartLine> lines = new ArrayList<>();
        for (Object[] r : rows) {
            String sku = (String) r[0];
            Product product = products.get(sku);
            BigDecimal seen = (BigDecimal) r[3];
            boolean available = product != null && !"OUT_OF_STOCK".equals(product.stockStatus());
            boolean dropped = product != null && Money.of(BigDecimal.valueOf(product.price())).compareTo(seen) < 0;
            lines.add(new CartLine(sku, r[1] != null ? (String) r[1] : sku, (int) r[2], seen, product, available, dropped));
        }
        return lines;
    }

    /** One SKU per line, quantities 1 to 20, at most 50 lines, only products on sale. */
    private Map<String, Integer> check(List<Line> lines) {
        Map<String, Integer> wanted = new LinkedHashMap<>();
        if (lines == null) {
            return wanted;
        }
        for (Line line : lines) {
            if (line == null || line.sku() == null || line.sku().isBlank()) {
                throw new InvalidOrderException("Each cart line needs a product");
            }
            int quantity = line.quantity() == null ? 1 : line.quantity();
            if (quantity < 1) {
                throw new InvalidOrderException("Quantities start at 1; remove the line instead");
            }
            wanted.merge(line.sku().strip(), Math.min(quantity, MAX_QUANTITY), (a, b) -> Math.min(a + b, MAX_QUANTITY));
        }
        if (wanted.size() > MAX_LINES) {
            throw new InvalidOrderException("A cart holds at most " + MAX_LINES + " products");
        }
        Map<String, Product> onSale = catalogue.products(wanted.keySet());
        wanted.keySet().retainAll(onSale.keySet());
        // The shop's own limit for one order, where a product has one
        wanted.replaceAll((sku, quantity) -> {
            Integer max = onSale.get(sku).maxPerOrder();
            return max != null && max > 0 ? Math.min(quantity, max) : quantity;
        });
        return wanted;
    }

    /**
     * The customer's open cart, locked until the transaction ends; created when create is true, else null if
     * there is none. Two requests at once both end up with the same cart (carts_one_active).
     */
    private Long activeCart(String customer, boolean create) {
        if (create) {
            jdbc.update("insert into carts (customer_id) values (?) on conflict (customer_id) where status = 'ACTIVE' do nothing",
                    customer);
        }
        List<Long> found = jdbc.queryForList("select cart_id from carts where customer_id = ? and status = 'ACTIVE' for update",
                Long.class, customer);
        return found.isEmpty() ? null : found.get(0);
    }

    private Map<String, Integer> linesOf(long cartId) {
        Map<String, Integer> lines = new LinkedHashMap<>();
        jdbc.query("select sku, quantity from cart_items where cart_id = ? order by added_at, sku",
                rs -> { lines.put(rs.getString("sku"), rs.getInt("quantity")); }, cartId);
        return lines;
    }

    private void write(long cartId, Map<String, Integer> before, Map<String, Integer> after) {
        for (String sku : before.keySet()) {
            if (!after.containsKey(sku)) {
                jdbc.update("delete from cart_items where cart_id = ? and sku = ?", cartId, sku);
            }
        }
        for (Map.Entry<String, Integer> e : after.entrySet()) {
            if (before.containsKey(e.getKey())) {
                jdbc.update("update cart_items set quantity = ? where cart_id = ? and sku = ?", e.getValue(), cartId, e.getKey());
            } else {
                jdbc.update("insert into cart_items (cart_id, sku, quantity, price_seen) "
                        + "select ?, sku, ?, coalesce(unit_price, 0) from inventory where sku = ?", cartId, e.getValue(), e.getKey());
            }
        }
        jdbc.update("update carts set updated_at = now() where cart_id = ?", cartId);
    }

    private void close(long cartId, Status status, String orderId) {
        jdbc.update("update carts set status = ?, closed_at = now(), order_id = ? where cart_id = ?", status.name(), orderId, cartId);
    }

    private String customerId() {
        return currentUser.require().userId();
    }
}
