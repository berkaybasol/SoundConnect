package com.berkayb.soundconnect.modules.profile.StudioProfile.entity;

import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashSet;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tbl_studio_profile")
public class StudioProfile extends BaseEntity {
/**
 * Her profil bir kullaniciya baglidir.
 * User entity ile birebir (OneToOne) iliski.
 * Bir kullanici sadece bir StudioProfile sahibi olabilir.
  */
@OneToOne
@JoinColumn(name = "user_id", nullable = false, unique = true)
private User user;

@Column(nullable = true)
private String name;

@Column(length = 1024)
private String description;

private String profilePicture; // url veya dosya yolu

private String adress;

private String phone;

private String website;
	
	/**
	 * @ElementCollection
	 *  primitive degerler icin entity acmadan ayri bir tabloda veri olarak saklamamizi saglar.
	 */

@ElementCollection(fetch = FetchType.LAZY)
@CollectionTable(name = "studio_facilities", joinColumns = @JoinColumn(name = "studio_profile_id"))
@Column (name = "facility")
@Builder.Default // bos bir set olarak olustur.
private Set<String> facilities = new HashSet<>(); // studyonun olanaklari. oda sayisi vs.

private String instagramUrl;
private String youtubeUrl;

// TODO ileride, fotograf, ses ve video icin mediya entity ile manytomany iliski kurulcak






}