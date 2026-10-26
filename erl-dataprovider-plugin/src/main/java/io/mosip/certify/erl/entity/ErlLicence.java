package io.mosip.certify.erl.entity;
import jakarta.persistence.*;
import java.math.BigInteger;
import org.hibernate.annotations.Immutable;
@Entity
@Immutable
@Table(name = "lcn_licence", catalog = "licence")
public class ErlLicence {
    @Id
    @Column(name = "id", columnDefinition = "BIGINT UNSIGNED")
    private BigInteger id;
}
