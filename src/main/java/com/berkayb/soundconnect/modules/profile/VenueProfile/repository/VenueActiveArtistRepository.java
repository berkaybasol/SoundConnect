package com.berkayb.soundconnect.modules.profile.VenueProfile.repository;

import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/**
 * Bounded scalar projections avoid loading venue collections or one media row per artist.
 * Both the live association and accepted consent must exist. Old or pending requests alone
 * therefore cannot publish an artist in the venue's directory.
 */
public interface VenueActiveArtistRepository extends Repository<Venue, UUID> {
    @Query("select (count(profile) > 0) from VenueProfile profile join profile.venue venue "
            + "where venue.id = :venueId and " + VenueRepository.PUBLIC_VISIBILITY)
    boolean existsVenueProfile(@Param("venueId") UUID venueId);

    String MUSICIAN_FROM = """
            from Venue venue join venue.activeMusicians musician join musician.user musicianUser
            """;
    String MUSICIAN_FILTER = """
            where venue.id = :venueId and
            """ + VenueRepository.PUBLIC_VISIBILITY + """
            and exists (select request.id from ArtistVenueConnectionRequest request
                where request.venue.id = venue.id and request.musicianProfile.id = musician.id
                and request.status = com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestStatus.ACCEPTED)
            and (:q = '' or locate(
                lower(function('translate', :q, 'ÇĞİIÖŞÜçğıöşü', 'CGIIOSUcgiosu')),
                lower(function('translate', coalesce(musician.stageName, ''), 'ÇĞİIÖŞÜçğıöşü', 'CGIIOSUcgiosu'))) > 0
                or locate(
                lower(function('translate', :q, 'ÇĞİIÖŞÜçğıöşü', 'CGIIOSUcgiosu')),
                lower(function('translate', musicianUser.username, 'ÇĞİIÖŞÜçğıöşü', 'CGIIOSUcgiosu'))) > 0)
            """;
    String MUSICIAN_NAME = "coalesce(nullif(trim(musician.stageName), ''), musicianUser.username)";
    String IMAGE_URL = "coalesce(nullif(trim(image.thumbnailUrl), ''), nullif(trim(image.playbackUrl), ''))";
    String PUBLIC_IMAGE_FILTER = """
            and image.kind = com.berkayb.soundconnect.modules.media.enums.MediaKind.IMAGE
            and image.status = com.berkayb.soundconnect.modules.media.enums.MediaStatus.READY
            and image.visibility = com.berkayb.soundconnect.modules.media.enums.MediaVisibility.PUBLIC
            """;

    @Query(value = "select new com.berkayb.soundconnect.modules.profile.VenueProfile.repository.VenueActiveArtistRow("
            + "musician.id, " + MUSICIAN_NAME + ", " + IMAGE_URL + ") " + MUSICIAN_FROM
            + "left join MediaAsset image on image.id = musician.profilePictureMediaId " + PUBLIC_IMAGE_FILTER
            + MUSICIAN_FILTER + " order by lower(" + MUSICIAN_NAME + "), musician.id",
            countQuery = "select count(musician) " + MUSICIAN_FROM + MUSICIAN_FILTER)
    Page<VenueActiveArtistRow> findMusicians(@Param("venueId") UUID venueId, @Param("q") String query,
                                           Pageable pageable);

    String BAND_FROM = "from Venue venue join venue.activeBands band ";
    String BAND_FILTER = """
            where venue.id = :venueId and
            """ + VenueRepository.PUBLIC_VISIBILITY + """
            and exists (select request.id from ArtistVenueConnectionRequest request
                where request.venue.id = venue.id and request.band.id = band.id
                and request.status = com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestStatus.ACCEPTED)
            and (:q = '' or locate(
                lower(function('translate', :q, 'ÇĞİIÖŞÜçğıöşü', 'CGIIOSUcgiosu')),
                lower(function('translate', coalesce(band.name, ''), 'ÇĞİIÖŞÜçğıöşü', 'CGIIOSUcgiosu'))) > 0)
            """;

    @Query(value = "select new com.berkayb.soundconnect.modules.profile.VenueProfile.repository.VenueActiveArtistRow("
            + "band.id, band.name, " + IMAGE_URL + ") " + BAND_FROM
            + "left join MediaAsset image on image.id = band.profilePictureMediaId " + PUBLIC_IMAGE_FILTER
            + BAND_FILTER + " order by lower(band.name), band.id",
            countQuery = "select count(band) " + BAND_FROM + BAND_FILTER)
    Page<VenueActiveArtistRow> findBands(@Param("venueId") UUID venueId, @Param("q") String query,
                                       Pageable pageable);
}
