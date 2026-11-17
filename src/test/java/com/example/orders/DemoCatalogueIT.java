package com.example.orders;

import com.example.orders.demo.DemoCatalogueSeeder;
import com.example.orders.repository.CategoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;

/**
 * The development shop: 50 categories two levels deep and 160 products with specifications, made
 * through the normal services; running it again adds nothing.
 */
@TestPropertySource(properties = "orders.demo.catalogue=true")
class DemoCatalogueIT extends IntegrationTestBase {

    @Autowired CategoryRepository categories;
    @Autowired JdbcTemplate jdbc;
    @Autowired DemoCatalogueSeeder seeder;

    @Test
    void theDemoShopHasFiftyCategoriesAndItsProducts_andARestartAddsNothing() throws Exception {
        assertThat(categories.count()).isGreaterThanOrEqualTo(50);
        anonymous().get("/api/public/categories").then().statusCode(200)
            .body("slug", hasItem("mobiles-tablets"))
            .body("find { it.slug == 'home-kitchen' }.children.slug", hasItem("home-decor"));

        anonymous().get("/api/public/catalog/S-VOLT-0100").then().statusCode(200)
            .body("product.brand", equalTo("Voltix"))
            .body("categoryName", equalTo("Smartphones"));
        given().get("/api/admin/catalog/S-VOLT-0100").then()
            .body("specs.find { it.name == 'RAM' }.value", equalTo("8 GB"))
            .body("suggestedSpecs", hasItem("Battery"));

        // A main category shows the products of all its sub-categories
        anonymous().queryParam("category", "computers").get("/api/public/catalog").then()
            .body("size()", greaterThanOrEqualTo(16));

        int products = jdbc.queryForObject("select count(*) from inventory", Integer.class);
        seeder.run(new DefaultApplicationArguments());
        assertThat(jdbc.queryForObject("select count(*) from inventory", Integer.class)).isEqualTo(products);
        // Every demo product starts with a price history and an opening stock entry
        assertThat(jdbc.queryForObject("select count(*) from inventory i where i.sku like '%-0%' "
            + "and not exists (select 1 from stock_movements m where m.sku = i.sku and m.movement_type = 'OPENING')",
            Integer.class)).isZero();
    }

    @Test
    void clothingGstFollowsThePrice() {
        // Men's T-shirts carry the rule: Rs 2,999 is above Rs 2,500 + 5%, so 18%; Rs 499 is 5%
        anonymous().get("/api/public/catalog/MST-THRE-0106").then().body("product.gstRate", equalTo(5.0f));
        anonymous().get("/api/public/catalog/MST-URBA-0406").then().body("product.gstRate", equalTo(18.0f));
    }
}
