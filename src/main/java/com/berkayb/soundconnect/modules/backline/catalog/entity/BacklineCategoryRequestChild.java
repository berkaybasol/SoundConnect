package com.berkayb.soundconnect.modules.backline.catalog.entity;

import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;

@Getter
@NoArgsConstructor
@Entity
@Table(
        name = "tbl_backline_category_request_child",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_backline_request_child_position", columnNames = {"request_id", "position"}),
                @UniqueConstraint(name = "uk_backline_request_child_name", columnNames = {"request_id", "normalized_name"})
        },
        indexes = @Index(name = "idx_backline_request_child_request", columnList = "request_id")
)
@Check(constraints = "position between 0 and 9")
public class BacklineCategoryRequestChild extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false, updatable = false)
    private BacklineCategoryRequest request;

    @Column(nullable = false, updatable = false)
    private int position;

    @Column(nullable = false, updatable = false, length = 160)
    private String name;

    @Column(name = "normalized_name", nullable = false, updatable = false, length = 160)
    private String normalizedName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_category_id")
    private BacklineCategory resolvedCategory;

    BacklineCategoryRequestChild(
            BacklineCategoryRequest request,
            int position,
            String name,
            String normalizedName
    ) {
        this.request = request;
        this.position = position;
        this.name = name;
        this.normalizedName = normalizedName;
    }

    public void resolveTo(BacklineCategory category) {
        resolvedCategory = category;
    }
}
