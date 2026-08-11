package com.berkayb.soundconnect.modules.collab.entity;

import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tbl_collab_saved_listing", uniqueConstraints =
        @UniqueConstraint(name = "uk_collab_saved_user_listing", columnNames = {"user_id", "collab_id"}),
        indexes = @Index(name = "idx_collab_saved_user", columnList = "user_id,created_at,id"))
public class CollabSavedListing extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "collab_id", nullable = false)
    private Collab listing;
}
