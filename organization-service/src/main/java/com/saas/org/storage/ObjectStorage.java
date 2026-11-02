package com.saas.org.storage;

import java.util.Optional;

/**
 * Where uploaded files live (REQ-PERS-03). The application depends only on this interface: the local
 * implementation is replaced by a provider such as S3 by adding another implementation and changing
 * configuration, with no change to the code that stores or reads a logo. Keys are chosen by the caller
 * and always carry the tenant id, so one tenant's files can never be addressed through another's.
 */
public interface ObjectStorage {

    /** Stores (or replaces) the object under {@code key}. */
    void put(String key, byte[] content);

    /** The object's bytes, or empty if there is none under {@code key}. */
    Optional<byte[]> get(String key);

    /** Removes the object; removing a missing one is not an error. */
    void delete(String key);
}
