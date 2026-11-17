package com.example.orders.service;

import com.example.orders.exception.InvalidOrderException;
import com.example.orders.exception.InvalidOrderStateException;
import com.example.orders.exception.NotFoundException;
import com.example.orders.exception.TooManyAttemptsException;
import com.example.orders.money.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Gift cards: admins issue them in batches, and a customer redeems a card's whole value into their wallet.
 * <p>
 * A code is money to whoever holds it, so only its SHA-256 is stored and the full codes are shown once,
 * when the batch is issued. Codes are 16 characters from 31 that cannot be mistaken for each other
 * (79 random bits), and a customer who enters wrong codes too often is stopped for a while.
 */
@Service
public class GiftCardService {

    private static final Logger log = LoggerFactory.getLogger(GiftCardService.class);

    // No 0/O or 1/I/L, which people misread on a printed card
    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CODE_LENGTH = 16;
    static final BigDecimal MIN_AMOUNT = Money.of(10);
    static final BigDecimal MAX_AMOUNT = Money.of(10_000);
    static final int MAX_BATCH = 500;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    public enum Status { ACTIVE, REDEEMED, VOID, EXPIRED }

    /** What an admin sends to issue cards. expiresAt defaults to a year from now. */
    public record NewBatch(BigDecimal amount, Integer count, LocalDateTime expiresAt, String label) {}

    /** A card as admins see it later: only the last four characters of the code. */
    public record Card(long id, String maskedCode, BigDecimal amount, String currency, String label,
                       LocalDateTime expiresAt, Status status, String redeemedBy, String redeemedByName,
                       LocalDateTime redeemedAt, LocalDateTime voidedAt, String voidedBy, String voidReason,
                       LocalDateTime createdAt, String createdBy) {}

    /** A card just issued, with its full code. Shown once. */
    public record IssuedCard(long id, String code, BigDecimal amount, LocalDateTime expiresAt) {}

    public record Batch(String label, BigDecimal amount, LocalDateTime expiresAt, List<IssuedCard> cards) {}

    /** A card redeemed into the wallet, and what the wallet holds now. */
    public record Redeemed(BigDecimal amount, String lastFour, BigDecimal balance, WalletService.Entry entry) {}

    private static final RowMapper<Card> CARD = (rs, i) -> {
        Status stored = Status.valueOf(rs.getString("status"));
        LocalDateTime expires = rs.getTimestamp("expires_at").toLocalDateTime();
        Status status = stored == Status.ACTIVE && !LocalDateTime.now().isBefore(expires) ? Status.EXPIRED : stored;
        return new Card(rs.getLong("gift_card_id"), mask(rs.getString("last_four")), Money.of(rs.getBigDecimal("amount")),
                rs.getString("currency"), rs.getString("batch_label"), expires, status, rs.getString("redeemed_by"),
                rs.getString("redeemed_by_name"), time(rs.getTimestamp("redeemed_at")), time(rs.getTimestamp("voided_at")),
                rs.getString("voided_by"), rs.getString("void_reason"), time(rs.getTimestamp("created_at")),
                rs.getString("created_by"));
    };

    private static final String SELECT = """
            select g.*, u.full_name as redeemed_by_name from gift_cards g
            left join app_users u on u.user_id = g.redeemed_by
            """;

    private final JdbcTemplate jdbc;
    private final WalletService wallets;
    private final TransactionTemplate transaction;
    private final SecureRandom random = new SecureRandom();
    private final int maxFailures;
    private final int failureWindowMinutes;

    public GiftCardService(JdbcTemplate jdbc, WalletService wallets, PlatformTransactionManager transactions,
                           @Value("${orders.gift-cards.max-failed-attempts:5}") int maxFailures,
                           @Value("${orders.gift-cards.failed-attempts-window-minutes:60}") int failureWindowMinutes) {
        this.jdbc = jdbc;
        this.wallets = wallets;
        this.transaction = new TransactionTemplate(transactions);
        this.maxFailures = maxFailures;
        this.failureWindowMinutes = failureWindowMinutes;
    }

    // ── Admins ─────────────────────────────────────────────────────────────

