package com.berkayb.soundconnect.modules.backline.catalog.entity;

import com.berkayb.soundconnect.modules.backline.catalog.model.BacklineCategoryRequestStatus;
import com.berkayb.soundconnect.modules.backline.catalog.model.BacklineCategoryRequestType;
import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@NoArgsConstructor
@Entity
@Table(
        name = "tbl_backline_category_request",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_backline_category_request_client",
                columnNames = {"studio_profile_id", "client_request_id"}
        ),
        indexes = {
                @Index(name = "idx_backline_request_studio_status", columnList = "studio_profile_id,status,created_at"),
                @Index(name = "idx_backline_request_admin_status", columnList = "status,created_at")
        }
)
public class BacklineCategoryRequest extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "studio_profile_id", nullable = false, updatable = false)
    private StudioProfile studioProfile;

    @Column(name = "requested_by_user_id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID requestedByUserId;

    @Column(name = "client_request_id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID clientRequestId;

    @Column(name = "request_payload_hash", nullable = false, updatable = false, length = 64)
    private String requestPayloadHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, updatable = false, length = 24)
    private BacklineCategoryRequestType type;

    @Column(name = "requested_name", nullable = false, updatable = false, length = 160)
    private String requestedName;

    @Column(name = "normalized_requested_name", nullable = false, updatable = false, length = 160)
    private String normalizedRequestedName;

    @Column(name = "requester_note", updatable = false, length = 300)
    private String requesterNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_category_id", updatable = false)
    private BacklineCategory parentCategory;

    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    @BatchSize(size = 50)
    private List<BacklineCategoryRequestChild> proposedChildren = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BacklineCategoryRequestStatus status;

    @Column(name = "reviewed_by_user_id", columnDefinition = "uuid")
    private UUID reviewedByUserId;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_root_category_id")
    private BacklineCategory resolvedRootCategory;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_category_id")
    private BacklineCategory resolvedCategory;

    @Version
    @Column(nullable = false)
    private long version;

    public static BacklineCategoryRequest create(
            StudioProfile studioProfile,
            UUID requestedByUserId,
            UUID clientRequestId,
            String requestPayloadHash,
            BacklineCategoryRequestType type,
            String requestedName,
            String normalizedRequestedName,
            String requesterNote,
            BacklineCategory parentCategory,
            List<CategoryCandidate> children
    ) {
        BacklineCategoryRequest request = new BacklineCategoryRequest();
        request.studioProfile = studioProfile;
        request.requestedByUserId = requestedByUserId;
        request.clientRequestId = clientRequestId;
        request.requestPayloadHash = requestPayloadHash;
        request.type = type;
        request.requestedName = requestedName;
        request.normalizedRequestedName = normalizedRequestedName;
        request.requesterNote = requesterNote;
        request.parentCategory = parentCategory;
        request.status = BacklineCategoryRequestStatus.PENDING;
        for (int position = 0; position < children.size(); position++) {
            CategoryCandidate child = children.get(position);
            request.proposedChildren.add(new BacklineCategoryRequestChild(
                    request,
                    position,
                    child.name(),
                    child.normalizedName()
            ));
        }
        return request;
    }

    public void withdraw() {
        ensurePending();
        status = BacklineCategoryRequestStatus.WITHDRAWN;
    }

    public void approve(
            UUID reviewerUserId,
            Instant decisionTime,
            String note,
            BacklineCategory rootCategory,
            BacklineCategory category
    ) {
        ensurePending();
        status = BacklineCategoryRequestStatus.APPROVED;
        reviewedByUserId = reviewerUserId;
        reviewedAt = decisionTime;
        decisionNote = note;
        resolvedRootCategory = rootCategory;
        resolvedCategory = category;
    }

    public void reject(UUID reviewerUserId, Instant decisionTime, String note) {
        ensurePending();
        status = BacklineCategoryRequestStatus.REJECTED;
        reviewedByUserId = reviewerUserId;
        reviewedAt = decisionTime;
        decisionNote = note;
    }

    private void ensurePending() {
        if (status != BacklineCategoryRequestStatus.PENDING) {
            throw new IllegalStateException("Only pending category requests can transition");
        }
    }

    public record CategoryCandidate(String name, String normalizedName) {
    }
}
