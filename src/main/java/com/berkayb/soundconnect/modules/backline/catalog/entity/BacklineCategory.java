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
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.BatchSize;

@Getter
@NoArgsConstructor
@Entity
@BatchSize(size = 50)
@Table(
        name = "tbl_backline_category",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_backline_category_parent_normalized",
                columnNames = {"parent_id", "normalized_name"}
        ),
        indexes = {
                @Index(name = "idx_backline_category_parent_active", columnList = "parent_id,active,sort_order"),
                @Index(name = "idx_backline_category_normalized", columnList = "normalized_name")
        }
)
@Check(constraints = "level in (0, 1) and sort_order >= 0")
public class BacklineCategory extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private BacklineCategory parent;

    @Column(nullable = false, unique = true, length = 96)
    private String code;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(name = "normalized_name", nullable = false, length = 160)
    private String normalizedName;

    @Column(name = "icon_key", length = 128)
    private String iconKey;

    @Column(nullable = false)
    private short level;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Version
    @Column(nullable = false)
    private long version;

    public static BacklineCategory createRoot(
            String code,
            String name,
            String normalizedName,
            String iconKey,
            int sortOrder
    ) {
        return create(null, code, name, normalizedName, iconKey, (short) 0, sortOrder);
    }

    public static BacklineCategory createChild(
            BacklineCategory parent,
            String code,
            String name,
            String normalizedName,
            int sortOrder
    ) {
        if (parent == null || !parent.isRoot()) {
            throw new IllegalArgumentException("A backline subcategory must belong to a root category");
        }
        return create(parent, code, name, normalizedName, null, (short) 1, sortOrder);
    }

    private static BacklineCategory create(
            BacklineCategory parent,
            String code,
            String name,
            String normalizedName,
            String iconKey,
            short level,
            int sortOrder
    ) {
        BacklineCategory category = new BacklineCategory();
        category.parent = parent;
        category.code = code;
        category.name = name;
        category.normalizedName = normalizedName;
        category.iconKey = iconKey;
        category.level = level;
        category.sortOrder = sortOrder;
        category.active = true;
        return category;
    }

    public boolean isRoot() {
        return level == 0 && parent == null;
    }

    public boolean isLeaf() {
        return level == 1 && parent != null;
    }

    public void activate() {
        active = true;
    }

    public void synchronizeReferenceData(
            BacklineCategory expectedParent,
            String expectedName,
            String expectedNormalizedName,
            String expectedIconKey,
            int expectedSortOrder
    ) {
        if (expectedSortOrder < 0) {
            throw new IllegalArgumentException("Backline category sort order cannot be negative");
        }
        boolean root = expectedParent == null;
        parent = expectedParent;
        level = root ? (short) 0 : (short) 1;
        name = expectedName;
        normalizedName = expectedNormalizedName;
        iconKey = root ? expectedIconKey : null;
        sortOrder = expectedSortOrder;
        active = true;
    }
}
