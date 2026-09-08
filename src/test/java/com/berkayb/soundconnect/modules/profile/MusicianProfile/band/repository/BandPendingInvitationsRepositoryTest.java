package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=false",
        "spring.application.name=band-pending-invitations-repository-test"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Sql(statements = {
        "create table if not exists tbl_band (id uuid primary key, name varchar(100), profile_picture_media_id uuid)",
        "create table if not exists tbl_user (id uuid primary key, user_name varchar(255), profile_picture varchar(255))",
        "create table if not exists tbl_musician_profile (id uuid primary key, user_id uuid unique, profile_picture_media_id uuid)",
        "create table if not exists tbl_producer_profile (id uuid primary key, user_id uuid unique, profile_picture_media_id uuid)",
        "create table if not exists tbl_band_member (id uuid primary key, band_id uuid not null, user_id uuid not null, band_role varchar(30), status varchar(30), invitation_id uuid, created_at timestamp, updated_at timestamp)",
        "delete from tbl_band_member", "delete from tbl_musician_profile", "delete from tbl_producer_profile", "delete from tbl_user"
})
class BandPendingInvitationsRepositoryTest {
    @Autowired BandMemberRepository repository;
    @Autowired JdbcTemplate jdbc;
    final UUID bandId = UUID.randomUUID();
    final LocalDateTime time = LocalDateTime.of(2026, 9, 7, 12, 0);

    @Test void receivedInvitationsAreRecipientScopedPendingOnlyAndPaginated() {
        UUID recipient = insert(bandId, "recipient", "PENDING", time);
        UUID image = UUID.randomUUID();
        jdbc.update("insert into tbl_band(id,name,profile_picture_media_id) values (?,?,?)",bandId,"Şahbaz",image);
        UUID otherBand = UUID.randomUUID();
        jdbc.update("insert into tbl_band(id,name) values (?,?)",otherBand,"Diğer");
        jdbc.update("insert into tbl_band_member(id,band_id,user_id,status,created_at,updated_at) values (?,?,?,?,?,?)",
            UUID.randomUUID(),otherBand,recipient,"PENDING",time,time.plusMinutes(1));
        insert(bandId,"outsider","PENDING",time.plusMinutes(2));
        var first = repository.findReceivedInvitationSummaries(recipient,BandMemberShipStatus.PENDING,PageRequest.of(0,1));
        var second = repository.findReceivedInvitationSummaries(recipient,BandMemberShipStatus.PENDING,PageRequest.of(1,1));
        assertThat(first.getTotalElements()).isEqualTo(2);
        assertThat(first.getContent()).containsExactly(new BandReceivedInvitationRow(otherBand,"Diğer",null,null));
        assertThat(second.getContent()).containsExactly(new BandReceivedInvitationRow(bandId,"Şahbaz",image,null));
        jdbc.update("update tbl_band_member set status='ACTIVE' where user_id=? and band_id=?",recipient,bandId);
        jdbc.update("update tbl_band_member set status='REJECTED' where user_id=? and band_id=?",recipient,otherBand);
        assertThat(repository.findReceivedInvitationSummaries(recipient,BandMemberShipStatus.PENDING,PageRequest.of(0,20)).getContent()).isEmpty();
    }

    @Test void onlyPendingRowsForRequestedBandAreProjectedWithStablePageCount() {
        UUID first = insert(bandId, "a", "PENDING", time);
        UUID second = insert(bandId, "b", "PENDING", time.plusMinutes(1));
        UUID third = insert(bandId, "c", "PENDING", time.plusMinutes(2));
        insert(bandId, "active", "ACTIVE", time.plusMinutes(3));
        insert(bandId, "left", "LEFT", time.plusMinutes(3));
        insert(bandId, "rejected", "REJECTED", time.plusMinutes(3));
        insert(UUID.randomUUID(), "otherband", "PENDING", time.plusMinutes(3));
        var page0 = repository.findPendingInvitationSummaries(bandId, BandMemberShipStatus.PENDING, PageRequest.of(0, 2));
        var page1 = repository.findPendingInvitationSummaries(bandId, BandMemberShipStatus.PENDING, PageRequest.of(1, 2));
        assertThat(page0.getContent()).extracting(BandPendingInvitationRow::userId).containsExactly(third, second);
        assertThat(page1.getContent()).extracting(BandPendingInvitationRow::userId).containsExactly(first);
        assertThat(page0.getTotalElements()).isEqualTo(3);
        assertThat(page0.getTotalPages()).isEqualTo(2);
        assertThat(page0.isLast()).isFalse();
        assertThat(page1.isLast()).isTrue();
        assertThat(page0.getContent()).allSatisfy(row -> {
            assertThat(row.profilePictureMediaId()).isEqualTo(musicianImage(row.userId()));
        });
    }

