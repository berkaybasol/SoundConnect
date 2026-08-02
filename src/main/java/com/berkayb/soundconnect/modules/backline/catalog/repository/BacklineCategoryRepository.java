package com.berkayb.soundconnect.modules.backline.catalog.repository;

import com.berkayb.soundconnect.modules.backline.catalog.entity.BacklineCategory;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BacklineCategoryRepository extends JpaRepository<BacklineCategory, UUID> {

    Page<BacklineCategory> findByParentIsNullAndActiveTrue(Pageable pageable);

    @Query("""
            select c from BacklineCategory c
            where c.parent.id in :parentIds and c.active = true
            order by c.parent.id asc, c.sortOrder asc, c.name asc, c.id asc
            """)
    List<BacklineCategory> findActiveChildren(@Param("parentIds") Collection<UUID> parentIds);

    Optional<BacklineCategory> findByIdAndActiveTrue(UUID id);

    boolean existsByCode(String code);

    Optional<BacklineCategory> findByCode(String code);

    Optional<BacklineCategory> findByParentIsNullAndNormalizedName(String normalizedName);

    Optional<BacklineCategory> findByParentIdAndNormalizedName(UUID parentId, String normalizedName);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from BacklineCategory c where c.parent is null and c.normalizedName = :normalizedName")
    Optional<BacklineCategory> findRootForUpdate(@Param("normalizedName") String normalizedName);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from BacklineCategory c where c.parent.id = :parentId and c.normalizedName = :normalizedName")
    Optional<BacklineCategory> findChildForUpdate(
            @Param("parentId") UUID parentId,
            @Param("normalizedName") String normalizedName
    );

    @Query("select coalesce(max(c.sortOrder), -1) from BacklineCategory c where c.parent is null")
    int findMaximumRootSortOrder();

    @Query("select coalesce(max(c.sortOrder), -1) from BacklineCategory c where c.parent.id = :parentId")
    int findMaximumChildSortOrder(@Param("parentId") UUID parentId);
}
