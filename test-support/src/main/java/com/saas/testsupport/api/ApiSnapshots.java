package com.saas.testsupport.api;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The committed record of each service's API (REQ-ARCH-03): an OpenAPI document per service, and the GraphQL schema of
 * the journal, kept under {@code src/test/resources/api-snapshots} of the module. A test compares what the running
 * service produces with its snapshot and fails when
 * <ul>
 *   <li>something a client could rely on is gone or changed ({@link OpenApiCompatibility}, {@link GraphQlCompatibility}), or</li>
 *   <li>the API has something the snapshot does not (a new path, property, field), so the snapshot is stale and the next
 *       removal would go unnoticed.</li>
 * </ul>
 * Run the module's tests with {@code -Dapi.snapshot.update=true} to write the snapshot from the running service. That is
 * for additions; never to make an incompatible change pass - that one goes under a new versioned path (REST) or behind
 * {@code @deprecated} (GraphQL).
 */
public final class ApiSnapshots {

    public static final String UPDATE_PROPERTY = "api.snapshot.update";
    public static final String DIRECTORY_PROPERTY = "api.snapshot.dir";
    private static final String DEFAULT_DIRECTORY = "src/test/resources/api-snapshots";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** What a comparison found: {@code incompatible} fails the build as a broken contract, {@code stale} as an outdated snapshot. */
    public record Verdict(List<String> incompatible, List<String> stale) {

        public boolean ok() {
            return incompatible.isEmpty() && stale.isEmpty();
        }

        public String describe(String name) {
            StringBuilder message = new StringBuilder();
            if (!incompatible.isEmpty()) {
                message.append("API ").append(name).append(" changed in a way that breaks existing clients (REQ-ARCH-03):\n  - ")
                        .append(String.join("\n  - ", incompatible))
                        .append("\nDo not update the snapshot. Keep the old behaviour and add the new one next to it: a new versioned path"
                                + " for REST, @deprecated plus a new field for GraphQL.");
            }
            if (!stale.isEmpty()) {
                message.append("The snapshot of ").append(name).append(" is out of date: the API has things it does not (shown from the"
                                + " snapshot's side, so 'removed' reads as 'new in the API'):\n  - ")
                        .append(String.join("\n  - ", stale))
                        .append("\nIf these are intended additions, regenerate it with -D").append(UPDATE_PROPERTY).append("=true and commit it.");
            }
            return message.toString();
        }
    }

    private ApiSnapshots() {
    }

    // ---- comparison ----

    public static Verdict compareOpenApi(String snapshotJson, String currentJson) {
        JsonNode snapshot = parse(snapshotJson);
        JsonNode current = parse(currentJson);
        List<String> incompatible = OpenApiCompatibility.incompatibleChanges(snapshot, current);
        List<String> stale = incompatible.isEmpty() ? OpenApiCompatibility.incompatibleChanges(current, snapshot) : List.of();
        return new Verdict(incompatible, stale);
    }

    public static Verdict compareGraphQl(String snapshotSdl, String currentSdl) {
        List<String> incompatible = GraphQlCompatibility.incompatibleChanges(snapshotSdl, currentSdl);
        List<String> stale = incompatible.isEmpty() ? GraphQlCompatibility.incompatibleChanges(currentSdl, snapshotSdl) : List.of();
        return new Verdict(incompatible, stale);
    }

    // ---- the committed files ----

    /** Compares the service's current OpenAPI document with {@code name} in the snapshot directory, or writes it when updating. */
    public static void verifyOpenApi(String name, String currentJson) {
        String normalized = normalizeOpenApi(currentJson);
        if (updating()) {
            write(name, normalized);
            return;
        }
        Verdict verdict = compareOpenApi(read(name), normalized);
        if (!verdict.ok()) {
            throw new AssertionError(verdict.describe(name));
        }
    }

    public static void verifyGraphQl(String name, String currentSdl) {
        if (updating()) {
            write(name, currentSdl.stripTrailing() + "\n");
            return;
        }
        Verdict verdict = compareGraphQl(read(name), currentSdl);
        if (!verdict.ok()) {
            throw new AssertionError(verdict.describe(name));
        }
    }

