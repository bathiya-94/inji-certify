package io.mosip.certify.services;

import io.mosip.certify.api.dto.VCResult;
import io.mosip.certify.core.constants.ErrorConstants;
import io.mosip.certify.core.constants.VCDM2Constants;
import io.mosip.certify.core.constants.VCFormats;
import io.mosip.certify.core.constants.VCIErrorConstants;
import io.mosip.certify.core.exception.CertifyException;
import io.mosip.certify.credential.Credential;
import io.mosip.certify.credential.CredentialFactory;
import io.mosip.certify.config.StatusListPublicationProperties;
import io.mosip.certify.entity.StatusListCredential;
import io.mosip.certify.exception.ManagementApiException;
import io.mosip.certify.repository.CredentialStatusTransactionRepository;
import org.springframework.http.HttpStatus;
import io.mosip.certify.repository.StatusListCredentialRepository;
import io.mosip.certify.utils.BitStringStatusListUtils;
import io.mosip.certify.vcformatters.VCFormatter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Service class for managing Status List Credentials
 * Responsible for generating, retrieving, and managing status list VCs
 */
@Slf4j
@Service
public class StatusListCredentialService {

    @Autowired
    private StatusListCredentialRepository statusListCredentialRepository;

    @Autowired
    private VCFormatter vcFormatter;

    @Autowired
    private CredentialFactory credentialFactory;

    @Autowired
    @Lazy
    private StatusListAllocationService allocationService;

    @Autowired
    private StatusListPublicationProperties publicationProperties;

    @Autowired
    private CredentialStatusTransactionRepository transactionRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${mosip.certify.status-list.signature-crypto-suite:Ed25519Signature2020}")
    private String signatureCryptoSuite;

    @Value("${mosip.certify.status-list.signature-algo:EdDSA}")
    private String signatureAlgo;

    @Value("${mosip.certify.data-provider-plugin.did-url}")
    private String didUrl;

    @Value("${mosip.certify.domain.url}")
    private String domainUrl;

    @Value("#{${mosip.certify.statuslist.size-in-kb:16}}") // value in kb
    private long statusListSizeInKB;

    @Value("#{${mosip.certify.signature-algo.key-alias-mapper}}")
    private Map<String, List<List<String>>> keyAliasMapper;

    @Value("${mosip.certify.status-list.key-manager-ref-id:ED25519_SIGN}")
    private String statusListKeyManagerRefId;

    public String getStatusListCredential(String id) throws CertifyException {
        log.info("Processing status list credential request for ID: {}", id);

        StatusListCredential statusList = findStatusListById(id)
                .orElseThrow(() -> new CertifyException(ErrorConstants.STATUS_LIST_NOT_FOUND));

        try {
            JSONObject vcDocument = new JSONObject(statusList.getVcDocument());
            log.info("Successfully retrieved status list credential for ID: {}", id);

            // Convert JSONObject to Map for consistent return type
            return vcDocument.toString();

        } catch (Exception e) {
            log.error("Unexpected error retrieving status list credential with ID: {}", id, e);
            throw new CertifyException(ErrorConstants.STATUS_RETRIEVAL_ERROR);
        }
    }

    /** A published status list for the public GET together with the validator caches use to revalidate it. */
    public record PublishedStatusList(String body, String etag) {
    }

