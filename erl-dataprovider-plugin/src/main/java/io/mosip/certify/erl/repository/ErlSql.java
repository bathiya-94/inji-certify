package io.mosip.certify.erl.repository;
public final class ErlSql {
    private ErlSql() { }
    public static final String FIND_FOR_ISSUANCE = """
SELECT
    l.id AS licenceId,
    l.licence_number AS licenseNo,
    vc.name AS classOfVehicle,
    ft.name AS fuelType,
    l.vehicle_number AS vehicleNo,
    CONVERT(FROM_BASE64(o.name) USING utf8mb4) AS ownerName,
    NULLIF(CONCAT_WS(', ',
        NULLIF(CONVERT(FROM_BASE64(o.address_line_1) USING utf8mb4), ''),
        NULLIF(CONVERT(FROM_BASE64(o.address_line_2) USING utf8mb4), ''),
        NULLIF(o.city, '')
    ), '') AS ownerAddress,
    v.gross_weight AS vehicleWeight,
    (
        SELECT c.amount
        FROM licence.lcn_licence_charge AS c
        WHERE c.licence_id = l.id
          AND c.deleted_at IS NULL
          AND c.charge_type_code = :licenceFeeCode
        ORDER BY c.created_at DESC, c.id DESC
        LIMIT 1
    ) AS licenceFee,
    (
        SELECT c.amount
        FROM licence.lcn_licence_charge AS c
        WHERE c.licence_id = l.id
          AND c.deleted_at IS NULL
          AND c.charge_type_code = :arrearsCode
        ORDER BY c.created_at DESC, c.id DESC
        LIMIT 1
    ) AS arrears,
    (
        SELECT c.amount
        FROM licence.lcn_licence_charge AS c
        WHERE c.licence_id = l.id
          AND c.deleted_at IS NULL
          AND c.charge_type_code = :finesCode
        ORDER BY c.created_at DESC, c.id DESC
        LIMIT 1
    ) AS finesPaid,
    l.valid_from AS validityPeriodStartDate,
    l.valid_to AS validityPeriodEndDate,
    l.issued_at AS issuedDate
FROM (
    SELECT
        id, licence_number, vehicle_number, vehicle_id, owner_id,
        valid_from, valid_to, issued_at
    FROM licence.lcn_licence
    WHERE vehicle_number = :vehicleNumber
      AND issued_at = :issuedDate
      AND deleted_at IS NULL
    ORDER BY id DESC
    LIMIT 1
) AS l
LEFT JOIN licence.lcn_owner AS o ON o.id = l.owner_id
LEFT JOIN licence.lcn_vehicle AS v ON v.id = l.vehicle_id
LEFT JOIN licence.lcn_vehicle_class AS vc ON vc.id = v.vehicle_class_id
LEFT JOIN licence.lcn_fuel_type AS ft ON ft.id = v.fuel_type_id
""";
}
