package com.berkayb.soundconnect.constant;

public class EndPoints {
	public static final String API = "/api";
	public static final String VERSION = "/v1";
	public static final String USERS = API + VERSION + "/users";
	public static final String GET_ALL_USERS = "/get-all";
	public static final String GET_USER_BY_ID = "/{id}";
	public static final String SAVE_USER = "/save";
	public static final String UPDATE_USER = "/update/{id}";
	public static final String DELETE_USER = "/delete/{id}";
}