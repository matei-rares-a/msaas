package com.saas.org.controller;

import com.saas.org.dto.MembershipSummaryResponse;
import com.saas.org.service.MembershipLookupService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/internal/users")
@RequiredArgsConstructor
public class InternalMembershipController {

    private final MembershipLookupService membershipLookupService;

    @GetMapping("/{userId}/memberships")
    public List<MembershipSummaryResponse> memberships(@PathVariable UUID userId) {
        return membershipLookupService.findMembershipsForUser(userId);
    }
}
