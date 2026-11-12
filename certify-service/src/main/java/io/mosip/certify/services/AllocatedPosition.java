package io.mosip.certify.services;

/**
 * A committed status list position reserved for one credential.
 *
 * @param statusListId the status list the position belongs to
 * @param index        the position within that list
 */
public record AllocatedPosition(String statusListId, long index) {
}
