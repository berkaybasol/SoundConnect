package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.request.BandDeletionDecision;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandDeletionOption;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandMemberRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BandAccountLifecycle {
    private final JdbcTemplate jdbc;
    private final BandRepository bands;
    private final BandMemberRepository members;
    private final BandService bandService;
    private final EntityManager entityManager;

    private record FoundedBand(UUID id, String name) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<BandDeletionOption> deletionOptions(UUID userId) {
        var result = new ArrayList<BandDeletionOption>();
        for (var band : foundedBands(userId)) {
            if (!members.hasOtherAvailableFounder(band.id(), userId)) {
                result.add(new BandDeletionOption(band.id(), band.name(), successors(band.id(), userId)));
            }
        }
        return result;
    }

    /** The verified erasure command already holds the user's write lock. All choices commit with identity erasure. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void eraseMemberships(UUID userId, List<BandDeletionDecision> decisions) {
        var byBand = new HashMap<UUID, BandDeletionDecision>();
        if (decisions != null) {
            for (var decision : decisions) {
                if (decision == null || decision.bandId() == null || decision.action() == null
                        || (decision.action() == BandDeletionDecision.Action.TRANSFER) != (decision.successorUserId() != null)
                        || byBand.put(decision.bandId(), decision) != null) throw conflict();
            }
        }
        // The same aggregate lock serializes leave, roster changes, profile updates and closure.
        // Include old covers even if the original uploader left that band long ago.
        jdbc.queryForList("""
                select b.id from tbl_band b where exists
                  (select 1 from tbl_band_member m where m.band_id=b.id and m.user_id=?)
                  or exists (select 1 from tbl_media_asset a where a.id=b.profile_picture_media_id
                      and a.owner_type='USER' and a.owner_id=?)
                order by b.id for update of b
                """, UUID.class, userId, userId);
        for (var band : foundedBands(userId)) {
            if (members.hasOtherAvailableFounder(band.id(), userId)) continue;
            var decision = byBand.remove(band.id());
            if (decision == null) throw conflict();
            if (decision.action() == BandDeletionDecision.Action.CLOSE) {
                bandService.deleteBand(band.id(), userId);
                entityManager.flush();
            } else {
                transfer(band.id(), userId, decision.successorUserId());
            }
        }
        if (!byBand.isEmpty()) throw conflict();
        // Reuse the original image and storage URLs when a surviving band still displays a
        // USER-scoped cover from its creation. Personal titles/descriptions depart with the account.
        bands.adoptReferencedCovers("USER", userId);
        jdbc.update("delete from tbl_band_member where user_id=?", userId);
    }

    private List<FoundedBand> foundedBands(UUID userId) {
        return jdbc.query("""
                select b.id,b.name from tbl_band b join tbl_band_member m on m.band_id=b.id
                where m.user_id=? and m.status='ACTIVE' and m.band_role='FOUNDER' order by b.id
                """, (rs, n) -> new FoundedBand(rs.getObject(1, UUID.class), rs.getString(2)), userId);
    }

    private List<BandDeletionOption.Successor> successors(UUID bandId, UUID userId) {
        return jdbc.query("""
                select u.id,u.user_name from tbl_band_member m join tbl_user u on u.id=m.user_id
                where m.band_id=? and m.status='ACTIVE' and m.user_id<>? and u.status='ACTIVE'
                  and not u.access_suspended and u.erased_at is null and u.email_verified
                  and exists (select 1 from tbl_musician_profile p where p.user_id=u.id)
                  and exists (select 1 from user_roles ur join tbl_role r on ur.role_id=r.id
                      where ur.user_id=u.id and r.name='ROLE_MUSICIAN')
                  and (select count(*) from tbl_band_member allocated where allocated.user_id=u.id
                      and allocated.status='ACTIVE' and allocated.band_role='FOUNDER') < ?
                order by lower(u.user_name), u.id
                """, (rs, n) -> new BandDeletionOption.Successor(rs.getObject(1, UUID.class), rs.getString(2)),
                bandId, userId, BandServiceImpl.MAX_FOUNDED_BANDS_PER_USER);
    }

    private void transfer(UUID bandId, UUID userId, UUID successorId) {
        // Lock only a currently active member of this band. This is the same account lock
        // used by band creation and deletion. Recheck eligibility/quota after acquiring it.
        try {
            var locked = jdbc.queryForList("""
                    select u.id from tbl_user u join tbl_band_member m on m.user_id=u.id
                    where m.band_id=? and m.status='ACTIVE' and u.id=? and u.id<>?
                    for no key update of u nowait
                    """, UUID.class, bandId, successorId, userId);
            if (locked.isEmpty()) throw conflict();
        } catch (PessimisticLockingFailureException busy) {
            throw conflict();
        }
        if (successors(bandId, userId).stream().noneMatch(member -> member.userId().equals(successorId))) {
            throw conflict();
        }
        jdbc.update("""
                update tbl_band_member set band_role='FOUNDER', title_version=title_version+1,
                    updated_at=now() where band_id=? and user_id=?
                """, bandId, successorId);
    }

    private static SoundConnectException conflict() {
        return new SoundConnectException(ErrorType.BAND_DELETION_DECISION_CONFLICT);
    }
}
