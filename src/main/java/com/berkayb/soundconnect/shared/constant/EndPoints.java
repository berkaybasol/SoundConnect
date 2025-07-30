package com.berkayb.soundconnect.shared.constant;

import org.springframework.security.core.parameters.P;

public class EndPoints {
	
	public static final String API = "/api";
	public static final String VERSION = "/v1";
	
	public static class User {
		public static final String BASE = API + VERSION + "/users";
		public static final String GET_ALL = "/get-all-users";
		public static final String BY_ID = "/{id}";
		public static final String SAVE = "/save";
		public static final String UPDATE = "/update/{id}";
		public static final String DELETE = "/delete/{id}";
	}
	
	public static class ProfileMusician {
		public static final String BASE = API + VERSION + "/musician-profiles";
		public static final String GET_ALL = "/get-all-profiles";
		public static final String GET_MY_PROFILE = "/musician-profiles/{id}";
		public static final String CREATE = "/create-profile";
		public static final String UPDATE = "/update/{id}";
		public static final String GET_BY_ID = "/{id}";   // Public profil view (id: MusicianProfile id'si)
	}
	
	public static class ProfileVenue {
		public static final String BASE = API + VERSION + "/venue-profiles";
		public static final String GET_ALL = "/get-all-venues";
		public static final String GET_PROFILE = "/{venueId}/profile";
		public static final String CREATE_PROFILE = "/create-profile/{venueId}";
		public static final String UPDATE_PROFILE = "/{venueId}/profile";
		public static final String GET_BY_ID = "/{venueId}"; // Public profil view (id: MusicianProfile id'si)
	}
	
	public static class ArtistVenueConnections {
		public static final String BASE = API + VERSION + "/artist-venue-connections";
		public static final String REQUEST = "/request";
		public static final String ACCEPT = "/{requestId}/accept";
		public static final String REJECT = "/{requestId}/reject";
		public static final String GET_REQUESTS_BY_MUSICIAN = "/musician/{musicianProfileId}";
		public static final String GET_REQUESTS_BY_VENUE = "/venue/{venueId}";
	}
	
	public static class Instrument {
		public static final String BASE = API + VERSION + "/instruments";
		public static final String SAVE = "/save-instrument";
		public static final String GET_ALL = "/get-all-instruments";
	}
	
	public static class Follow {
		public static final String BASE = API + VERSION + "/follows";
		
	}
	
	public static class Permission{
		public static final String BASE = API + VERSION + "/permissions";
		public static final String SAVE = "/save-permission";
		public static final String GET_ALL = "/get-all-permissions";
		public static final String DELETE = "/delete-permission/{id}";
		
	}
	
	public static class Role {
		public static final String BASE = API + VERSION + "/roles";
		public static final String SAVE = "/save-role";
		public static final String GET_ALL = "/get-all-roles";
		public static final String DELETE = "/delete-role/{id}"; //
	}
	
	public static class Auth {
		public static final String BASE = API + VERSION + "/auth";
		public static final String REGISTER = "/register";
		public static final String LOGIN = "/login";
		public static final String VERIFY_EMAIL = "/verify-email";
		public static final String GOOGLE_SIGN_IN = "/google-sign-in";
		public static final String COMPLETE_GOOGLE_PROFILE = "/complete-google-profile";
	}
	
	public static class City {
		public static final String BASE = API + VERSION + "/cities";
		public static final String SAVE = "/save-city";
		public static final String GET_ALL = "/get-all-cities";
		public static final String GET_CITY = "/get-city/{id}";
		public static final String DELETE = "/delete-city/{id}";
		public static final String PRETTY = "/pretty";
	}
	
	public static class District {
		public static final String BASE = API + VERSION + "/districts";
		public static final String SAVE = "/save-district";
		public static final String GET_ALL = "/get-all-districts";
		public static final String GET_BY_ID = "/get-by-id/{id}";
		public static final String GET_BY_CITY = "/get-by-city/{cityId}";
		public static final String DELETE = "/delete-district/{id}";
	}//
	
	public static class Neighborhood {
		public static final String BASE = API + VERSION + "/neighborhoods";
		public static final String SAVE = "/save";
		public static final String GET_ALL = "/get-all";
		public static final String GET_BY_ID = "/get-by-id/{id}";
		public static final String GET_BY_DISTRICT = "/get-by-district/{districtId}";
		public static final String DELETE = "/delete/{id}";
		
	}
	
	public static class Venue {
		public static final String BASE = API + VERSION + "/venues";
		public static final String SAVE = "/save";
		public static final String UPDATE = "/update/{id}";
		public static final String GET_ALL = "/get-all";
		public static final String GET_BY_ID = "/get-by-id/{id}";
		public static final String DELETE = "/delete/{id}";
	}
	
	
	
}