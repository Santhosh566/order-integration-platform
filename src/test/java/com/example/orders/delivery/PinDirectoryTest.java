package com.example.orders.delivery;

import com.example.orders.money.IndianState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PinDirectoryTest {

    @Test
    void readsQuotedCells_andTheFilesOwnStateNames() {
        assertThat(PinDirectory.parseLine("\"Andaman, Nicobar\",744101,\"He said \"\"hi\"\"\"")).containsExactly(
                "Andaman, Nicobar", "744101", "He said \"hi\"");
        assertThat(PinDirectory.state("THE DADRA AND NAGAR HAVELI AND DAMAN AND DIU")).contains(IndianState.DADRA_NAGAR_HAVELI_DAMAN_DIU);
        assertThat(PinDirectory.state("JAMMU & KASHMIR")).contains(IndianState.JAMMU_AND_KASHMIR);
        assertThat(PinDirectory.state("KARNATAKA")).contains(IndianState.KARNATAKA);
        assertThat(PinDirectory.titleCase("BENGALURU URBAN")).isEqualTo("Bengaluru Urban");
    }
}
