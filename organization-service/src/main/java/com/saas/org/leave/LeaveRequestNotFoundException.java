package com.saas.org.leave;

/** No such leave request for this caller (it does not exist, or it is someone else's and the caller may not see it): a 404. */
public class LeaveRequestNotFoundException extends RuntimeException {

    public LeaveRequestNotFoundException() {
        super("no such leave request");
    }
}
