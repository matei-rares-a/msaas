package com.saas.e2e;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What every end-to-end test needs: the gateway, a client for the calls a user makes, and read access to the database for
 * the two things an email would carry (an invitation code, a password reset code) and for the plan catalog. Everything else
 * goes through the gateway exactly as a client would, so the tests prove the services work together, not each on their own.
 */
public final class Platform {

    public static final String PASSWORD = "Correct-Horse-9-Battery";

    private static final String GATEWAY = System.getProperty("e2e.gateway", "http://localhost:18080");
    private static final String DB_URL = System.getProperty("e2e.db.url", "jdbc:postgresql://localhost:15432");
    private static final String DB_USER = System.getProperty("e2e.db.user", "postgres");
    private static final String DB_PASSWORD = System.getProperty("e2e.db.password", "");
    private static final AtomicInteger COUNTER = new AtomicInteger();

    static {
        RestAssured.baseURI = GATEWAY;
        RestAssured.useRelaxedHTTPSValidation();
    }

    private Platform() {
    }

    /** A client for the calls a person makes: a token, or none. */
    public static RequestSpecification as(String token) {
        RequestSpecification spec = RestAssured.given().contentType(ContentType.JSON).accept(ContentType.JSON);
        return token == null ? spec : spec.header("Authorization", "Bearer " + token);
    }

    public static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "-" + COUNTER.incrementAndGet() + "@e2e.example";
    }

    /** A person with an account and, once they have one, a token for the organization they chose. */
    public static final class User {
        public final String email;
        private String token;
        private UUID tenantId;

        private User(String email) {
            this.email = email;
        }

        public String token() {
            return token;
        }

        public UUID tenantId() {
            return tenantId;
        }

        public RequestSpecification api() {
            return as(token);
        }

        public UUID id() {
            return UUID.fromString(db("saas_global", "SELECT id FROM auth.users WHERE email = ?", email));
        }

        /** Logs in again and keeps the token the server gives; only valid for a user with exactly one organization. */
        public User signIn() {
            Response response = as(null).body(Map.of("email", email, "password", PASSWORD)).post("/auth/login");
            response.then().statusCode(200);
            token = response.path("accessToken");
            if (token == null) {
                throw new IllegalStateException("login of " + email + " did not give a token: " + response.asString());
            }
            tenantId = UUID.fromString(new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1])).replaceAll(".*\"tenantId\":\"([^\"]+)\".*", "$1"));
            return this;
        }

        /** Signs in to a tenant the user belongs to among several, through the choice a multi-tenant login offers. */
        public User signInTo(UUID tenant) {
            Response login = as(null).body(Map.of("email", email, "password", PASSWORD)).post("/auth/login");
            login.then().statusCode(200);
            if (login.path("accessToken") != null) {
                return signIn();
            }
            Response chosen = as(null).body(Map.of("loginSessionId", login.path("loginSessionId"), "tenantId", tenant.toString())).post("/auth/login/tenant");
            chosen.then().statusCode(200);
            token = chosen.path("accessToken");
            tenantId = tenant;
            return this;
        }
    }

    /** Registers an account: no token, no organization (a new user belongs to nothing yet). */
    public static User register(String prefix) {
        String email = uniqueEmail(prefix);
        as(null).body(Map.of("email", email, "password", PASSWORD)).post("/auth/register").then().statusCode(201);
        return new User(email);
    }

    /** The identity token a user with no organization holds after logging in. */
    public static String identityToken(User user) {
        Response login = as(null).body(Map.of("email", user.email, "password", PASSWORD)).post("/auth/login");
        login.then().statusCode(200);
        String identity = login.path("identityToken");
        if (identity == null) {
            throw new IllegalStateException(user.email + " is already in an organization: " + login.asString());
        }
        return identity;
    }

    private static UUID roomyPlan;

    private static synchronized UUID roomyPlan() {
        if (roomyPlan == null) {
            roomyPlan = plan(1000);
        }
        return roomyPlan;
    }

    /**
     * A new account that creates an organization and so becomes its ADMIN, signed in to it, with a subscription to a plan with room
     * for everyone a test needs: until a plan is known to the organization service, invitations are blocked (REQ-BILL-03).
     */
    public static User organizationWithAdmin(String name) {
        return organizationWithPlan(name, roomyPlan(), 1000);
    }

    public static User organizationWithPlan(String name, UUID plan, int maxUsers) {
        User admin = register("admin");
        String identity = identityToken(admin);
        as(identity).body(Map.of("name", name)).post("/organizations").then().statusCode(201);
        admin.signIn();
        admin.api().body(Map.of("planId", plan.toString())).post("/billing/subscriptions").then().statusCode(org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.is(200), org.hamcrest.Matchers.is(201)));
        // the plan reaches the organization service by an event, not by a call
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(90)).pollInterval(java.time.Duration.ofMillis(500)).until(() ->
                String.valueOf(maxUsers).equals(db("saas_shard_1", "SELECT max_users FROM org.tenants WHERE id = ?::uuid", admin.tenantId().toString())));
        return admin;
    }

    /** A new account invited by the admin with the role, who accepted and is signed in to that organization. */
    public static User invitedMember(User admin, String role) {
        User member = register("member");
        Response invitation = admin.api().body(Map.of("email", member.email, "role", role)).post("/organizations/" + admin.tenantId() + "/invitations");
        invitation.then().statusCode(201);
        String code = db("saas_shard_1", "SELECT token FROM org.invitations WHERE email = ? AND tenant_id = ?::uuid", member.email, admin.tenantId().toString());
        as(identityToken(member)).body(Map.of("token", code)).post("/invitations/accept").then().statusCode(200);
        return member.signIn();
    }

    // ---- the database, for what an email would carry ----

    /** The first column of the first row, or null: for a code that only an email gives, and for the catalog of plans. */
    public static String db(String database, String sql, String... parameters) {
        try (Connection connection = DriverManager.getConnection(DB_URL + "/" + database, DB_USER, DB_PASSWORD);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setString(i + 1, parameters[i]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("database " + database + ": " + sql, e);
        }
    }

    public static void dbUpdate(String database, String sql, String... parameters) {
        try (Connection connection = DriverManager.getConnection(DB_URL + "/" + database, DB_USER, DB_PASSWORD);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setString(i + 1, parameters[i]);
            }
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("database " + database + ": " + sql, e);
        }
    }

    /** A plan in the global catalog, which is owned by the platform and has no endpoint a client could add to. */
    public static UUID plan(int maxUsers) {
        UUID id = UUID.randomUUID();
        dbUpdate("saas_global", "INSERT INTO global.plans (id, name, price_eur, max_users) VALUES (?::uuid, ?, 9.99, " + maxUsers + ")",
                id.toString(), "E2E " + id.toString().substring(0, 6));
        return id;
    }

    /** The event types of the tenant journal, newest first, read through the GraphQL API an ADMIN or a MANAGER has. */
    public static List<String> journalTypes(User reader) {
        Response response = reader.api().body(Map.of("query", "query { activityLog(size: 100) { items { eventType } } }")).post("/notifications/graphql");
        response.then().statusCode(200);
        return response.jsonPath().getList("data.activityLog.items.eventType", String.class);
    }

    public static List<String> unreadTypes(User user) {
        Response page = user.api().queryParam("size", 50).get("/notifications/in-app");
        page.then().statusCode(200);
        List<String> types = new ArrayList<>();
        page.jsonPath().getList("items.findAll { !it.read }.eventType", String.class).forEach(types::add);
        return types;
    }
}
