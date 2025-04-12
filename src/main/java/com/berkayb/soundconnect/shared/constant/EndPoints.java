package com.berkayb.soundconnect.shared.constant;

public class EndPoints {
	public static final String API = "/api";
	public static final String VERSION = "/v1";
	public static final String USERS = API + VERSION + "/users";
	public static final String INSTRUMENTS = API + VERSION + "/users";
	public static final String FOLLOW = API + VERSION + "/follows";
	public static final String GET_ALL_USERS = "/get-all-users";
	public static final String GET_USER_BY_ID = "/{id}";
	public static final String SAVE_USER = "/save";
	public static final String UPDATE_USER = "/update/{id}";
	public static final String DELETE_USER = "/delete/{id}";
	
	public static final String SAVE_INSTRUMENT = "/save-instrument";
	public static final String GET_ALL_INSTRUMENTS = "/get-all-instruments";
}