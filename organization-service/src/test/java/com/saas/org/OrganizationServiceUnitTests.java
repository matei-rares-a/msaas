package com.saas.org;

import com.saas.org.dto.CreateOrganizationRequest;
import com.saas.org.dto.InviteRequest;
import com.saas.org.dto.UpdateRoleRequest;
import com.saas.org.entity.Invitation;
import com.saas.org.entity.Membership;
import com.saas.org.entity.Organization;
import com.saas.org.repository.InvitationRepository;
import com.saas.org.repository.MembershipRepository;
import com.saas.org.repository.OrganizationRepository;
import com.saas.org.service.OrganizationService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;
import java.util.UUID;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class OrganizationServiceUnitTests {

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private MembershipRepository membershipRepository;

    @Mock
    private InvitationRepository invitationRepository;

    @Mock
    private RabbitProducer rabbitProducer;

    @InjectMocks
    private OrganizationService organizationService;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testCreateOrganization() {
        // Arrange
        UUID ownerId = UUID.randomUUID();
        String orgName = "Test Org";

        when(organizationRepository.save(any(Organization.class)))
            .thenAnswer(invocation -> {
                Organization org = invocation.getArgument(0);
                org.setId(UUID.randomUUID());
                return org;
            });

        when(membershipRepository.save(any(Membership.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        Organization result = organizationService.create(
            new CreateOrganizationRequest(orgName),
            ownerId
        );

        // Assert
        assertNotNull(result);
        assertEquals(orgName, result.getName());
        assertEquals(ownerId, result.getOwnerId());
        verify(organizationRepository, times(1)).save(any(Organization.class));
        verify(membershipRepository, times(1)).save(any(Membership.class));
    }

    @Test
    void testListUserOrganizations() {
        // Arrange
        UUID userId = UUID.randomUUID();
        List<Organization> orgs = List.of(
            createTestOrg("Org 1"),
            createTestOrg("Org 2")
        );

        when(organizationRepository.findByOwnerId(userId)).thenReturn(orgs);

        // Act
        List<Organization> result = organizationService.listUserOrganizations(userId);

        // Assert
        assertEquals(2, result.size());
        verify(organizationRepository, times(1)).findByOwnerId(userId);
    }

    @Test
    void testInviteMember() {
        // Arrange
        UUID orgId = UUID.randomUUID();
        String email = "invited@example.com";
        String role = "MANAGER";

        Organization org = createTestOrg("Test Org");
        org.setId(orgId);

        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
        when(invitationRepository.save(any(Invitation.class)))
            .thenAnswer(invocation -> {
                Invitation inv = invocation.getArgument(0);
                inv.setId(UUID.randomUUID());
                inv.setToken("inv-token-123");
                return inv;
            });

        // Act
        Invitation result = organizationService.invite(
            orgId,
            new InviteRequest(email, role)
        );

        // Assert
        assertNotNull(result);
        assertEquals(email, result.getEmail());
        assertEquals(role, result.getRole());
        assertNotNull(result.getToken());
        verify(invitationRepository, times(1)).save(any(Invitation.class));
        verify(rabbitProducer, times(1)).send(any());
    }

    @Test
    void testInviteNonExistentOrgThrowsException() {
        // Arrange
        UUID orgId = UUID.randomUUID();
        when(organizationRepository.findById(orgId)).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(ResourceNotFoundException.class, () -> {
            organizationService.invite(orgId, new InviteRequest("email@test.com", "MEMBER"));
        });
    }

    @Test
    void testGetMembers() {
        // Arrange
        UUID orgId = UUID.randomUUID();
        List<Membership> members = List.of(
            createTestMembership(orgId, "ADMIN"),
            createTestMembership(orgId, "MEMBER")
        );

        when(membershipRepository.findByOrganizationId(orgId)).thenReturn(members);

        // Act
        List<Membership> result = organizationService.getMembers(orgId);

        // Assert
        assertEquals(2, result.size());
        verify(membershipRepository, times(1)).findByOrganizationId(orgId);
    }

    @Test
    void testUpdateMemberRole() {
        // Arrange
        UUID orgId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        String newRole = "MANAGER";

        Membership member = createTestMembership(orgId, "MEMBER");
        member.setId(memberId);

        when(membershipRepository.findById(memberId)).thenReturn(Optional.of(member));

        // Act
        Membership result = organizationService.updateMemberRole(
            orgId,
            memberId,
            new UpdateRoleRequest(newRole)
        );

        // Assert
        assertEquals(newRole, result.getRole());
        verify(membershipRepository, times(1)).save(member);
    }

    @Test
    void testRemoveMember() {
        // Arrange
        UUID orgId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();

        Membership member = createTestMembership(orgId, "MEMBER");
        member.setId(memberId);

        when(membershipRepository.findById(memberId)).thenReturn(Optional.of(member));

        // Act
        organizationService.removeMember(orgId, memberId);

        // Assert
        verify(membershipRepository, times(1)).delete(member);
    }

    @Test
    void testMultiTenancyEnforcement() {
        // Arrange
        UUID userId = UUID.randomUUID();
        UUID org1Id = UUID.randomUUID();
        UUID org2Id = UUID.randomUUID();

        Organization org1 = createTestOrg("Org 1");
        org1.setId(org1Id);
        org1.setOwnerId(userId);

        // User should not be able to access org2
        when(membershipRepository.findByUserIdAndOrganizationId(userId, org2Id))
            .thenReturn(Optional.empty());

        // Act & Assert
        assertFalse(organizationService.hasAccessToOrganization(userId, org2Id));
    }

    @Test
    void testRoleBasedAccess() {
        // Arrange
        UUID orgId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID managerId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();

        Membership adminMembership = createTestMembership(orgId, "ADMIN");
        adminMembership.setUserId(adminId);

        Membership managerMembership = createTestMembership(orgId, "MANAGER");
        managerMembership.setUserId(managerId);

        Membership memberMembership = createTestMembership(orgId, "MEMBER");
        memberMembership.setUserId(memberId);

        // Act & Assert
        assertTrue(organizationService.canManageMembers(adminMembership));
        assertTrue(organizationService.canManageMembers(managerMembership));
        assertFalse(organizationService.canManageMembers(memberMembership));

        assertTrue(organizationService.isAdmin(adminMembership));
        assertFalse(organizationService.isAdmin(memberMembership));
    }

    @Test
    void testAcceptInvitation() {
        // Arrange
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String invitationToken = "inv-token-123";

        Invitation invitation = new Invitation();
        invitation.setId(UUID.randomUUID());
        invitation.setEmail("user@example.com");
        invitation.setOrganizationId(orgId);
        invitation.setRole("MEMBER");
        invitation.setToken(invitationToken);
        invitation.setAccepted(false);

        when(invitationRepository.findByToken(invitationToken))
            .thenReturn(Optional.of(invitation));
        when(membershipRepository.save(any(Membership.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        Membership result = organizationService.acceptInvitation(invitationToken, userId);

        // Assert
        assertNotNull(result);
        assertEquals(orgId, result.getOrganizationId());
        assertEquals(userId, result.getUserId());
        assertTrue(invitation.isAccepted());
        verify(membershipRepository, times(1)).save(any(Membership.class));
    }

    @Test
    void testAcceptExpiredInvitationThrowsException() {
        // Arrange
        String invitationToken = "expired-token";
        Invitation invitation = new Invitation();
        invitation.setToken(invitationToken);
        invitation.setExpiresAt(java.time.LocalDateTime.now().minusDays(1));

        when(invitationRepository.findByToken(invitationToken))
            .thenReturn(Optional.of(invitation));

        // Act & Assert
        assertThrows(IllegalArgumentException.class, () -> {
            organizationService.acceptInvitation(invitationToken, UUID.randomUUID());
        });
    }

    @Test
    void testDeleteOrganization() {
        // Arrange
        UUID orgId = UUID.randomUUID();
        Organization org = createTestOrg("To Delete");
        org.setId(orgId);

        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));

        // Act
        organizationService.delete(orgId);

        // Assert
        verify(organizationRepository, times(1)).delete(org);
        verify(membershipRepository, times(1)).deleteByOrganizationId(orgId);
    }

    // Helper methods
    private Organization createTestOrg(String name) {
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        org.setName(name);
        org.setOwnerId(UUID.randomUUID());
        return org;
    }

    private Membership createTestMembership(UUID orgId, String role) {
        Membership membership = new Membership();
        membership.setId(UUID.randomUUID());
        membership.setUserId(UUID.randomUUID());
        membership.setOrganizationId(orgId);
        membership.setRole(role);
        return membership;
    }
}
