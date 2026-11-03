package com.saas.testsupport.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The additive-only rule for a REST API (REQ-ARCH-03): what a client built against the old OpenAPI document could
 * no longer rely on in the new one. A path, an operation, a response, a parameter or a property that was there and
 * is gone is incompatible, and so is a type that changed, an enum value that was removed, and anything the client
 * must now send that it did not have to before (a new required parameter, body property or body). A new path, a new
 * operation, a new optional parameter and a new property are fine. A change that is incompatible belongs under a new
 * versioned path ({@code /v2/...}) next to the old one, which is an addition and so passes.
 */
public final class OpenApiCompatibility {

    private static final List<String> METHODS = List.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

    private enum Direction { REQUEST, RESPONSE }

    private final JsonNode oldDocument;
    private final JsonNode newDocument;
    private final Set<String> problems = new TreeSet<>();
    private final Set<String> visited = new HashSet<>();

    private OpenApiCompatibility(JsonNode oldDocument, JsonNode newDocument) {
        this.oldDocument = oldDocument;
        this.newDocument = newDocument;
    }

    /** What is incompatible going from {@code oldDocument} to {@code newDocument}; empty when nothing is. */
    public static List<String> incompatibleChanges(JsonNode oldDocument, JsonNode newDocument) {
        OpenApiCompatibility check = new OpenApiCompatibility(oldDocument, newDocument);
        check.comparePaths();
        return new ArrayList<>(check.problems);
    }

    private void comparePaths() {
        JsonNode oldPaths = oldDocument.path("paths");
        JsonNode newPaths = newDocument.path("paths");
        for (Iterator<Map.Entry<String, JsonNode>> paths = oldPaths.fields(); paths.hasNext(); ) {
            Map.Entry<String, JsonNode> path = paths.next();
            JsonNode current = newPaths.get(path.getKey());
            if (current == null) {
                problems.add("path removed: " + path.getKey());
                continue;
            }
            for (String method : METHODS) {
                JsonNode oldOperation = path.getValue().get(method);
                if (oldOperation == null) {
                    continue;
                }
                String label = method.toUpperCase() + " " + path.getKey();
                JsonNode newOperation = current.get(method);
                if (newOperation == null) {
                    problems.add("operation removed: " + label);
                    continue;
                }
                compareOperation(label, oldOperation, newOperation);
            }
        }
    }

    private void compareOperation(String label, JsonNode oldOperation, JsonNode newOperation) {
        compareParameters(label, oldOperation.path("parameters"), newOperation.path("parameters"));
        compareRequestBody(label, oldOperation.get("requestBody"), newOperation.get("requestBody"));
        compareResponses(label, oldOperation.path("responses"), newOperation.path("responses"));
    }

    private void compareParameters(String label, JsonNode oldParameters, JsonNode newParameters) {
        Map<String, JsonNode> oldByKey = parametersByKey(oldParameters, oldDocument);
        Map<String, JsonNode> newByKey = parametersByKey(newParameters, newDocument);
        for (Map.Entry<String, JsonNode> entry : oldByKey.entrySet()) {
            JsonNode current = newByKey.get(entry.getKey());
            if (current == null) {
                problems.add(label + ": parameter removed: " + entry.getKey());
                continue;
            }
            if (!entry.getValue().path("required").asBoolean(false) && current.path("required").asBoolean(false)) {
                problems.add(label + ": parameter became required: " + entry.getKey());
            }
            compareSchema(label + " parameter " + entry.getKey(), entry.getValue().get("schema"), current.get("schema"), Direction.REQUEST);
        }
        for (Map.Entry<String, JsonNode> entry : newByKey.entrySet()) {
            if (!oldByKey.containsKey(entry.getKey()) && entry.getValue().path("required").asBoolean(false)) {
                problems.add(label + ": new required parameter: " + entry.getKey());
            }
        }
    }

    private static Map<String, JsonNode> parametersByKey(JsonNode parameters, JsonNode document) {
        Map<String, JsonNode> byKey = new java.util.TreeMap<>();
        for (JsonNode parameter : parameters) {
            JsonNode resolved = resolve(parameter, document);
            byKey.put(resolved.path("in").asText() + " " + resolved.path("name").asText(), resolved);
        }
        return byKey;
    }

    private void compareRequestBody(String label, JsonNode oldBody, JsonNode newBody) {
        if (oldBody == null && newBody == null) {
            return;
        }
        if (oldBody != null && newBody == null) {
            problems.add(label + ": request body removed");
            return;
        }
        JsonNode resolvedNew = resolve(newBody, newDocument);
        if (oldBody == null) {
            if (resolvedNew.path("required").asBoolean(false)) {
                problems.add(label + ": request body is now required");
            }
            return;
        }
        JsonNode resolvedOld = resolve(oldBody, oldDocument);
        if (!resolvedOld.path("required").asBoolean(false) && resolvedNew.path("required").asBoolean(false)) {
            problems.add(label + ": request body became required");
        }
        compareContent(label + " request body", resolvedOld.path("content"), resolvedNew.path("content"), Direction.REQUEST);
    }

    private void compareResponses(String label, JsonNode oldResponses, JsonNode newResponses) {
        for (Iterator<Map.Entry<String, JsonNode>> responses = oldResponses.fields(); responses.hasNext(); ) {
            Map.Entry<String, JsonNode> response = responses.next();
            JsonNode current = newResponses.get(response.getKey());
            if (current == null) {
                problems.add(label + ": response " + response.getKey() + " removed");
                continue;
            }
            compareContent(label + " response " + response.getKey(), resolve(response.getValue(), oldDocument).path("content"),
                    resolve(current, newDocument).path("content"), Direction.RESPONSE);
        }
    }

