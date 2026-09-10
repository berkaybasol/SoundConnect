package com.berkayb.soundconnect.modules.tablegroup.profileshare;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** Immutable publication identity, with a public-only source snapshot after the table ends. */
@Entity
@Getter
@NoArgsConstructor
@Table(name = "tbl_table_group_profile_share", uniqueConstraints = @UniqueConstraint(
        name = "uq_table_group_profile_share_owner_source", columnNames = {"owner_user_id", "table_group_id"}))
public class TableGroupProfileShare {
    @Id @Column(nullable = false) private UUID id;
    @Column(name = "owner_user_id", nullable = false) private UUID ownerUserId;
    @Column(name = "listener_profile_id", nullable = false) private UUID listenerProfileId;
    @Column(name = "table_group_id", nullable = false) private UUID tableGroupId;
    @Column(length = 500) private String note;
    @Column(name = "published_at", nullable = false) private Instant publishedAt;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "final_source", columnDefinition = "jsonb", insertable = false, updatable = false)
    private TableGroupProfileShareResponse.Source finalSource;
    @Column(name = "final_source_frozen", nullable = false, insertable = false, updatable = false,
            columnDefinition = "boolean default false")
    private boolean finalSourceFrozen;

    public TableGroupProfileShare(UUID ownerUserId, UUID listenerProfileId, UUID tableGroupId,
                                 String note, Instant publishedAt) {
        this.id = UUID.randomUUID();
        this.ownerUserId = ownerUserId;
        this.listenerProfileId = listenerProfileId;
        this.tableGroupId = tableGroupId;
        this.note = note;
        this.publishedAt = publishedAt;
    }
}
