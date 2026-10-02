package com.saas.notification;

import com.saas.testsupport.containers.PostgresTestContainer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.kafka.listener.auto-startup=false"
})
@AutoConfigureMockMvc
@Tag("REQ-UPL-01")
class GenericUploadRemovedIT implements PostgresTestContainer {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void uploadEndpointIsGoneAndWritesNothing() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "evil.svg", "image/svg+xml", "<svg onload=alert(1)>".getBytes());

        mockMvc.perform(multipart("/notifications/uploads").file(file)).andExpect(status().isNotFound());

        assertThat(Files.exists(Path.of("uploads"))).isFalse();
    }

    @Test
    void noEndpointAcceptsFiles() {
        assertThat(handlerMapping.getHandlerMethods().values())
                .noneMatch(GenericUploadRemovedIT::acceptsMultipartFile);
    }

    private static boolean acceptsMultipartFile(HandlerMethod method) {
        return Arrays.stream(method.getMethodParameters())
                .anyMatch(p -> MultipartFile.class.isAssignableFrom(p.getParameterType()));
    }
}
