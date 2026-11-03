package com.saas.org.cache;

/** The kinds of entry the service caches (REQ-SCALE-01); part of the Redis key. */
public final class CacheRegions {

    /** A tenant's theme as the application reads it; removed when the palette or the logo changes. */
    public static final String THEME = "theme";

    /** A member's role and read-only flag; removed when the role changes or the member joins or leaves. */
    public static final String MEMBER_ACCESS = "member-access";

    /** Billing's answer about whether a tenant may write; removed by the subscription-changed event. */
    public static final String ACCESS_STATUS = "access-status";

    private CacheRegions() {
    }
}
