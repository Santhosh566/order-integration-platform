package com.example.orders.money;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Indian states and union territories with their GST state code (the first two digits of a
 * GSTIN), the usual abbreviations, and the region whose warehouse serves them.
 *
 * The GST state of the delivery address is the place of supply: the same state as the seller
 * means CGST + SGST, another state means IGST.
 */
public enum IndianState {

    JAMMU_AND_KASHMIR("01", "Jammu and Kashmir", Region.NORTH, "JK"),
    HIMACHAL_PRADESH("02", "Himachal Pradesh", Region.NORTH, "HP"),
    PUNJAB("03", "Punjab", Region.NORTH, "PB"),
    CHANDIGARH("04", "Chandigarh", Region.NORTH, "CH"),
    UTTARAKHAND("05", "Uttarakhand", Region.NORTH, "UK", "UT"),
    HARYANA("06", "Haryana", Region.NORTH, "HR"),
    DELHI("07", "Delhi", Region.NORTH, "DL", "NCT OF DELHI", "NEW DELHI"),
    RAJASTHAN("08", "Rajasthan", Region.NORTH, "RJ"),
    UTTAR_PRADESH("09", "Uttar Pradesh", Region.NORTH, "UP"),
    BIHAR("10", "Bihar", Region.EAST, "BR"),
    SIKKIM("11", "Sikkim", Region.EAST, "SK"),
    ARUNACHAL_PRADESH("12", "Arunachal Pradesh", Region.EAST, "AR"),
    NAGALAND("13", "Nagaland", Region.EAST, "NL"),
    MANIPUR("14", "Manipur", Region.EAST, "MN"),
    MIZORAM("15", "Mizoram", Region.EAST, "MZ"),
    TRIPURA("16", "Tripura", Region.EAST, "TR"),
    MEGHALAYA("17", "Meghalaya", Region.EAST, "ML"),
    ASSAM("18", "Assam", Region.EAST, "AS"),
    WEST_BENGAL("19", "West Bengal", Region.EAST, "WB"),
    JHARKHAND("20", "Jharkhand", Region.EAST, "JH"),
    ODISHA("21", "Odisha", Region.EAST, "OD", "OR", "ORISSA"),
    CHHATTISGARH("22", "Chhattisgarh", Region.EAST, "CG", "CT"),
    MADHYA_PRADESH("23", "Madhya Pradesh", Region.WEST, "MP"),
    GUJARAT("24", "Gujarat", Region.WEST, "GJ"),
    DADRA_NAGAR_HAVELI_DAMAN_DIU("26", "Dadra and Nagar Haveli and Daman and Diu", Region.WEST, "DH", "DN", "DD"),
    MAHARASHTRA("27", "Maharashtra", Region.WEST, "MH"),
    KARNATAKA("29", "Karnataka", Region.SOUTH, "KA"),
    GOA("30", "Goa", Region.WEST, "GA"),
    LAKSHADWEEP("31", "Lakshadweep", Region.SOUTH, "LD"),
    KERALA("32", "Kerala", Region.SOUTH, "KL"),
    TAMIL_NADU("33", "Tamil Nadu", Region.SOUTH, "TN"),
    PUDUCHERRY("34", "Puducherry", Region.SOUTH, "PY", "PONDICHERRY"),
    ANDAMAN_AND_NICOBAR("35", "Andaman and Nicobar Islands", Region.EAST, "AN"),
    TELANGANA("36", "Telangana", Region.SOUTH, "TS", "TG"),
    ANDHRA_PRADESH("37", "Andhra Pradesh", Region.SOUTH, "AP"),
    LADAKH("38", "Ladakh", Region.NORTH, "LA");

    /** Where the regional warehouses are. */
    public enum Region { NORTH, SOUTH, EAST, WEST }

    private static final Map<String, IndianState> LOOKUP = new HashMap<>();

    static {
        for (IndianState state : values()) {
            LOOKUP.put(state.gstCode, state);
            LOOKUP.put(key(state.displayName), state);
            for (String alias : state.aliases) {
                LOOKUP.put(key(alias), state);
            }
        }
    }

    private final String gstCode;
    private final String displayName;
    private final Region region;
    private final String[] aliases;

    IndianState(String gstCode, String displayName, Region region, String... aliases) {
        this.gstCode = gstCode;
        this.displayName = displayName;
        this.region = region;
        this.aliases = aliases;
    }

    public String gstCode() { return gstCode; }
    public String displayName() { return displayName; }
    public Region region() { return region; }

    /**
     * Accepts the name ("Karnataka", "tamil nadu"), an abbreviation ("KA") or the GST code ("29").
     */
    public static Optional<IndianState> find(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(LOOKUP.get(key(value)));
    }

    public static boolean isIndia(String country) {
        if (country == null) {
            return false;
        }
        String c = country.trim().toUpperCase(Locale.ROOT);
        return c.equals("IN") || c.equals("IND") || c.equals("INDIA");
    }

    private static String key(String value) {
        String upper = value.trim().toUpperCase(Locale.ROOT).replace("&", "AND");
        return upper.replaceAll("[^A-Z0-9]", "");
    }
}
