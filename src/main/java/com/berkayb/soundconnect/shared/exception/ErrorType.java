package com.berkayb.soundconnect.shared.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorType {
	
	// USER (1000-1099)
	USER_NOT_FOUND(1001, "User not found", HttpStatus.NOT_FOUND, "Kullanıcı sistemde bulunamadı."),
	USER_ALREADY_EXISTS(1002, "User already exists", HttpStatus.CONFLICT, "Bu kullanıcı zaten mevcut."),
	EMAIL_ALREADY_EXISTS(1003, "Email already exists", HttpStatus.CONFLICT, "Bu email adresi zaten kullanılıyor."),
	USER_ALREADY_REGISTERED(1004,"User already registered",HttpStatus.CONFLICT, "Profil zaten tanimlanmis."),
	
	// AUTH (1100-1199)
	INVALID_CREDENTIALS(1100, "Invalid username or password", HttpStatus.UNAUTHORIZED, "Kullanıcı adı veya şifre hatalı."),
	UNAUTHORIZED(1101, "You are not authorized", HttpStatus.UNAUTHORIZED, "Bu işlemi yapmak için giriş yapmalısınız."),
	FORBIDDEN_ACCESS(1102, "You don't have permission to access this resource", HttpStatus.FORBIDDEN, "Bu kaynağa erişim izniniz yok."),
	TOKEN_EXPIRED(1103, "JWT token has expired", HttpStatus.UNAUTHORIZED, "Oturum süresi dolmuş. Lütfen tekrar giriş yapın."),
	AUTH_RATE_LIMITED(1104, "Too many authentication requests", HttpStatus.TOO_MANY_REQUESTS, "Çok fazla kimlik doğrulama isteği gönderildi. Lütfen kısa süre sonra tekrar deneyin."),
	
	// FOLLOW (1200-1299)
	FOLLOW_RELATION_NOT_FOUND(1200, "Follow relation not found", HttpStatus.NOT_FOUND, "Takip ilişkisi bulunamadı."),
	ALREADY_FOLLOWING(1201, "You are already following this user", HttpStatus.CONFLICT, "Bu kullanıcıyı zaten takip ediyorsunuz."),
	CANNOT_FOLLOW_SELF(1202, "You cannot follow yourself", HttpStatus.BAD_REQUEST, "Kendinizi takip edemezsiniz."),
	BAND_ALREADY_FOLLOWED(1203, "Band already followed", HttpStatus.CONFLICT, "Bu band zaten takip ediliyor."),
	BAND_FOLLOW_RELATION_NOT_FOUND(1204, "Band follow relation not found", HttpStatus.NOT_FOUND, "Band takip ilişkisi bulunamadı."),
	BAND_MEMBER_CANNOT_FOLLOW_OWN_BAND(1205, "Band member cannot follow own band", HttpStatus.BAD_REQUEST, "Band üyesi kendi grubunu takip edemez."),
	// PROFILE (1300-1399)
	PROFILE_ALREADY_EXISTS(1300, "Profile already exists", HttpStatus.CONFLICT, "Bu profil zaten var."),
	PROFILE_NOT_FOUND(1301, "Profile not found", HttpStatus.NOT_FOUND, "Profil bulunamadi."),
	PROFILE_MEDIA_NOT_FOUND(1302, "Profile media not found", HttpStatus.NOT_FOUND, "Profil medyasi bulunamadi."),
	
	// INSTRUMENT (1400-1499)
	INSTRUMENT_NOT_FOUND(1400, "Instrument not found", HttpStatus.NOT_FOUND, "Enstrüman bulunamadı."),
	INSTRUMENT_ALREADY_EXISTS(1401,"Instrument already exists", HttpStatus.CONFLICT,"Bu isimde enstruman zaten var."),
	
	// ARTISTVENUECONNECTION (1500 - 1599)
	REQUEST_PENDING_ALREADY(1500,"Request Pending Already", HttpStatus.CONFLICT, "Basvuru zaten beklemede."),
	REQUEST_NOT_FOUND(1501,"Request not found", HttpStatus.NOT_FOUND, "Basvuru bulunamadi."),
	REQUEST_ALREADY_ACCEPTED(1502,"Request already accepted.", HttpStatus.CONFLICT,"Basvuru zaten onaylandi."),
	REQUEST_ALREADY_REJECTED(1503,"Request already rejected.", HttpStatus.CONFLICT,"Basvuru zaten reddedildi."),
	REQUEST_CANCEL_NOT_ALLOWED(1504,"Only pending requests can be cancelled",HttpStatus.CONFLICT,"Yalnızca beklemede olan istekler iptal edilebilir."),
	CONNECTION_NOT_ACTIVE(1505,"Artist-venue connection is not active",HttpStatus.CONFLICT,"Sanatçı-mekan bağlantısı aktif değil."),
	VENUE_SEARCH_QUERY_REQUIRED(1506,"Search query is required",HttpStatus.BAD_REQUEST,"Arama sorgusu gereklidir."),
	REQUEST_DISCONNECT_NOT_ALLOWED(1507,"Only accepted requests can be disconnected",HttpStatus.CONFLICT,"Yalnızca kabul edilen isteklerin bağlantısı kesilebilir."),
	REQUEST_BY_TYPE_REQUIRED(1508, "Request by type is required", HttpStatus.BAD_REQUEST, "İsteği başlatan taraf belirtilmelidir."
	),
	
	// VENUEAPPLICATION ( 1600 - 1699)
	VENUE_APPLICATION_ALREADY_EXISTS(1600,"Venue application already exists", HttpStatus.CONFLICT, "Zaten basvuru yapilmis."),
	VENUE_APPLICATION_NOT_FOUND(1601,"Venue application not found", HttpStatus.NOT_FOUND, "Basvuru bulunamadi"),
	INVALID_APPLICATION_STATUS(1602,"Invalid application status", HttpStatus.CONFLICT, "Bu basvuruya zaten islem yapilmis"),
	
	// DM
	CANNOT_DM_SELF(1603, "You cannot dm yourself", HttpStatus.BAD_REQUEST, "Kendinize mesaj atamazsiniz."),
	CONVERSATION_NOT_FOUND(1604,"Conversation not found", HttpStatus.NOT_FOUND, "Konusma bulunamadi."),
	MESSAGE_NOT_FOUND(1605,"Message not found", HttpStatus.NOT_FOUND, "Mesaj bulunamadi."),
	NOT_AUTHORIZED(1606,"User is not authorized to read this message", HttpStatus.FORBIDDEN, "Bu kullanicinin bu " +
			"mesaji okumaya yetkisi yok"),
	NOT_PARTICIPANT_OF_CONVERSATION(1607,"User is not a participant of the conversation",HttpStatus.FORBIDDEN,"Bu " +
			"kullanici bu konusmanin katilimsici degil"),
	
	// MEDIA & HLS ( 1800 - 1900)
	MEDIA_ASSET_NOT_FOUND(1800, "Media asset not found", HttpStatus.NOT_FOUND, "Yüklenmek istenen medya varlığı bulunamadı."),
	MEDIA_ASSET_DELETE_FORBIDDEN(1801, "You are not allowed to delete this media",HttpStatus.FORBIDDEN,"Bu medya " +
			"varligini silmeye yetkiniz yok"),
	MEDIA_UPLOAD_INVALID_REQUEST(1802,"Invalid media upload request",HttpStatus.BAD_REQUEST,"Medya yukleme istegi gecersiz. Tur, mimeType veya boyut hatali"),
	MEDIA_UPLOAD_UNSUPPORTED_MIME(1803,"Unsupported MIME type",HttpStatus.BAD_REQUEST,"Bu Mime turune izin verilmiyor"),
	MEDIA_UPLOAD_SIZE_EXCEEDED(1804,"Upload size exceeded",HttpStatus.BAD_REQUEST,"Dosya boyutu limitini asiyor"),
	MEDIA_ASSET_ID_REQUIRED(1805,"Asset ID is required", HttpStatus.BAD_REQUEST,"assetId bos olamaz"),
	INVALID_HLS_REQUEST(1806,"Invalid HLS request", HttpStatus.BAD_REQUEST, "HLS istegi gecersiz, gerekli alanlar eksik."),
	HLS_PREFIX_REQUIRED(1807,"HLS prefix is required", HttpStatus.BAD_REQUEST, "HLS prefix bos olamaz"),
	SOURCE_KEY_REQUIRED(1808,"Source key is required", HttpStatus.BAD_REQUEST, "Source key bos olamaz"),
	ASSET_ID_REQUIRED(1809,"Asset ID is required", HttpStatus.BAD_REQUEST, "assetId bos olamaz"),
	HLS_PROCESS_SKIPPED_TERMINAL(1810,"HLS proces skipped due to terminal asset state",HttpStatus.CONFLICT,"Asset terminal durumda oldugu icin islem atlandi." ),
	MEDIA_INPUT_PATH_REQUIRED(1811,"Input path is required", HttpStatus.BAD_REQUEST, "Input path bos olamaz"),
	MEDIA_NOT_IMPLEMENTED(1812,"Media not implemented", HttpStatus.BAD_REQUEST, "medya henuz implement edilmemis"),
	MEDIA_KIND_INVALID(1813,"Media kind invalid.", HttpStatus.BAD_REQUEST,"Yanlis medya turu"),
	MEDIA_ASSET_NOT_READY(1814,"Media asset not ready.", HttpStatus.CONFLICT,"medya varligi hazir degil"),
	MEDIA_STORAGE_OBJECT_NOT_FOUND(1815, "Uploaded object not found", HttpStatus.CONFLICT, "Yuklenen dosya depolama alaninda bulunamadi"),
	MEDIA_UPLOAD_METADATA_MISMATCH(1816, "Uploaded object metadata mismatch", HttpStatus.UNPROCESSABLE_ENTITY, "Yuklenen dosyanin boyutu veya icerik turu baslatilan yukleme ile uyusmuyor"),
	MEDIA_ASSET_STATE_INVALID(1817, "Media asset state invalid", HttpStatus.CONFLICT, "Medya varligi bu islem icin uygun durumda degil"),
	MEDIA_ASSET_OWNER_MISMATCH(1818, "Media asset owner mismatch", HttpStatus.FORBIDDEN, "Medya varligi islem yapilan profile ait degil"),
	MEDIA_ASSET_NOT_PUBLIC(1819, "Media asset is not public", HttpStatus.CONFLICT, "Yayinlanan parca icin medya gorunurlugu PUBLIC olmalidir"),
	MEDIA_UPLOAD_RATE_LIMITED(1820, "Media upload quota exceeded", HttpStatus.TOO_MANY_REQUESTS, "Medya yukleme kotasi asildi. Lutfen daha sonra tekrar deneyin."),
	MEDIA_UPLOAD_CONCURRENCY_LIMITED(1821, "Too many concurrent media uploads", HttpStatus.TOO_MANY_REQUESTS, "Ayni anda cok fazla medya yuklemesi baslatildi. Devam eden yuklemeleri tamamlayin veya daha sonra tekrar deneyin."),
	MEDIA_UPLOAD_GUARD_UNAVAILABLE(1822, "Media upload protection unavailable", HttpStatus.SERVICE_UNAVAILABLE, "Medya yukleme servisi gecici olarak kullanilamiyor. Lutfen tekrar deneyin."),
	MEDIA_ASSET_IN_USE(1823, "Media asset is in use", HttpStatus.CONFLICT, "Bu medya bir profil veya icerikte kullanildigi icin silinemez."),
	
	
	// NOTIFICATION (1900 - 2000)
	NOTIFICATION_NOT_FOUND(1900,"Notification not found", HttpStatus.NOT_FOUND, "Bildirim bulunamadi." ),
	NOTIFICATION_ALREADY_READ(1901,"Notification already read", HttpStatus.CONFLICT, "Bildirim zaten okunmus" ),
	NOTIFICATION_UPDATE_FAILED(1902,"Notification updated failed", HttpStatus.INTERNAL_SERVER_ERROR,"Bildirim guncellenemedi."),
	NOTIFICATION_ALREADY_DELETED(1903,"Notification already deleted.", HttpStatus.CONFLICT, "Bildirim zaten silinmis." ),
	
	// VALIDATION (4000-4099)
	VALIDATION_ERROR(4000, "Validation failed", HttpStatus.BAD_REQUEST, "Alanlardan biri ya da birkaçı doğrulama hatası verdi."),
	MISSING_REQUEST_PARAMETER(4001, "Missing request parameter", HttpStatus.BAD_REQUEST, "Zorunlu istek parametresi eksik."),
	MALFORMED_REQUEST(4002, "Malformed request", HttpStatus.BAD_REQUEST, "İstek gövdesi eksik veya geçersiz JSON içeriyor."),
	TYPE_MISMATCH(4003, "Invalid parameter type", HttpStatus.BAD_REQUEST, "İstek parametrelerinden biri beklenen türde değil."),
	CONSTRAINT_VIOLATION(4004, "Constraint validation failed", HttpStatus.BAD_REQUEST, "İstek kısıt doğrulamasından geçemedi."),
	DATA_INTEGRITY_CONFLICT(4005, "Data integrity conflict", HttpStatus.CONFLICT, "İstek mevcut verilerle çakışıyor."),
	ENDPOINT_NOT_FOUND(4006, "Endpoint not found", HttpStatus.NOT_FOUND, "İstenen API endpoint'i bulunamadı."),
	METHOD_NOT_ALLOWED(4007, "Method not allowed", HttpStatus.METHOD_NOT_ALLOWED, "Bu endpoint belirtilen HTTP metodunu desteklemiyor."),
	UNSUPPORTED_MEDIA_TYPE(4008, "Unsupported media type", HttpStatus.UNSUPPORTED_MEDIA_TYPE, "İstek gövdesinin içerik türü desteklenmiyor."),
	
	// ROLE - PERMISSION (5000-5099)
	ROLE_NOT_FOUND(5001, "Role not found", HttpStatus.NOT_FOUND, "İlgili rol sistemde bulunamadı."),
	ROLE_ALREADY_EXISTS(5002, "Role already exists", HttpStatus.CONFLICT, "Bu rol zaten mevcut."),
	PERMISSION_NOT_FOUND(5003, "Permission not found", HttpStatus.NOT_FOUND, "İzin bulunamadı."),
	PERMISSION_ALREADY_EXISTS(5004, "Permission already exists", HttpStatus.CONFLICT, "Bu izin zaten mevcut."),
	
	// LOCATION (6000-6099)
	CITY_NOT_FOUND(6001, "City not found", HttpStatus.NOT_FOUND, "Şehir bilgisi bulunamadı."),
	CITY_ALREADY_EXISTS(6002, "City already exists", HttpStatus.CONFLICT, "Bu şehir zaten sistemde kayıtlı."),
	DISTRICT_NOT_FOUND(6003, "District not found", HttpStatus.NOT_FOUND, "İlçe bilgisi bulunamadı."),
	DISTRICT_ALREADY_EXISTS(6004, "District already exists", HttpStatus.CONFLICT, "Bu ilçe zaten kayıtlı."),
	NEIGHBORHOOD_ALREADY_EXISTS(6005, "Neighborhood already exists", HttpStatus.CONFLICT, "Bu mahalle zaten mevcut."),
	NEIGHBORHOOD_NOT_FOUND(6006, "Neighborhood not found", HttpStatus.NOT_FOUND, "Mahalle bilgisi bulunamadı."),
	INVALID_CITY_NAME(6007, "Invalid city name", HttpStatus.BAD_REQUEST, "Geçersiz şehir adı girdiniz."),
	DISTRICT_CITY_MISMATCH(6008, "District does not belong to the selected city", HttpStatus.BAD_REQUEST, "Ilce secilen sehre ait degil"),
	NEIGHBORHOOD_DISTRICT_MISMATCH(6009, "Neighborhood does not belong to the selected district",
	                               HttpStatus.BAD_REQUEST,
	                               "mahalle secilen ilceye ait degil"),
	
	// VENUE (7000-7099)
	VENUE_NOT_FOUND(7001, "Venue not found", HttpStatus.NOT_FOUND, "Mekan bilgisi bulunamadı."),
	
	// TOKEN (8000-8099)
	TOKEN_NOT_FOUND(8001, "TOKEN not found", HttpStatus.NOT_FOUND, "Token bulunamadı."),
	
	// MAIL (9000-9099)
	MAIL_QUEUE_ERROR(9001, "Mail could not be queued", HttpStatus.INTERNAL_SERVER_ERROR, "Mail kuyruğa alınamadı."),
	
	// TABLEGROUP (9100-9200)
	VENUE_ID_AND_NAME_CONFLICT(9100,"Both venueId and venueName are provided", HttpStatus.BAD_REQUEST, "Hem venueId hem de venueName dolu olamaz"),
	VENUE_INFORMATION_REQUIRED(9101,"Venue information is required", HttpStatus.BAD_REQUEST, "Mekan bilgisi (venue) girilmeli."),
	INVALID_AGE_RANGE(9102, "Invalid age range", HttpStatus.BAD_REQUEST,"Yas araligi gecersiz"),
	GENDER_AND_COUNT_MISMATCH(9103,"Gender preference and participant count mismatch", HttpStatus.BAD_REQUEST,"Cinsiyet tercihi ve kisi sayisi esit olmali"),
	TABLE_END_DATE_PASSED(9104,"Table end date has already passed", HttpStatus.BAD_REQUEST,"Masa bitis tarihi bitmis olamaz veya masa suresi bitmis"),
	TABLE_GROUP_NOT_FOUND(9105,"Table group not found", HttpStatus.NOT_FOUND,"Table group bulunamadi"),
	MAX_PARTICIPANT_LIMIT(9106,"Max participant limit",HttpStatus.CONFLICT,"Masa dolu."),
	ALREADY_PARTICIPANT(9107,"Already participant",HttpStatus.CONFLICT,"Zaten masadasin veya basvuru yapmissin"),
	PARTICIPANT_NOT_FOUND(9108,"Participant not found",HttpStatus.NOT_FOUND,"Basvuru bulunamadi"),
	OWNER_CANNOT_LEAVE(9109,"Owner cannot leave",HttpStatus.BAD_REQUEST,"Masa sahibi masadan ayrilamaz"),
	
	// BAND (9200 - 9250)
	BAND_ALREADY_EXISTS(9200,"Band already exists", HttpStatus.CONFLICT, "Bu band zaten mevcut."),
	BAND_MEMBER_NOT_FOUND(9201,"Band member not found", HttpStatus.NOT_FOUND, "Band member bulunamadi."),
	BAND_MEMBER_NOT_ACTIVE(9202,"Band member not active", HttpStatus.CONFLICT, "Band member aktif degil."),
	BAND_NOT_FOUND(9203,"Band not found", HttpStatus.NOT_FOUND, "Band bulunamadi."),
	BAND_INVITE_UNAUTHORIZED(9204, "You are not authorized", HttpStatus.FORBIDDEN, "Founder degilsin"),
	BAND_MEMBER_ALREADY_EXISTS(9205, "Band member already exists", HttpStatus.CONFLICT, "Bu kullanıcı zaten bandde."),
	BAND_INVITE_STATUS_INVALID(9206, "Band invite status is invalid", HttpStatus.BAD_REQUEST, "Davet durumu geçersiz."),
	BAND_REMOVE_UNAUTHORIZED(9207, "You are not authorized to remove members", HttpStatus.FORBIDDEN, "Üyeleri çıkarmaya yetkin yok."),
	BAND_CANNOT_REMOVE_FOUNDER(9208, "Cannot remove another founder", HttpStatus.FORBIDDEN, "Başka bir founder'ı çıkaramazsın."),
	BAND_FOUNDER_CANNOT_LEAVE(9209, "Founder cannot leave the band", HttpStatus.FORBIDDEN, "Founder gruptan ayrılamaz."),
	INVALID_PERFORMER_SELECTION(9210, "Invalid performer selection", HttpStatus.BAD_REQUEST, "Ayni anda hem grup hem muzisyen secilemez."),
	INVALID_PERFORMER_SELECTION_V2(9211, "Performer selection is required", HttpStatus.BAD_REQUEST, "Etkinlik icin bir sanatci secilmelidir."),
	MUSICIAN_NOT_FOUND(9212,"Musician not found", HttpStatus.NOT_FOUND, "Musician bulunamadi."),
	BAND_CREATE_LIMIT_EXCEEDED(9213, "Band create limit exceeded", HttpStatus.CONFLICT, "En fazla 3 band oluşturabilirsin."),
	
	// EVENT (9250 - 9299)
	EVENT_NOT_FOUND(9250,"Event not found", HttpStatus.NOT_FOUND, "Etkinlik bulunamadi."),
	INVALID_PARAMETER(9251,"Invalid parameter", HttpStatus.BAD_REQUEST, "Parametre geçersiz."),
	
	// COLLAB (9300 - 9349)
	COLLAB_NOT_FOUND_OR_NOT_OWNER(9301,"Collab not found or not owner", HttpStatus.NOT_FOUND, "Collab bulunamadi veya sahibi degil."),
	COLLAB_EXPIRATION_REQUIRED(9302,"Collab expiration required.", HttpStatus.BAD_REQUEST, "Eskik veya hatali veri " +
			"yolladiniz"),
	COLLAB_NOT_FOUND(9304,"Collab not found", HttpStatus.NOT_FOUND, "Collab bulunamadi"),
	COLLAB_NOT_OWNER(9305, "User is not the owner of the collab", HttpStatus.FORBIDDEN,"Bu collab'in sahibi degilsin"),
	COLLAB_EXPIRED(9306,"Collab has expired",HttpStatus.GONE,"Collab suresi dolmus"),
	COLLAB_SLOT_NOT_REQUIRED(9307,"Instrument not required in this collab",HttpStatus.BAD_REQUEST, "Bu enstruman bu collab icin istenmiyor"),
	COLLAB_SLOT_ALREADY_FILLED(9308,"Instrument slot already filled",HttpStatus.CONFLICT, "Bu enstruman zaten doldurulmus"),
	COLLAB_SLOT_NOT_FILLED(9309,"Instrument slot not filled",HttpStatus.CONFLICT,"Bu enstruman henuz doldurulmamis"),
	COLLAB_SLOT_ALREADY_FULL(9310,"Slot already full",HttpStatus.CONFLICT, "Zaten dolu"),
	COLLAB_SLOT_ALREADY_EMPTY(9311,"Slot already empty",HttpStatus.CONFLICT, "Zaten bos"),
	COLLAB_SLOT_REQUIRED(9312, "Required slot list is missing or empty", HttpStatus.BAD_REQUEST, "Gerekli enstrüman listesi boş veya eksik"),
	
	// COMMENT(9350 - 9399)
	COMMENT_NOT_FOUND(9350,"Comment not found", HttpStatus.NOT_FOUND, "Yorum bulunamadi."),
	COMMENT_TEXT_INVALID(9351,"Comment text must not be empty or longer than MAX_COMMENT_LENGTH.",HttpStatus.BAD_REQUEST,"Yorum metni bos veya maksimum uzunlugu asamaz."),
	COMMENT_PARENT_TARGET_MISMATCH(9352,"Comment parent target mismatch",HttpStatus.BAD_REQUEST,"yorum yanit hedefi hatali"),
	COMMENT_FORBIDDEN(9353,"COMMENT_FORBIDDEN",HttpStatus.FORBIDDEN,"Bu yorumu silme yetkiniz yok"),
	COMMENT_PARENT_DELETED(9355,"Comment parent is deleted.", HttpStatus.CONFLICT,"Silinmis yoruma yanit verilemez"),
	COMMENT_REPLY_DEPTH_NOT_ALLOWED(9354,"Comment reply depth not allowed", HttpStatus.CONFLICT,"yanita yanit " +
			"verilemez"),
	
	// OVERTHINKING(9400 - 9449)
	OVERTHINKING_MULTIPLE_MUSIC_SOURCE(9400,"You cannot multiple music source",HttpStatus.BAD_REQUEST,"Birden fazla kaynak gonderemezsiniz"),
	OVERTHINKING_POST_NOT_FOUND(9401,"Overthinking post not found",HttpStatus.NOT_FOUND,"Overthinking postu bulunamadi."),
	OVERTHINKING_POST_NOT_ANONYMOUS(9405,"Overthinking post is not anonymous",HttpStatus.BAD_REQUEST,"Bu post anonim olmadığı için profil görüntüleme isteği gönderilemez."),
	OVERTHINKING_REVEAL_REQUEST_ALREADY_EXISTS(9406, "Reveal request already exists", HttpStatus.CONFLICT, "Bu post için daha önce profil görüntüleme isteği gönderdiniz."),
	OVERTHINKING_REVEAL_REQUEST_SELF_NOT_ALLOWED(9407, "Author cannot request own profile reveal", HttpStatus.BAD_REQUEST, "Kendi postunuz için profil görüntüleme isteği gönderemezsiniz."),
	OVERTHINKING_REVEAL_REQUEST_NOT_FOUND(9408, "Reveal request not found", HttpStatus.NOT_FOUND, "Profil görüntüleme isteği bulunamadı."),
	OVERTHINKING_REVEAL_REQUEST_ALREADY_DECIDED(9409, "Reveal request already decided", HttpStatus.CONFLICT, "Bu profil görüntüleme isteği daha önce sonuçlandırılmış."),
	OVERTHINKING_REVEAL_REQUEST_INVALID_STATUS (9410, "Reveal request invalid status", HttpStatus.BAD_REQUEST, "status gecersiz"),
	OVERTHINKING_SPOTIFY_SOURCE_INVALID(9411,"Spotify source invalid", HttpStatus.BAD_REQUEST,"spotify kaynagi yanlis"),
	
	
	// TRACK(9450 - 9499)
	TRACK_NOT_FOUND(9450,"Track not found", HttpStatus.NOT_FOUND, "Parca bulunamadi"),
	TRACK_OWNER_INVALID(9451,"Track owner invalid", HttpStatus.FORBIDDEN, "Parca sahibi dogrulanamadi"),
	
	// SETLIST( 9500 - 9599)
	SETLIST_NOT_FOUND(9500,"Setlist not found", HttpStatus.NOT_FOUND, "Setlist bulunamadi"),
	SETLIST_SET_NOT_FOUND(9501,"Setlistset not found", HttpStatus.NOT_FOUND, "Setlistset bulunamadi"),
	
	// PULSE (9600 - 9649)
	ROOM_NOT_FOUND(9600,"Room not found", HttpStatus.NOT_FOUND, "oda bulunamadi"),
	
	// SPOTIFY (9650-9699)
	SPOTIFY_AUTH_FAILED(9650, "Spotify auth failed", HttpStatus.BAD_GATEWAY, "Spotify ile bağlantı kurulamadı. (Kimlik doğrulama hatası)"),
	SPOTIFY_RATE_LIMITED(9651, "Spotify rate limited", HttpStatus.TOO_MANY_REQUESTS, "Spotify çok fazla istek algıladı. Lütfen kısa süre sonra tekrar dene."),
	SPOTIFY_NOT_FOUND(9652, "Spotify resource not found", HttpStatus.NOT_FOUND, "Spotify kaynağı bulunamadı."),
	SPOTIFY_BAD_REQUEST(9653, "Spotify bad request", HttpStatus.BAD_REQUEST, "Spotify isteği geçersiz."),
	SPOTIFY_UPSTREAM_ERROR(9654, "Spotify upstream error", HttpStatus.BAD_GATEWAY, "Spotify servisinde geçici bir sorun var. Lütfen tekrar dene."),
	SPOTIFY_TIMEOUT(9655, "Spotify timeout", HttpStatus.GATEWAY_TIMEOUT, "Spotify yanıt vermedi. Lütfen tekrar dene."),
	SPOTIFY_UNEXPECTED_ERROR(9656, "Spotify unexpected error", HttpStatus.INTERNAL_SERVER_ERROR, "Spotify işlemi sırasında beklenmeyen bir hata oluştu."),
	SPOTIFY_FORBIDDEN(9657, "Spotify forbidden", HttpStatus.FORBIDDEN, "Spotify bu isteğe izin vermedi."),
	
	// ENGAGEMENT (9700 - 9749)
	ENGAGEMENT_NOT_FOUND(9700,"target not found", HttpStatus.NOT_FOUND, "target bulunamadi"),
	
	// PROMOTION (9750 - 9799)
	PROMOTION_NOT_FOUND(9750, "Promotion not found", HttpStatus.NOT_FOUND, "Promotion kaydı bulunamadı."),
	PROMOTION_MEDIA_NOT_FOUND(9751, "Promotion media asset not found", HttpStatus.NOT_FOUND, "Promotion için kullanılan medya kaydı bulunamadı."),
	PROMOTION_INVALID_DATE_RANGE(9752, "Promotion invalid date range", HttpStatus.BAD_REQUEST, "Promotion başlangıç ve bitiş tarih aralığı geçersiz."),
	PROMOTION_INVALID_PRIORITY(9753, "Promotion invalid priority", HttpStatus.BAD_REQUEST, "Promotion öncelik değeri geçersiz."),
	
	
	// GENEL (9999)
	BAD_REQUEST(9998,"Bad request", HttpStatus.BAD_REQUEST, "Istek gecersiz."),
	INTERNAL_ERROR(9999, "Internal error", HttpStatus.INTERNAL_SERVER_ERROR, "Beklenmeyen bir sunucu hatası oluştu.");
	
	private final int code;
	private final String message;
	private final HttpStatus httpStatus;
	private final String details;
	
	ErrorType(int code, String message, HttpStatus httpStatus, String details) {
		this.code = code;
		this.message = message;
		this.httpStatus = httpStatus;
		this.details = details;
	}
}
