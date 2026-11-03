package com.saas.org.lunch;

/** The weekly settings of the module (REQ-LUNCH-01): {@code cutoff} is {@code HH:mm} in the tenant's {@code timezone}. */
public record LunchConfigView(String day, String cutoff, int groupSize, String timezone) {
}
