package com.berkayb.soundconnect.modules.social.comment.entity;

import com.berkayb.soundconnect.modules.social.comment.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

/**
 * hedef icerik targetType + targetId ile temsil edilir
 * parentComment ile tek seviye thread/cevap yapisi kurulabilir
 */

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Table(
		name = "tbl_comment",
		indexes = {
				// Belirli bir içerik üzerindeki yorumları hızlı listelemek için
				@Index(name = "idx_comment_target", columnList = "target_type, target_id"),
				// Thread/cevaplar için parent üzerine index
				@Index(name = "idx_comment_parent", columnList = "parent_comment_id")
		}
)
public class Comment extends BaseEntity {
	
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;
	
	
	// yorum nereye yapildi
	@Enumerated(EnumType.STRING)
	@Column(name = "target_type", nullable = false, length = 50)
	private EngagementTargetType targetType;
	
	
	// yorumun bagli oldu yerin idsi
	@Column(name = "target_id", nullable = false, columnDefinition = "uuid")
	private UUID targetId;
	
	// yorum metni
	@Column(name = "text", nullable = false, length = 2000)
	private String text;
	
	/// thread cevap yapisi icin parent comment
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "parent_comment_id")
	private Comment parentComment;
	
	
	// soft delete
	@Column(name = "is_deleted", nullable = false)
	private boolean deleted;
	
	public boolean isDeleted() {
		return deleted;
	}
}