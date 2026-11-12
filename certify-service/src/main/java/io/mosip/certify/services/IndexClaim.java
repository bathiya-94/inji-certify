package io.mosip.certify.services;

/**
 * A status list position that was claimed together with its allocation count.
 *
 * @param index      the claimed position in the list
 * @param listClosed true when this claim used the last permitted allocation, so the list is now closed
 */
public record IndexClaim(long index, boolean listClosed) {
}
