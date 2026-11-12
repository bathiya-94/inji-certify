package io.mosip.certify.services;

import io.mosip.certify.config.StatusListPublicationProperties;
import io.mosip.certify.core.constants.ErrorConstants;
import io.mosip.certify.core.exception.CertifyException;
import io.mosip.certify.entity.CredentialStatusTransaction;
import io.mosip.certify.entity.StatusListCredential;
import io.mosip.certify.repository.CredentialStatusTransactionRepository;
import io.mosip.certify.repository.StatusListCredentialRepository;
import io.mosip.certify.utils.BitStringStatusListUtils;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Publishes the pending changes of one status list: updates its bitstring, signs it again and marks exactly the
 * included changes as processed, all in one transaction. A signing or database failure rolls everything back and
 * leaves the changes pending for the next run.
 */
@Slf4j
@Service
public class StatusListPublicationService {

    /** Returned when another node holds the list's lock; that node is publishing it. */
    public static final int LOCKED_BY_ANOTHER_NODE = -1;

    @Autowired
    private StatusListCredentialRepository statusListRepository;

    @Autowired
    private CredentialStatusTransactionRepository transactionRepository;

    @Autowired
    private StatusListCredentialService statusListCredentialService;

    @Autowired
    private StatusListPublicationProperties properties;

    /** Source of "now" for stored timestamps, always UTC. Replaced in tests. */
    private Clock clock = Clock.systemUTC();

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * @param batchSize the most changes to publish in this call
     * @return the number of changes published, 0 when nothing is pending, or {@link #LOCKED_BY_ANOTHER_NODE}
     */
    @Transactional
    public int publish(String statusListId, int batchSize) {
        Optional<StatusListCredential> locked = statusListRepository.lockForPublication(statusListId);
        if (locked.isEmpty()) {
            return LOCKED_BY_ANOTHER_NODE;
        }
        // Read the pending changes only after the list is locked, so a concurrent publisher cannot also take them.
        List<CredentialStatusTransaction> pending = transactionRepository
                .findByStatusListCredentialIdAndIsProcessedFalseOrderByCreatedDtimesAscTransactionLogIdAsc(
                        statusListId, PageRequest.of(0, batchSize));
        if (pending.isEmpty()) {
            return 0;
        }

        StatusListCredential list = locked.get();
        JSONObject document = new JSONObject(list.getVcDocument());
        JSONObject subject = document.getJSONObject("credentialSubject");
        subject.put("encodedList", BitStringStatusListUtils.updateEncodedList(
                subject.getString("encodedList"), latestStatusPerIndex(pending), list.getCapacityInKB()));
        subject.put("ttl", properties.getTtlMs());
        document.put("validFrom", StatusListCredentialService.utcTimestamp(clock.instant()));

        list.setVcDocument(statusListCredentialService.resignStatusListCredential(document.toString()));
        list.setUpdatedDtimes(LocalDateTime.now(clock));
        statusListRepository.save(list);

        LocalDateTime processedAt = LocalDateTime.now(clock);
        for (CredentialStatusTransaction transaction : pending) {
            transaction.setProcessedTime(processedAt);
            transaction.setIsProcessed(true);
        }
        transactionRepository.saveAll(pending);
        log.info("Published status list {} with {} change(s)", statusListId, pending.size());
        return pending.size();
    }

    /** The latest requested value for each position; the list is already in request order. */
    private static Map<Long, Boolean> latestStatusPerIndex(List<CredentialStatusTransaction> pending) {
        Map<Long, Boolean> latest = new HashMap<>();
        for (CredentialStatusTransaction transaction : pending) {
            if (transaction.getStatusListIndex() == null || transaction.getStatusValue() == null) {
                throw new CertifyException(ErrorConstants.STATUS_LIST_UPDATE_FAILED,
                        "Pending change " + transaction.getTransactionLogId() + " has no position or value.");
            }
            latest.put(transaction.getStatusListIndex(), transaction.getStatusValue());
        }
        return latest;
    }
}
