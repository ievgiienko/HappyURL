package shorturl;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.testng.AbstractTestNGSpringContextTests;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@TestPropertySource(properties = {
        "custom.domain=localhost",
        "server.port=8080"
})
public class ApiTests extends AbstractTestNGSpringContextTests {

    // These values must match the ones in TestPropertySource/application.properties
    private static final String DOMAIN = "localhost";
    private static final int PORT = 8080;
    private static final String BASE_SHORT_URL_PREFIX = "http://" + DOMAIN + ":" + PORT + "/my/";

    @BeforeClass
    public void setUp() {
        RestAssured.baseURI = "http://" + DOMAIN;
        RestAssured.port = PORT;
    }

    // Utility to extract the ID from a full short URL string
    private String extractId(String fullShortUrl) {
        // Assumes format: http://domain:port/my/{id}
        return fullShortUrl.substring(fullShortUrl.lastIndexOf("/") + 1);
    }

    // 1. Test GET /shorten_simple with valid URL parameter
    @Test
    public void testShortenSimple_validUrl() {
        String longUrl = "https://example.com";
        Response response =
                given()
                        .queryParam("url", longUrl)
                        .when()
                        .get("/shorten_simple")
                        .then()
                        .statusCode(200)
                        .contentType(ContentType.JSON)
                        .extract().response();

        String returnedUrl = response.jsonPath().getString("url");
        String fullShortUrl = response.jsonPath().getString("shortUrl");

        Assert.assertEquals(returnedUrl, longUrl, "Returned URL should match the input");
        Assert.assertTrue(fullShortUrl.startsWith(BASE_SHORT_URL_PREFIX),
                "Short URL should start with " + BASE_SHORT_URL_PREFIX);
        Assert.assertFalse(extractId(fullShortUrl).isEmpty(), "Extracted id should not be empty");
    }

    // 2. Test POST /shorten with valid JSON payload
    @Test
    public void testShorten_validUrl() {
        String longUrl = "https://openai.com";
        Response response =
                given()
                        .contentType(ContentType.JSON)
                        .body("{\"url\":\"" + longUrl + "\"}")
                        .when()
                        .post("/shorten")
                        .then()
                        .statusCode(200)
                        .contentType(ContentType.JSON)
                        .extract().response();

        String returnedUrl = response.jsonPath().getString("url");
        String fullShortUrl = response.jsonPath().getString("shortUrl");

        Assert.assertEquals(returnedUrl, longUrl, "Returned URL should match the input");
        Assert.assertTrue(fullShortUrl.startsWith(BASE_SHORT_URL_PREFIX),
                "Short URL should start with " + BASE_SHORT_URL_PREFIX);
        Assert.assertFalse(extractId(fullShortUrl).isEmpty(), "Extracted id should not be empty");
    }

    // 3. Test GET /my/{id} redirection with valid ID (after creating one)
    @Test
    public void testRedirect_validId() {
        // Create a shortened URL
        String longUrl = "https://spring.io";
        String fullShortUrl =
                given()
                        .queryParam("url", longUrl)
                        .when()
                        .get("/shorten_simple")
                        .then()
                        .statusCode(200)
                        .extract().jsonPath().getString("shortUrl");

        String id = extractId(fullShortUrl);

        // Now test redirection without following the redirect
        Response response =
                given()
                        .redirects().follow(false)
                        .when()
                        .get("/my/" + id)
                        .then()
                        .statusCode(302)
                        .extract().response();

        // Verify the Location header and Cache-Control header
        String location = response.getHeader("Location");
        Assert.assertEquals(location, longUrl, "Location header should match original URL");

        String cacheControl = response.getHeader("Cache-Control");
        Assert.assertEquals(cacheControl, "no-cache, no-store, must-revalidate", "Cache-Control header mismatch");
    }

    // 4. Test GET /my/{id} with an invalid/non-existent id
    @Test
    public void testRedirect_invalidId() {
        // Use an id that is unlikely to exist
        String invalidId = "999999999";
        Response response =
                given()
                        .redirects().follow(false)
                        .when()
                        .get("/my/" + invalidId)
                        .then()
                        .extract().response();
        int statusCode = response.statusCode();
        Assert.assertTrue(statusCode == 302 || statusCode >= 400,
                "Invalid id should produce an error or not found status");
    }

