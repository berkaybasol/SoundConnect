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
		public static final String DELETE = "/delete-role/{id}";
	}
	
	public static class Auth {
		public static final String BASE = API + VERSION + "/auth";
		public static final String REGISTER = "/register";
		public static final String LOGIN = "/login";
	}
}