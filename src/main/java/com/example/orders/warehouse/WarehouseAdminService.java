package com.example.orders.warehouse;

import com.example.orders.delivery.PinDirectory;
import com.example.orders.exception.InvalidOrderException;
import com.example.orders.exception.ProductNotFoundException;
import com.example.orders.model.InventoryItem;
import com.example.orders.model.StockMovement;
import com.example.orders.money.IndianState;
import com.example.orders.repository.InventoryRepository;
import com.example.orders.service.StockLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.Locale;

/**
 * What an admin does with warehouses: open one, change its name, address, cut-off or position, close or reopen it,
 * and move stock between warehouses.
 *
 * A warehouse's code, GSTIN and document prefixes are fixed once it exists: invoices have been issued under them.
 * Its state comes from the GSTIN (its first two digits) and must be the state of its PIN code.
 */
@Service
public class WarehouseAdminService {

    private static final Logger log = LoggerFactory.getLogger(WarehouseAdminService.class);

    /** GSTIN: state code, PAN (5 letters, 4 digits, 1 letter), entity number, Z, check character. */
    static final String GSTIN = "[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][0-9A-Z]Z[0-9A-Z]";

    /**
     * A new warehouse, or the fields to change (null keeps one). latitude and longitude may be left out: the centre
     * of the PIN code is used.
     */
    public record WarehouseRequest(String code, String name, String address, String pin, String gstin,
                                   String invoicePrefix, String creditNotePrefix, LocalTime cutoffTime,
                                   Double latitude, Double longitude, Boolean active) {}

    /** Moves stock from one warehouse to another: it leaves one and arrives in the other at once. */
    public record Transfer(String sku, Long fromWarehouseId, Long toWarehouseId, Integer quantity, String reason) {}

    public record TransferResult(StockMovement out, StockMovement in) {}

    private final JdbcTemplate jdbc;
    private final WarehouseService warehouses;
    private final PinDirectory pins;
    private final InventoryRepository inventory;
    private final StockLedger ledger;

    public WarehouseAdminService(JdbcTemplate jdbc, WarehouseService warehouses, PinDirectory pins,
                                 InventoryRepository inventory, StockLedger ledger) {
        this.jdbc = jdbc;
        this.warehouses = warehouses;
        this.pins = pins;
        this.inventory = inventory;
        this.ledger = ledger;
    }

    @Transactional
    public WarehouseService.Warehouse create(WarehouseRequest r) {
        if (r == null) {
            throw new InvalidOrderException("Send the warehouse");
        }
        String code = upper(r.code());
        if (code == null || !code.matches("[A-Z0-9]{2,10}")) {
            throw new InvalidOrderException("The code is 2 to 10 capital letters or digits, e.g. GGN");
        }
        String name = text(r.name(), "Name", 100, true);
        String address = text(r.address(), "Address", 300, true);
        String pin = r.pin() == null ? "" : r.pin().replaceAll("\\s", "");
        if (!pin.matches("[1-9][0-9]{5}")) {
            throw new InvalidOrderException("The PIN code has 6 digits and does not start with 0");
        }
        String gstin = upper(r.gstin());
        if (gstin == null || !gstin.matches(GSTIN)) {
            throw new InvalidOrderException("The GSTIN has 15 characters, like 06AABCO1234F1Z9");
        }
        IndianState state = IndianState.find(gstin.substring(0, 2))
                .orElseThrow(() -> new InvalidOrderException("The GSTIN starts with an unknown state code " + gstin.substring(0, 2)));
        pins.find(pin).ifPresent(place -> {
            if (place.state() != state) {
                throw new InvalidOrderException("PIN " + pin + " is in " + place.state().displayName() + ", but the GSTIN is from "
                        + state.displayName() + ". A warehouse is registered in the state it is in.");
            }
        });
        String invoicePrefix = prefix(r.invoicePrefix(), "Invoice prefix");
        String creditNotePrefix = prefix(r.creditNotePrefix(), "Credit note prefix");
        if (invoicePrefix.equals(creditNotePrefix)) {
            throw new InvalidOrderException("Invoices and credit notes need different prefixes");
        }
        Integer clash = jdbc.queryForObject("select count(*) from warehouses where invoice_prefix in (?, ?) or credit_note_prefix in (?, ?)",
                Integer.class, invoicePrefix, creditNotePrefix, invoicePrefix, creditNotePrefix);
        if (clash != null && clash > 0) {
            throw new InvalidOrderException("Another warehouse already numbers its documents " + invoicePrefix + " or " + creditNotePrefix);
        }
        PinDirectory.Point at = position(r.latitude(), r.longitude(), pin);
        try {
            Long id = jdbc.queryForObject("insert into warehouses (code, name, address, pin, state_code, gstin, location, cutoff_time, "
                    + "active, invoice_prefix, credit_note_prefix) values (?, ?, ?, ?, ?, ?, "
                    + "ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?, ?, ?, ?) returning warehouse_id", Long.class,
                    code, name, address, pin, state.gstCode(), gstin, at.longitude(), at.latitude(),
                    r.cutoffTime() != null ? r.cutoffTime() : LocalTime.of(14, 0), !Boolean.FALSE.equals(r.active()),
                    invoicePrefix, creditNotePrefix);
            log.info("[WAREHOUSE] Opened {} ({}) in {}", code, name, state.displayName());
            return warehouses.get(id);
        } catch (DuplicateKeyException e) {
            throw new InvalidOrderException("There is already a warehouse " + code);
        }
    }