    // 5. Test GET /stat returns a list (even if empty)
    @Test
    public void testStat_returnsList() {
        Response response =
                given()
                        .when()
                        .get("/stat")
                        .then()
                        .statusCode(200)
                        .contentType(ContentType.JSON)
                        .extract().response();
        List<Map<String, Object>> stats = response.jsonPath().getList("$");
        Assert.assertNotNull(stats, "Statistics list should not be null");
    }

    // 6. Test that creating the same URL twice returns the same shortened URL
    @Test
    public void testMultipleShortenSameUrl() {
        String longUrl = "https://duplicate.com";
        String fullShortUrl1 =
                given()
                        .queryParam("url", longUrl)
                        .when()
                        .get("/shorten_simple")
                        .then()
                        .extract().jsonPath().getString("shortUrl");

        String fullShortUrl2 =
                given()
                        .contentType(ContentType.JSON)
                        .body("{\"url\":\"" + longUrl + "\"}")
                        .when()
                        .post("/shorten")
                        .then()
                        .extract().jsonPath().getString("shortUrl");

        Assert.assertEquals(fullShortUrl1, fullShortUrl2, "The same URL should have the same shortened URL");
    }

    // !!!! // 7. Test that after a redirection the count and lastAccess update
    @Test
    public void testCountAndLastAccess_update() throws InterruptedException {
        String longUrl = "https://update.com";
        // Create shortened URL
        String fullShortUrl =
                given()
                        .queryParam("url", longUrl)
                        .when()
                        .get("/shorten_simple")
                        .then()
                        .extract().jsonPath().getString("shortUrl");

        String id = extractId(fullShortUrl);

        // Get initial stats for this URL
        List<Map<String, Object>> statsBefore =
                given()
                        .when()
                        .get("/stat")
                        .then()
                        .extract().jsonPath().getList("$");

        Map<String, Object> targetBefore = statsBefore.stream()
                .filter(map -> fullShortUrl.equals(map.get("shortUrl")))
                .findFirst().orElse(null);
        Assert.assertNotNull(targetBefore, "URL should be present in stats");
        Number countBefore = (Number) targetBefore.get("redirects");
        String lastAccessBefore = (String) targetBefore.get("lastAccess");

        // Wait a bit to ensure lastAccess changes
        Thread.sleep(1000);

        // Perform redirection without following it
        given()
                .redirects().follow(false)
                .when()
                .get("/my/" + id)
                .then()
                .statusCode(302);

        // Get stats after redirection
        List<Map<String, Object>> statsAfter =
                given()
                        .when()
                        .get("/stat")
                        .then()
                        .extract().jsonPath().getList("$");

        Map<String, Object> targetAfter = statsAfter.stream()
                .filter(map -> fullShortUrl.equals(map.get("shortUrl")))
                .findFirst().orElse(null);
        Assert.assertNotNull(targetAfter, "URL should be present in stats after redirection");
        Number countAfter = (Number) targetAfter.get("redirects");
        String lastAccessAfter = (String) targetAfter.get("lastAccess");

        Assert.assertEquals(countAfter.longValue(), countBefore.longValue() + 1, "Redirect count should increment");
        Assert.assertNotEquals(lastAccessBefore, lastAccessAfter, "Last access timestamp should update");
    }

    // 8. Test POST /shorten with an empty URL (bad request scenario)
    @Test
    public void testShorten_emptyUrl() {
        Response response =
                given()
                        .contentType(ContentType.JSON)
                        .body("{\"url\":\"\"}")
                        .when()
                        .post("/shorten")
                        .then()
                        .extract().response();

        // Depending on implementation, either 200 or 400 may be returned.
        Assert.assertTrue(response.statusCode() == 200 || response.statusCode() == 400,
                "Empty URL should either be handled or result in a bad request");
    }

    // 9. Test calling an invalid HTTP method on /my/{id} endpoint
    @Test
    public void testInvalidMethod_onRedirect() {
        String someId = "1";
        given()
                .when()
                .post("/my/" + someId)
                .then()
                .statusCode(405); // Method Not Allowed expected
    }

    // 10. Test that /shorten_simple response has JSON content type header
    @Test
    public void testContentTypeJson_onShortenSimple() {
        given()
                .queryParam("url", "https://jsonheader.com")
                .when()
                .get("/shorten_simple")
                .then()
                .contentType(ContentType.JSON);
    }