    @Test void currentInvitationIsScopedToBandAndRecipientAndCarriesStoredIdentity() {
        UUID recipient = insert(bandId, "recipient", "PENDING", time);
        UUID invitationId = UUID.randomUUID();
        jdbc.update("insert into tbl_band(id,name) values (?,?)",bandId,"Şahbaz");
        jdbc.update("update tbl_band_member set invitation_id=? where band_id=? and user_id=?",invitationId,bandId,recipient);
        assertThat(repository.findCurrentReceivedInvitation(bandId,recipient,BandMemberShipStatus.PENDING))
            .contains(new BandReceivedInvitationRow(bandId,"Şahbaz",null,invitationId));
        assertThat(repository.findCurrentReceivedInvitation(bandId,UUID.randomUUID(),BandMemberShipStatus.PENDING)).isEmpty();
        assertThat(repository.findCurrentReceivedInvitation(UUID.randomUUID(),recipient,BandMemberShipStatus.PENDING)).isEmpty();
        jdbc.update("update tbl_band_member set status='ACTIVE' where band_id=? and user_id=?",bandId,recipient);
        assertThat(repository.findCurrentReceivedInvitation(bandId,recipient,BandMemberShipStatus.PENDING)).isEmpty();
    }

    @Test void nullLegacyAvatarStillProjectsCanonicalMusicianImage() {
        UUID user = insert(bandId, "aedrum", "PENDING", time);
        UUID canonicalImage = musicianImage(user);
        jdbc.update("update tbl_user set profile_picture = null where id = ?", user);
        var page = repository.findPendingInvitationSummaries(bandId, BandMemberShipStatus.PENDING, PageRequest.of(0, 20));
        assertThat(page.getContent()).containsExactly(new BandPendingInvitationRow(user, "aedrum", canonicalImage));
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test void canonicalMusicianImageWinsOverLegacyAndOtherProfileAvatar() {
        UUID user = insert(bandId, "aedrum", "PENDING", time);
        UUID canonicalImage = musicianImage(user);
        UUID producerImage = UUID.randomUUID();
        jdbc.update("insert into tbl_producer_profile(id,user_id,profile_picture_media_id) values (?,?,?)", UUID.randomUUID(), user, producerImage);
        jdbc.update("update tbl_user set profile_picture = 'https://cdn.test/legacy-other-profile.jpg' where id = ?", user);
        var page = repository.findPendingInvitationSummaries(bandId, BandMemberShipStatus.PENDING, PageRequest.of(0, 20));
        assertThat(page.getContent()).containsExactly(new BandPendingInvitationRow(user, "aedrum", canonicalImage));
        assertThat(page.getContent().getFirst().profilePictureMediaId()).isNotEqualTo(producerImage);
    }

    @Test void absentMusicianProfileDoesNotDropInvitationOrFallbackToLegacyOrProducerImage() {
        UUID user = insert(bandId, "aedrum", "PENDING", time);
        jdbc.update("delete from tbl_musician_profile where user_id = ?", user);
        jdbc.update("insert into tbl_producer_profile(id,user_id,profile_picture_media_id) values (?,?,?)", UUID.randomUUID(), user, UUID.randomUUID());
        var page = repository.findPendingInvitationSummaries(bandId, BandMemberShipStatus.PENDING, PageRequest.of(0, 20));
        assertThat(page.getContent()).containsExactly(new BandPendingInvitationRow(user, "aedrum", null));
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test void musicianWithoutImageDoesNotFallbackToLegacyAvatar() {
        UUID user = insert(bandId, "aedrum", "PENDING", time);
        jdbc.update("update tbl_musician_profile set profile_picture_media_id = null where user_id = ?", user);
        var page = repository.findPendingInvitationSummaries(bandId, BandMemberShipStatus.PENDING, PageRequest.of(0, 20));
        assertThat(page.getContent()).containsExactly(new BandPendingInvitationRow(user, "aedrum", null));
    }

    @Test void equalTimestampsRemainDeterministicWithoutDuplicatesAcrossPages() {
        for (int index = 0; index < 5; index++) insert(bandId, "member" + index, "PENDING", time);
        var full = repository.findPendingInvitationSummaries(bandId, BandMemberShipStatus.PENDING, PageRequest.of(0, 10));
        var ids = new HashSet<UUID>();
        for (int page = 0; page < 5; page++) {
            var single = repository.findPendingInvitationSummaries(bandId, BandMemberShipStatus.PENDING, PageRequest.of(page, 1));
            assertThat(single.getContent()).containsExactly(full.getContent().get(page));
            assertThat(ids.add(single.getContent().getFirst().userId())).isTrue();
        }
    }

    @Test void inviteAcceptedOrRejectedBetweenRefreshesLeavesPendingList() {
        UUID accepted = insert(bandId, "accepted", "PENDING", time);
        UUID rejected = insert(bandId, "rejected", "PENDING", time);
        assertThat(repository.findPendingInvitationSummaries(bandId, BandMemberShipStatus.PENDING, PageRequest.of(0, 20)).getTotalElements()).isEqualTo(2);
        jdbc.update("update tbl_band_member set status = 'ACTIVE' where user_id = ?", accepted);
        jdbc.update("update tbl_band_member set status = 'REJECTED' where user_id = ?", rejected);
        var refreshed = repository.findPendingInvitationSummaries(bandId, BandMemberShipStatus.PENDING, PageRequest.of(0, 20));
        assertThat(refreshed.getContent()).isEmpty();
        assertThat(refreshed.getTotalElements()).isZero();
    }

    @Test void unknownBandAndBeyondEndReturnEmptyPage() {
        insert(bandId, "pending", "PENDING", time);
        assertThat(repository.findPendingInvitationSummaries(UUID.randomUUID(), BandMemberShipStatus.PENDING, PageRequest.of(0, 20)).getContent()).isEmpty();
        assertThat(repository.findPendingInvitationSummaries(bandId, BandMemberShipStatus.PENDING, PageRequest.of(5, 20)).getContent()).isEmpty();
    }

    @Test void creationQuotaCountsOnlyTheRequestedUsersActiveFounderRows() {
        UUID actor=UUID.randomUUID(), outsider=UUID.randomUUID();
        jdbc.update("insert into tbl_user(id,user_name) values (?,?)",actor,"quota-actor");
        jdbc.update("insert into tbl_user(id,user_name) values (?,?)",outsider,"quota-outsider");
        for (BandRole role : BandRole.values()) {
            for (BandMemberShipStatus status : BandMemberShipStatus.values()) {
                UUID membershipBand=UUID.randomUUID();
                jdbc.update("insert into tbl_band(id,name) values (?,?)",membershipBand,role.name()+"-"+status.name());
                jdbc.update("insert into tbl_band_member(id,band_id,user_id,band_role,status) values (?,?,?,?,?)",
                        UUID.randomUUID(),membershipBand,actor,role.name(),status.name());
            }
        }
        UUID outsiderBand=UUID.randomUUID();
        jdbc.update("insert into tbl_band(id,name) values (?,?)",outsiderBand,"outsider-band");
        jdbc.update("insert into tbl_band_member(id,band_id,user_id,band_role,status) values (?,?,?,?,?)",
                UUID.randomUUID(),outsiderBand,outsider,"FOUNDER","ACTIVE");
        assertThat(repository.countByUserIdAndStatusAndBandRole(actor,BandMemberShipStatus.ACTIVE,BandRole.FOUNDER))
                .isEqualTo(1);
        assertThat(repository.countByUserIdAndStatus(actor,BandMemberShipStatus.ACTIVE)).isEqualTo(BandRole.values().length);
    }

    private UUID insert(UUID band, String username, String status, LocalDateTime updatedAt) {
        UUID user = UUID.randomUUID();
        jdbc.update("insert into tbl_user(id,user_name,profile_picture) values (?,?,?)", user, username, "avatar-" + username);
        jdbc.update("insert into tbl_musician_profile(id,user_id,profile_picture_media_id) values (?,?,?)", UUID.randomUUID(), user, UUID.randomUUID());
        jdbc.update("insert into tbl_band_member(id,band_id,user_id,status,created_at,updated_at) values (?,?,?,?,?,?)",
                UUID.randomUUID(), band, user, status, time.minusDays(1), updatedAt);
        return user;
    }

    private UUID musicianImage(UUID user) {
        return jdbc.queryForObject("select profile_picture_media_id from tbl_musician_profile where user_id = ?", UUID.class, user);
    }
}
