package com.berkayb.soundconnect.modules.profile.OrganizerProfile.entity;

import com.berkayb.soundconnect.modules.profile.shared.BaseProfile;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@Entity
@Table(name = "tbl_organizer_profile")
public class OrganizerProfile extends BaseProfile {
//TODO ????
	
}