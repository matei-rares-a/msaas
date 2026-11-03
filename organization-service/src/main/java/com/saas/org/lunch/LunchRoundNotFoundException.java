package com.saas.org.lunch;

/** No such round (or none the caller can see): a 404. */
public class LunchRoundNotFoundException extends RuntimeException {

    public LunchRoundNotFoundException() {
        super("no such lunch round");
    }
}
