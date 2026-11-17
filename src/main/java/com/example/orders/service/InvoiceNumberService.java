package com.example.orders.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * GST document numbers: consecutive within each financial year (April to March), unique, and at
 * most 16 characters, e.g. INV/26-27/000042 for invoices and CN/26-27/000007 for credit notes.
 *
 * A database sequence would leave gaps when a transaction rolls back. Here the counter is a row
 * updated in the caller's transaction, so a rolled-back document gives its number back, and the
 * row lock makes concurrent callers take turns.
 */
@Service
public class InvoiceNumberService {

    public enum Series {
        INVOICE("INV"), CREDIT_NOTE("CN");

        private final String prefix;

        Series(String prefix) {
            this.prefix = prefix;
        }
    }

    private final JdbcTemplate jdbcTemplate;

    public InvoiceNumberService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String next(Series series, LocalDate date) {
        String year = financialYear(date);
        Long number = jdbcTemplate.queryForObject(
                "INSERT INTO document_sequences (series, financial_year, last_number) VALUES (?, ?, 1) "
                        + "ON CONFLICT (series, financial_year) "
                        + "DO UPDATE SET last_number = document_sequences.last_number + 1 "
                        + "RETURNING last_number",
                Long.class, series.prefix, year);
        return String.format("%s/%s/%06d", series.prefix, year, number);
    }

    /** "26-27" for any date from 1 April 2026 to 31 March 2027. */
    static String financialYear(LocalDate date) {
        int start = date.getMonthValue() >= 4 ? date.getYear() : date.getYear() - 1;
        return String.format("%02d-%02d", start % 100, (start + 1) % 100);
    }
}
