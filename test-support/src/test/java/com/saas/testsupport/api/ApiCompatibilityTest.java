package com.saas.testsupport.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The additive-only rule for REST and GraphQL (REQ-ARCH-03, REQ-MON-08): taking a field, a path, an operation or a
 * parameter out of an API, or changing what it is, is reported; adding to it is not. The documents here are small
 * hand-written ones, so each rule is shown on its own; the per-service snapshot tests run the same checks on the real
 * ones.
 */
@Tag("REQ-ARCH-03")
class ApiCompatibilityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String THING = """
            "Thing": {"type": "object", "required": ["id"], "properties": {
                "id": {"type": "string", "format": "uuid"},
                "name": {"type": "string"},
                "status": {"type": "string", "enum": ["ACTIVE", "SUSPENDED"]},
                "part": {"$ref": "#/components/schemas/Part"},
                "tags": {"type": "array", "items": {"$ref": "#/components/schemas/Part"}}}}""";
    private static final String PART = """
            "Part": {"type": "object", "properties": {"size": {"type": "integer", "format": "int32"},
                "parent": {"$ref": "#/components/schemas/Part"}}}""";
    private static final String CREATE = """
            "CreateThing": {"type": "object", "required": ["name"], "properties": {"name": {"type": "string"}, "note": {"type": "string"}}}""";

    private static final String GET_THINGS = """
            "/things": {"get": {"parameters": [{"name": "page", "in": "query", "required": false, "schema": {"type": "integer"}}],
                "responses": {"200": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/Thing"}}}}, "404": {"description": "none"}}},
              "post": {"requestBody": {"required": true, "content": {"application/json": {"schema": {"$ref": "#/components/schemas/CreateThing"}}}},
                "responses": {"201": {"description": "created"}}}}""";

    private static JsonNode doc(String paths, String... schemas) {
        try {
            return MAPPER.readTree("{\"openapi\":\"3.0.1\",\"paths\":{" + paths + "},\"components\":{\"schemas\":{" + String.join(",", schemas) + "}}}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode base() {
        return doc(GET_THINGS, THING, PART, CREATE);
    }

    private static List<String> changes(JsonNode before, JsonNode after) {
        return OpenApiCompatibility.incompatibleChanges(before, after);
    }

    // ---- what is incompatible ----

    @Test
    void anUnchangedDocumentHasNoIncompatibleChange() {
        assertThat(changes(base(), base())).isEmpty();
    }

    @Test
    void aPropertyTakenOutOfAResponseIsReportedWhereverItIs() {
        String withoutName = THING.replace("\"name\": {\"type\": \"string\"},", "");
        String withoutSize = PART.replace("\"size\": {\"type\": \"integer\", \"format\": \"int32\"},", "");

        assertThat(changes(base(), doc(GET_THINGS, withoutName, PART, CREATE))).anyMatch(c -> c.contains("property removed: name"));
        assertThat(changes(base(), doc(GET_THINGS, THING, withoutSize, CREATE))).as("also inside a nested and an array type")
                .anyMatch(c -> c.contains("property removed: size"));
    }

    @Test
    void aPathAnOperationAndAResponseTakenOutAreReported() {
        assertThat(changes(base(), doc("", THING, PART, CREATE))).contains("path removed: /things");
        assertThat(changes(base(), doc(GET_THINGS.replace("\"post\"", "\"put\""), THING, PART, CREATE))).contains("operation removed: POST /things");
        assertThat(changes(base(), doc(GET_THINGS.replace(", \"404\": {\"description\": \"none\"}", ""), THING, PART, CREATE)))
                .contains("GET /things: response 404 removed");
    }

    @Test
    void aChangedTypeOrFormatIsReported() {
        String retyped = THING.replace("\"name\": {\"type\": \"string\"}", "\"name\": {\"type\": \"integer\"}");
        String reformatted = PART.replace("int32", "int64");

        assertThat(changes(base(), doc(GET_THINGS, retyped, PART, CREATE))).anyMatch(c -> c.contains("type changed from string to integer"));
        assertThat(changes(base(), doc(GET_THINGS, THING, reformatted, CREATE))).anyMatch(c -> c.contains("format changed from int32 to int64"));
    }

    @Test
    void anEnumValueTakenOutIsReported() {
        String narrowed = THING.replace("[\"ACTIVE\", \"SUSPENDED\"]", "[\"ACTIVE\"]");

        assertThat(changes(base(), doc(GET_THINGS, narrowed, PART, CREATE))).anyMatch(c -> c.contains("enum value removed: SUSPENDED"));
    }

    @Test
    void aParameterTakenOutOrMadeRequiredOrAddedAsRequiredIsReported() {
        String removed = GET_THINGS.replace("\"parameters\": [{\"name\": \"page\", \"in\": \"query\", \"required\": false, \"schema\": {\"type\": \"integer\"}}],", "");
        String required = GET_THINGS.replace("\"required\": false", "\"required\": true");
        String extra = GET_THINGS.replace("\"parameters\": [", "\"parameters\": [{\"name\": \"tenant\", \"in\": \"query\", \"required\": true, \"schema\": {\"type\": \"string\"}},");

        assertThat(changes(base(), doc(removed, THING, PART, CREATE))).anyMatch(c -> c.contains("parameter removed: query page"));
        assertThat(changes(base(), doc(required, THING, PART, CREATE))).anyMatch(c -> c.contains("parameter became required: query page"));
        assertThat(changes(base(), doc(extra, THING, PART, CREATE))).anyMatch(c -> c.contains("new required parameter: query tenant"));
    }

    @Test
    void whatTheClientMustNowSendIsReported() {
        String requiredNote = CREATE.replace("\"required\": [\"name\"]", "\"required\": [\"name\", \"note\"]");
        String withoutBody = GET_THINGS.replace("\"requestBody\": {\"required\": true, \"content\": {\"application/json\": {\"schema\": {\"$ref\": \"#/components/schemas/CreateThing\"}}}},", "");
        String newRequired = CREATE.replace("\"properties\": {", "\"properties\": {\"kind\": {\"type\": \"string\"}, ").replace("[\"name\"]", "[\"name\", \"kind\"]");

        assertThat(changes(base(), doc(GET_THINGS, THING, PART, requiredNote))).anyMatch(c -> c.contains("property became required: note"));
        assertThat(changes(base(), doc(GET_THINGS, THING, PART, newRequired))).anyMatch(c -> c.contains("new required property: kind"));
        assertThat(changes(base(), doc(withoutBody, THING, PART, CREATE))).contains("POST /things: request body removed");
    }

    @Test
    void aResponsePropertyThatIsNoLongerAlwaysThereIsReported() {
        String optional = THING.replace("\"required\": [\"id\"], ", "");

        assertThat(changes(base(), doc(GET_THINGS, optional, PART, CREATE))).anyMatch(c -> c.contains("property is no longer always present: id"));
    }

    @Test
    void aRecursiveTypeDoesNotLoop() {
        assertThat(changes(base(), base())).isEmpty();
        assertThat(PART).contains("\"parent\": {\"$ref\": \"#/components/schemas/Part\"}");
    }

    // ---- what is not ----

    @Test
    void anAdditionIsNotIncompatible() {
        String morePaths = GET_THINGS + ", \"/v2/things\": {\"get\": {\"responses\": {\"200\": {\"description\": \"ok\"}}}}";
        String moreOperations = GET_THINGS.replace("\"post\"", "\"delete\": {\"responses\": {\"204\": {\"description\": \"gone\"}}}, \"post\"");
        String optionalParameter = GET_THINGS.replace("\"parameters\": [", "\"parameters\": [{\"name\": \"size\", \"in\": \"query\", \"schema\": {\"type\": \"integer\"}},");
        String moreProperties = THING.replace("\"properties\": {", "\"properties\": {\"createdAt\": {\"type\": \"string\"}, ");
        String moreEnum = THING.replace("[\"ACTIVE\", \"SUSPENDED\"]", "[\"ACTIVE\", \"SUSPENDED\", \"CLOSED\"]");
        String optionalRequestField = CREATE.replace("\"properties\": {", "\"properties\": {\"label\": {\"type\": \"string\"}, ");

        assertThat(changes(base(), doc(morePaths, THING, PART, CREATE))).isEmpty();
        assertThat(changes(base(), doc(moreOperations, THING, PART, CREATE))).isEmpty();
        assertThat(changes(base(), doc(optionalParameter, THING, PART, CREATE))).isEmpty();
        assertThat(changes(base(), doc(GET_THINGS, moreProperties, PART, CREATE))).isEmpty();
        assertThat(changes(base(), doc(GET_THINGS, moreEnum, PART, CREATE))).isEmpty();
        assertThat(changes(base(), doc(GET_THINGS, THING, PART, optionalRequestField))).isEmpty();
    }

    @Test
    void aBreakingChangeBehindANewVersionedPathIsAnAddition() {
        String v2 = GET_THINGS + ", \"/v2/things\": {\"get\": {\"responses\": {\"200\": {\"content\": {\"application/json\": {\"schema\": {\"$ref\": \"#/components/schemas/ThingV2\"}}}}}}}";
        String thingV2 = "\"ThingV2\": {\"type\": \"object\", \"properties\": {\"identifier\": {\"type\": \"string\"}}}";

        assertThat(changes(base(), doc(v2, THING, PART, CREATE, thingV2))).isEmpty();
    }

    // ---- the verdict a snapshot test reports ----

    @Test
    void aRemovalIsABrokenContractAndAnAdditionIsAStaleSnapshotAndAWordingChangeIsNeither() {
        String snapshot = ApiSnapshots.normalizeOpenApi(base().toString());
        String removed = ApiSnapshots.normalizeOpenApi(doc(GET_THINGS, THING.replace("\"name\": {\"type\": \"string\"},", ""), PART, CREATE).toString());
        String added = ApiSnapshots.normalizeOpenApi(doc(GET_THINGS, THING.replace("\"properties\": {", "\"properties\": {\"createdAt\": {\"type\": \"string\"}, "), PART, CREATE).toString());
        String reworded = ApiSnapshots.normalizeOpenApi(base().toString().replace("\"description\":\"none\"", "\"description\":\"nothing here\""));

        ApiSnapshots.Verdict broken = ApiSnapshots.compareOpenApi(snapshot, removed);
        ApiSnapshots.Verdict stale = ApiSnapshots.compareOpenApi(snapshot, added);

        assertThat(broken.incompatible()).isNotEmpty();
        assertThat(broken.describe("x")).contains("breaks existing clients").contains("Do not update the snapshot");
        assertThat(stale.incompatible()).isEmpty();
        assertThat(stale.stale()).isNotEmpty();
        assertThat(stale.describe("x")).contains("out of date").contains("-D" + ApiSnapshots.UPDATE_PROPERTY + "=true");
        assertThat(ApiSnapshots.compareOpenApi(snapshot, reworded).ok()).isTrue();
        assertThat(ApiSnapshots.compareOpenApi(snapshot, snapshot).ok()).isTrue();
    }

    @Test
    void theSnapshotFormIgnoresTheOrderOfThingsAndTheServerUrl() throws Exception {
        JsonNode first = MAPPER.readTree("{\"servers\":[{\"url\":\"http://localhost\"}],\"paths\":{\"/b\":{},\"/a\":{}},"
                + "\"x\":{\"required\":[\"b\",\"a\"],\"parameters\":[{\"name\":\"z\",\"in\":\"query\"},{\"name\":\"a\",\"in\":\"query\"}]}}");
        JsonNode second = MAPPER.readTree("{\"x\":{\"parameters\":[{\"in\":\"query\",\"name\":\"a\"},{\"in\":\"query\",\"name\":\"z\"}],"
                + "\"required\":[\"a\",\"b\"]},\"paths\":{\"/a\":{},\"/b\":{}}}");

        assertThat(ApiSnapshots.normalizeOpenApi(first.toString())).isEqualTo(ApiSnapshots.normalizeOpenApi(second.toString()))
                .doesNotContain("servers").doesNotContain("\r");
    }

    // ---- GraphQL ----

    private static final String SCHEMA = """
            type Query {
                entries(filter: Filter, page: Int = 0): Page!
            }
            input Filter { from: String, kinds: [String!] }
            type Page { items: [Entry!]!, total: Int! }
            type Entry { id: ID!, kind: String!, actor: String, note: String }
            """;

    @Test
    @Tag("REQ-MON-08")
    void aFieldTakenOutOfTheGraphQlSchemaIsReported() {
        assertThat(GraphQlCompatibility.incompatibleChanges(SCHEMA, SCHEMA.replace(", note: String", "")))
                .anyMatch(c -> c.contains("Entry.note"));
        assertThat(GraphQlCompatibility.incompatibleChanges(SCHEMA, SCHEMA.replace("total: Int!", "")))
                .anyMatch(c -> c.contains("Page.total"));
    }

    @Test
    @Tag("REQ-MON-08")
    void aChangedFieldTypeATakenOutArgumentAndANewRequiredArgumentAreReported() {
        assertThat(GraphQlCompatibility.incompatibleChanges(SCHEMA, SCHEMA.replace("kind: String!", "kind: Int!"))).isNotEmpty();
        assertThat(GraphQlCompatibility.incompatibleChanges(SCHEMA, SCHEMA.replace(", page: Int = 0", ""))).isNotEmpty();
        assertThat(GraphQlCompatibility.incompatibleChanges(SCHEMA, SCHEMA.replace("filter: Filter", "filter: Filter, tenant: String!"))).isNotEmpty();
        assertThat(GraphQlCompatibility.incompatibleChanges(SCHEMA, SCHEMA.replace("input Filter { from: String, kinds: [String!] }", "input Filter { kinds: [String!] }")))
                .isNotEmpty();
    }

    @Test
    @Tag("REQ-MON-08")
    void deprecatingAFieldAndAddingANewOneIsAllowed() {
        String evolved = SCHEMA.replace("note: String", "note: String @deprecated(reason: \"use comment\"), comment: String")
                .replace("entries(filter: Filter, page: Int = 0)", "entries(filter: Filter, page: Int = 0, size: Int = 20)");

        assertThat(GraphQlCompatibility.incompatibleChanges(SCHEMA, evolved)).isEmpty();
    }

    @Test
    @Tag("REQ-MON-08")
    void theGraphQlVerdictSeparatesABrokenContractFromAStaleSnapshot() {
        ApiSnapshots.Verdict broken = ApiSnapshots.compareGraphQl(SCHEMA, SCHEMA.replace(", note: String", ""));
        ApiSnapshots.Verdict stale = ApiSnapshots.compareGraphQl(SCHEMA, SCHEMA.replace("note: String", "note: String, comment: String"));

        assertThat(broken.incompatible()).isNotEmpty();
        assertThat(stale.incompatible()).isEmpty();
        assertThat(stale.stale()).isNotEmpty();
        assertThat(ApiSnapshots.compareGraphQl(SCHEMA, SCHEMA).ok()).isTrue();
    }
}
