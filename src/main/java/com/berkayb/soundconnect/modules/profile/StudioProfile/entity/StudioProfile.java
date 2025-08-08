package com.berkayb.soundconnect.modules.profile.StudioProfile.entity;

import com.berkayb.soundconnect.modules.profile.shared.BaseProfile;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.HashSet;
import java.util.Set;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tbl_studio_profile")
public class StudioProfile extends BaseProfile {
	/**
	 * @ElementCollection
	 *  primitive degerler icin entity acmadan ayri bir tabloda veri olarak saklamamizi saglar.
	 */
@ElementCollection(fetch = FetchType.LAZY)
@CollectionTable(name = "studio_facilities", joinColumns = @JoinColumn(name = "studio_profile_id"))
@Column (name = "facility")
@Builder.Default // bos bir set olarak olustur.
private Set<String> facilities = new HashSet<>(); // studyonun olanaklari. oda sayisi vs.
}