    /** Name, address, cut-off, position and open/closed. The default warehouse cannot be closed. */
    @Transactional
    public WarehouseService.Warehouse update(long id, WarehouseRequest r) {
        WarehouseService.Warehouse w = warehouses.get(id);
        if (r == null) {
            return w;
        }
        if (r.code() != null && !upper(r.code()).equals(w.code()) || r.gstin() != null && !upper(r.gstin()).equals(w.gstin())
                || r.pin() != null && !r.pin().replaceAll("\\s", "").equals(w.pin())
                || r.invoicePrefix() != null && !upper(r.invoicePrefix()).equals(w.invoicePrefix())
                || r.creditNotePrefix() != null && !upper(r.creditNotePrefix()).equals(w.creditNotePrefix())) {
            throw new InvalidOrderException("A warehouse's code, PIN, GSTIN and document prefixes cannot change; open a new warehouse instead");
        }
        if (r.name() != null) {
            jdbc.update("update warehouses set name = ? where warehouse_id = ?", text(r.name(), "Name", 100, true), id);
        }
        if (r.address() != null) {
            jdbc.update("update warehouses set address = ? where warehouse_id = ?", text(r.address(), "Address", 300, true), id);
        }
        if (r.cutoffTime() != null) {
            jdbc.update("update warehouses set cutoff_time = ? where warehouse_id = ?", r.cutoffTime(), id);
        }
        if (r.latitude() != null || r.longitude() != null) {
            PinDirectory.Point at = position(r.latitude(), r.longitude(), w.pin());
            jdbc.update("update warehouses set location = ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography where warehouse_id = ?",
                    at.longitude(), at.latitude(), id);
        }
        if (r.active() != null && r.active() != w.active()) {
            if (!r.active() && w.isDefault()) {
                throw new InvalidOrderException(w.name() + " is the default warehouse and cannot be closed");
            }
            // Closed: no new orders are sent there; those it already holds still ship from it
            jdbc.update("update warehouses set active = ? where warehouse_id = ?", r.active(), id);
            log.info("[WAREHOUSE] {} {}", r.active() ? "Reopened" : "Closed", w.code());
        }
        return warehouses.get(id);
    }

    /**
     * Moves units of a product from one warehouse to another, in one transaction: a TRANSFER_OUT and a TRANSFER_IN
     * movement, so the shop's totals do not change and each warehouse's ledger says where the goods went.
     */
    @Transactional
    public TransferResult transfer(Transfer t) {
        if (t == null || t.sku() == null || t.fromWarehouseId() == null || t.toWarehouseId() == null || t.quantity() == null) {
            throw new InvalidOrderException("sku, fromWarehouseId, toWarehouseId and quantity are required");
        }
        if (t.quantity() <= 0) {
            throw new InvalidOrderException("Move at least one unit");
        }
        if (t.fromWarehouseId().equals(t.toWarehouseId())) {
            throw new InvalidOrderException("Choose two different warehouses");
        }
        WarehouseService.Warehouse from = warehouses.get(t.fromWarehouseId());
        WarehouseService.Warehouse to = warehouses.get(t.toWarehouseId());
        String note = t.reason() == null || t.reason().isBlank() ? null : t.reason().strip();
        if (note != null && note.length() > 400) {
            throw new InvalidOrderException("The reason is at most 400 characters");
        }
        InventoryItem item = inventory.findForUpdate(t.sku()).orElseThrow(() -> new ProductNotFoundException(t.sku()));
        StockMovement out = ledger.record(item, from.id(), StockMovement.Type.TRANSFER_OUT, -t.quantity(), 0, null,
                "To " + to.code() + (note != null ? ": " + note : ""));
        StockMovement in = ledger.record(item, to.id(), StockMovement.Type.TRANSFER_IN, t.quantity(), 0, null,
                "From " + from.code() + (note != null ? ": " + note : ""));
        log.info("[WAREHOUSE] Moved {} × {} from {} to {}", t.quantity(), t.sku(), from.code(), to.code());
        return new TransferResult(out, in);
    }

    /** Given, or the centre of the PIN code; inside India either way. */
    private PinDirectory.Point position(Double latitude, Double longitude, String pin) {
        if (latitude != null || longitude != null) {
            if (latitude == null || longitude == null || !PinDirectory.inIndia(latitude, longitude)) {
                throw new InvalidOrderException("Give both latitude and longitude, inside India");
            }
            return new PinDirectory.Point(latitude, longitude);
        }
        return pins.centre(pin).orElseThrow(() -> new InvalidOrderException(
                "The PIN directory has no position for " + pin + "; give the warehouse's latitude and longitude"));
    }

    private static String prefix(String value, String field) {
        String p = upper(value);
        if (p == null || !p.matches("[A-Z]{2,3}")) {
            throw new InvalidOrderException(field + " is 2 or 3 capital letters, e.g. GGI");
        }
        return p;
    }

    private static String text(String value, String field, int max, boolean required) {
        String v = value == null ? null : value.strip();
        if (v == null || v.isEmpty()) {
            if (required) {
                throw new InvalidOrderException(field + " is required");
            }
            return null;
        }
        if (v.length() > max) {
            throw new InvalidOrderException(field + " is at most " + max + " characters");
        }
        return v;
    }

    private static String upper(String value) {
        return value == null ? null : value.strip().toUpperCase(Locale.ROOT);
    }
}
