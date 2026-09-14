package com.berkayb.soundconnect.modules.notification.repository;

import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.support.NotificationAudiencePolicy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQL filtering keeps pages, totals and every badge projection consistent, including legacy rows. */
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationAudienceRepositoryImpl implements NotificationAudienceRepository {
    private static final String WHERE = " where n.recipient_id=:recipient and " + NotificationAudiencePolicy.VISIBLE_SQL;
    private static final String ORDER = " order by n.occurred_at desc, n.id desc";
    private final EntityManager entityManager;

    @Override public Page<Notification> findByRecipientId(UUID recipient, Pageable pageable) {
        return page(recipient, null, pageable);
    }

    @Override public Page<Notification> findByRecipientIdAndTypeIn(UUID recipient, Collection<NotificationType> types, Pageable pageable) {
        if (types == null || types.isEmpty()) return Page.empty(pageable);
        return page(recipient, types.stream().map(Enum::name).distinct().toList(), pageable);
    }

    private Page<Notification> page(UUID recipient, List<String> types, Pageable pageable) {
        String filter = WHERE + (types == null ? "" : " and n.type in (:types)");
        Query items = visible("select n.* from tbl_notification n" + filter + ORDER, recipient, true);
        Query count = visible("select count(*) from tbl_notification n" + filter, recipient, false);
        if (types != null) { items.setParameter("types", types); count.setParameter("types", types); }
        if (pageable.isPaged()) {
            items.setFirstResult(Math.toIntExact(pageable.getOffset()));
            items.setMaxResults(pageable.getPageSize());
        }
        return new PageImpl<>(rows(items), pageable, ((Number) count.getSingleResult()).longValue());
    }

    @Override public List<Notification> findTop10ByRecipientIdOrderByOccurredAtDescIdDesc(UUID recipient) {
        return rows(visible("select n.* from tbl_notification n" + WHERE + ORDER, recipient, true).setMaxResults(10));
    }

    @Override public Optional<Notification> findByIdAndRecipientId(UUID id, UUID recipient) {
        return rows(visible("select n.* from tbl_notification n" + WHERE + " and n.id=:id", recipient, true)
                .setParameter("id", id)).stream().findFirst();
    }

    @Override public long countByRecipientIdAndReadIsFalse(UUID recipient) {
        return ((Number) visible("select count(*) from tbl_notification n" + WHERE + " and n.is_read=false", recipient, false)
                .getSingleResult()).longValue();
    }

    @Override @Transactional public int markAsRead(UUID id, UUID recipient) {
        entityManager.flush();
        return visible("update tbl_notification n set is_read=true" + WHERE + " and n.id=:id and n.is_read=false", recipient, false)
                .setParameter("id", id).executeUpdate();
    }

    @Override @Transactional public int markAllAsRead(UUID recipient) {
        entityManager.flush();
        return visible("update tbl_notification n set is_read=true" + WHERE + " and n.is_read=false", recipient, false).executeUpdate();
    }

    @Override @Transactional public int deleteByRecipientId(UUID recipient) {
        entityManager.flush();
        return visible("delete from tbl_notification n" + WHERE, recipient, false).executeUpdate();
    }

    private Query visible(String sql, UUID recipient, boolean entities) {
        Query query = entities ? entityManager.createNativeQuery(sql, Notification.class) : entityManager.createNativeQuery(sql);
        return query.setParameter("recipient", recipient).setParameter("businessTypes", NotificationAudiencePolicy.BUSINESS_TYPE_NAMES);
    }

    @SuppressWarnings("unchecked")
    private List<Notification> rows(Query query) { return query.getResultList(); }
}
