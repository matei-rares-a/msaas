package com.saas.notification.graphql;

import graphql.ExecutionResult;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.language.Definition;
import graphql.language.OperationDefinition;
import graphql.parser.InvalidSyntaxException;
import graphql.parser.Parser;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.support.DefaultExecutionGraphQlResponse;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * The journal can only be read (REQ-MON-08): a document that holds anything but a query operation is
 * refused before it is executed. The schema declares no Mutation or Subscription type, but the engine
 * would otherwise fail on such a document with an internal error instead of a clean refusal.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class QueryOnlyInterceptor implements WebGraphQlInterceptor {

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
        if (!onlyQueries(request.getDocument())) {
            GraphQLError error = GraphqlErrorBuilder.newError().errorType(ErrorType.BAD_REQUEST)
                    .message("Only query operations are supported").build();
            return Mono.just(new WebGraphQlResponse(new DefaultExecutionGraphQlResponse(request.toExecutionInput(), ExecutionResult.newExecutionResult().addError(error).build())));
        }
        return chain.next(request);
    }

    private static boolean onlyQueries(String document) {
        try {
            for (Definition<?> definition : Parser.parse(document).getDefinitions()) {
                if (definition instanceof OperationDefinition operation && operation.getOperation() != OperationDefinition.Operation.QUERY) {
                    return false;
                }
            }
            return true;
        } catch (InvalidSyntaxException e) {
            return true; // the engine reports the syntax error itself
        }
    }
}
