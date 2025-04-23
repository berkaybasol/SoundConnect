package com.berkayb.soundconnect.modules.role.enums;

public enum PermissionEnum {
	// USER
	READ_USER, // sisteme kayit olan herkes bu role sahip olur.
	
	WRITE_USER,
	DELETE_USER,
	READ_ALL_USERS,
	
	// ROLE
	READ_ROLE,
	WRITE_ROLE,
	DELETE_ROLE,
	
	// PERMISSION işlemleri yetkisi
	READ_PERMISSION,
	WRITE_PERMISSION,
	DELETE_PERMISSION,
	
	// VENUE
	READ_VENUE, // mekan sahipleri icin
	WRITE_VENUE,
	DELETE_VENUE,
	ASSIGN_ARTIST_TO_VENUE, // mekan sahipleri icin
	
	// LOCATION
	READ_LOCATION,
	WRITE_LOCATION,
	DELETE_LOCATION
	
}