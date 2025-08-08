package com.berkayb.soundconnect.modules.profile.ListenerProfile.entity;

import com.berkayb.soundconnect.modules.profile.shared.BaseProfile;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@Entity
@Table(name = "tbl_listener-profile")
public class ListenerProfile extends BaseProfile {
	//TODO ????
}