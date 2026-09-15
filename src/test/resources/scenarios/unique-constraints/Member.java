package fx;

import com.wavjaby.jdbc.annotation.Column;
import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.Table;
import com.wavjaby.jdbc.annotation.UniqueConstraint;

/** Single-column unique, a group named by field, a group named by column, and a named constraint. */
@Table(repositoryClass = MemberRepository.class, uniqueConstraints = {
        @UniqueConstraint(fieldNames = {"firstName", "lastName"}),
        @UniqueConstraint(columnNames = {"tenant_id", "code"}),
        @UniqueConstraint(name = "member_email_UK", fieldNames = {"email"})
})
public record Member(
        @Id
        long id,

        @Column(unique = true)
        String handle,

        String firstName,
        String lastName,

        @Column(name = "TENANT_ID")
        long tenantId,
        String code,
        String email
) {
}
