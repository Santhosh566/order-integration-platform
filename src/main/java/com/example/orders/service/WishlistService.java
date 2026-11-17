package com.example.orders.service;

import com.example.orders.exception.InvalidOrderException;
import com.example.orders.exception.ProductNotFoundException;
import com.example.orders.graphql.CatalogueModels.Product;
import com.example.orders.graphql.CatalogueSearch;
import com.example.orders.model.CustomerNotification;
import com.example.orders.model.InventoryItem;
import com.example.orders.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A customer's saved products, and "email me when it is back in stock".
 *
 * The email is queued in the transaction that brings the stock back (StockLedger), through the
 * customer-notification outbox, so it goes out once the stock change is committed and never for a
 * change that rolled back. Each request is answered once: the customer asks again to hear again.
 */
@Service
public class WishlistService {

    private static final Logger log = LoggerFactory.getLogger(WishlistService.class);

    /** Enough for any real shopper; stops a script from filling the table. */
    static final int MAX_ITEMS = 200;

    /**
     * A saved product. product is null when it is no longer sold; name is kept so the list can say which.
     *
     * @param notifiedAt when the back-in-stock email was queued, or null
     */
    public record Entry(String sku, String name, Product product, LocalDateTime addedAt,
                        boolean notifyWhenAvailable, LocalDateTime notifiedAt) {
        /** Waiting for an email that has not been sent yet. */
        public boolean waiting() {
            return notifyWhenAvailable && notifiedAt == null;
        }
    }

    /** @param notifyWhenAvailable true asks for the email (again), false cancels it, null leaves it */
    public record Save(Boolean notifyWhenAvailable) {}

    private final JdbcTemplate jdbc;
    private final CatalogueSearch catalogue;
    private final CurrentUser currentUser;
    private final CustomerNotificationService notifications;
    private final String shopUrl;

    public WishlistService(JdbcTemplate jdbc, CatalogueSearch catalogue, CurrentUser currentUser,
                           CustomerNotificationService notifications,
                           @Value("${orders.shop-url:http://localhost:5173}") String shopUrl) {
        this.jdbc = jdbc;
        this.catalogue = catalogue;
        this.currentUser = currentUser;
        this.notifications = notifications;
        this.shopUrl = shopUrl.replaceAll("/+$", "");
    }

    /** The signed-in customer's wishlist, newest first. */
    @Transactional(readOnly = true)
    public List<Entry> list() {
        String customer = customerId();
        List<Row> rows = jdbc.query("select w.sku, i.product_name, w.created_at, w.notify_when_available, w.notified_at "
                + "from wishlist_items w join inventory i on i.sku = w.sku where w.customer_id = ? "
                + "order by w.created_at desc, w.sku", (rs, n) -> new Row(rs.getString("sku"), rs.getString("product_name"),
                rs.getTimestamp("created_at").toLocalDateTime(), rs.getBoolean("notify_when_available"),
                rs.getTimestamp("notified_at") == null ? null : rs.getTimestamp("notified_at").toLocalDateTime()), customer);
        var products = catalogue.products(rows.stream().map(Row::sku).toList());
        return rows.stream().map(r -> new Entry(r.sku(), r.name(), products.get(r.sku()), r.addedAt(),
                r.wantsEmail(), r.notifiedAt())).toList();
    }

    /**
     * Saves a product, or changes the back-in-stock request of a saved one. Saving twice is harmless.
     */
    @Transactional
    public Entry save(String sku, Save request) {
        String customer = customerId();
        Product product = catalogue.product(sku);
        if (product == null) {
            throw new ProductNotFoundException(sku);
        }
        Boolean notify = request == null ? null : request.notifyWhenAvailable();
        Integer saved = jdbc.queryForObject("select count(*) from wishlist_items where customer_id = ?", Integer.class, customer);
        boolean known = Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from wishlist_items where customer_id = ? and sku = ?)", Boolean.class, customer, sku));
        if (!known && saved != null && saved >= MAX_ITEMS) {
            throw new InvalidOrderException("Your wishlist is full (" + MAX_ITEMS + " products). Remove some to save more.");
        }
        // Asking again re-arms a request that was already answered
        jdbc.update("insert into wishlist_items (customer_id, sku, notify_when_available) values (?, ?, coalesce(?::boolean, false)) "
                + "on conflict (customer_id, sku) do update set "
                + "notify_when_available = coalesce(?::boolean, wishlist_items.notify_when_available), "
                + "notified_at = case when ?::boolean then null else wishlist_items.notified_at end",
                customer, sku, notify, notify, Boolean.TRUE.equals(notify));
        return list().stream().filter(e -> e.sku().equals(sku)).findFirst().orElseThrow();
    }

    @Transactional
    public void remove(String sku) {
        jdbc.update("delete from wishlist_items where customer_id = ? and sku = ?", customerId(), sku);
    }

    /**
     * The product can be bought again (stock came back, or it was put back on sale): emails everyone
     * still waiting for it, in the caller's transaction, and marks their requests answered.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int productAvailable(InventoryItem item) {
        if (!item.isActive() || item.getUnitPrice() == null || item.getAvailableQuantity() <= 0) {
            return 0;
        }
        List<String> customers = jdbc.queryForList("update wishlist_items set notified_at = now() "
                + "where sku = ? and notify_when_available and notified_at is null returning customer_id",
                String.class, item.getSku());
        String name = item.getProductName() != null ? item.getProductName() : item.getSku();
        String link = shopUrl + "/shop/" + URLEncoder.encode(item.getSku(), StandardCharsets.UTF_8).replace("+", "%20");
        for (String customer : customers) {
            notifications.queue(new CustomerNotification(null, customer, "Back in stock: " + name,
                    "Good news: " + name + " is back in stock. Stock may be limited, so order soon:\n" + link
                            + "\n\nYou asked us to tell you when it was back. We will not email you about it again "
                            + "unless you ask on the product page."));
        }
        if (!customers.isEmpty()) {
            log.info("[WISHLIST] {} is available again; {} customer(s) will be emailed", item.getSku(), customers.size());
        }
        return customers.size();
    }

    private String customerId() {
        return currentUser.require().userId();
    }

    private record Row(String sku, String name, LocalDateTime addedAt, boolean wantsEmail, LocalDateTime notifiedAt) {}
}
