package com.berkayb.soundconnect.shared.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorType {
	
	// USER (1000-1099)
	USER_NOT_FOUND(1001, "User not found", HttpStatus.NOT_FOUND, "Kullanıcı sistemde bulunamadı."),
	USER_ALREADY_EXISTS(1002, "User already exists", HttpStatus.CONFLICT, "Bu kullanıcı zaten mevcut."),
	EMAIL_ALREADY_EXISTS(1003, "Email already exists", HttpStatus.CONFLICT, "Bu email adresi zaten kullanılıyor."),
	
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
	
	// ARTISTVENUECONNECTION (1500 - 1599)
	REQUEST_PENDING_ALREADY(1500,"Request Pending Already", HttpStatus.CONFLICT, "Basvuru zaten beklemede."),
	REQUEST_NOT_FOUND(1501,"Request not found", HttpStatus.NOT_FOUND, "Basvuru bulunamadi."),
	REQUEST_ALREADY_ACCEPTED(1502,"Request already accepted.", HttpStatus.BAD_REQUEST,"Basvuru zaten onaylandi."),
	REQUEST_ALREADY_REJECTED(1503,"Request already rejected.", HttpStatus.BAD_REQUEST,"Basvuru zaten reddedildi."),
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
	
	// VENUE (7000-7099)
	VENUE_NOT_FOUND(7001, "Venue not found", HttpStatus.NOT_FOUND, "Mekan bilgisi bulunamadı."),
	
	// TOKEN (8000-8099)
	TOKEN_NOT_FOUND(8001, "TOKEN not found", HttpStatus.NOT_FOUND, "Token bulunamadı."),
	
	// MAIL (9000-9099)
	MAIL_QUEUE_ERROR(9001, "Mail could not be queued", HttpStatus.INTERNAL_SERVER_ERROR, "Mail kuyruğa alınamadı."),
	
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