    private void compareContent(String label, JsonNode oldContent, JsonNode newContent, Direction direction) {
        for (Iterator<Map.Entry<String, JsonNode>> types = oldContent.fields(); types.hasNext(); ) {
            Map.Entry<String, JsonNode> type = types.next();
            JsonNode current = newContent.get(type.getKey());
            if (current == null) {
                problems.add(label + ": media type removed: " + type.getKey());
                continue;
            }
            compareSchema(label + " " + type.getKey(), type.getValue().get("schema"), current.get("schema"), direction);
        }
    }

    private void compareSchema(String where, JsonNode oldSchema, JsonNode newSchema, Direction direction) {
        if (oldSchema == null || newSchema == null) {
            return;
        }
        if (oldSchema.has("$ref") && newSchema.has("$ref")
                && !visited.add(oldSchema.get("$ref").asText() + "|" + newSchema.get("$ref").asText() + "|" + direction)) {
            return; // the same pair of types was compared already (and a recursive type would never end)
        }
        JsonNode oldResolved = resolve(oldSchema, oldDocument);
        JsonNode newResolved = resolve(newSchema, newDocument);

        compareScalar(where, "type", oldResolved, newResolved);
        compareScalar(where, "format", oldResolved, newResolved);
        compareEnum(where, oldResolved, newResolved);
        compareProperties(where, oldResolved, newResolved, direction);
        compareSchema(where + "[]", oldResolved.get("items"), newResolved.get("items"), direction);
        if (oldResolved.path("additionalProperties").isObject() && newResolved.path("additionalProperties").isObject()) {
            compareSchema(where + "{}", oldResolved.get("additionalProperties"), newResolved.get("additionalProperties"), direction);
        }
        for (String composition : List.of("allOf", "oneOf", "anyOf")) {
            compareComposition(where, composition, oldResolved, newResolved, direction);
        }
    }

    private void compareScalar(String where, String field, JsonNode oldSchema, JsonNode newSchema) {
        if (oldSchema.has(field) && !oldSchema.get(field).equals(newSchema.get(field))) {
            problems.add(where + ": " + field + " changed from " + oldSchema.get(field).asText() + " to "
                    + (newSchema.has(field) ? newSchema.get(field).asText() : "nothing"));
        }
    }

    private void compareEnum(String where, JsonNode oldSchema, JsonNode newSchema) {
        if (!oldSchema.has("enum")) {
            return;
        }
        Set<String> remaining = names(newSchema.path("enum"));
        for (JsonNode value : oldSchema.get("enum")) {
            if (!remaining.contains(value.asText())) {
                problems.add(where + ": enum value removed: " + value.asText());
            }
        }
    }

    private void compareProperties(String where, JsonNode oldSchema, JsonNode newSchema, Direction direction) {
        JsonNode oldProperties = oldSchema.path("properties");
        JsonNode newProperties = newSchema.path("properties");
        Set<String> oldRequired = names(oldSchema.path("required"));
        Set<String> newRequired = names(newSchema.path("required"));
        for (Iterator<Map.Entry<String, JsonNode>> properties = oldProperties.fields(); properties.hasNext(); ) {
            Map.Entry<String, JsonNode> property = properties.next();
            JsonNode current = newProperties.get(property.getKey());
            if (current == null) {
                problems.add(where + ": property removed: " + property.getKey());
                continue;
            }
            compareSchema(where + "." + property.getKey(), property.getValue(), current, direction);
            if (direction == Direction.REQUEST && !oldRequired.contains(property.getKey()) && newRequired.contains(property.getKey())) {
                problems.add(where + ": property became required: " + property.getKey());
            }
            if (direction == Direction.RESPONSE && oldRequired.contains(property.getKey()) && !newRequired.contains(property.getKey())) {
                problems.add(where + ": property is no longer always present: " + property.getKey());
            }
        }
        if (direction == Direction.REQUEST) {
            for (String name : newRequired) {
                if (!oldProperties.has(name) && newProperties.has(name)) {
                    problems.add(where + ": new required property: " + name);
                }
            }
        }
    }

    private void compareComposition(String where, String composition, JsonNode oldSchema, JsonNode newSchema, Direction direction) {
        JsonNode oldParts = oldSchema.get(composition);
        if (oldParts == null) {
            return;
        }
        JsonNode newParts = newSchema.get(composition);
        if (newParts == null || newParts.size() != oldParts.size()) {
            problems.add(where + ": " + composition + " changed");
            return;
        }
        for (int i = 0; i < oldParts.size(); i++) {
            compareSchema(where + "." + composition + "[" + i + "]", oldParts.get(i), newParts.get(i), direction);
        }
    }

    private static Set<String> names(JsonNode array) {
        Set<String> names = new HashSet<>();
        array.forEach(name -> names.add(name.asText()));
        return names;
    }

    /** Follows {@code #/components/...} references, which is all springdoc generates. */
    private static JsonNode resolve(JsonNode node, JsonNode document) {
        JsonNode current = node;
        for (int hops = 0; current != null && current.has("$ref") && hops < 20; hops++) {
            String ref = current.get("$ref").asText();
            JsonNode target = ref.startsWith("#/") ? document.at(ref.substring(1)) : null;
            if (target == null || target.isMissingNode()) {
                return current;
            }
            current = target;
        }
        return current;
    }
}
