package com.example.orders.money;

import com.example.orders.demo.DemoTiles;
import com.example.orders.storage.ImageProcessor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class GstRulesTest {

    @Test
    void clothingIsTaxedAtFivePercentUpToRs2500APiece_andEighteenAbove() {
        // Rs 2,625 incl. GST is exactly Rs 2,500 at 5%
        assertThat(GstRules.rateFor(GstRules.APPAREL_2500, new BigDecimal("2625.00"))).isEqualByComparingTo("5");
        assertThat(GstRules.rateFor(GstRules.APPAREL_2500, new BigDecimal("2625.01"))).isEqualByComparingTo("18");
        assertThat(GstRules.rateFor(GstRules.APPAREL_2500, new BigDecimal("499"))).isEqualByComparingTo("5");
        assertThat(GstRules.rateFor(null, new BigDecimal("499"))).isNull();
    }

    @Test
    void demoTilesPassThePhotoChecks() {
        byte[] tile = DemoTiles.render("Voltix", "Voltix Nova 5G with a long name that wraps", "Smartphones", "#1c7ed6");
        ImageProcessor.Processed processed = new ImageProcessor().process(tile);
        assertThat(processed.width()).isEqualTo(1000);
        assertThat(processed.thumb()).isNotEmpty();
    }
}