    public static String read(String name) {
        Path file = directory().resolve(name);
        if (!Files.isRegularFile(file)) {
            throw new AssertionError("There is no API snapshot " + file.toAbsolutePath() + ". Create it from the running service with -D"
                    + UPDATE_PROPERTY + "=true and commit it: without a baseline nothing can be checked.");
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(String name, String content) {
        try {
            Path file = directory().resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean updating() {
        return Boolean.parseBoolean(System.getProperty(UPDATE_PROPERTY, "false"));
    }

    private static Path directory() {
        return Path.of(System.getProperty(DIRECTORY_PROPERTY, DEFAULT_DIRECTORY));
    }


    // ---- proof that the check bites on the real snapshot, not only on small samples ----

    /**
     * For the committed OpenAPI snapshot {@code name}: the check must report it when the service stops offering any one
     * of its paths, and when it stops returning (or accepting) any one property of any one of its schemas. Fails when
     * some removal would go unnoticed.
     */
    public static void assertOpenApiRemovalsAreCaught(String name, String currentJson) {
        String snapshot = read(name);
        JsonNode current = parse(normalizeOpenApi(currentJson));
        int tried = 0;
        List<String> unnoticed = new ArrayList<>();

        for (Iterator<String> paths = current.path("paths").fieldNames(); paths.hasNext(); ) {
            String path = paths.next();
            JsonNode tampered = current.deepCopy();
            ((ObjectNode) tampered.get("paths")).remove(path);
            tried++;
            if (compareOpenApi(snapshot, tampered.toString()).incompatible().isEmpty()) {
                unnoticed.add("path " + path);
            }
        }
        for (Iterator<Map.Entry<String, JsonNode>> schemas = current.path("components").path("schemas").fields(); schemas.hasNext(); ) {
            Map.Entry<String, JsonNode> schema = schemas.next();
            for (Iterator<String> properties = schema.getValue().path("properties").fieldNames(); properties.hasNext(); ) {
                String property = properties.next();
                JsonNode tampered = current.deepCopy();
                ((ObjectNode) tampered.at("/components/schemas/" + schema.getKey() + "/properties")).remove(property);
                tried++;
                if (compareOpenApi(snapshot, tampered.toString()).incompatible().isEmpty()) {
                    unnoticed.add("property " + schema.getKey() + "." + property);
                }
            }
        }
        if (tried == 0 || !unnoticed.isEmpty()) {
            throw new AssertionError("Removals of " + name + " that the check does not report (" + tried + " tried): " + unnoticed);
        }
    }

    /** The same for the GraphQL snapshot: every field, input field and enum value, renamed, must be reported as gone. */
    public static void assertGraphQlRemovalsAreCaught(String name, String currentSdl) {
        String snapshot = read(name);
        String[] lines = currentSdl.split("\\R");
        int tried = 0;
        List<String> unnoticed = new ArrayList<>();
        boolean inDescription = false;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().startsWith("\"\"\"")) {
                inDescription = lines[i].trim().length() == 3 ? !inDescription : inDescription;
                continue;
            }
            java.util.regex.Matcher member = java.util.regex.Pattern.compile("^(\\s+)([A-Za-z_]\\w*)(\\(.*\\))?(:.*)?$").matcher(lines[i]);
            if (inDescription || !member.matches()) {
                continue;
            }
            String[] renamed = lines.clone();
            renamed[i] = member.group(1) + member.group(2) + "Renamed" + (member.group(3) == null ? "" : member.group(3))
                    + (member.group(4) == null ? "" : member.group(4));
            tried++;
            if (GraphQlCompatibility.incompatibleChanges(snapshot, String.join("\n", renamed)).isEmpty()) {
                unnoticed.add(lines[i].trim());
            }
        }
        if (tried == 0 || !unnoticed.isEmpty()) {
            throw new AssertionError("Removals from " + name + " that the check does not report (" + tried + " tried): " + unnoticed);
        }
    }
    // ---- a stable form of the OpenAPI document, so a snapshot does not change when the order of things does ----

    /** Sorted keys, sorted lists where the order carries no meaning, no server URL (it is the test's), LF line ends. */
    public static String normalizeOpenApi(String json) {
        JsonNode document = parse(json);
        if (document instanceof ObjectNode object) {
            object.remove("servers");
        }
        try {
            DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
            DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
            printer.indentObjectsWith(indenter);
            printer.indentArraysWith(indenter);
            return MAPPER.writer(printer).writeValueAsString(canonical(document, null)) + "\n";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonNode canonical(JsonNode node, String key) {
        if (node.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> fields = node.fields(); fields.hasNext(); ) {
                Map.Entry<String, JsonNode> field = fields.next();
                sorted.put(field.getKey(), canonical(field.getValue(), field.getKey()));
            }
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            sorted.forEach(result::set);
            return result;
        }
        if (node.isArray()) {
            List<JsonNode> items = new ArrayList<>();
            node.forEach(item -> items.add(canonical(item, null)));
            if (items.stream().allMatch(JsonNode::isTextual)) {
                items.sort(Comparator.comparing(JsonNode::asText));
            } else if ("parameters".equals(key)) {
                items.sort(Comparator.comparing(item -> item.path("in").asText() + " " + item.path("name").asText() + item.path("$ref").asText()));
            } else if ("tags".equals(key)) {
                items.sort(Comparator.comparing(item -> item.path("name").asText()));
            }
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            items.forEach(result::add);
            return result;
        }
        return node;
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
