package com.example.orders.delivery;

import com.example.orders.money.IndianState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * India Post's "All India Pincode Directory" (data.gov.in, Government Open Data Licence - India):
 * which state and district each PIN code is in. It is downloaded by hand from data.gov.in (the site
 * asks for a name and a captcha), then imported here: by the admin's upload, or at startup from
 * ORDERS_PIN_DIRECTORY_FILE. The file has one row per post office; one row per PIN is kept.
 */
@Service
public class PinDirectory implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PinDirectory.class);

    public record Place(String pin, String district, IndianState state) {}

    public record ImportResult(int pins, int rowsRead, int rowsSkipped, List<String> unknownStates) {}

    private final JdbcTemplate jdbc;
    private final String startupFile;

    public PinDirectory(JdbcTemplate jdbc, @Value("${orders.pin-directory.file:}") String startupFile) {
        this.jdbc = jdbc;
        this.startupFile = startupFile;
    }

    public Optional<Place> find(String pin) {
        if (pin == null || !pin.matches("[1-9][0-9]{5}")) {
            return Optional.empty();
        }
        return jdbc.query("select pin, district, state_code from pin_codes where pin = ?",
                (rs, n) -> new Place(rs.getString("pin"), rs.getString("district"),
                        IndianState.find(rs.getString("state_code")).orElse(null)), pin)
                .stream().filter(p -> p.state() != null).findFirst();
    }

    public long size() {
        Long n = jdbc.queryForObject("select count(*) from pin_codes", Long.class);
        return n == null ? 0 : n;
    }

    /** An empty directory, and a file named in the settings: import it, in the background. */
    @Override
    public void run(ApplicationArguments args) {
        if (startupFile.isBlank() || size() > 0) {
            return;
        }
        Path file = Path.of(startupFile);
        Thread.ofVirtual().name("pin-directory").start(() -> {
            try (InputStream in = Files.newInputStream(file)) {
                ImportResult result = importCsv(in);
                log.info("[PIN] Imported {} PIN codes from {}", result.pins(), file);
            } catch (Exception e) {
                log.warn("[PIN] Could not import {}: {}", file, e.getMessage());
            }
        });
    }

    /**
     * Reads the directory CSV. Columns are found by name (pincode, district or districtname,
     * statename), so older and newer versions of the file both work. Re-importing updates.
     */
    @Transactional
    public ImportResult importCsv(InputStream csv) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(csv, StandardCharsets.UTF_8));
        String headerLine = reader.readLine();
        if (headerLine == null) {
            throw new IllegalArgumentException("The file is empty");
        }
        List<String> header = parseLine(headerLine.replace("\uFEFF", "")).stream()
                .map(h -> h.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "")).toList();
        int pinCol = header.indexOf("pincode");
        int districtCol = header.contains("district") ? header.indexOf("district") : header.indexOf("districtname");
        int stateCol = header.indexOf("statename");
        if (pinCol < 0 || districtCol < 0 || stateCol < 0) {
            throw new IllegalArgumentException("Expected the India Post directory, with columns pincode, district and statename; got "
                    + headerLine);
        }

        Map<String, Object[]> byPin = new LinkedHashMap<>();
        TreeSet<String> unknownStates = new TreeSet<>();
        int read = 0;
        int skipped = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            read++;
            List<String> cells = parseLine(line);
            if (cells.size() <= Math.max(pinCol, Math.max(districtCol, stateCol))) {
                skipped++;
                continue;
            }
            String pin = cells.get(pinCol).trim();
            String stateName = cells.get(stateCol).trim();
            Optional<IndianState> state = state(stateName);
            if (!pin.matches("[1-9][0-9]{5}") || state.isEmpty()) {
                if (state.isEmpty() && !stateName.isEmpty()) {
                    unknownStates.add(stateName);
                }
                skipped++;
                continue;
            }
            String district = titleCase(cells.get(districtCol).trim());
            byPin.putIfAbsent(pin, new Object[] {pin, district.isEmpty() ? "-" : district,
                    state.get().gstCode(), state.get().displayName()});
        }

        List<Object[]> rows = new ArrayList<>(byPin.values());
        for (int from = 0; from < rows.size(); from += 1000) {
            jdbc.batchUpdate("insert into pin_codes (pin, district, state_code, state_name) values (?, ?, ?, ?) "
                    + "on conflict (pin) do update set district = excluded.district, state_code = excluded.state_code, "
                    + "state_name = excluded.state_name", rows.subList(from, Math.min(from + 1000, rows.size())));
        }
        log.info("[PIN] {} PIN codes from {} rows ({} skipped, unknown states: {})", rows.size(), read, skipped, unknownStates);
        return new ImportResult(rows.size(), read, skipped, List.copyOf(unknownStates));
    }

    /** The file spells some states its own way ("THE DADRA AND NAGAR HAVELI AND DAMAN AND DIU"). */
    static Optional<IndianState> state(String name) {
        String plain = name.trim().replaceFirst("(?i)^the\\s+", "").replace("&", "and");
        return IndianState.find(plain);
    }

    static String titleCase(String text) {
        StringBuilder out = new StringBuilder();
        boolean start = true;
        for (char c : text.toLowerCase(Locale.ROOT).toCharArray()) {
            out.append(start ? Character.toUpperCase(c) : c);
            start = !Character.isLetter(c);
        }
        return out.toString();
    }

    /** One CSV line; quoted cells may hold commas and doubled quotes. */
    static List<String> parseLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString());
        return cells;
    }
}