    @Transactional
    public Batch issue(NewBatch request, String admin) {
        if (request == null || request.amount() == null) {
            throw new InvalidOrderException("Give each card's value");
        }
        if (request.amount().stripTrailingZeros().scale() > 0) {
            throw new InvalidOrderException("A gift card's value is a whole number of rupees");
        }
        BigDecimal amount = Money.of(request.amount());
        if (amount.compareTo(MIN_AMOUNT) < 0 || amount.compareTo(MAX_AMOUNT) > 0) {
            throw new InvalidOrderException("A gift card is worth " + Money.format(MIN_AMOUNT, "INR") + " to "
                    + Money.format(MAX_AMOUNT, "INR"));
        }
        int count = request.count() == null ? 1 : request.count();
        if (count < 1 || count > MAX_BATCH) {
            throw new InvalidOrderException("Issue 1 to " + MAX_BATCH + " cards at a time");
        }
        LocalDateTime expires = request.expiresAt() == null ? LocalDateTime.now().plusYears(1) : request.expiresAt();
        if (!expires.isAfter(LocalDateTime.now().plusDays(1))) {
            throw new InvalidOrderException("The cards must stay valid for at least a day");
        }
        if (expires.isAfter(LocalDateTime.now().plusYears(3))) {
            throw new InvalidOrderException("The cards can be valid for at most 3 years");
        }
        String label = request.label() == null || request.label().isBlank() ? null : request.label().strip();
        if (label != null && label.length() > 80) {
            throw new InvalidOrderException("The batch name can be at most 80 characters");
        }

        List<IssuedCard> cards = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String code = newCode();
            Long id = jdbc.queryForObject("""
                    insert into gift_cards (code_hash, last_four, amount, batch_label, expires_at, created_by)
                    values (?, ?, ?, ?, ?, ?) returning gift_card_id""",
                    Long.class, hash(code), code.substring(CODE_LENGTH - 4), amount, label, expires, admin);
            cards.add(new IssuedCard(id, format(code), amount, expires));
        }
        log.info("[GIFT CARDS] {} issued {} card(s) of {} INR{}, valid until {}", admin, count, amount,
                label == null ? "" : " (" + label + ")", expires);
        return new Batch(label, amount, expires, cards);
    }

    @Transactional(readOnly = true)
    public List<Card> cards(Status status, int limit) {
        int max = Math.max(1, Math.min(limit, 1000));
        if (status == null) {
            return jdbc.query(SELECT + "order by g.created_at desc, g.gift_card_id desc limit ?", CARD, max);
        }
        String where = switch (status) {
            case ACTIVE -> "g.status = 'ACTIVE' and g.expires_at > now()";
            case EXPIRED -> "g.status = 'ACTIVE' and g.expires_at <= now()";
            case REDEEMED -> "g.status = 'REDEEMED'";
            case VOID -> "g.status = 'VOID'";
        };
        return jdbc.query(SELECT + "where " + where + " order by g.created_at desc, g.gift_card_id desc limit ?", CARD, max);
    }

    /** Stops a card that was lost or issued by mistake. A redeemed card's money is already in a wallet. */
    @Transactional
    public Card voidCard(long id, String reason, String admin) {
        if (reason == null || reason.isBlank()) {
            throw new InvalidOrderException("Say why the card is stopped");
        }
        if (reason.strip().length() > 200) {
            throw new InvalidOrderException("The reason can be at most 200 characters");
        }
        List<String> found = jdbc.queryForList("select status from gift_cards where gift_card_id = ? for update",
                String.class, id);
        if (found.isEmpty()) {
            throw new NotFoundException("Gift card " + id + " not found");
        }
        switch (Status.valueOf(found.get(0))) {
            case REDEEMED -> throw new InvalidOrderStateException("This card was already redeemed into a customer's wallet. "
                    + "To take the money back, take it from their wallet.");
            case VOID -> throw new InvalidOrderStateException("This card is already stopped");
            default -> { }
        }
        jdbc.update("update gift_cards set status = 'VOID', voided_at = now(), voided_by = ?, void_reason = ? "
                + "where gift_card_id = ?", admin, reason.strip(), id);
        log.info("[GIFT CARDS] {} stopped card {}: {}", admin, id, reason.strip());
        return jdbc.query(SELECT + "where g.gift_card_id = ?", CARD, id).get(0);
    }

    // ── Customers ──────────────────────────────────────────────────────────

    /**
     * Adds the card's value to the customer's wallet. Each try is recorded on its own, so a failed one
     * still counts towards the limit.
     *
     * @throws TooManyAttemptsException after too many wrong codes in the last hour
     */
    public Redeemed redeem(String customerId, String rawCode) {
        Integer failures = jdbc.queryForObject("""
                select count(*) from gift_card_attempts where customer_id = ? and not success
                and created_at > now() - make_interval(mins => ?)""", Integer.class, customerId, failureWindowMinutes);
        if (failures != null && failures >= maxFailures) {
            throw new TooManyAttemptsException("Too many wrong gift card codes. Try again in an hour, "
                    + "or contact support if your card does not work.");
        }
        try {
            Redeemed redeemed = transaction.execute(status -> redeemInTransaction(customerId, rawCode));
            recordAttempt(customerId, true);
            return redeemed;
        } catch (InvalidOrderException e) {
            recordAttempt(customerId, false);
            throw e;
        }
    }

    private Redeemed redeemInTransaction(String customerId, String rawCode) {
        String code = normalize(rawCode);
        if (code.length() != CODE_LENGTH) {
            throw new InvalidOrderException("A gift card code has 16 letters and digits, like ABCD-EFGH-JKMN-PQRS");
        }
        var found = jdbc.queryForList("select * from gift_cards where code_hash = ? for update", hash(code));
        if (found.isEmpty()) {
            throw new InvalidOrderException("This gift card code is not valid. Check it and try again.");
        }
        var card = found.get(0);
        long id = ((Number) card.get("gift_card_id")).longValue();
        LocalDateTime expires = ((java.sql.Timestamp) card.get("expires_at")).toLocalDateTime();
        switch (Status.valueOf((String) card.get("status"))) {
            case REDEEMED -> throw new InvalidOrderException(customerId.equals(card.get("redeemed_by"))
                    ? "You already added this card to your wallet on " + DAY.format(((java.sql.Timestamp) card.get("redeemed_at")).toLocalDateTime())
                    : "This gift card has already been used");
            case VOID -> throw new InvalidOrderException("This gift card has been stopped. Contact support if you think this is wrong.");
            default -> { }
        }
        if (!LocalDateTime.now().isBefore(expires)) {
            throw new InvalidOrderException("This gift card expired on " + DAY.format(expires));
        }
        BigDecimal amount = Money.of((BigDecimal) card.get("amount"));
        jdbc.update("update gift_cards set status = 'REDEEMED', redeemed_by = ?, redeemed_at = now() where gift_card_id = ?",
                customerId, id);
        String lastFour = (String) card.get("last_four");
        WalletService.Entry entry = wallets.credit(customerId, WalletService.Kind.GIFT_CARD, amount, null, id,
                "Gift card " + mask(lastFour), customerId);
        log.info("[GIFT CARDS] {} redeemed card {} for {} INR", customerId, id, amount);
        return new Redeemed(amount, lastFour, entry.balanceAfter(), entry);
    }

    private void recordAttempt(String customerId, boolean success) {
        jdbc.update("insert into gift_card_attempts (customer_id, success) values (?, ?)", customerId, success);
    }

    // ── Codes ──────────────────────────────────────────────────────────────

    private String newCode() {
        char[] code = new char[CODE_LENGTH];
        for (int i = 0; i < CODE_LENGTH; i++) {
            code[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(code);
    }

    /** Upper case without spaces or dashes; people type codes every which way. */
    static String normalize(String code) {
        return code == null ? "" : code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    /** ABCD-EFGH-JKMN-PQRS */
    static String format(String code) {
        return String.join("-", code.substring(0, 4), code.substring(4, 8), code.substring(8, 12), code.substring(12));
    }

    static String mask(String lastFour) {
        return "XXXX-XXXX-XXXX-" + lastFour;
    }

    static String hash(String normalizedCode) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalizedCode.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static LocalDateTime time(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
