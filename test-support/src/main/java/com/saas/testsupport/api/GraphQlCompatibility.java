package com.saas.testsupport.api;

import graphql.introspection.Introspection;
import graphql.schema.GraphQLSchema;
import graphql.schema.diff.DiffEvent;
import graphql.schema.diff.DiffLevel;
import graphql.schema.diff.DiffSet;
import graphql.schema.diff.SchemaDiff;
import graphql.schema.diff.reporting.DifferenceReporter;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.UnExecutableSchemaGenerator;

import java.util.ArrayList;
import java.util.List;

/**
 * The additive-only rule for a GraphQL schema (REQ-ARCH-03, REQ-MON-08): a type, field, argument or enum value that was
 * there and is gone, or whose type changed, or a new argument the caller must give, is incompatible. The way out is to
 * mark the field {@code @deprecated} and add the new one next to it; there is no versioning by path for GraphQL. Uses
 * graphql-java's own schema diff, so what counts as breaking is the library's definition.
 */
public final class GraphQlCompatibility {

    private GraphQlCompatibility() {
    }

    /** What is incompatible going from {@code oldSdl} to {@code newSdl}; empty when nothing is. */
    public static List<String> incompatibleChanges(String oldSdl, String newSdl) {
        GraphQLSchema oldSchema = schema(oldSdl);
        GraphQLSchema newSchema = schema(newSdl);
        List<String> breaking = new ArrayList<>();
        DifferenceReporter reporter = new DifferenceReporter() {
            @Override
            public void report(DiffEvent event) {
                if (event.getLevel() == DiffLevel.BREAKING) {
                    breaking.add(event.getTypeName() + (event.getFieldName() == null ? "" : "." + event.getFieldName()) + ": " + event.getReasonMsg());
                }
            }

            @Override
            public void onEnd() {
            }
        };
        // graphql-java's diff reads the schemas by introspection, and a service that switches introspection off for
        // production (REQ-MON-08) does so for the whole JVM, so a test that ran before this one would break it
        boolean introspectionWasOn = Introspection.isEnabledJvmWide();
        Introspection.enabledJvmWide(true);
        try {
            new SchemaDiff().diffSchema(DiffSet.diffSet(oldSchema, newSchema), reporter);
        } finally {
            Introspection.enabledJvmWide(introspectionWasOn);
        }
        return breaking.stream().sorted().distinct().toList();
    }
    private static GraphQLSchema schema(String sdl) {
        return UnExecutableSchemaGenerator.makeUnExecutableSchema(new SchemaParser().parse(sdl));
    }
}
