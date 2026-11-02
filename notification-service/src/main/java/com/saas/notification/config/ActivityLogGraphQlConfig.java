package com.saas.notification.config;

import com.saas.notification.activity.ActivityLogReader;
import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.execution.instrumentation.Instrumentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * The journal's read API and its protection against expensive queries (REQ-MON-08): a maximum query
 * depth, and a maximum complexity in which a list field costs its page size times what it selects, so
 * neither deep nesting nor the same expensive field repeated under many aliases can be used to make the
 * service do unbounded work. Both limits are configuration ({@code saas.graphql.*}).
 */
@Configuration
public class ActivityLogGraphQlConfig {

    @Bean
    public ActivityLogReader activityLogReader(DataSource dataSource, PlatformTransactionManager transactionManager) {
        return new ActivityLogReader(dataSource, transactionManager);
    }

    @Bean
    public Instrumentation maxQueryDepthInstrumentation(@Value("${saas.graphql.max-depth}") int maxDepth) {
        return new MaxQueryDepthInstrumentation(maxDepth);
    }

    @Bean
    public Instrumentation maxQueryComplexityInstrumentation(@Value("${saas.graphql.max-complexity}") int maxComplexity,
            @Value("${saas.graphql.max-page-size}") int maxPageSize) {
        return new MaxQueryComplexityInstrumentation(maxComplexity, (environment, childComplexity) -> {
            if ("items".equals(environment.getField().getName())) {
                // the page size the client asked for (or the default) is how many entries this selection is repeated for
                Object size = environment.getParentEnvironment() == null ? null
                        : environment.getParentEnvironment().getArguments().get("size");
                int entries = size instanceof Number n ? Math.max(1, Math.min(n.intValue(), maxPageSize)) : 20;
                return 1 + entries * childComplexity;
            }
            return 1 + childComplexity;
        });
    }
}
