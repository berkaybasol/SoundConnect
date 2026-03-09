package com.berkayb.soundconnect.modules.spotify.service;

public interface SpotifyTokenService {
	// Spotify Api cagrilarinda kullanilacak gecerli acces token'i doner. token yoksa veya suresi bittiyse otomatik yeniler
	String getAccessToken();
}