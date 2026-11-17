package com.example.orders;

import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * The shop window: categories two levels deep, product descriptions, and product photos stored in a
 * real MinIO (the same image the hub project uses).
 */
class CataloguePhotosIT extends IntegrationTestBase {

    static final MinIOContainer MINIO = new MinIOContainer("minio/minio:latest");
    static final String BUCKET = "order-platform-products";

    static {
        MINIO.start();
    }

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("orders.storage.endpoint", MINIO::getS3URL);
        registry.add("orders.storage.access-key", MINIO::getUserName);
        registry.add("orders.storage.secret-key", MINIO::getPassword);
        registry.add("orders.storage.bucket", () -> BUCKET);
    }

    @BeforeAll
    static void bucket() throws Exception {
        MinioClient admin = minio();
        if (!admin.bucketExists(io.minio.BucketExistsArgs.builder().bucket(BUCKET).build())) {
            admin.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        }
    }

    // ── Categories and descriptions ─────────────────────────────────────────

    @Test
    void categoriesGoTwoLevelsDeep_andAMainCategoryShowsItsSubCategoriesProducts() {
        long office = createCategory("Office " + suffix(), null).extract().jsonPath().getLong("id");
        String officeSlug = given().get("/api/admin/categories").then().extract().jsonPath()
            .getString("find { it.id == " + office + " }.slug");
        long pens = createCategory("Pens " + suffix(), office).extract().jsonPath().getLong("id");
        createCategory("Gel pens", pens).statusCode(400).body("reason", containsString("two levels"));

        String sku = newSku(5);
        setPrice(sku, 59.0);
        given().contentType(ContentType.JSON)
            .body(Map.of("expectedVersion", version(sku), "categoryId", pens,
                         "description", "Smooth gel ink.\n\nPack of one."))
            .patch("/api/admin/catalog/" + sku).then().statusCode(200)
            .body("categoryId", equalTo((int) pens)).body("description", equalTo("Smooth gel ink.\n\nPack of one."));

        anonymous().queryParam("category", officeSlug).get("/api/public/catalog").then().statusCode(200)
            .body("sku", hasItem(sku));
        anonymous().get("/api/public/catalog/" + sku).then().statusCode(200)
            .body("product.description", equalTo("Smooth gel ink.\n\nPack of one."))
            .body("categoryName", containsString("Pens"));

        given().delete("/api/admin/categories/" + pens).then().statusCode(409).body("reason", containsString("still has products"));
        given().delete("/api/admin/categories/" + office).then().statusCode(409);
        asUser(TestTokens.ALICE, "CUSTOMER").contentType(ContentType.JSON).body(Map.of("name", "X"))
            .post("/api/admin/categories").then().statusCode(403);
    }

    @Test
    void aDescriptionHasALimit_andAnEmptyOneIsRemoved() {
        String sku = newSku(5);
        given().contentType(ContentType.JSON).body(Map.of("expectedVersion", version(sku), "description", "x".repeat(5001)))
            .patch("/api/admin/catalog/" + sku).then().statusCode(400);
        given().contentType(ContentType.JSON).body(Map.of("expectedVersion", version(sku), "description", "  "))
            .patch("/api/admin/catalog/" + sku).then().statusCode(200).body("description", nullValue());
    }

    // ── Photos ──────────────────────────────────────────────────────────────

    @Test
    void aPhoneShotIsTurnedUpright_resized_andLosesItsLocation_thenServedForAYear() throws Exception {
        String sku = newSku(5);
        setPrice(sku, 99.0);
        // 800 × 400 as stored, but tagged "rotate 90°": shown upright it is 400 × 800
        byte[] phoneShot = withExif(jpeg(800, 400), 6, "GPS-SECRET-12.97N-77.59E");

        var reply = upload(sku, phoneShot, "photo.jpg", "image/jpeg", "Gel pen, side view").statusCode(201)
            .body("width", equalTo(400)).body("height", equalTo(800))
            .body("altText", equalTo("Gel pen, side view")).body("position", equalTo(0))
            .extract().jsonPath();
        String largeKey = reply.getString("largeKey");
        String thumbKey = reply.getString("thumbKey");

        byte[] large = anonymous().get("/api/public/images/" + largeKey).then().statusCode(200)
            .contentType("image/jpeg")
            .header("Cache-Control", containsString("immutable"))
            .header("X-Content-Type-Options", "nosniff")
            .extract().asByteArray();
        assertThat(new String(large, StandardCharsets.ISO_8859_1)).doesNotContain("GPS-SECRET");
        BufferedImage shown = ImageIO.read(new ByteArrayInputStream(large));
        assertThat(shown.getWidth()).isEqualTo(400);
        assertThat(shown.getHeight()).isEqualTo(800);

        BufferedImage thumb = ImageIO.read(new ByteArrayInputStream(
            anonymous().get("/api/public/images/" + thumbKey).then().statusCode(200).extract().asByteArray()));
        assertThat(thumb.getHeight()).isEqualTo(400);
        assertThat(thumb.getWidth()).isEqualTo(200);

        anonymous().get("/api/public/catalog").then().body("find { it.sku == '" + sku + "' }.imageKey", equalTo(thumbKey));
    }

    @Test
    void onlyRealPicturesOfAReasonableSize_areAccepted() {
        String sku = newSku(5);
        byte[] svg = "<svg xmlns='http://www.w3.org/2000/svg' onload='alert(1)'><rect width='400' height='400'/></svg>"
            .getBytes(StandardCharsets.UTF_8);
        upload(sku, svg, "logo.svg", "image/svg+xml", null).statusCode(400).body("reason", containsString("JPEG, PNG or WebP"));
        upload(sku, "not a picture".getBytes(StandardCharsets.UTF_8), "fake.jpg", "image/jpeg", null).statusCode(400);
        upload(sku, jpeg(100, 100), "tiny.jpg", "image/jpeg", null).statusCode(400).body("reason", containsString("too small"));

        byte[] fiveAndABit = new byte[5 * 1024 * 1024 + 1000];
        new Random(1).nextBytes(fiveAndABit);
        upload(sku, fiveAndABit, "big.jpg", "image/jpeg", null).statusCode(400).body("reason", containsString("at most 5 MB"));
        // Over the photo limit but under the upload limit: the clear message; over both: refused at the door
        upload(sku, new byte[8 * 1024 * 1024], "large.jpg", "image/jpeg", null).statusCode(400)
            .body("reason", containsString("at most 5 MB"));
        upload(sku, new byte[32 * 1024 * 1024], "huge.jpg", "image/jpeg", null).statusCode(413);

        asUser(TestTokens.DRIVER_ONE, "DELIVERY").multiPart("file", "p.jpg", jpeg(400, 400), "image/jpeg")
            .post("/api/admin/catalog/" + sku + "/images").then().statusCode(403);
    }

    @Test
    void transparentPngsGetAWhiteBackground_andAProductHoldsAtMostEightPhotos() throws Exception {
        String sku = newSku(5);
        String key = upload(sku, transparentPng(), "cut-out.png", "image/png", null).statusCode(201)
            .extract().path("largeKey");
        BufferedImage stored = ImageIO.read(new ByteArrayInputStream(
            anonymous().get("/api/public/images/" + key).then().extract().asByteArray()));
        // A transparent corner comes out white, not black
        assertThat(new Color(stored.getRGB(2, 2)).getRed()).isGreaterThan(240);

        for (int i = 1; i < 8; i++) {
            upload(sku, jpeg(300, 300), "p" + i + ".jpg", "image/jpeg", null).statusCode(201);
        }
        upload(sku, jpeg(300, 300), "p9.jpg", "image/jpeg", null).statusCode(409).body("reason", containsString("at most 8"));
    }

    @Test
    void reorderingPicksTheMainPhoto_andDeletingRemovesTheFiles() throws Exception {
        String sku = newSku(5);
        setPrice(sku, 49.0);
        var first = upload(sku, jpeg(300, 300), "a.jpg", "image/jpeg", "Front").statusCode(201).extract().jsonPath();
        var second = upload(sku, jpeg(300, 300), "b.jpg", "image/jpeg", "Back").statusCode(201).extract().jsonPath();
        long firstId = first.getLong("id");
        long secondId = second.getLong("id");

        given().contentType(ContentType.JSON).body(Map.of("imageIds", List.of(secondId, firstId)))
            .put("/api/admin/catalog/" + sku + "/images/order").then().statusCode(200);
        anonymous().get("/api/public/catalog").then()
            .body("find { it.sku == '" + sku + "' }.imageKey", equalTo(second.getString("thumbKey")))
            .body("find { it.sku == '" + sku + "' }.imageAlt", equalTo("Back"));
        given().contentType(ContentType.JSON).body(Map.of("imageIds", List.of(secondId)))
            .put("/api/admin/catalog/" + sku + "/images/order").then().statusCode(400);

        given().delete("/api/admin/catalog/" + sku + "/images/" + secondId).then().statusCode(204);
        anonymous().get("/api/public/images/" + second.getString("largeKey")).then().statusCode(404);
        assertThatThrownBy(() -> minio().statObject(StatObjectArgs.builder().bucket(BUCKET)
            .object(second.getString("largeKey")).build())).hasMessageContaining("does not exist");
        anonymous().get("/api/public/catalog/" + sku).then()
            .body("images.id", not(hasItem((int) secondId)))
            .body("images[0].id", equalTo((int) firstId));

        // Only keys of known photos are served
        anonymous().get("/api/public/images/products/" + sku + "/nothing-here.jpg").then().statusCode(404);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private io.restassured.response.ValidatableResponse createCategory(String name, Long parentId) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("name", name));
        if (parentId != null) {
            body.put("parentId", parentId);
        }
        return given().contentType(ContentType.JSON).body(body).post("/api/admin/categories").then();
    }

    private io.restassured.response.ValidatableResponse upload(String sku, byte[] bytes, String name, String type, String alt) {
        var request = given().multiPart("file", name, bytes, type);
        if (alt != null) {
            request.multiPart("altText", alt);
        }
        return request.post("/api/admin/catalog/" + sku + "/images").then();
    }

    private int version(String sku) {
        return given().get("/api/admin/catalog/" + sku).then().extract().path("product.version");
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private static MinioClient minio() {
        return MinioClient.builder().endpoint(MINIO.getS3URL()).credentials(MINIO.getUserName(), MINIO.getPassword()).build();
    }

    static byte[] jpeg(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(30, 90, 160));
        g.fillRect(0, 0, width, height);
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, width / 4, height / 4);
        g.dispose();
        return write(image, "jpg");
    }

    static byte[] transparentPng() {
        BufferedImage image = new BufferedImage(400, 400, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.RED);
        g.fillOval(100, 100, 200, 200);
        g.dispose();
        return write(image, "png");
    }

    private static byte[] write(BufferedImage image, String format) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, format, out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Adds an EXIF block, as a phone camera does: an orientation tag, and a marker standing in for the
     * GPS position, which must not survive the upload.
     */
    static byte[] withExif(byte[] jpeg, int orientation, String marker) {
        ByteArrayOutputStream tiff = new ByteArrayOutputStream();
        tiff.writeBytes(new byte[] {'M', 'M', 0, 42, 0, 0, 0, 8});           // big-endian TIFF, IFD at 8
        tiff.writeBytes(new byte[] {0, 1});                                 // one entry
        tiff.writeBytes(new byte[] {0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0}); // Orientation, SHORT
        tiff.writeBytes(new byte[] {0, 0, 0, 0});                           // no next IFD
        tiff.writeBytes(marker.getBytes(StandardCharsets.US_ASCII));
        byte[] payload = tiff.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xD8);                                                     // start of image
        int length = 2 + 6 + payload.length;
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xE1, (byte) (length >> 8), (byte) length});
        out.writeBytes("Exif\0\0".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(payload);
        out.write(jpeg, 2, jpeg.length - 2);                                 // the rest of the original
        return out.toByteArray();
    }
}
