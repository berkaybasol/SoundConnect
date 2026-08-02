package com.berkayb.soundconnect.modules.studio.room.entity;

import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Entity
@Table(
        name = "tbl_studio_room_feature",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_studio_room_feature_order", columnNames = {"room_id", "order_index"}),
                @UniqueConstraint(name = "uk_studio_room_feature_label", columnNames = {"room_id", "label"})
        }
)
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class StudioRoomFeature extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private StudioRoom room;

    @Column(nullable = false, length = 60)
    private String label;

    @Column(name = "order_index", nullable = false)
    private int orderIndex;
}
