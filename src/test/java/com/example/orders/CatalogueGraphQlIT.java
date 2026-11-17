package com.example.orders;

import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * The shop's GraphQL catalogue over the demo shop: search with typos, filters with their counts,
 * sorting, the product page in one request, and the limits that keep queries cheap.
 */
@TestPropertySource(properties = "orders.demo.catalogue=true")
class CatalogueGraphQlIT extends IntegrationTestBase {

    private static final String PAGE = """
        query($filter: ProductFilter, $sort: ProductSort, $size: Int) {
          products(filter: $filter, sort: $sort, size: $size) {
            totalItems size
            items { sku name brand price stockStatus }
            facets { categories { slug parentSlug count } brands { brand count } price { min max } }
          }
        }""";

    private JsonPath graphql(String query, Map<String, Object> variables) {
        return anonymous().contentType(ContentType.JSON).body(Map.of("query", query, "variables", variables))
            .post("/graphql").then().statusCode(200).extract().jsonPath();
    }

    private JsonPath products(Map<String, Object> filter, String sort, int size) {
        JsonPath result = graphql(PAGE, Map.of("filter", filter, "sort", sort, "size", size));
        assertThat(result.getList("errors")).isNull();
        return result;
    }

    @Test
    void searchForgivesTyposAndFindsTheStartOfAWord() {
        assertThat(products(Map.of("text", "smartphone"), "RELEVANCE", 10).getList("data.products.items.name", String.class))
            .contains("Voltix Nova 5G", "Kairo Edge Pro");
        assertThat(products(Map.of("text", "hedphones"), "RELEVANCE", 10).getList("data.products.items.brand", String.class))
            .contains("Sonix");
        assertThat(products(Map.of("text", "kurt"), "RELEVANCE", 10).getList("data.products.items.name", String.class))
            .anyMatch(n -> n.contains("Kurta"));
        // Words from the description count too
        assertThat(products(Map.of("text", "chakki atta"), "RELEVANCE", 5).getString("data.products.items[0].name"))
            .isEqualTo("Annam Whole Wheat Atta 10 kg");
    }

    @Test
    void categoryTilesCountTheProductsOfTheirSubCategories() {
        JsonPath tree = graphql("{ categories { slug productCount imageKey children { slug productCount } } }", Map.of());
        assertThat(tree.getList("errors")).isNull();
        int computers = tree.getInt("data.categories.find { it.slug == 'computers' }.productCount");
        assertThat(computers).isEqualTo(products(Map.of("categories", List.of("computers")), "RELEVANCE", 1)
            .getInt("data.products.totalItems"));
        assertThat(tree.getInt("data.categories.find { it.slug == 'computers' }.children.find { it.slug == 'laptops' }.productCount"))
            .isEqualTo(4);
        // Every main category of the demo shop has something in it
        assertThat(tree.getList("data.categories.productCount", Integer.class)).allMatch(n -> n > 0);
    }

    @Test
    void filtersNarrowTheList_andEachCountIgnoresItsOwnFilter() {
        JsonPath computers = products(Map.of("categories", List.of("computers")), "PRICE_LOW_HIGH", 48);
        List<Double> prices = computers.getList("data.products.items.price", Double.class);
        assertThat(prices).hasSizeGreaterThanOrEqualTo(16).isSorted();
        assertThat(computers.getInt("data.products.facets.categories.find { it.slug == 'laptops' }.count")).isEqualTo(4);

        // Choosing one brand still shows how many the other brands have
        JsonPath voltix = products(Map.of("categories", List.of("computers"), "brands", List.of("Voltix")), "RELEVANCE", 48);
        assertThat(voltix.getList("data.products.items.brand", String.class)).containsOnly("Voltix");
        assertThat(voltix.getInt("data.products.facets.brands.find { it.brand == 'Kairo' }.count")).isGreaterThan(0);

        JsonPath band = products(Map.of("categories", List.of("computers"), "minPrice", 5000, "maxPrice", 25000), "PRICE_HIGH_LOW", 48);
        assertThat(band.getList("data.products.items.price", Double.class))
            .allMatch(p -> p >= 5000 && p <= 25000).isSortedAccordingTo((a, b) -> Double.compare(b, a));
        // The price range is that of the computers, as if no price were chosen
        assertThat(band.getDouble("data.products.facets.price.max")).isGreaterThan(25000);

        JsonPath inStock = products(Map.of("categories", List.of("power-banks"), "inStockOnly", true), "RELEVANCE", 48);
        assertThat(inStock.getList("data.products.items.stockStatus", String.class)).doesNotContain("OUT_OF_STOCK");
    }

    @Test
    void theProductPageComesInOneRequest() {
        JsonPath page = graphql("""
            query { product(sku: "S-VOLT-0100") {
              name brand price gstRate maxPerOrder rating { average count }
              category { slug name parentSlug parentName }
              specs { name value }
              images { largeKey }
              related(limit: 3) { sku category { slug } }
            } }""", Map.of());
        assertThat(page.getList("errors")).isNull();
        assertThat(page.getString("data.product.category.parentSlug")).isEqualTo("mobiles-tablets");
        assertThat(page.getString("data.product.specs.find { it.name == 'RAM' }.value")).isEqualTo("8 GB");
        assertThat(page.getList("data.product.related.category.slug", String.class)).containsOnly("smartphones").hasSizeLessThanOrEqualTo(3);
        assertThat(page.getList("data.product.related.sku", String.class)).doesNotContain("S-VOLT-0100");

        assertThat(graphql("query { product(sku: \"NO-SUCH\") { sku } }", Map.of()).getString("data.product")).isNull();
    }

    @Test
    void suggestionsOfferCategoriesBrandsAndProducts() {
        JsonPath s = graphql("query { suggestions(text: \"lapt\") { kind label slug sku } }", Map.of());
        assertThat(s.getList("data.suggestions.findAll { it.kind == 'CATEGORY' }.slug", String.class)).contains("laptops");
        assertThat(s.getList("data.suggestions.findAll { it.kind == 'PRODUCT' }.sku", String.class)).isNotEmpty();
    }

    @Test
    void queriesAreKeptCheap() {
        // A page is at most 48 products
        assertThat(graphql("query { products(size: 500) { size } }", Map.of()).getInt("data.products.size")).isEqualTo(48);

        // Related of related of related … is refused before it runs
        String deep = "query { product(sku: \"S-VOLT-0100\") { related { related { related { related { related { related { related { sku } } } } } } } } }";
        anonymous().contentType(ContentType.JSON).body(Map.of("query", deep)).post("/graphql").then()
            .statusCode(200).body("errors[0].message", containsString("depth"));

        // The schema is browsable while developing, and GraphiQL is there to try it
        anonymous().get("/graphiql").then().statusCode(200).body(containsString("GraphiQL"));
        anonymous().get("/graphql").then().statusCode(not(equalTo(200)));
    }

    @Test
    void graphqlIsReadOnly() {
        anonymous().contentType(ContentType.JSON).body(Map.of("query", "mutation { x }")).post("/graphql").then()
            .statusCode(200).body("errors", notNullValue());
    }
}
