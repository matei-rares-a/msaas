package com.saas.notification;

import com.saas.testsupport.api.ApiSnapshots;
import com.saas.testsupport.containers.PostgresTestContainer;
import graphql.schema.idl.SchemaPrinter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.execution.GraphQlSource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The API of this service against its committed snapshots (REQ-ARCH-03, REQ-MON-08): the REST API (OpenAPI) and the
 * GraphQL schema of the journal may only grow. Taking out or changing anything a client could rely on fails the build;
 * so does an addition the snapshot does not have yet, which is regenerated with {@code -Dapi.snapshot.update=true}.
 */
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.kafka.listener.auto-startup=false",
        "test.marker=ApiSnapshotIT"
})
@AutoConfigureMockMvc
@Tag("REQ-ARCH-03")
class ApiSnapshotIT implements PostgresTestContainer {

    private static final String OPENAPI = "openapi.json";
    private static final String GRAPHQL = "activity-log.graphqls";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private GraphQlSource graphQlSource;

    private String openApi() throws Exception {
        return mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String graphQlSchema() {
        return new SchemaPrinter(SchemaPrinter.Options.defaultOptions().includeIntrospectionTypes(false).includeDirectiveDefinitions(false)).print(graphQlSource.schema());
    }

    @Test
    void theRestApiOnlyGrewSinceItsSnapshot() throws Exception {
        ApiSnapshots.verifyOpenApi(OPENAPI, openApi());
    }

    @Test
    void takingAPathOrAPropertyOutOfTheRestApiWouldFailTheBuild() throws Exception {
        ApiSnapshots.assertOpenApiRemovalsAreCaught(OPENAPI, openApi());
    }

    @Test
    @Tag("REQ-MON-08")
    void theJournalSchemaOnlyGrewSinceItsSnapshot() {
        ApiSnapshots.verifyGraphQl(GRAPHQL, graphQlSchema());
    }

    @Test
    @Tag("REQ-MON-08")
    void takingAFieldOutOfTheJournalSchemaWouldFailTheBuild() {
        ApiSnapshots.assertGraphQlRemovalsAreCaught(GRAPHQL, graphQlSchema());
    }
}
