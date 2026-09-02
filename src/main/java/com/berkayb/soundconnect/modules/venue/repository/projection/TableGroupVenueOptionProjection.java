package com.berkayb.soundconnect.modules.venue.repository.projection;

import java.util.UUID;

/**
 * Scalar projection for TableGroup venue autocomplete. Keeping this separate
 * from VenueResponseDto prevents unrelated owner/contact/profile data and lazy
 * collections from being loaded for each suggestion. The venue profile media
 * ID is the sole internal enrichment pointer and is never exposed directly.
 */
public interface TableGroupVenueOptionProjection {
	UUID getId();
	String getName();
	UUID getProfilePictureMediaId();
	String getAddress();
	UUID getCityId();
	String getCityName();
	UUID getDistrictId();
	String getDistrictName();
	UUID getNeighborhoodId();
	String getNeighborhoodName();
}