    /**
     * The list to serve to verifiers. A list with a revocation that has waited too long to be published is
     * refused instead of being served as current: a verifier that trusted it would accept revoked credentials.
     *
     * @throws ManagementApiException 404 when the list does not exist, 503 when it is stale
     */
    public PublishedStatusList getPublishedStatusList(String id) {
        StatusListCredential statusList = findStatusListById(id)
                .orElseThrow(() -> new ManagementApiException(HttpStatus.NOT_FOUND, ErrorConstants.STATUS_LIST_NOT_FOUND,
                        "Status list not found."));
        Optional<LocalDateTime> oldestPending = transactionRepository.findOldestPendingTime(id);
        if (oldestPending.isPresent()) {
            long waitedSeconds = java.time.Duration.between(oldestPending.get(), LocalDateTime.now(java.time.ZoneOffset.UTC)).toSeconds();
            if (waitedSeconds > publicationProperties.getMaxUnpublishedAgeSeconds()) {
                log.error("Status list {} has a change unpublished for {}s (limit {}s); refusing to serve it as current",
                        id, waitedSeconds, publicationProperties.getMaxUnpublishedAgeSeconds());
                throw new ManagementApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorConstants.STATUS_LIST_UNAVAILABLE,
                        "The status list is being updated. Try again shortly.");
            }
        }
        String body = statusList.getVcDocument();
        return new PublishedStatusList(body, strongEtag(body));
    }

    /**
     * A UTC timestamp in the one format the signer reads ({@code yyyy-MM-dd'T'HH:mm:ss.SSS'Z'}). Never use
     * {@code Instant.toString()} for a signed date: it prints microseconds, or no fraction at all when the
     * milliseconds are zero, and the signer rejects both.
     */
    static String utcTimestamp(java.time.Instant instant) {
        return java.time.format.DateTimeFormatter.ofPattern(io.mosip.certify.core.constants.Constants.UTC_DATETIME_PATTERN)
                .withZone(java.time.ZoneOffset.UTC).format(instant);
    }

    /** A strong validator: a hash of the exact bytes served, so it changes only when the published list changes. */
    static String strongEtag(String body) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "\"" + java.util.HexFormat.of().formatHex(digest) + "\"";
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Find status list credential by ID
     *
     * @param id the ID of the status list credential
     * @return Optional containing StatusListCredential if found
     */
    public Optional<StatusListCredential> findStatusListById(String id) {
        log.info("Finding status list credential by ID: {}", id);

        try {
            return statusListCredentialRepository.findById(id);
        } catch (Exception e) {
            log.error("Error finding status list credential by ID: {}", id, e);
            return Optional.empty();
        }
    }

    /**
     * Find a suitable status list for the given purpose
     *
     * @param statusPurpose the purpose of the status list (e.g., "revocation", "suspension")
     * @return Optional containing StatusListCredential if found
     */
    public Optional<StatusListCredential> findSuitableStatusList(String statusPurpose, StatusListCredential.CredentialStatus status) {
        return statusListCredentialRepository.findFirstByStatusPurposeAndCredentialStatusOrderByCreatedDtimesDesc(statusPurpose, status);
    }

    /**
     * Generate a new status list credential for the specified purpose
     *
     * @param statusPurpose the purpose of the status list (e.g., "revocation", "suspension")
     * @return the generated StatusListCredential
     */
    @Transactional
    public StatusListCredential generateStatusListCredential(String statusPurpose) {
        log.info("Generating new status list credential with purpose: {}", statusPurpose);

        try {
            // Generate unique ID for status list
            String id = UUID.randomUUID().toString();
            String statusListId = domainUrl + "/v1/certify/credentials/status-list/" + id;

            // Create the template data for the status list VC
            JSONObject statusListData = new JSONObject();

            JSONArray contextList = new JSONArray();
            contextList.put("https://www.w3.org/ns/credentials/v2");
            statusListData.put("@context", contextList);

            JSONArray typeList = new JSONArray();
            typeList.put("VerifiableCredential");
            typeList.put("BitstringStatusListCredential");
            statusListData.put("type", typeList);

            statusListData.put("id", statusListId);
            statusListData.put("issuer", didUrl);
            statusListData.put("validFrom", utcTimestamp(java.time.Instant.now()));

            JSONObject credentialSubject = new JSONObject();
            credentialSubject.put("id", statusListId);
            credentialSubject.put("type", "BitstringStatusList");
            credentialSubject.put("statusPurpose", statusPurpose);
            credentialSubject.put("ttl", publicationProperties.getTtlMs());

            // Create empty encoded list (all 0s)
            String encodedList = BitStringStatusListUtils.createEmptyEncodedList(statusListSizeInKB);
            credentialSubject.put("encodedList", encodedList);

            statusListData.put("credentialSubject", credentialSubject);

            log.debug("Created status list VC: id={}, purpose={}", statusListId, statusPurpose);

            String vcDocS = addProofAndHandleResult(statusListData, ErrorConstants.VC_ISSUANCE_FAILED);

            // Create and save the status list credential entity
            StatusListCredential statusListCredential = new StatusListCredential();
            statusListCredential.setId(id);
            statusListCredential.setVcDocument(vcDocS);
            statusListCredential.setCredentialType("BitstringStatusListCredential");
            statusListCredential.setStatusPurpose(statusPurpose);
            statusListCredential.setCapacityInKB(statusListSizeInKB);
            statusListCredential.setCredentialStatus(StatusListCredential.CredentialStatus.AVAILABLE);
            statusListCredential.setCreatedDtimes(LocalDateTime.now(java.time.ZoneOffset.UTC));

            // Save to database
            StatusListCredential savedCredential = statusListCredentialRepository.saveAndFlush(statusListCredential);
            log.info("Saved StatusListCredential: ID={}, CreatedDtimes={}", savedCredential.getId(), savedCredential.getCreatedDtimes());
            initializeAvailableIndices(savedCredential);

            return savedCredential;

        } catch (JSONException e) {
            log.error("Error occurred while generating status list credential JSON.", e);
            throw new CertifyException(ErrorConstants.STATUS_LIST_GENERATION_JSON_ERROR, "Error occurred while generating status list credential JSON.");
        } catch (Exception e) {
            log.error("Error occurred while generating status list credential.", e);
            throw new CertifyException(ErrorConstants.STATUS_LIST_GENERATION_FAILED, "Error occurred while generating status list credential.");
        }
    }

    /**
     * Initialize available indices for a newly created status list using Database Query Approach
     *
     * @param statusListCredential the status list credential
     */
    @Transactional
    public void initializeAvailableIndices(StatusListCredential statusListCredential) {
        log.info("Initializing available indices for status list: {}", statusListCredential.getId());

        try {

            Query checkQuery = entityManager.createNativeQuery("SELECT COUNT(*) FROM status_list_credential WHERE id = ?");
            checkQuery.setParameter(1, statusListCredential.getId());
            Object count = checkQuery.getSingleResult();
            log.info("StatusListCredential with ID {} exists in DB: {}", statusListCredential.getId(), count);

            String insertSql = """
                    INSERT INTO status_list_available_indices
                    (status_list_credential_id, list_index, is_assigned, cr_dtimes)
                    SELECT ?, generate_series(0, ? - 1), false, NOW()
                    """;

            Query nativeQuery = entityManager.createNativeQuery(insertSql);
            nativeQuery.setParameter(1, statusListCredential.getId());
            nativeQuery.setParameter(2, statusListCredential.getCapacityInKB() * 1024L * 8L); // Convert KB to bits

            int rowsInserted = nativeQuery.executeUpdate();
            log.info("Successfully initialized {} available indices for status list: {}", rowsInserted, statusListCredential.getId());
        } catch (Exception e) {
            log.error("Error occurred during initialization of available indices for status list: {}", statusListCredential.getId(), e);
            throw new CertifyException(ErrorConstants.STATUS_LIST_INDEX_INITIALIZATION_FAILED, "Error occurred during initialization of available indices for status list: " + statusListCredential.getId());
        }
    }

    // Add this method to StatusListCredentialService.java

    /**
     * Re-sign a status list credential with updated content
     *
     * @param vcDocumentJson The updated VC document as JSON string
     * @return The re-signed VC document as JSON string
     */
    @Transactional
    public String resignStatusListCredential(String vcDocumentJson) {
        log.info("Re-signing status list credential");

        try {
            // Remove existing proof if present before re-signing
            JSONObject vcDocument = new JSONObject(vcDocumentJson);
            if (vcDocument.has("proof")) {
                vcDocument.remove("proof");
            }

            return addProofAndHandleResult(vcDocument, ErrorConstants.VC_RESIGNING_FAILED);
        } catch (Exception e) {
            log.error("Error re-signing status list credential", e);
            throw new CertifyException(ErrorConstants.VC_RESIGNING_FAILED);
        }
    }

    /**
     * Reserves a position in the active status list and adds the matching {@code credentialStatus} entry.
     * The reservation commits before the credential is signed. It is not covered by a surrounding
     * transaction so that a failed issuance can never hand the same position to another credential.
     */
    public void addCredentialStatus(JSONObject jsonObject, String statusPurpose) throws CertifyException {
        log.info("Adding credential status for status list integration");

        AllocatedPosition position = allocationService.allocate(statusPurpose);

        JSONObject credentialStatus = new JSONObject();
        String statusId = domainUrl + "/v1/certify/credentials/status-list/" + position.statusListId();
        credentialStatus.put("id", statusId + "#" + position.index());
        credentialStatus.put("type", "BitstringStatusListEntry");
        credentialStatus.put("statusPurpose", statusPurpose);
        credentialStatus.put("statusListIndex", String.valueOf(position.index()));
        credentialStatus.put("statusListCredential", statusId);
        jsonObject.put(VCDM2Constants.CREDENTIAL_STATUS, credentialStatus);

        log.info("Successfully added credential status with index {} in status list {}", position.index(), position.statusListId());
    }

    /**
     * Helper method to add a proof to a VC and handle the result.
     */
    private String addProofAndHandleResult(JSONObject vcDocument, String errorConstant) throws CertifyException {
        List<List<String>> aliases =
                (keyAliasMapper != null) ? keyAliasMapper.get(signatureAlgo) : null;
        if (aliases == null || aliases.isEmpty()
                || aliases.get(0) == null || aliases.get(0).isEmpty()
                || aliases.get(0).get(0) == null || aliases.get(0).get(0).isBlank()) {
            log.error("No key chooser configuration found for the signature crypto suite: {}", signatureCryptoSuite);
            throw new CertifyException(ErrorConstants.KEY_ALIAS_NOT_CONFIGURED, "No key chooser configuration found for the signature crypto suite: " + signatureCryptoSuite);
        }
        String appId = aliases.get(0).get(0);

        Credential cred = credentialFactory.getCredential(VCFormats.LDP_VC)
                .orElseThrow(() -> new CertifyException(VCIErrorConstants.UNSUPPORTED_CREDENTIAL_FORMAT));

        VCResult<?> vcResult = cred.addProof(
                vcDocument.toString(),
                "",
                signatureAlgo,
                appId,
                statusListKeyManagerRefId,
                didUrl,
                signatureCryptoSuite
        );

        if (vcResult.getCredential() == null) {
            log.error("Failed to add proof to VC - vcResult.getCredential() returned null");
            throw new CertifyException(errorConstant);
        }
        return vcResult.getCredential().toString();
    }

}