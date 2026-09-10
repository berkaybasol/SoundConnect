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
		public static final String MY_USERNAME = "/me/username";
	}

	public static class AdminDashboard {
		public static final String BASE = API + VERSION + "/admin/dashboard";
		public static final String SUMMARY = "/summary";
	}
	
	public static class TableGroup {
		public static final String BASE = API + VERSION + "/table-groups";
		
		public static final String CREATE = "/create";
		public static final String VENUE_OPTIONS = "/venue-options";
		public static final String LIST_ACTIVE = "/active";
		public static final String LIST_MINE = "/mine";
		public static final String DETAIL = "/{tableGroupId}";
		public static final String JOIN = "/{tableGroupId}/join";
		public static final String APPROVE = "/{tableGroupId}/approve/{participantId}";
		public static final String REJECT = "/{tableGroupId}/reject/{participantId}";
		public static final String LEAVE = "/{tableGroupId}/leave";
		public static final String KICK = "/{tableGroupId}/kick/{participantId}";
		public static final String CANCEL = "/{tableGroupId}/cancel";
		
		public static class Chat {
			public static final String BASE = API + VERSION + "/table-groups/{tableGroupId}/chat"; //degisti
			public static final String MESSAGES = "/messages";
			public static final String GET_UNREAD_BADGE = "/unread-badge";

			public static class Game {
				public static final String BASE = Chat.BASE + "/games";
				public static final String ACTIVE = "/active";
				public static final String BY_ID = "/{gameId}";
				public static final String JOIN = BY_ID + "/join";
				public static final String LEAVE = BY_ID + "/leave";
				public static final String START = BY_ID + "/start";
				public static final String CANCEL = BY_ID + "/cancel";
				public static final String ACTIONS = BY_ID + "/actions";
			}
		}
	}
	
	public static class USER_PROFILE {
		public static final String PUBLIC_BASE = API + VERSION + "/public/users";
		public static final String PROFILES_BY_USER = "/{userId}/profiles";
	}
	
	public static class BandFollow {
		public static final String BASE = API + VERSION + "/band-follows";
		
		public static final String FOLLOW = "/bands/{bandId}";
		public static final String UNFOLLOW = "/bands/{bandId}";
		public static final String IS_FOLLOWING = "/bands/{bandId}/is-following";
		public static final String FOLLOWER_COUNT = "/bands/{bandId}/followers/count";
		public static final String FOLLOWERS = "/bands/{bandId}/followers";
		public static final String MY_FOLLOWED_BANDS = "/me/bands";
	}
	
	public static class PROFILE_RESOLVER {
		public static final String PUBLIC_BASE = API + VERSION + "/public/profiles";
		public static final String BY_USER = "/by-user/{userId}";
	}
	
	public static class Promotion {
		public static final String BASE = API + VERSION + "/promotions";
		
		// Admin işlemleri
		public static final String SAVE = "/save";
		public static final String UPDATE = "/update/{id}";
		public static final String DELETE = "/delete/{id}";
		public static final String BY_ID = "/{id}";
		public static final String GET_ALL_BY_PLACEMENT = "/placement/{placement}";
		public static final String GET_ALL_BY_STATUS = "/status/{status}";
		public static final String GET_ALL_BY_TYPE = "/type/{type}";
		
		// Client/Public gösterim işlemi
		public static final String GET_DISPLAYABLE_BY_PLACEMENT = "/displayable/{placement}";
	}
	
	public static class Spotify {  
		
		public static final String BASE = API + VERSION + "/spotify";
		
		// ui icin track picker
		public static final String SEARCH_TRACKS = "/search/tracks";
		
		// track detail secilmis trackidleri profilde gostermek icin
		public static final String TRACK_BY_ID = "/tracks/{trackId}";
		
		// batch endpoint
		public static final String TRACKS_BY_IDS = "/tracks/by-ids";
		
		
	}
	
	public static class Pulse {
		
		public static final String BASE = API + VERSION + "/pulse/rooms";
		
	}
	
	public static class Setlist {
		
		public static final String BASE = API + VERSION + "/setlists";
		
		// CREATE SETLIST
		public static final String CREATE = "/create";
		
		// DELETE SETLIST
		public static final String DELETE = "/{setlistId}";
		
		// GET SETLIST DETAIL
		public static final String BY_ID = "/{setlistId}";
		
		// ADD SET TO SETLIST
		public static final String ADD_SET = "/{setlistId}/sets";
		
		// PDF EXPORT
		public static final String PDF_EXPORT = "/{setlistId}/pdf-html";
		
		// ADD ITEM TO SET
		public static final String ADD_ITEM = "/sets/{setId}/items";
	}
	
	public static class Overthinking {
		
		public static final String BASE = API + VERSION + "/overthinking";
		
		public static final String CREATE = "/create";
		public static final String UPDATE = "/{postId}";
		public static final String DELETE = "/{postId}";
		public static final String BY_ID = "/{postId}";
		
		public static final String MY_POSTS = "/me";
		public static final String BY_ARTIST = "/artist/{artistId}";
		public static final String FEED = "/feed";
		
		// REVEAL REQUEST
		public static final String CREATE_REVEAL_REQUEST = "/{postId}/reveal-requests";
		public static final String APPROVE_REVEAL_REQUEST = "/reveal-requests/{requestId}/approve";
		public static final String REJECT_REVEAL_REQUEST = "/reveal-requests/{requestId}/reject";
		public static final String INCOMING_REVEAL_REQUESTS = "/reveal-requests/incoming";
		public static final String INCOMING_PENDING_REVEAL_REQUEST_COUNT = "/reveal-requests/incoming/pending-count";
		public static final String INCOMING_REVEAL_UNREAD_STATUS = "/reveal-requests/incoming/unread-status";
		public static final String INCOMING_REVEAL_SEEN = "/reveal-requests/incoming/seen";
		public static final String SENT_REVEAL_REQUESTS = "/reveal-requests/sent";
	}
	
	
	public static class Track {
		
		public static final String BASE =
				API + VERSION + "/musician-profiles/{profileId}/tracks";
		
		// CREATE
		public static final String CREATE = ""; // POST → BASE
		
		// GET ALL
		public static final String LIST = "";  // GET → BASE
		
		// GET BY ID
		public static final String BY_ID = "/{trackId}"; // GET
		
		// DELETE
		public static final String DELETE = "/{trackId}"; // DELETE
	}
	
	public static class BandTrack {
		public static final String BASE =
				API + VERSION + "/bands/{bandId}/tracks";
		
		public static final String CREATE = "";           // POST
		public static final String LIST = "";             // GET
		public static final String BY_ID = "/{trackId}";  // GET
		public static final String DELETE = "/{trackId}"; // DELETE
	}
	
	public static class Collab {
		public static final String BASE  = API + VERSION + "/collabs";
		public static final String ACTORS_ME = "/actors/me";
		public static final String DRAFTS = "/drafts";
		public static final String DRAFT_BY_ID = "/drafts/{listingId}";
		public static final String BY_ID = "/{listingId}";
		public static final String PUBLISH = "/{listingId}/publish";
		public static final String CLOSE = "/{listingId}/close";
		public static final String ME_LISTINGS = "/me/listings";
		public static final String SAVED = "/{listingId}/saved";
		public static final String ME_SAVED = "/me/saved";
		public static final String APPLICATIONS = "/{listingId}/applications";
		public static final String INCOMING = "/me/listings/{listingId}/applications";
		public static final String ME_APPLICATIONS = "/me/applications";
		public static final String ACCEPT = "/applications/{applicationId}/accept";
		public static final String REJECT = "/applications/{applicationId}/reject";
		public static final String WITHDRAW = "/applications/{applicationId}/withdraw";
		public static final String ME_JOBS = "/me/jobs";
		public static final String CONFIRM_COMPLETION = "/jobs/{jobId}/confirm-completion";
		public static final String JOB_REVIEWS = "/jobs/{jobId}/reviews";
		public static final String ACTOR_REVIEWS = "/actors/{actorId}/reviews";
		public static final String REPORTS = "/{listingId}/reports";
	}

	public static class CollabAdmin {
		public static final String BASE = API + VERSION + "/admin/collab/reports";
		public static final String REVIEW = "/{reportId}/review";
	}
	
	public static class Comment {
		
		// ===== BASE ===== //
		public static final String BASE = API + VERSION + "/comments";
		
		// ===== CREATE ===== //
		// POST /comments/{targetType}/{targetId}
		public static final String CREATE = "/{targetType}/{targetId}";
		
		// ===== DELETE ===== //
		// DELETE /comments/{commentId}
		public static final String DELETE = "/{commentId}";
		
		// ===== LIST ROOT COMMENTS ===== //
		// GET /comments/{targetType}/{targetId}
		public static final String LIST_BY_TARGET = "/{targetType}/{targetId}";
		
		// ===== LIST REPLIES ===== //
		// GET /comments/replies/{commentId}
		public static final String LIST_REPLIES = "/replies/{commentId}";
	}
	
	public static class Like {
		
		// ===== BASE ===== //
		public static final String BASE = API + VERSION + "/likes";
		
		// POST /likes/{targetType}/{targetId}
		public static final String LIKE = "/{targetType}/{targetId}";
		
		// DELETE /likes/{targetType}/{targetId}
		public static final String UNLIKE = "/{targetType}/{targetId}";
		
		// GET /likes/{targetType}/{targetId}/count
		public static final String COUNT = "/{targetType}/{targetId}/count";
		
		// GET /likes/{targetType}/{targetId}/is-liked
		public static final String IS_LIKED = "/{targetType}/{targetId}/is-liked";
	}
	
	public static class Event {
		
		// ===== BASE PATHS =====
		public static final String USER_BASE  = API + VERSION + "/events"; // public discovery
		public static final String OWNER_BASE = API + VERSION + "/venue-owner/events"; //  venue owner event yonetim endpointleri
		
		// =====→ VENUE OWNER ENDPOINTS ←=====
		public static final String CREATE = "";          //  POST /venue-owner/events
		public static final String DELETE = "/{eventId}"; //  DELETE /venue-owner/events/{eventId}
		
		// =====→ USER / PUBLIC ENDPOINTS ←=====
		public static final String USER_BY_ID = "/{eventId}";  // GET /events/{eventId}
		public static final String USER_TODAY = "/today";      // GET /events/today
		public static final String USER_BY_DATE = "/date/{date}";
		public static final String USER_BY_CITY = "/city/{cityId}";
		public static final String USER_BY_DISTRICT = "/district/{districtId}";
		public static final String USER_BY_NEIGHBORHOOD = "/neighborhood/{neighborhoodId}";
		public static final String USER_BY_VENUE = "/venue/{venueId}";
		
		public static final String OWNER_BY_VENUE = "/venue/{venueId}";
		public static final String USER_WEEKLY_BY_VENUE = "/venue/{venueId}/weekly";
		
		// Multi-filter search (future)
		public static final String USER_SEARCH = "/search";   // GET /events/search?date=..&cityId=..
	}
	
	public static class Band {
		public static final String USER_BASE = API + VERSION + "/user/bands"; //degisti
		public static final String PUBLIC_BASE = API + VERSION + "/public/bands"; //eklendi
		
		// Kullanıcının kendi bandleri
		public static final String MY_BANDS = "/my"; // GET - /api/v1/user/bands/my
		public static final String CREATE = "/create"; // POST - /api/v1/user/bands/create
		
		// Band detay
		public static final String BY_ID = "/{bandId}"; // GET - /api/v1/user/bands/{bandId}
		public static final String PUBLIC_BY_ID = "/{bandId}"; //eklendi
		
		// Search
		public static final String SEARCH = "/search"; //eklendi
		
		// Band davet işlemleri (davet et, kabul et, reddet vs.)
		public static final String INVITE = "/{bandId}/invite"; // POST - davet gönder
		public static final String ACCEPT_INVITE = "/{bandId}/accept"; // POST - daveti kabul et
		public static final String REJECT_INVITE = "/{bandId}/reject"; // POST - daveti reddet
		public static final String REMOVE_MEMBER = "/{bandId}/remove/{userId}"; // DELETE - üyeyi çıkar
		public static final String LEAVE = "/{bandId}/leave"; // PATCH - bandden ayrıl
		
		// Band üye listesi
		public static final String MEMBERS = "/{bandId}/members"; // GET - band üyelerini getir
		
		// Band güncelleme/silme
		//burayi sil
		// public static final String UPDATE = "/{bandId}/update";
		
		public static final String DELETE = "/{bandId}/delete";
	}
	
	public static class Notification {
		public static final String USER_BASE = API + VERSION + "/user/notifications";
		public static final String LIST = "";
		public static final String RECENT = "/recent";
		public static final String UNREAD_COUNT = "/unread-count";
		public static final String MARK_READ = "/{id}/read";
		public static final String MARK_ALL_READ = "/read-all";
		public static final String CLEAR_ALL = "/clear-all";
		public static final String DELETE = "/{id}";
	}

	public static class StudioTrack {
		public static final String BASE =
				API + VERSION + "/studio-profiles/{studioProfileId}/tracks";
		public static final String BY_ID = "/{trackId}";
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
		
		public static final String PUBLIC_BASE = API + VERSION + "/public/musician-profiles"; //eklendi
		public static final String SEARCH = "/search"; //eklendi
		public static final String PUBLIC_BY_PROFILE_ID = "/{profileId}"; //eklendi
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
	
	public static class Media {
		// user
		public static final String USER_BASE = API + VERSION + "/user/media";
		public static final String INIT_UPLOAD = "/init-upload";           // POST
		public static final String COMPLETE_UPLOAD = "/complete-upload";   // POST
		public static final String LIST_BY_OWNER = "/owner/{ownerType}/{ownerId}"; // GET ?page=..&size=..
		public static final String LIST_BY_OWNER_AND_KIND = "/owner/{ownerType}/{ownerId}/kind/{kind}"; // GET
		public static final String ACCESS_URL = "/{assetId}/access-url";       // GET (owner-authorized, short-lived)
		public static final String DELETE = "/{assetId}";                  // DELETE
		
		// public
		public static final String PUBLIC_BASE = API + VERSION + "/public/media";
		public static final String PUBLIC_BY_OWNER = "/owner/{ownerType}/{ownerId}";              // GET
		public static final String PUBLIC_BY_OWNER_AND_KIND = "/owner/{ownerType}/{ownerId}/kind/{kind}"; // GET
		
		// admin (gerekirse)
		public static final String ADMIN_BASE = API + VERSION + "/admin/media";
		public static final String ADMIN_LIST_BY_STATUS = "/by-status/{status}"; // GET
	}
	
	public static class VenueProfile {
		
		public static final String USER_BASE = API + VERSION + "/user/venue-profiles";
		public static final String ADMIN_BASE = API + VERSION + "/admin/venue-profiles";
		public static final String PUBLIC_BASE = API + VERSION + "/public/venue-profiles"; //eklendi
		
		// User
		public static final String ME = "/me";
		public static final String UPDATE = "/update/{venueId}";
		public static final String MY_DETAIL = "/me/{venueId}/detail"; //eklendi
		public static final String MY_DETAIL_UPDATE = "/me/{venueId}/detail"; //eklendi
		
		// Admin
		public static final String BY_USER_ID = "/by-user/{userId}";
		public static final String ADMIN_UPDATE = "/by-user/{userId}/{venueId}/update";
		public static final String ADMIN_CREATE = "/create/{venueId}";
		
		// Public //eklendi
		public static final String PUBLIC_DETAIL = "/{venueId}"; //eklendi
	}
	
	public static class ListenerProfile {
		public static final String USER_BASE = API + VERSION + "/user/listener-profiles";
		public static final String ADMIN_BASE = API + VERSION + "/admin/listener-profiles";
		public static final String PUBLIC_BASE = API + VERSION + "/public/listener-profiles";
		
		public static final String ME = "/me";
		public static final String CREATE = "/create";
		public static final String UPDATE = "/update";
		public static final String AVATAR = "/me/avatar";
		public static final String VISIBILITY = "/me/visibility";
		public static final String PLAYLISTS = "/me/playlists";
		
		public static final String BY_USER_ID = "/by-user/{userId}";
		public static final String ADMIN_UPDATE = "/by-user/{userId}/update";
		
		public static final String PUBLIC_BY_PROFILE_ID = "/{profileId}";
		
		public static final String SEARCH = "/search";
	}
	
	public static class StudioProfile {
		public static final String USER_BASE = API + VERSION + "/user/studio-profiles";
		public static final String ADMIN_BASE = API + VERSION + "/admin/studio-profiles";
		public static final String PUBLIC_BASE = API + VERSION + "/public/studio-profiles";
		
		// User için
		public static final String ME = "/me";
		public static final String UPDATE = "/update";
		
		// Admin için
		public static final String BY_USER_ID = "/by-user/{userId}";
		public static final String ADMIN_UPDATE = "/by-user/{userId}/update";
		public static final String PUBLIC_BY_PROFILE_ID = "/{profileId}";
	}

	public static class StudioApplication {
		public static final String USER_BASE = API + VERSION + "/user/studio-applications";
		public static final String ADMIN_BASE = API + VERSION + "/admin/studio-applications";
		public static final String CREATE = "/create";
		public static final String MY_APPLICATIONS = "/my";
		public static final String MY_PENDING = "/my/pending";
		public static final String BY_STATUS = "/by-status";
		public static final String GET_BY_ID = "/{id}";
		public static final String APPROVE = "/approve/{applicationId}";
		public static final String REJECT = "/reject/{applicationId}";
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

		/** GET /api/v1/user/dm/unread-count */
		public static final String UNREAD_COUNT = "/unread-count";
		
		
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
		public static final String CANCEL = "/{requestId}/cancel";
		public static final String DISCONNECT = "/{requestId}/disconnect";
		public static final String GET_REQUESTS_BY_MUSICIAN = "/musician/{musicianProfileId}";
		public static final String GET_REQUESTS_BY_VENUE = "/venue/{venueId}";
		public static final String GET_REQUESTS_BY_BAND = "/band/{bandId}";
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
		
		public static final String FOLLOWERS_COUNT = "/count-followers/{userId}";
		public static final String FOLLOWING_COUNT = "/count-following/{userId}";
		
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
		public static final String VERIFY_CODE = "/verify-code";
		public static final String GOOGLE_SIGN_IN = "/google-sign-in";
		public static final String COMPLETE_GOOGLE_PROFILE = "/complete-google-profile";
		public static final String RESEND_CODE = "/resend-code";
		public static final String USERNAME_AVAILABILITY = "/username-availability";
		public static final String PASSWORD_RESET_ACCOUNT = "/password-reset/account";
		public static final String FORGOT_PASSWORD = "/forgot-password";
		public static final String RESET_PASSWORD = "/reset-password";
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
		public static final String SEARCH = "/search";
	}
}
