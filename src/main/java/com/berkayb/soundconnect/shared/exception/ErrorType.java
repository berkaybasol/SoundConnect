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
	INVALID_CREDENTIALS(1100, "Invalid username or password", HttpStatus.CONFLICT, "Kullanıcı adı veya şifre hatalı."),
	UNAUTHORIZED(1101, "You are not authorized", HttpStatus.UNAUTHORIZED, "Bu işlemi yapmak için giriş yapmalısınız."),
	FORBIDDEN_ACCESS(1102, "You don't have permission to access this resource", HttpStatus.FORBIDDEN, "Bu kaynağa erişim izniniz yok."),
	TOKEN_EXPIRED(1103, "JWT token has expired", HttpStatus.UNAUTHORIZED, "Oturum süresi dolmuş. Lütfen tekrar giriş yapın."),
	
	// FOLLOW (1200-1299)
	FOLLOW_RELATION_NOT_FOUND(1200, "Follow relation not found", HttpStatus.NOT_FOUND, "Takip ilişkisi bulunamadı."),
	ALREADY_FOLLOWING(1201, "You are already following this user", HttpStatus.CONFLICT, "Bu kullanıcıyı zaten takip ediyorsunuz."),
	CANNOT_FOLLOW_SELF(1202, "You cannot follow yourself", HttpStatus.BAD_REQUEST, "Kendinizi takip edemezsiniz."),
	
	// PROFILE (1300-1399)
	PROFILE_ALREADY_EXISTS(1300, "Profile already exists", HttpStatus.BAD_REQUEST, "Bu profil zaten var."),
	PROFILE_NOT_FOUND(1301, "Profile not found", HttpStatus.NOT_FOUND, "Profil bulunamadi."),
	
	// INSTRUMENT (1400-1499)
	INSTRUMENT_NOT_FOUND(1400, "Instrument not found", HttpStatus.NOT_FOUND, "Enstrüman bulunamadı."),
	INSTRUMENT_ALREADY_EXISTS(1401,"Instrument alreadyi exists", HttpStatus.BAD_REQUEST,"Bu isimde enstruman zaten var."),
	
	// ARTISTVENUECONNECTION (1500 - 1599)
	REQUEST_PENDING_ALREADY(1500,"Request Pending Already", HttpStatus.CONFLICT, "Basvuru zaten beklemede."),
	REQUEST_NOT_FOUND(1501,"Request not found", HttpStatus.NOT_FOUND, "Basvuru bulunamadi."),
	REQUEST_ALREADY_ACCEPTED(1502,"Request already accepted.", HttpStatus.BAD_REQUEST,"Basvuru zaten onaylandi."),
	REQUEST_ALREADY_REJECTED(1503,"Request already rejected.", HttpStatus.BAD_REQUEST,"Basvuru zaten reddedildi."),
	
	// VENUEAPPLICATION ( 1600 - 1699)
	VENUE_APPLICATION_ALREADY_EXISTS(1600,"Venue application already exists", HttpStatus.BAD_REQUEST, "Zaten basvuru yapilmis."),
	VENUE_APPLICATION_NOT_FOUND(1601,"Venue application not found", HttpStatus.BAD_REQUEST, "Basvuru bulunamadi"),
	INVALID_APPLICATION_STATUS(1602,"Invalid application status", HttpStatus.BAD_REQUEST, "Bu basvuruya zaten islem yapilmis"),
	
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
	
	// NOTIFICATION (1900 - 2000)
	NOTIFICATION_NOT_FOUND(1900,"Notification not found", HttpStatus.NOT_FOUND, "Bildirim bulunamadi." ),
	NOTIFICATION_ALREADY_READ(1901,"Notification already read", HttpStatus.CONFLICT, "Bildirim zaten okunmus" ),
	NOTIFICATION_UPDATE_FAILED(1902,"Notification updated failed", HttpStatus.INTERNAL_SERVER_ERROR,"Bildirim guncellenemedi."),
	NOTIFICATION_ALREADY_DELETED(1903,"Notification already deleted.", HttpStatus.CONFLICT, "Bildirim zaten silinmis." ),
	
	// VALIDATION (4000-4099)
	VALIDATION_ERROR(4000, "Validation failed", HttpStatus.BAD_REQUEST, "Alanlardan biri ya da birkaçı doğrulama hatası verdi."),
	
	// ROLE - PERMISSION (5000-5099)
	ROLE_NOT_FOUND(5001, "Role not found", HttpStatus.NOT_FOUND, "İlgili rol sistemde bulunamadı."),
	ROLE_ALREADY_EXISTS(5002, "Role already exists", HttpStatus.BAD_REQUEST, "Bu rol zaten mevcut."),
	PERMISSION_NOT_FOUND(5003, "Permission not found", HttpStatus.NOT_FOUND, "İzin bulunamadı."),
	PERMISSION_ALREADY_EXISTS(5004, "Permission already exists", HttpStatus.BAD_REQUEST, "Bu izin zaten mevcut."),
	
	// LOCATION (6000-6099)
	CITY_NOT_FOUND(6001, "City not found", HttpStatus.NOT_FOUND, "Şehir bilgisi bulunamadı."),
	CITY_ALREADY_EXISTS(6002, "City already exists", HttpStatus.BAD_REQUEST, "Bu şehir zaten sistemde kayıtlı."),
	DISTRICT_NOT_FOUND(6003, "District not found", HttpStatus.NOT_FOUND, "İlçe bilgisi bulunamadı."),
	DISTRICT_ALREADY_EXISTS(6004, "District already exists", HttpStatus.BAD_REQUEST, "Bu ilçe zaten kayıtlı."),
	NEIGHBORHOOD_ALREADY_EXISTS(6005, "Neighborhood already exists", HttpStatus.BAD_REQUEST, "Bu mahalle zaten mevcut."),
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
	TABLE_END_DATE_PASSED(9104,"Table end date has already passed", HttpStatus.BAD_REQUEST,"Masa bitis tarihi bitmis olamaz"),
	TABLE_GROUP_NOT_FOUND(9105,"Table group not found", HttpStatus.NOT_FOUND,"Table group bulunamadi"),
	
	// GENEL (9999)
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