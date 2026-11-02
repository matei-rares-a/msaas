package com.saas.notification.graphql;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Component;

/**
 * Turns the failures this API raises on purpose into the GraphQL error classifications a client can act
 * on, with a message that is ours and safe to show: UNAUTHORIZED (no identity), FORBIDDEN (wrong role,
 * REQ-MON-02) and BAD_REQUEST (a filter that does not parse). Anything else is left to Spring's default,
 * which reports INTERNAL_ERROR without the exception's message, so no internals leak.
 */
@Component
public class JournalExceptionResolver extends DataFetcherExceptionResolverAdapter {

    @Override
    protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
        ErrorType type;
        if (ex instanceof AuthenticationRequiredException) {
            type = ErrorType.UNAUTHORIZED;
        } else if (ex instanceof SecurityException) {
            type = ErrorType.FORBIDDEN;
        } else if (ex instanceof IllegalArgumentException) {
            type = ErrorType.BAD_REQUEST;
        } else {
            return null;
        }
        return GraphqlErrorBuilder.newError(env).errorType(type).message(ex.getMessage()).build();
    }
}
