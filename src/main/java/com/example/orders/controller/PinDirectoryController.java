package com.example.orders.controller;

import com.example.orders.delivery.PinDirectory;
import com.example.orders.exception.InvalidOrderException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/** The India Post PIN directory behind delivery estimates. Admins only (/api/admin). */
@RestController
public class PinDirectoryController {

    private final PinDirectory pins;

    public PinDirectoryController(PinDirectory pins) {
        this.pins = pins;
    }

    @GetMapping("/api/admin/pin-codes")
    public Map<String, Object> summary() {
        return Map.of("pins", pins.size());
    }

    /** The CSV from data.gov.in ("All India Pincode Directory"), as downloaded. */
    @PostMapping(value = "/api/admin/pin-codes", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PinDirectory.ImportResult upload(@RequestParam("file") MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            return pins.importCsv(in);
        } catch (IllegalArgumentException e) {
            throw new InvalidOrderException(e.getMessage());
        }
    }
}
