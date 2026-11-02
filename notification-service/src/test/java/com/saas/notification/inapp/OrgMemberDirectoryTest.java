package com.saas.notification.inapp;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The client side of "who are the admins and managers of this tenant" (REQ-NOTIF-03): asks precisely, and fails loudly. */
@Tag("REQ-NOTIF-03")
class OrgMemberDirectoryTest {

    private OrgDirectoryStub stub;
    private HttpServer custom;

    @AfterEach
    void stop() {
        if (stub != null) {
            stub.close();
        }
        if (custom != null) {
            custom.stop(0);
        }
    }

    private OrgMemberDirectory directory(String baseUrl) {
        return new OrgMemberDirectory(RestClient.builder(), baseUrl, Duration.ofMillis(500));
    }

    @Test
    void itAsksForTheTenantsMembersWithTheRolesSortedAndReturnsTheirIds() {
        stub = new OrgDirectoryStub();
        UUID tenant = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        UUID manager = UUID.randomUUID();
        stub.member(tenant, admin, "ADMIN");
        stub.member(tenant, manager, "MANAGER");
        stub.member(tenant, UUID.randomUUID(), "USER");
        stub.member(UUID.randomUUID(), UUID.randomUUID(), "ADMIN");

        var found = directory(stub.baseUrl()).memberIdsWithRoles(tenant, Set.of("MANAGER", "ADMIN"));

        assertThat(found).containsExactlyInAnyOrder(admin, manager);
        assertThat(stub.requests()).containsExactly("/internal/tenants/" + tenant + "/members?roles=ADMIN,MANAGER");
    }

    @Test
    void aTenantWithNoSuchMembersIsAnEmptyListNotAnError() {
        stub = new OrgDirectoryStub();

        assertThat(directory(stub.baseUrl()).memberIdsWithRoles(UUID.randomUUID(), Set.of("ADMIN"))).isEmpty();
    }

    @Test
    void aServerErrorIsAnExceptionSoTheEventIsRetriedAndNotSilentlyDropped() {
        stub = new OrgDirectoryStub();
        stub.failWith(500);

        assertThatThrownBy(() -> directory(stub.baseUrl()).memberIdsWithRoles(UUID.randomUUID(), Set.of("ADMIN")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("could not ask organization-service");
    }

    @Test
    void aClientErrorIsAnException() {
        stub = new OrgDirectoryStub();
        stub.failWith(404);

        assertThatThrownBy(() -> directory(stub.baseUrl()).memberIdsWithRoles(UUID.randomUUID(), Set.of("ADMIN")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anUnreachableServiceIsAnException() {
        stub = new OrgDirectoryStub();
        String closed = stub.baseUrl();
        stub.close();
        stub = null;

        assertThatThrownBy(() -> directory(closed).memberIdsWithRoles(UUID.randomUUID(), Set.of("ADMIN")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aServiceThatDoesNotAnswerInTimeIsAnException() throws IOException {
        custom = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        custom.createContext("/", exchange -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        custom.start();

        assertThatThrownBy(() -> directory("http://127.0.0.1:" + custom.getAddress().getPort())
                .memberIdsWithRoles(UUID.randomUUID(), Set.of("ADMIN"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anAnswerWithoutAListOfIdsIsAnException() throws IOException {
        custom = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        custom.createContext("/", exchange -> {
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        custom.start();

        assertThatThrownBy(() -> directory("http://127.0.0.1:" + custom.getAddress().getPort())
                .memberIdsWithRoles(UUID.randomUUID(), Set.of("ADMIN"))).isInstanceOf(IllegalStateException.class);
    }
}
