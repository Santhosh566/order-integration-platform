package com.example.orders.service;

import com.example.orders.exception.InvalidOrderStateException;
import com.example.orders.model.Order;
import com.example.orders.model.OrderItem;
import com.example.orders.money.IndianState;
import com.example.orders.money.Money;
import com.example.orders.repository.OrderRepository;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Phase 4.2: Invoice PDF Generator & Claim Check Storage
 *
 * Generates an official digital PDF invoice and offloads the heavy binary
 * document to the local File Vault (data/invoices/), returning a lightweight
 * Claim Check ticket to the Camel message stream.
 *
 * An invoice exists only for an order that was paid. Its number and date are assigned
 * once. The amounts show exactly what was charged, so its total always matches the payment.
 */
@Service
public class InvoiceService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);

    private final OrderRepository orderRepository;
    private final OrderStateService orderStateService;
    private final InvoiceNumberService invoiceNumbers;
    private final String sellerName;
    private final String sellerAddress;
    private final Path invoiceDir;

    public InvoiceService(OrderRepository orderRepository,
                          OrderStateService orderStateService,
                          InvoiceNumberService invoiceNumbers,
                          @Value("${orders.seller.legal-name}") String sellerName,
                          @Value("${orders.seller.address}") String sellerAddress,
                          @Value("${orders.invoice-dir}") String invoiceDir) {
        this.orderRepository = orderRepository;
        this.orderStateService = orderStateService;
        this.invoiceNumbers = invoiceNumbers;
        this.sellerName = sellerName;
        this.sellerAddress = sellerAddress;
        this.invoiceDir = Paths.get(invoiceDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.invoiceDir);
        } catch (IOException e) {
            log.error("Failed to create invoice directory: {}", e.getMessage());
        }
    }

    /**
     * Generates a PDF invoice for an order and offloads it to the Claim Check store.
     *
     * @param orderId the order identifier
     * @return a lightweight Claim Check metadata map
     */
    @Transactional
    public Map<String, Object> generateInvoiceAndClaimCheck(String orderId) {
        Order order = issueInvoice(orderId);
        Path targetPdf = invoicePath(orderId);

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document document = new Document();
            PdfWriter.getInstance(document, baos);
            document.open();

            // Document Header
            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, Color.DARK_GRAY);
            Paragraph title = new Paragraph("TAX INVOICE", titleFont);
            title.setAlignment(Element.ALIGN_CENTER);
            title.setSpacingAfter(10);
            document.add(title);

            Font metaFont = FontFactory.getFont(FontFactory.HELVETICA, 10, Color.BLACK);
            Font boldFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.BLACK);
            document.add(new Paragraph(sellerName, boldFont));
            document.add(new Paragraph(sellerAddress, metaFont));
            document.add(new Paragraph("GSTIN: " + orElse(order.getSellerGstin(), "Not registered"), metaFont));
            document.add(new Paragraph(" "));

            document.add(new Paragraph("Invoice Number: " + order.getInvoiceNumber(), metaFont));
            document.add(new Paragraph("Invoice Date: " + order.getInvoiceDate(), metaFont));
            document.add(new Paragraph("Order Reference: " + order.getOrderId(), metaFont));
            document.add(new Paragraph("Billed To: " + recipient(order) + " (" + order.getCustomerId() + ")", metaFont));
            document.add(new Paragraph("Ship To: " + shipTo(order), metaFont));
            document.add(new Paragraph("Place of Supply: " + placeOfSupply(order), metaFont));
            document.add(new Paragraph("Carrier: " + orElse(order.getCarrier(), "Not booked")
                    + "   Tracking Number: " + orElse(order.getTrackingNumber(), "PENDING"), metaFont));
            document.add(new Paragraph(" "));

            // Items Table
            // A discount shown on the invoice is not part of the taxable value (CGST Act s.15(3)(a))
            String[] headers = {"Product", "HSN", "Qty", "Unit Price", "Discount", "Taxable Value", "GST", "Total"};
            PdfPTable table = new PdfPTable(headers.length);
            table.setWidthPercentage(100);
            table.setWidths(new float[] {3.0f, 1.0f, 0.7f, 1.5f, 1.4f, 1.7f, 2.3f, 1.7f});
            table.setSpacingBefore(10);
            table.setSpacingAfter(15);

            Font headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.WHITE);
            for (String h : headers) {
                PdfPCell cell = new PdfPCell(new Phrase(h, headerFont));
                cell.setBackgroundColor(new Color(41, 128, 185));
                cell.setPadding(5);
                table.addCell(cell);
            }

            Font rowFont = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.BLACK);
            String currency = order.getCurrency();
            for (OrderItem item : order.getItems()) {
                table.addCell(new PdfPCell(new Phrase(product(item), rowFont)));
                table.addCell(new PdfPCell(new Phrase(orElse(item.getHsnCode(), "-"), rowFont)));
                table.addCell(new PdfPCell(new Phrase(String.valueOf(item.getQuantity()), rowFont)));
                table.addCell(new PdfPCell(new Phrase(Money.formatCode(item.getPrice(), currency), rowFont)));
                table.addCell(new PdfPCell(new Phrase(item.getDiscount().signum() > 0
                        ? "-" + Money.formatCode(item.getDiscount(), currency) : "-", rowFont)));
                table.addCell(new PdfPCell(new Phrase(Money.formatCode(item.getTaxableValue(), currency), rowFont)));
                table.addCell(new PdfPCell(new Phrase(lineTax(item, currency, false), rowFont)));
                table.addCell(new PdfPCell(new Phrase(Money.formatCode(item.getLineTotal(), currency), rowFont)));
            }
            document.add(table);

            // Totals section
            Font totalFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, Color.BLACK);
            if (order.getDiscountTotal().signum() > 0) {
                document.add(new Paragraph("Discount" + (order.getCouponCode() == null ? "" : " (coupon " + order.getCouponCode() + ")")
                        + ": -" + Money.formatCode(order.getDiscountTotal(), currency) + " (taken off the lines above)", metaFont));
            }
            document.add(new Paragraph("Taxable Value: " + Money.formatCode(order.getSubtotal(), currency), metaFont));
            for (String[] row : taxRows(order)) {
                document.add(new Paragraph(row[0] + ": " + Money.formatCode(new java.math.BigDecimal(row[1]), currency), metaFont));
            }
            document.add(new Paragraph("Shipping: Free", metaFont));
            document.add(new Paragraph("Grand Total: " + Money.formatCode(order.getTotal(), currency), totalFont));
            document.add(new Paragraph("Payment Status: " + paymentStatus(order), metaFont));
            document.add(new Paragraph(taxNote(order) + " Tax payable on reverse charge: No.", metaFont));
            document.add(new Paragraph(" "));

            Font footerFont = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 9, Color.GRAY);
            Paragraph footer = new Paragraph("Thank you for choosing Order Integration Platform. For inquiries, contact support@example.com", footerFont);
            footer.setAlignment(Element.ALIGN_CENTER);
            document.add(footer);

            document.close();

            byte[] pdfBytes = baos.toByteArray();
            Files.write(targetPdf, pdfBytes);

            log.info("[INVOICE GENERATED] Wrote {} bytes to {} for Order {}",
                     pdfBytes.length, targetPdf.toAbsolutePath(), orderId);

            // Construct Claim Check ticket
            Map<String, Object> claimCheck = new HashMap<>();
            claimCheck.put("orderId", orderId);
            claimCheck.put("claimTicket", "TICKET-INV-" + orderId);
            claimCheck.put("filePath", targetPdf.toAbsolutePath().toString());
            claimCheck.put("invoiceDownloadUrl", "/api/invoices/" + orderId);
            claimCheck.put("fileSizeBytes", pdfBytes.length);
            claimCheck.put("generatedAt", LocalDateTime.now().toString());

            return claimCheck;

        } catch (Exception e) {
            log.error("[INVOICE ERROR] Failed to generate invoice for Order {}: {}", orderId, e.getMessage(), e);
            throw new RuntimeException("Invoice generation failed", e);
        }
    }

    /**
     * Reads invoice bytes from the Claim Check storage.
     */
    @Transactional
    public byte[] getInvoicePdf(String orderId) {
        // Rebuilt on each download, so a later refund or delivery shows on the document
        generateInvoiceAndClaimCheck(orderId);
        try {
            return Files.readAllBytes(invoicePath(orderId));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read invoice PDF for order " + orderId, e);
        }
    }

    private static String recipient(Order order) {
        return order.getShippingAddress() != null && order.getShippingAddress().getRecipientName() != null
                ? order.getShippingAddress().getRecipientName() : order.getCustomerId();
    }

    private static String product(OrderItem item) {
        return item.getProductName() != null ? item.getProductName() + " (" + item.getSku() + ")" : item.getSku();
    }

    private static String placeOfSupply(Order order) {
        if (order.getPlaceOfSupply() == null) {
            return "Not recorded";
        }
        return IndianState.find(order.getPlaceOfSupply())
                .map(state -> state.displayName() + " (" + state.gstCode() + ")")
                .orElse(order.getPlaceOfSupply());
    }

    /** "CGST 9% ₹381.38 + SGST 9% ₹381.38", "IGST 18% ₹762.76" or "-" for an untaxed line. */
    private static String lineTax(OrderItem item, String currency, boolean symbol) {
        if (item.taxAmount().signum() == 0) {
            return "-";
        }
        String half = rate(item.getGstRate().divide(java.math.BigDecimal.valueOf(2)));
        if (item.getIgstAmount().signum() > 0) {
            return "IGST " + rate(item.getGstRate()) + "% " + amount(item.getIgstAmount(), currency, symbol);
        }
        return "CGST " + half + "% " + amount(item.getCgstAmount(), currency, symbol)
                + " + SGST " + half + "% " + amount(item.getSgstAmount(), currency, symbol);
    }

    private static String amount(java.math.BigDecimal value, String currency, boolean symbol) {
        return symbol ? Money.format(value, currency) : Money.formatCode(value, currency);
    }

    private static String rate(java.math.BigDecimal rate) {
        return rate.stripTrailingZeros().toPlainString();
    }

    /** Label and amount of each tax the order paid. */
    private static java.util.List<String[]> taxRows(Order order) {
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        if (order.getCgstTotal().signum() > 0) {
            rows.add(new String[] {"CGST", order.getCgstTotal().toPlainString()});
            rows.add(new String[] {"SGST", order.getSgstTotal().toPlainString()});
        }
        if (order.getIgstTotal().signum() > 0) {
            rows.add(new String[] {"IGST", order.getIgstTotal().toPlainString()});
        }
        if (rows.isEmpty()) {
            rows.add(new String[] {"Tax", "0.00"});
        }
        return rows;
    }

    private static String taxNote(Order order) {
        return order.getTaxTotal().signum() > 0
                ? "Prices include GST; the tax above is part of the total."
                : "No GST was charged on this order.";
    }

    private static String orElse(String value, String fallback) {
        return value != null ? value : fallback;
    }

    private static String shipTo(Order order) {
        return order.getShippingAddress() != null ? order.getShippingAddress().singleLine() : "Not recorded";
    }

    /**
     * Gives a paid order its invoice number now, if it has none yet, so a credit note can refer to it.
     */
    @Transactional
    public void ensureInvoiceNumber(String orderId) {
        issueInvoice(orderId);
    }

    /**
     * Loads a paid order and gives it its invoice number and date the first time.
     */
    private Order issueInvoice(String orderId) {
        // Lock first, then read: an order read before the lock could be stale (see OrderStateService.lock)
        Order order = orderStateService.lock(orderId);
        if (order.getPaymentTransactionId() == null && !order.getStatus().isRevenue()) {
            throw new InvalidOrderStateException("Order " + orderId + " was not paid, so it has no invoice");
        }
        if (order.getInvoiceNumber() == null) {
            LocalDate today = LocalDate.now();
            order.setInvoiceNumber(invoiceNumbers.next(InvoiceNumberService.Series.INVOICE, today));
            order.setInvoiceDate(today);
            orderRepository.save(order);
        }
        return order;
    }

    private static String paymentStatus(Order order) {
        if (order.isCashOnDelivery() && order.getPaymentTransactionId() == null) {
            return "CASH ON DELIVERY (" + Money.formatCode(order.amountDue(), order.getCurrency()) + " to be paid at the door)";
        }
        if (order.getRefundedAt() != null) {
            return "REFUNDED";
        }
        return order.getRefundedTotal().signum() > 0
                ? "PARTLY REFUNDED (" + Money.formatCode(order.getRefundedTotal(), order.getCurrency()) + ")"
                : order.isCashOnDelivery() ? "PAID (cash on delivery)" : "PAID";
    }

    /**
     * Stored orders from before id validation could hold anything, so the path is checked as well.
     */
    private Path invoicePath(String orderId) {
        Path path = invoiceDir.resolve(orderId + "_invoice.pdf").normalize();
        if (!path.startsWith(invoiceDir)) {
            throw new IllegalArgumentException("Unsafe order id for invoice path");
        }
        return path;
    }
}