    // 11. Test that redirect endpoint returns the correct Cache-Control header
    @Test
    public void testCacheControlHeader_onRedirect() {
        String longUrl = "https://cachecontrol.com";
        String fullShortUrl =
                given()
                        .queryParam("url", longUrl)
                        .when()
                        .get("/shorten_simple")
                        .then()
                        .extract().jsonPath().getString("shortUrl");

        String id = extractId(fullShortUrl);

        Response response =
                given()
                        .redirects().follow(false)
                        .when()
                        .get("/my/" + id)
                        .then()
                        .statusCode(302)
                        .extract().response();

        String cacheControl = response.getHeader("Cache-Control");
        Assert.assertEquals(cacheControl, "no-cache, no-store, must-revalidate", "Cache-Control header mismatch");
    }

    // 12. Test that redirect endpoint returns Location header with the original URL
    @Test
    public void testLocationHeader_onRedirect() {
        String longUrl = "https://locationheader.com";
        String fullShortUrl =
                given()
                        .queryParam("url", longUrl)
                        .when()
                        .get("/shorten_simple")
                        .then()
                        .extract().jsonPath().getString("shortUrl");

        String id = extractId(fullShortUrl);

        Response response =
                given()
                        .redirects().follow(false)
                        .when()
                        .get("/my/" + id)
                        .then()
                        .statusCode(302)
                        .extract().response();

        String location = response.getHeader("Location");
        Assert.assertEquals(location, longUrl, "Location header should equal the original URL");
    }

    // 13. Test that GET /stat returns JSON array with proper fields
    @Test
    public void testStat_fields() {
        Response response =
                given()
                        .when()
                        .get("/stat")
                        .then()
                        .statusCode(200)
                        .contentType(ContentType.JSON)
                        .extract().response();

        List<Map<String, Object>> stats = response.jsonPath().getList("$");
        if (!stats.isEmpty()) {
            Map<String, Object> entry = stats.get(0);
            Assert.assertTrue(entry.containsKey("url"), "Stat entry should contain 'url'");
            Assert.assertTrue(entry.containsKey("shortUrl"), "Stat entry should contain 'shortUrl'");
            Assert.assertTrue(entry.containsKey("redirects"), "Stat entry should contain 'redirects'");
            Assert.assertTrue(entry.containsKey("lastAccess"), "Stat entry should contain 'lastAccess'");
        }
    }

    // 14. Test POST /shorten returns a valid JSON containing both url and shortUrl
    @Test
    public void testShortenUsingJSON_returnsValidJSON() {
        String longUrl = "https://jsonvalid.com";
        Response response =
                given()
                        .contentType(ContentType.JSON)
                        .body("{\"url\":\"" + longUrl + "\"}")
                        .when()
                        .post("/shorten")
                        .then()
                        .statusCode(200)
                        .contentType(ContentType.JSON)
                        .extract().response();

        String returnedUrl = response.jsonPath().getString("url");
        String fullShortUrl = response.jsonPath().getString("shortUrl");

        Assert.assertEquals(returnedUrl, longUrl, "Returned URL should match");
        Assert.assertTrue(fullShortUrl.startsWith(BASE_SHORT_URL_PREFIX),
                "Short URL should start with " + BASE_SHORT_URL_PREFIX);
    }

    // !!! // 15. Test that after multiple redirections, the statistics reflect the correct count
    @Test
    public void testMultipleRedirects_updateStat() {
        String longUrl = "https://multiple.com";
        String fullShortUrl =
                given()
                        .queryParam("url", longUrl)
                        .when()
                        .get("/shorten_simple")
                        .then()
                        .extract().jsonPath().getString("shortUrl");

        String id = extractId(fullShortUrl);

        // Perform three redirects without following them
        for (int i = 0; i < 3; i++) {
            given()
                    .redirects().follow(false)
                    .when()
                    .get("/my/" + id)
                    .then()
                    .statusCode(302);
        }

        // Get stats and check the count is at least 3
        Response statResponse =
                given()
                        .when()
                        .get("/stat")
                        .then()
                        .statusCode(200)
                        .extract().response();

        List<Map<String, Object>> stats = statResponse.jsonPath().getList("$");
        Map<String, Object> target = stats.stream()
                .filter(map -> fullShortUrl.equals(map.get("shortUrl")))
                .findFirst().orElse(null);
        Assert.assertNotNull(target, "Stat entry for the URL should be present");
        Number redirects = (Number) target.get("redirects");
        Assert.assertTrue(redirects.longValue() >= 3, "Redirect count should be at least 3");
    }
}
