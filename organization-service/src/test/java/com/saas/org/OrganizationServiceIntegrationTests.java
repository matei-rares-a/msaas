package com.saas.org;

import com.saas.org.entity.Membership;
import com.saas.org.entity.Organization;
import com.saas.org.repository.MembershipRepository;
import com.saas.org.repository.OrganizationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class OrganizationServiceIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private MembershipRepository membershipRepository;

    private static final String AUTH_HEADER = "Authorization";
    private static final String ORG_ID_HEADER = "X-Org-Id";
    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String ROLE_HEADER = "X-Role";

    @Test
    void testCreateOrganization() throws Exception {
        String payload = """
            {
                "name": "Test Organization"
            }
        """;

        mockMvc.perform(post("/organizations")
                .header(USER_ID_HEADER, UUID.randomUUID().toString())
                .header(ORG_ID_HEADER, UUID.randomUUID().toString())
                .header(ROLE_HEADER, "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("Test Organization"))
                .andExpect(jsonPath("$.ownerId").exists());
    }

    @Test
    void testListOrganizations() throws Exception {
        mockMvc.perform(get("/organizations")
                .header(USER_ID_HEADER, UUID.randomUUID().toString())
                .header(ORG_ID_HEADER, UUID.randomUUID().toString())
                .header(ROLE_HEADER, "ADMIN")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", isA(ArrayList.class)));
    }

    @Test
    void testInviteMember() throws Exception {
        // Create organization first
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        org.setName("Test Org");
        org.setOwnerId(UUID.randomUUID());
        organizationRepository.save(org);

        String payload = """
            {
                "email": "newmember@example.com",
                "role": "MANAGER"
            }
        """;

        mockMvc.perform(post("/organizations/{id}/invite", org.getId())
                .header(USER_ID_HEADER, org.getOwnerId().toString())
                .header(ORG_ID_HEADER, org.getId().toString())
                .header(ROLE_HEADER, "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("newmember@example.com"))
                .andExpect(jsonPath("$.role").value("MANAGER"))
                .andExpect(jsonPath("$.token").exists());
    }

    @Test
    void testGetMembers() throws Exception {
        // Create organization and member
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        org.setName("Test Org");
        org.setOwnerId(UUID.randomUUID());
        organizationRepository.save(org);

        Membership member = new Membership();
        member.setId(UUID.randomUUID());
        member.setUserId(UUID.randomUUID());
        member.setOrganizationId(org.getId());
        member.setRole("ADMIN");
        membershipRepository.save(member);

        mockMvc.perform(get("/organizations/{id}/members", org.getId())
                .header(USER_ID_HEADER, org.getOwnerId().toString())
                .header(ORG_ID_HEADER, org.getId().toString())
                .header(ROLE_HEADER, "ADMIN")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", isA(ArrayList.class)))
                .andExpect(jsonPath("$[0].role").value("ADMIN"));
    }

    @Test
    void testUpdateMemberRole() throws Exception {
        // Create organization and member
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        org.setName("Test Org");
        org.setOwnerId(UUID.randomUUID());
        organizationRepository.save(org);

        Membership member = new Membership();
        member.setId(UUID.randomUUID());
        member.setUserId(UUID.randomUUID());
        member.setOrganizationId(org.getId());
        member.setRole("MEMBER");
        membershipRepository.save(member);

        String payload = """
            {
                "role": "MANAGER"
            }
        """;

        mockMvc.perform(patch("/organizations/{id}/members/{memberId}/role", org.getId(), member.getId())
                .header(USER_ID_HEADER, org.getOwnerId().toString())
                .header(ORG_ID_HEADER, org.getId().toString())
                .header(ROLE_HEADER, "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MANAGER"));
    }

    @Test
    void testMultiTenancyEnforcement() throws Exception {
        // Create two organizations
        UUID org1Id = UUID.randomUUID();
        UUID org2Id = UUID.randomUUID();
        UUID userId1 = UUID.randomUUID();
        UUID userId2 = UUID.randomUUID();

        // User 1 tries to access org 2 data - should fail
        mockMvc.perform(get("/organizations/{id}/members", org2Id)
                .header(USER_ID_HEADER, userId1.toString())
                .header(ORG_ID_HEADER, org1Id.toString())
                .header(ROLE_HEADER, "ADMIN")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }
}
