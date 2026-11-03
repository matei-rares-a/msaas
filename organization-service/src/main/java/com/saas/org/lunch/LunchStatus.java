package com.saas.org.lunch;

/** OPEN until the cut-off, then FORMED (groups made) or CANCELLED (REQ-LUNCH-02, REQ-LUNCH-03). */
public enum LunchStatus {
    OPEN, FORMED, CANCELLED
}
