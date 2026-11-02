package com.saas.gateway;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the gateway exposes (REQ-PERS-07): the platform-operator endpoints live under {@code /internal} and
 * must never be routed, so switching a feature flag is possible only from inside the network. A route that
 * covers {@code /internal} would hand that API to every client.
 */
@Tag("REQ-PERS-07")
class GatewayRoutesTest {

    private static Properties routes() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        return yaml.getObject();
    }

    @Test
    void noRouteExposesTheInternalApi() {
        Properties properties = routes();
        String predicates = properties.stringPropertyNames().stream()
                .filter(name -> name.startsWith("spring.cloud.gateway.routes") && name.contains("predicates"))
                .map(properties::getProperty)
                .collect(Collectors.joining(" | "));

        assertThat(predicates).contains("/organizations/**");
        assertThat(predicates).doesNotContain("/internal").doesNotContain("Path=/**");
    }

    @Test
    void everyRoutedPathIsAnExplicitServicePrefix() {
        Properties properties = routes();
        var paths = properties.stringPropertyNames().stream()
                .filter(name -> name.startsWith("spring.cloud.gateway.routes") && name.contains("predicates"))
                .map(properties::getProperty)
                .map(value -> value.replace("Path=", ""))
                .flatMap(value -> java.util.Arrays.stream(value.split(",")))
                .toList();

        assertThat(paths).isNotEmpty().allSatisfy(path -> assertThat(path)
                .matches("^/(auth|organizations|invitations|billing|notifications)/\\*\\*$"));
    }
}
