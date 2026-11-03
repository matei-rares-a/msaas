package com.saas.org.lunch;

import java.util.List;
import java.util.UUID;

/**
 * One round with its groups, as much of them as the caller may see (REQ-LUNCH-04): a participant sees their own group, an
 * approver (MANAGER or ADMIN) sees every group, anybody else sees neither. Groups are lists of user ids; the service keeps
 * no names, so the app puts names on them.
 */
public record LunchRoundDetail(LunchRoundView round, List<UUID> myGroup, List<List<UUID>> groups) {
}
