package com.berkayb.soundconnect.role.entity;

import com.berkayb.soundconnect.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.util.HashSet;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tbl_role")
public class Role {
	
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	
	@Column(nullable = false, unique = true)
	private String name;
	
	
	@Builder.Default
	@ManyToMany(fetch = FetchType.EAGER) // EAGER: rol ile birlikte tüm izinler hemen yüklenir
	@JoinTable(
			name = "role_permissions",
			joinColumns = @JoinColumn(name = "role_id"),
			inverseJoinColumns = @JoinColumn(name = "permission_id")
	)
	private Set<Permission> permissions = new HashSet<>();
	
	// Bu role sahip kullanıcılar (User.roles içindeki mappedBy alanına bağlı)
	// mappedBy = "roles" dediğimizde bu ilişkiyi User entity'si yönetecek demektir
	@ToString.Exclude
	@EqualsAndHashCode.Exclude
	@ManyToMany(mappedBy = "roles")
	private Set<User> users = new HashSet<>();
}