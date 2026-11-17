package com.example.orders.controller;

import com.example.orders.graphql.CatalogueModels.Product;
import com.example.orders.graphql.CatalogueSearch;
import com.example.orders.money.Money;
import com.example.orders.repository.ProductImageRepository;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

/**
 * A shared product link shows a proper card in WhatsApp, Telegram and the like: they read the page
 * without running the app, so its title, price, description and photo are written into the HTML
 * here. The app itself then starts as on any other page.
 */
@Controller
public class ProductPreviewController {

    private static final Resource INDEX = new ClassPathResource("static/index.html");

    private final CatalogueSearch search;
    private final ProductImageRepository images;

    public ProductPreviewController(CatalogueSearch search, ProductImageRepository images) {
        this.search = search;
        this.images = images;
    }

    @GetMapping(value = "/shop/{sku}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> productPage(@PathVariable String sku) throws IOException {
        if (!INDEX.exists()) {
            // No built app in this jar (development: Vite serves it on :5173)
            return ResponseEntity.notFound().build();
        }
        String html;
        try (InputStream in = INDEX.getInputStream()) {
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Product product = search.product(sku);
        if (product != null) {
            html = html.replace("</head>", tags(product) + "</head>");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).contentType(MediaType.TEXT_HTML).body(html);
    }

    private String tags(Product product) {
        String base = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        String url = base + "/shop/" + product.sku();
        String title = product.name() + " · " + Money.format(BigDecimal.valueOf(product.price()), product.currency());
        String description = product.description() == null ? "Free delivery anywhere in India. 7-day returns."
                : firstParagraph(product.description());
        StringBuilder tags = new StringBuilder("\n")
                .append(meta("og:type", "product"))
                .append(meta("og:title", title))
                .append(meta("og:description", description))
                .append(meta("og:url", url))
                .append("    <meta name=\"description\" content=\"").append(escape(description)).append("\">\n")
                .append("    <meta name=\"twitter:card\" content=\"summary_large_image\">\n");
        images.findBySkuOrderByPositionAscIdAsc(product.sku()).stream().findFirst().ifPresent(photo ->
                tags.append(meta("og:image", base + "/api/public/images/" + photo.getLargeKey()))
                        .append(meta("og:image:width", String.valueOf(photo.getWidth())))
                        .append(meta("og:image:height", String.valueOf(photo.getHeight()))));
        return tags.toString();
    }

    private static String meta(String property, String content) {
        return "    <meta property=\"" + property + "\" content=\"" + escape(content) + "\">\n";
    }

    private static String firstParagraph(String text) {
        String first = text.strip().split("\\n\\s*\\n", 2)[0].replaceAll("\\s+", " ");
        return first.length() > 200 ? first.substring(0, 197) + "…" : first;
    }

    /** Text a seller typed must not be able to end the attribute or the tag. */
    static String escape(String text) {
        return text.replace("&", "&amp;").replace("\"", "&quot;").replace("'", "&#39;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }
}
