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
}
