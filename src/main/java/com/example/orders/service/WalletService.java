package com.example.orders.service;

import com.example.orders.exception.InvalidOrderException;
import com.example.orders.exception.NotFoundException;
import com.example.orders.model.Order;
import com.example.orders.money.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A customer's store credit. Money comes in from gift cards, from refunds of orders paid with it and from
 * admins (goodwill), and goes out only to pay for orders. It never expires and is never paid out as cash.
 * <p>
 * Every change locks the wallet's row, checks the balance and writes a history row with the balance after
 * it, all in the caller's transaction: a balance can neither go below zero nor drift from its history.
 */
@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    public enum Kind { GIFT_CARD, ORDER_PAYMENT, ORDER_REFUND, ADMIN_CREDIT, ADMIN_DEBIT }

    /** The largest single credit an admin may give, so a typo cannot hand out lakhs. */
    static final BigDecimal MAX_ADMIN_CREDIT = Money.of(25_000);

    public record Entry(long id, Kind kind, BigDecimal amount, BigDecimal balanceAfter, String reference,
                        String orderId, String giftCardLastFour, String note, String createdBy,
                        LocalDateTime createdAt) {}

    /** @param available what a new order can use: the balance less what orders being paid will take */
    public record Wallet(String customerId, BigDecimal balance, BigDecimal available, String currency, List<Entry> history) {}

    /** What an admin sends to add or take back store credit. */
    public record Adjustment(BigDecimal amount, String note) {}

    private static final RowMapper<Entry> ENTRY = (rs, i) -> new Entry(
            rs.getLong("wallet_txn_id"), Kind.valueOf(rs.getString("kind")), Money.of(rs.getBigDecimal("amount")),
            Money.of(rs.getBigDecimal("balance_after")), rs.getString("reference"), rs.getString("order_id"),
            rs.getString("last_four"), rs.getString("note"), rs.getString("created_by"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private final JdbcTemplate jdbc;

    public WalletService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** What the customer has now. */
    public BigDecimal balance(String customerId) {
        List<BigDecimal> found = jdbc.queryForList("select balance from wallets where customer_id = ?",
                BigDecimal.class, customerId);
        return found.isEmpty() ? Money.ZERO : Money.of(found.get(0));
    }

    /**
     * What a new order can take: the balance less what orders placed but not paid yet will take when they
     * are paid. Spending it twice is stopped here, and once more under the lock when the order is paid.
     */
    public BigDecimal available(String customerId) {
        BigDecimal held = jdbc.queryForObject("""
                select coalesce(sum(wallet_amount), 0) from orders
                where customer_id = ? and wallet_amount > 0 and payment_transaction_id is null
                and status in ('PENDING', 'INVENTORY_RESERVED') and deleted_at is null""", BigDecimal.class, customerId);
        return Money.of(balance(customerId).subtract(held).max(BigDecimal.ZERO));
    }

    /**
     * As an order is placed: the wallet pays as much of it as is available, the card the rest. The wallet's
     * row stays locked until the order is saved, so two orders placed at once cannot both count the same money.
     * The money itself is taken when the order is paid (PaymentGatewayService.recordWalletPayment).
     */
    public void applyToOrder(Order order) {
        order.setWalletAmount(BigDecimal.ZERO);
        if (!order.isWalletRequested()) {
            return;
        }
        if (order.isCashOnDelivery()) {
            throw new InvalidOrderException("Wallet balance can be used with online payment only, not with cash on delivery");
        }
        jdbc.update("insert into wallets (customer_id) values (?) on conflict (customer_id) do nothing", order.getCustomerId());
        jdbc.queryForList("select customer_id from wallets where customer_id = ? for update", String.class, order.getCustomerId());
        BigDecimal available = available(order.getCustomerId());
        if (available.signum() == 0) {
            throw new InvalidOrderException("Your wallet is empty. Pay by card, or add a gift card first.");
        }
        order.setWalletAmount(available.min(order.getTotal()));
    }

    /** The balance and the latest changes, newest first. */
    @Transactional(readOnly = true)
    public Wallet wallet(String customerId, int limit) {
        List<Entry> history = jdbc.query("""
                select t.*, g.last_four from wallet_transactions t
                left join gift_cards g on g.gift_card_id = t.gift_card_id
                where t.customer_id = ? order by t.created_at desc, t.wallet_txn_id desc limit ?""",
                ENTRY, customerId, Math.max(1, Math.min(limit, 200)));
        return new Wallet(customerId, balance(customerId), available(customerId), "INR", history);
    }

    /**
     * Adds money, in the caller's transaction, which must also hold whatever this credit is for (the gift
     * card's row, the order's row), so it is given once. Not @Transactional on purpose: a refusal here must
     * not mark the caller's transaction for rollback, so a caller can catch it and carry on.
     */
    public Entry credit(String customerId, Kind kind, BigDecimal amount, String orderId, Long giftCardId,
                        String note, String by) {
        if (!Money.isPositive(amount)) {
            throw new InvalidOrderException("The amount must be more than zero");
        }
        return change(customerId, kind, Money.of(amount), orderId, giftCardId, note, by);
    }

    /**
     * Takes money, in the caller's transaction (see credit).
     *
     * @throws InsufficientBalanceException when the wallet holds less than the amount; nothing is changed
     */
    public Entry debit(String customerId, Kind kind, BigDecimal amount, String orderId, String note, String by) {
        if (!Money.isPositive(amount)) {
            throw new InvalidOrderException("The amount must be more than zero");
        }
        return change(customerId, kind, Money.of(amount).negate(), orderId, null, note, by);
    }

    /** An admin gives store credit, as goodwill or to put right a mistake. */
    @Transactional
    public Entry adminCredit(String customerId, Adjustment adjustment, String admin) {
        requireCustomer(customerId);
        BigDecimal amount = checkAdjustment(adjustment);
        if (amount.compareTo(MAX_ADMIN_CREDIT) > 0) {
            throw new InvalidOrderException("An admin credit can be at most " + Money.format(MAX_ADMIN_CREDIT, "INR"));
        }
        Entry entry = credit(customerId, Kind.ADMIN_CREDIT, amount, null, null, adjustment.note().strip(), admin);
        log.info("[WALLET] {} credited {} to {}: {}", admin, amount, customerId, adjustment.note().strip());
        return entry;
    }

    /** An admin takes back store credit given by mistake; never more than is left. */
    @Transactional
    public Entry adminDebit(String customerId, Adjustment adjustment, String admin) {
        requireCustomer(customerId);
        BigDecimal amount = checkAdjustment(adjustment);
        Entry entry = debit(customerId, Kind.ADMIN_DEBIT, amount, null, adjustment.note().strip(), admin);
        log.info("[WALLET] {} took {} back from {}: {}", admin, amount, customerId, adjustment.note().strip());
        return entry;
    }

    private Entry change(String customerId, Kind kind, BigDecimal amount, String orderId, Long giftCardId,
                         String note, String by) {
        jdbc.update("insert into wallets (customer_id) values (?) on conflict (customer_id) do nothing", customerId);
        BigDecimal balance = jdbc.queryForObject("select balance from wallets where customer_id = ? for update",
                BigDecimal.class, customerId);
        BigDecimal after = Money.of(balance.add(amount));
        if (after.signum() < 0) {
            throw new InsufficientBalanceException(Money.of(balance), amount.negate());
        }
        jdbc.update("update wallets set balance = ?, updated_at = now() where customer_id = ?", after, customerId);
        String reference = "WAL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        Long id = jdbc.queryForObject("""
                insert into wallet_transactions (customer_id, kind, amount, balance_after, reference, order_id,
                    gift_card_id, note, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?) returning wallet_txn_id""",
                Long.class, customerId, kind.name(), amount, after, reference, orderId, giftCardId, note, by);
        return new Entry(id, kind, amount, after, reference, orderId, null, note, by, LocalDateTime.now());
    }

    private void requireCustomer(String customerId) {
        Integer customers = jdbc.queryForObject("select count(*) from app_users where user_id = ?", Integer.class, customerId);
        if (customers == null || customers == 0) {
            throw new NotFoundException("User " + customerId + " not found");
        }
    }

    private static BigDecimal checkAdjustment(Adjustment adjustment) {
        if (adjustment == null || !Money.isPositive(adjustment.amount())) {
            throw new InvalidOrderException("The amount must be more than zero");
        }
        if (adjustment.amount().stripTrailingZeros().scale() > 2) {
            throw new InvalidOrderException("The amount can have at most 2 decimals (paise)");
        }
        if (adjustment.note() == null || adjustment.note().isBlank()) {
            throw new InvalidOrderException("Say why, for the customer's history");
        }
        if (adjustment.note().strip().length() > 200) {
            throw new InvalidOrderException("The note can be at most 200 characters");
        }
        return Money.of(adjustment.amount());
    }

    /** The wallet holds less than was to be taken. Answered with 400. */
    public static class InsufficientBalanceException extends InvalidOrderException {
        private final BigDecimal balance;

        public InsufficientBalanceException(BigDecimal balance, BigDecimal wanted) {
            super("The wallet has " + Money.format(balance, "INR") + ", less than the "
                    + Money.format(wanted, "INR") + " needed");
            this.balance = balance;
        }

        public BigDecimal balance() {
            return balance;
        }
    }
}
