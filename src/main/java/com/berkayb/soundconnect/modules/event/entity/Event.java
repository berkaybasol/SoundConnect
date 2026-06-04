package com.berkayb.soundconnect.modules.event.entity;

import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Her event bir mekanda (venue) gerceklesir.
 * bir veya birden fazla sanatciyi (MusicianProfile)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tbl_event")
public class Event extends BaseEntity {

}