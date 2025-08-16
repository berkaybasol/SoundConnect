package com.berkayb.soundconnect.shared.constant;


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
	
	public static class MusicianProfile {
		public static final String USER_BASE = API + VERSION + "/user/musician-profiles";
		public static final String ADMIN_BASE = API + VERSION + "/admin/musician-profiles";
		
		// user
		public static final String ME = "/me";
		public static final String CREATE = "/create";
		public static final String UPDATE = "/update";
		
		// Admin
		public static final String BY_USER_ID = "/by-user/{userId}";
		public static final String ADMIN_UPDATE = "/by-user/{userId}/update";
	}
	
	public static class VenueApplication {
		public static final String USER_BASE = API + VERSION + "/user/venue-applications";
		public static final String ADMIN_BASE = API + VERSION + "/admin/venue-applications";
		
		// user
		public static final String CREATE = "/create";
		public static final String MY_APPLICATIONS = "/my";
		public static final String MY_PENDING = "/my/pending";
		
		//admin
		public static final String BY_STATUS = "/by-status";
		public static final String GET_BY_ID = "/{id}";
		public static final String APPROVE = "/approve/{applicationId}";
		public static final String REJECT = "/reject/{applicationId}";
	}
	
	
	public static class VenueProfile {
		
		public static final String USER_BASE = API + VERSION + "/user/venue-profiles";
		public static final String ADMIN_BASE = API + VERSION + "/admin/venue-profiles";
		
		// User
		public static final String ME = "/me";
		public static final String UPDATE = "/update/{venueId}";
		
		// Admin
		public static final String BY_USER_ID = "/by-user/{userId}";
		public static final String ADMIN_UPDATE = "/by-user/{userId}/{venueId}/update";
		public static final String ADMIN_CREATE = "/create/{venueId}";
	}
	
	
	public class ListenerProfile {
		public static final String USER_BASE = API + VERSION + "/user/listener-profiles";
		public static final String ADMIN_BASE = API + VERSION + "/admin/listener-profiles";
		
		// User için
		public static final String ME = "/me";
		public static final String CREATE = "/create";
		public static final String UPDATE = "/update";
		
		// Admin için
		public static final String BY_USER_ID = "/by-user/{userId}";
		public static final String ADMIN_UPDATE = "/update/{userId}";
	}
	
	public static class StudioProfile {
		public static final String USER_BASE = API + VERSION + "/user/studio-profiles";
		public static final String ADMIN_BASE = API + VERSION + "/admin/studio-profiles";
		
		// User için
		public static final String ME = "/me";
		public static final String UPDATE = "/update";
		
		// Admin için
		public static final String BY_USER_ID = "/by-user/{userId}";
		public static final String ADMIN_UPDATE = "/by-user/{userId}/update";
	}
	
	public static class DM {
		
		// === USER BASE ===
		public static final String USER_BASE = API + VERSION + "/user/dm";
		
		// Conversation (user)
		/** GET  /api/v1/user/dm/conversations/my */
		public static final String CONVERSATION_LIST = "/conversations/my";
		
		/** POST /api/v1/user/dm/conversations/between?otherUserId={uuid} */
		public static final String CONVERSATION_BETWEEN = "/conversations/between";
		
		// Message (user)
		/** GET  /api/v1/user/dm/messages/conversation/{conversationId} */
		public static final String MESSAGE_LIST = "/messages/conversation/{conversationId}";
		
		/** POST /api/v1/user/dm/messages  (body: DMMessageRequestDto) */
		public static final String MESSAGE_SEND = "/messages";
		
		/** PATCH /api/v1/user/dm/messages/{messageId}/read */
		public static final String MESSAGE_MARK_READ = "/messages/{messageId}/read";
		
		
		// === ADMIN BASE ===
		public static final String ADMIN_BASE = API + VERSION + "/admin/dm";
		
		// Conversation (admin)
		/** GET    /api/v1/admin/dm/conversations */
		public static final String ADMIN_CONVERSATIONS = "/conversations";
		
		/** GET    /api/v1/admin/dm/{conversationId}
		 *  DELETE /api/v1/admin/dm/{conversationId} */
		public static final String ADMIN_CONVERSATION_BY_ID = "/{conversationId}";
		
		// Message (admin)
		/** GET    /api/v1/admin/dm/messages?conversationId={uuid} */
		public static final String ADMIN_MESSAGES = "/messages";
		
		/** DELETE /api/v1/admin/dm/{conversationId}/messages/{messageId} */
		public static final String ADMIN_DELETE_MESSAGE = "/{conversationId}/messages/{messageId}";
	}
	
	
	public static class OrganizerProfile {
		public static final String USER_BASE = API + VERSION + "/user/organizer-profiles";
		public static final String ADMIN_BASE = API + VERSION + "/admin/organizer-profiles";
		
		// User için
		public static final String ME = "/me";
		public static final String UPDATE = "/update";
		
		// Admin için
		public static final String BY_USER_ID = "/by-user/{userId}";
		public static final String ADMIN_UPDATE = "/by-user/{userId}/update";
	}
	
	public static class ProducerProfile {
		public static final String USER_BASE = API + VERSION + "/user/producer-profiles";
		public static final String ADMIN_BASE = API + VERSION + "/admin/producer-profiles";
		
		// User
		public static final String ME = "/me";
		public static final String UPDATE = "/update";
		
		// Admin
		public static final String BY_USER_ID = "/by-user/{userId}";
		public static final String ADMIN_UPDATE = "/by-user/{userId}/update";
	}
	
	
	public static class ArtistVenueConnections {
		public static final String BASE = API + VERSION + "/artist-venue-connections";
		public static final String REQUEST = "/request";
		public static final String ACCEPT = "/{requestId}/accept";
		public static final String REJECT = "/{requestId}/reject";
		public static final String GET_REQUESTS_BY_MUSICIAN = "/musician/{musicianProfileId}";
		public static final String GET_REQUESTS_BY_VENUE = "/venue/{venueId}";
	}
	
	public class InstrumentEndpoints {
		public static final String USER_BASE = API + VERSION + "/user/instruments";
		public static final String ADMIN_BASE = API + VERSION + "/admin/instruments";
		
		// User
		public static final String LIST = "";
		public static final String GET_BY_ID = "/{id}";
		
		// Admin
		public static final String CREATE = "";
		public static final String DELETE = "/{id}";
	}
	
	
	public static class Follow {
		public static final String BASE = API + VERSION + "/follow";  // tekil “follow” kullanımı daha yaygın ve anlamlıdır.
		
		public static final String FOLLOW = "/";       // POST /api/v1/follow/  → takip et
		public static final String UNFOLLOW = "/unfollow"; // POST /api/v1/follow/unfollow → takipten çık
		
		public static final String GET_FOLLOWING = "/following/{userId}"; // GET /api/v1/follow/following/{userId}
		public static final String GET_FOLLOWERS = "/followers/{userId}"; // GET /api/v1/follow/followers/{userId}
		
		public static final String IS_FOLLOWING = "/is-following"; // GET /api/v1/follow/is-following?followerId=...&followingId=...
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