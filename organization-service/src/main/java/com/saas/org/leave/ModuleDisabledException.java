package com.saas.org.leave;

/** The module is switched off for this tenant, so it does not exist for the caller (REQ-MOD-01): a 404. */
public class ModuleDisabledException extends RuntimeException {

    public ModuleDisabledException() {
        super("this module is not enabled for the organization");
    }
}
