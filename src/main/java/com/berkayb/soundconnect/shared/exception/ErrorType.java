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
	USERNAME_CHANGE_COOLDOWN_ACTIVE(1005, "Kullanıcı adını değiştirdikten sonra 30 gün boyunca yeniden değiştiremezsin.", HttpStatus.CONFLICT, "Kullanıcı adını değiştirdikten sonra 30 gün boyunca yeniden değiştiremezsin."),
	
	// AUTH (1100-1199)
	INVALID_CREDENTIALS(1100, "Invalid username or password", HttpStatus.UNAUTHORIZED, "Kullanıcı adı veya şifre hatalı."),
	UNAUTHORIZED(1101, "You are not authorized", HttpStatus.UNAUTHORIZED, "Bu işlemi yapmak için giriş yapmalısınız."),
	FORBIDDEN_ACCESS(1102, "You don't have permission to access this resource", HttpStatus.FORBIDDEN, "Bu kaynağa erişim izniniz yok."),
	TOKEN_EXPIRED(1103, "JWT token has expired", HttpStatus.UNAUTHORIZED, "Oturum süresi dolmuş. Lütfen tekrar giriş yapın."),
	AUTH_RATE_LIMITED(1104, "Too many authentication requests", HttpStatus.TOO_MANY_REQUESTS, "Çok fazla kimlik doğrulama isteği gönderildi. Lütfen kısa süre sonra tekrar deneyin."),
	PENDING_VENUE_APPROVAL(1105, "Venue application is pending approval", HttpStatus.FORBIDDEN, "Mekan başvurunuz henüz onaylanmadı."),
	PENDING_STUDIO_APPROVAL(1106, "Studio application is pending approval", HttpStatus.FORBIDDEN, "Stüdyo başvurunuz henüz onaylanmadı."),
	PASSWORD_RESET_CODE_INVALID(1107, "Password reset code is invalid or expired", HttpStatus.BAD_REQUEST, "Şifre sıfırlama kodu geçersiz veya süresi dolmuş."),
	PASSWORD_RESET_EMAIL_NOT_FOUND(1108, "Password reset email was not found", HttpStatus.NOT_FOUND, "Bu e-posta adresiyle kayıtlı bir hesap bulunamadı."),
	PASSWORD_RESET_USERNAME_NOT_FOUND(1109, "Password reset username was not found", HttpStatus.NOT_FOUND, "Bu kullanıcı adıyla kayıtlı bir hesap bulunamadı."),
	PASSWORD_RESET_PROVIDER_UNSUPPORTED(1110, "Password reset is not supported for this account provider", HttpStatus.CONFLICT, "Bu hesap harici bir sağlayıcıyla giriş yapıyor. Şifre sıfırlama desteklenmiyor."),
	PASSWORD_RESET_DELIVERY_FAILED(1111, "Password reset email could not be queued", HttpStatus.SERVICE_UNAVAILABLE, "Şifre sıfırlama e-postası gönderilemedi. Lütfen tekrar deneyin."),
	STUDIO_APPLICATION_REJECTED(1112, "Studio application was rejected", HttpStatus.FORBIDDEN, "Stüdyo başvurunuz reddedildi. İtiraz veya bilgi için destek ekibimizle iletişime geçebilirsiniz."),
	
	// FOLLOW (1200-1299)
	FOLLOW_RELATION_NOT_FOUND(1200, "Follow relation not found", HttpStatus.NOT_FOUND, "Takip ilişkisi bulunamadı."),
	ALREADY_FOLLOWING(1201, "You are already following this user", HttpStatus.CONFLICT, "Bu kullanıcıyı zaten takip ediyorsunuz."),
	CANNOT_FOLLOW_SELF(1202, "You cannot follow yourself", HttpStatus.BAD_REQUEST, "Kendinizi takip edemezsiniz."),
	BAND_ALREADY_FOLLOWED(1203, "Band already followed", HttpStatus.CONFLICT, "Bu band zaten takip ediliyor."),
	BAND_FOLLOW_RELATION_NOT_FOUND(1204, "Band follow relation not found", HttpStatus.NOT_FOUND, "Band takip ilişkisi bulunamadı."),
	BAND_MEMBER_CANNOT_FOLLOW_OWN_BAND(1205, "Band member cannot follow own band", HttpStatus.BAD_REQUEST, "Band üyesi kendi grubunu takip edemez."),
	GHOST_PROFILE_CANNOT_BE_FOLLOWED(1206, "Ghost profiles cannot be followed", HttpStatus.CONFLICT, "Hayalet profiller takipçi kabul etmez."),
	FOLLOW_GRAPH_PRIVATE(1207, "Follow graph is private", HttpStatus.FORBIDDEN, "Bu kullanıcının takip bağlantıları gizlidir."),
	FOLLOW_RELATION_QUERY_FORBIDDEN(1208, "Follow relation query is forbidden", HttpStatus.FORBIDDEN, "Yalnızca kendi takip durumunuzu sorgulayabilirsiniz."),
	// PROFILE (1300-1399)
	PROFILE_ALREADY_EXISTS(1300, "Profile already exists", HttpStatus.CONFLICT, "Bu profil zaten var."),
	PROFILE_NOT_FOUND(1301, "Profile not found", HttpStatus.NOT_FOUND, "Profil bulunamadi."),
	PROFILE_MEDIA_NOT_FOUND(1302, "Profile media not found", HttpStatus.NOT_FOUND, "Profil medyasi bulunamadi."),
	LISTENER_PROFILE_CONTENT_LOCKED(1303, "Listener profile content is locked while profile visibility is restricted", HttpStatus.CONFLICT, "Profil görünürlüğü kısıtlıyken profil içeriği değiştirilemez."),
	LISTENER_PROFILE_VERSION_CONFLICT(1304, "Listener profile changed since it was loaded", HttpStatus.CONFLICT, "Profil başka bir işlem tarafından değiştirildi. Lütfen profili yenileyip tekrar deneyin."),
	PROFILE_TYPE_IMMUTABLE(1305, "Profile type cannot be changed after account creation", HttpStatus.CONFLICT, "Profil türü hesap oluşturulduktan sonra değiştirilemez."),
	LISTENER_PROFILE_VISIBILITY_RATE_LIMITED(1306, "Too many listener visibility requests", HttpStatus.TOO_MANY_REQUESTS, "Görünürlük ayarını çok sık değiştirmeye çalıştınız. Lütfen kısa süre sonra tekrar deneyin."),
	LISTENER_PROFILE_VISIBILITY_RATE_LIMIT_UNAVAILABLE(1307, "Listener visibility protection unavailable", HttpStatus.SERVICE_UNAVAILABLE, "Görünürlük ayarı geçici olarak kullanılamıyor. Lütfen kısa süre sonra tekrar deneyin."),
	LISTENER_PROFILE_CHOICE_REQUIRED(1308, "Listener profile visibility choice is required", HttpStatus.PRECONDITION_REQUIRED, "Devam etmek için profil görünürlüğü seçimini tamamlamalısınız."),
	
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
	STUDIO_APPLICATION_ALREADY_EXISTS(1608, "Studio application already exists", HttpStatus.CONFLICT, "Zaten bekleyen bir studyo basvurusu var."),
	STUDIO_APPLICATION_NOT_FOUND(1609, "Studio application not found", HttpStatus.NOT_FOUND, "Studyo basvurusu bulunamadi."),
	
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
	VENUE_OWNER_IMMUTABLE(7002, "Venue owner cannot be changed", HttpStatus.CONFLICT, "Mekan sahibi bu islemle degistirilemez."),
	
	// TOKEN (8000-8099)
	TOKEN_NOT_FOUND(8001, "TOKEN not found", HttpStatus.NOT_FOUND, "Token bulunamadı."),
	
	// MAIL (9000-9099)
	MAIL_QUEUE_ERROR(9001, "Mail could not be queued", HttpStatus.INTERNAL_SERVER_ERROR, "Mail kuyruğa alınamadı."),
	
	// TABLEGROUP (9100-9200)
	VENUE_ID_AND_NAME_CONFLICT(9100,"Both venueId and venueName are provided", HttpStatus.BAD_REQUEST, "Hem venueId hem de venueName dolu olamaz"),
	VENUE_INFORMATION_REQUIRED(9101,"Venue information is required", HttpStatus.BAD_REQUEST, "Mekan bilgisi (venue) girilmeli."),
	INVALID_AGE_RANGE(9102, "Invalid age range", HttpStatus.BAD_REQUEST,"Yas araligi gecersiz"),
	GENDER_AND_COUNT_MISMATCH(9103,"Gender preference and participant count mismatch", HttpStatus.BAD_REQUEST,"Cinsiyet tercihi ve kisi sayisi esit olmali"),
	TABLE_END_DATE_PASSED(9104,"Table or meeting time has already passed", HttpStatus.BAD_REQUEST,"Bulusma saati gecmiste olamaz veya masa suresi dolmus"),
	TABLE_GROUP_NOT_FOUND(9105,"Table group not found", HttpStatus.NOT_FOUND,"Table group bulunamadi"),
	MAX_PARTICIPANT_LIMIT(9106,"Max participant limit",HttpStatus.CONFLICT,"Masa dolu."),
	ALREADY_PARTICIPANT(9107,"Already participant",HttpStatus.CONFLICT,"Zaten masadasin veya basvuru yapmissin"),
	PARTICIPANT_NOT_FOUND(9108,"Participant not found",HttpStatus.NOT_FOUND,"Basvuru bulunamadi"),
	OWNER_CANNOT_LEAVE(9109,"Owner cannot leave",HttpStatus.BAD_REQUEST,"Masa sahibi masadan ayrilamaz"),
	TABLE_GROUP_RATE_LIMITED(9110,"Table group rate limited",HttpStatus.TOO_MANY_REQUESTS,"Cok fazla masa islemi yaptin. Lutfen kisa sure sonra tekrar dene."),
	TABLE_GROUP_RATE_LIMIT_UNAVAILABLE(9111,"Table group protection unavailable",HttpStatus.SERVICE_UNAVAILABLE,"Masa islemleri gecici olarak kullanilamiyor. Lutfen tekrar dene."),
	TABLE_GROUP_DURATION_INVALID(9112,"Table group meeting time is invalid",HttpStatus.BAD_REQUEST,"Bulusma saati en fazla 24 saat sonrasina secilebilir."),
	TABLE_GROUP_PAGE_REQUEST_INVALID(9113,"Table group page request is invalid",HttpStatus.BAD_REQUEST,"Sayfalama parametreleri gecersiz."),
	TABLE_GROUP_VENUE_LOCATION_MISMATCH(9114,"Venue location does not match the table location",HttpStatus.BAD_REQUEST,"Mekan konumu masa konumuyla uyusmuyor."),
	TABLE_GROUP_APPLICATION_LIMIT_REACHED(9115,"Table group application queue is full",HttpStatus.CONFLICT,"Bu masanin basvuru sirasi dolu. Lutfen daha sonra tekrar dene."),
	TABLE_GROUP_CHAT_LIMIT_REACHED(9116,"Table group chat storage limit reached",HttpStatus.CONFLICT,"Bu masa icin mesaj sinirina ulasildi."),
	TABLE_GROUP_GAME_NOT_FOUND(9117,"Table group game not found",HttpStatus.NOT_FOUND,"Oyun bulunamadi."),
	TABLE_GROUP_GAME_ACTIVE_EXISTS(9118,"An active table group game already exists",HttpStatus.CONFLICT,"Bu masada zaten aktif bir oyun var."),
	TABLE_GROUP_GAME_STATE_INVALID(9119,"Table group game state is invalid",HttpStatus.CONFLICT,"Oyun bu islem icin uygun durumda degil."),
	TABLE_GROUP_GAME_DEADLINE_PASSED(9120,"Table group game deadline passed",HttpStatus.CONFLICT,"Bu oyun adimi icin sure doldu."),
	TABLE_GROUP_GAME_NOT_PLAYER(9121,"User is not a game player",HttpStatus.FORBIDDEN,"Bu oyunun oyuncusu degilsin."),
	TABLE_GROUP_GAME_ACTION_CONFLICT(9122,"Game action conflicts with an existing action",HttpStatus.CONFLICT,"Bu tur icin hamleni zaten yaptin."),
	TABLE_GROUP_GAME_MIN_PLAYERS(9123,"Game requires at least two players",HttpStatus.CONFLICT,"Oyunu baslatmak icin en az iki oyuncu gerekli."),
	TABLE_GROUP_ACTOR_ROLE_FORBIDDEN(9124,"Account role cannot create or join table groups",HttpStatus.FORBIDDEN,"Mekan ve studyo hesaplari masa olusturamaz veya masaya katilamaz."),
	TABLE_GROUP_VENUE_OPTION_QUERY_INVALID(9125,"Venue option query must contain between 2 and 64 characters",HttpStatus.BAD_REQUEST,"Mekan arama metni 2 ile 64 karakter arasinda olmalidir."),
	TABLE_GROUP_VENUE_OPTION_LIMIT_INVALID(9126,"Venue option limit must be between 1 and 10",HttpStatus.BAD_REQUEST,"Mekan arama limiti 1 ile 10 arasinda olmalidir."),
	TABLE_GROUP_OWNER_ACTIVE_EXISTS(9127,"Owner already has an active table group",HttpStatus.CONFLICT,"Yeni masa oluşturmadan önce açık masanı kapatmalısın."),
	TABLE_GROUP_MESSAGE_IDEMPOTENCY_CONFLICT(9128,"Table group message idempotency conflict",HttpStatus.CONFLICT,"Mesaj anahtarı farklı bir içerikle daha önce kullanılmış."),
	
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
	COLLAB_NOT_FOUND(9300, "Collab listing not found", HttpStatus.NOT_FOUND, "İlan bulunamadı."),
	COLLAB_FORBIDDEN(9301, "Collab action forbidden", HttpStatus.FORBIDDEN, "Bu Collab işlemi için yetkiniz yok."),
	COLLAB_INVALID_ACTOR(9302, "Invalid Collab actor", HttpStatus.BAD_REQUEST, "Seçilen profil bu işlem için uygun değil."),
	COLLAB_LIFECYCLE_INVALID(9303, "Invalid Collab lifecycle transition", HttpStatus.CONFLICT, "İlan durumu bu işleme uygun değil."),
	COLLAB_EXPIRED(9304, "Collab listing expired", HttpStatus.GONE, "İlanın süresi dolmuş."),
	COLLAB_CADENCE_FIELDS_INVALID(9305, "Invalid Collab cadence fields", HttpStatus.BAD_REQUEST, "Düzenli veya ekstra ilan alanları geçersiz."),
	COLLAB_SPECIALTY_INVALID(9306, "Invalid Collab specialty", HttpStatus.BAD_REQUEST, "Aranan uzmanlık bilgisi geçersiz."),
	COLLAB_FEE_INVALID(9307, "Invalid Collab fee", HttpStatus.BAD_REQUEST, "Ücret bilgisi ilan türüne uygun değil."),
	COLLAB_IDEMPOTENCY_CONFLICT(9308, "Collab idempotency conflict", HttpStatus.CONFLICT, "İstek anahtarı farklı bir veriyle daha önce kullanılmış."),
	COLLAB_SELF_APPLICATION(9309, "Self application is not allowed", HttpStatus.BAD_REQUEST, "Kendi ilanına başvuramazsın."),
	COLLAB_APPLICATION_DUPLICATE(9310, "Collab application already exists", HttpStatus.CONFLICT, "Bu ilana zaten başvurdun."),
	COLLAB_APPLICATION_NOT_FOUND(9311, "Collab application not found", HttpStatus.NOT_FOUND, "Başvuru bulunamadı."),
	COLLAB_APPLICATION_STATUS_INVALID(9312, "Collab application status invalid", HttpStatus.CONFLICT, "Başvuru durumu bu işleme uygun değil."),
	COLLAB_JOB_NOT_FOUND(9313, "Collab job not found", HttpStatus.NOT_FOUND, "İş kaydı bulunamadı."),
	COLLAB_JOB_STATUS_INVALID(9314, "Collab job status invalid", HttpStatus.CONFLICT, "İş kaydı bu işleme uygun değil."),
	COLLAB_REVIEW_NOT_ALLOWED(9315, "Collab review not allowed", HttpStatus.FORBIDDEN, "Bu iş için değerlendirme yapamazsınız."),
	COLLAB_REVIEW_DUPLICATE(9316, "Collab review already exists", HttpStatus.CONFLICT, "Bu iş için değerlendirmeniz zaten mevcut."),
	COLLAB_STALE_UPDATE(9317, "Collab resource changed", HttpStatus.CONFLICT, "Kayıt değişti; yenileyip tekrar deneyin."),
	COLLAB_SELF_REPORT(9318, "Self report is not allowed", HttpStatus.BAD_REQUEST, "Kendi ilanını raporlayamazsın."),
	COLLAB_REPORT_DUPLICATE(9319, "Collab report already exists", HttpStatus.CONFLICT, "Bu ilanı daha önce raporladınız."),
	COLLAB_PUBLISHED_EDIT_RESTRICTED(9320, "Published Collab fields are locked", HttpStatus.CONFLICT, "Başvuru alan ilanın temel alanları değiştirilemez."),
	COLLAB_ACTOR_NOT_FOUND(9321, "Collab actor not found", HttpStatus.NOT_FOUND, "Collab profili bulunamadı."),
	COLLAB_PAGE_REQUEST_INVALID(9322, "Invalid Collab page request", HttpStatus.BAD_REQUEST, "Sayfalama parametreleri geçersiz."),
	COLLAB_REPORT_NOT_FOUND(9323, "Collab report not found", HttpStatus.NOT_FOUND, "Collab raporu bulunamadı."),
	COLLAB_REPORT_STATUS_INVALID(9324, "Collab report status invalid", HttpStatus.CONFLICT, "Collab raporu bu işlem için uygun durumda değil."),
	
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
	
	
	// STUDIO MANAGEMENT (9800 - 9899)
	STUDIO_ROOM_NOT_FOUND(9800, "Studio room not found", HttpStatus.NOT_FOUND, "Studio odasi bulunamadi."),
	STUDIO_ROOM_LIMIT_REACHED(9801, "Studio room limit reached", HttpStatus.CONFLICT, "Bir studyoda en fazla 10 aktif oda bulunabilir."),
	STUDIO_RESOURCE_FORBIDDEN(9802, "Studio resource forbidden", HttpStatus.FORBIDDEN, "Bu studio kaynagini yonetme yetkiniz yok."),
	STUDIO_RESOURCE_ARCHIVED(9803, "Studio resource archived", HttpStatus.CONFLICT, "Arsivlenmis bir studio kaynaginda bu islem yapilamaz."),
	STUDIO_STALE_UPDATE(9804, "Studio resource changed", HttpStatus.CONFLICT, "Kayit baska bir oturumda guncellendi. Lutfen yenileyip tekrar deneyin."),
	STUDIO_MEDIA_LIMIT_EXCEEDED(9805, "Studio media limit exceeded", HttpStatus.BAD_REQUEST, "Izin verilen fotograf siniri asildi."),
	STUDIO_TIME_ZONE_LOCKED(9806, "Studio time zone is locked", HttpStatus.CONFLICT, "Oda olusturulduktan sonra studyo saat dilimi degistirilemez."),
	STUDIO_RESERVATION_NOT_FOUND(9810, "Studio reservation not found", HttpStatus.NOT_FOUND, "Rezervasyon bulunamadi."),
	STUDIO_RESERVATION_CONFLICT(9811, "Studio reservation conflict", HttpStatus.CONFLICT, "Secilen saat araligi artik musait degil."),
	STUDIO_RESERVATION_STATUS_INVALID(9812, "Studio reservation status invalid", HttpStatus.CONFLICT, "Rezervasyon bu islem icin uygun durumda degil."),
	STUDIO_RESERVATION_SELF_NOT_ALLOWED(9813, "Studio self reservation not allowed", HttpStatus.BAD_REQUEST, "Kendi studyonuza rezervasyon olusturamazsiniz."),
	STUDIO_RESERVATION_WINDOW_INVALID(9814, "Studio reservation window invalid", HttpStatus.BAD_REQUEST, "Rezervasyon tarih veya saat araligi gecersiz."),
	STUDIO_BLOCK_NOT_FOUND(9815, "Studio room block not found", HttpStatus.NOT_FOUND, "Manuel doluluk kaydi bulunamadi."),
	STUDIO_RESERVATION_REQUESTER_OVERLAP(9816, "Overlapping reservation already exists", HttpStatus.CONFLICT, "Bu oda ve saat araliginda zaten aktif bir rezervasyon talebiniz var."),
	STUDIO_EQUIPMENT_NOT_FOUND(9820, "Studio equipment not found", HttpStatus.NOT_FOUND, "Ekipman bulunamadi."),
	STUDIO_EQUIPMENT_ALLOCATION_INVALID(9821, "Studio equipment allocation invalid", HttpStatus.CONFLICT, "Secilen adet bu tarih araligindaki mevcut dagilimla uyumlu degil."),
	BACKLINE_CATEGORY_NOT_FOUND(9830, "Backline category not found", HttpStatus.NOT_FOUND, "Backline kategorisi bulunamadi."),
	BACKLINE_CATEGORY_INVALID(9831, "Backline category invalid", HttpStatus.BAD_REQUEST, "Kategori ve alt kategori secimi gecersiz."),
	BACKLINE_CATEGORY_REQUEST_NOT_FOUND(9832, "Backline category request not found", HttpStatus.NOT_FOUND, "Kategori talebi bulunamadi."),
	BACKLINE_CATEGORY_REQUEST_DUPLICATE(9833, "Backline category request duplicate", HttpStatus.CONFLICT, "Ayni kategori icin bekleyen bir talep zaten var."),
	BACKLINE_CATEGORY_REQUEST_STATUS_INVALID(9834, "Backline category request status invalid", HttpStatus.CONFLICT, "Kategori talebi bu islem icin uygun durumda degil."),

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
