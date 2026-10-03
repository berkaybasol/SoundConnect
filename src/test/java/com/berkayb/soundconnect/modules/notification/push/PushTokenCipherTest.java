package com.berkayb.soundconnect.modules.notification.push;

import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;

class PushTokenCipherTest {
    @Test void requiresAReal256BitKeyAndAuthenticatedCiphertext() {
        var properties=new PushProperties();
        assertThatThrownBy(()->new PushTokenCipher(properties)).isInstanceOf(IllegalStateException.class);
        properties.setTokenEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        var cipher=new PushTokenCipher(properties);
        String first=cipher.encrypt("token"); String second=cipher.encrypt("token");
        assertThat(first).isNotEqualTo(second); assertThat(cipher.decrypt(first)).isEqualTo("token");
        assertThatThrownBy(()->cipher.decrypt(first.substring(0,first.length()-4)+"AAAA")).isInstanceOf(IllegalStateException.class);
        assertThat(PushTokenCipher.hash("token")).hasSize(64);
    }
    @Test void rejectsLeaseTooShortForProviderDeadlineAndUnsafeTtl() {
        var properties=new PushProperties(); assertThat(properties.isTimingSafe()).isTrue();
        properties.setLeaseDuration(java.time.Duration.ofSeconds(5)); assertThat(properties.isTimingSafe()).isFalse();
        properties.setLeaseDuration(java.time.Duration.ofMinutes(2)); properties.setMessageTtl(java.time.Duration.ofDays(29));
        assertThat(properties.isTimingSafe()).isFalse();
    }
}
