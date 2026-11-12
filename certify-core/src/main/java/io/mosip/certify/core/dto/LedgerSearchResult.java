package io.mosip.certify.core.dto;

import java.util.List;

/**
 * Result of a ledger search together with what the controller needs for its response headers.
 *
 * @param records   the records to return, at most the requested page size
 * @param hasMore   whether more matching records exist beyond this page
 * @param paginated whether the caller asked for pagination (sent a limit or an offset)
 */
public record LedgerSearchResult<T>(List<T> records, boolean hasMore, boolean paginated) {
}
