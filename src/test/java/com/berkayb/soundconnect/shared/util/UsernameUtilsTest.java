package com.berkayb.soundconnect.shared.util;

import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsernameUtilsTest {

	@Test
	void stripsOnlyTheExplicitBoundaryWhitespaceSet() {
		assertThat(UsernameUtils.normalize(
				"\t\u00A0\u2003\uFEFFBeRKay\uFEFF\u2003\u00A0\r"
		)).isEqualTo("berkay");

		assertThat(UsernameUtils.normalize("A\u00A0B\u2003C\uFEFFD"))
				.isEqualTo("a\u00A0b\u2003c\uFEFFd");
	}

	@Test
	void usesSimplePerCodePointLowercaseCompatibleWithDart() {
		assertThat(UsernameUtils.normalize("\u0130USER")).isEqualTo("iuser");
		assertThat(UsernameUtils.normalize("\u039F\u03A3")).isEqualTo("\u03BF\u03C3");
		assertThat(UsernameUtils.normalize("\u03A3\u039F\u03A3")).isEqualTo("\u03C3\u03BF\u03C3");
		assertThat(UsernameUtils.normalize("\u1E9E")).isEqualTo("\u00DF");
		assertThat(UsernameUtils.normalize("\uD801\uDC00")).isEqualTo("\uD801\uDC28");
	}

	@Test
	void canonicalLengthUsesJavaUtf16CodeUnitsAtAstralBoundaries() {
		String twoEmoji = "\uD83D\uDE00\uD83D\uDE00";
		String fifteenEmoji = twoEmoji.repeat(7) + "\uD83D\uDE00";
		String sixteenEmoji = twoEmoji.repeat(8);

		assertThat(UsernameUtils.normalizeAndValidate(twoEmoji)).isEqualTo(twoEmoji);
		assertThat(UsernameUtils.normalizeAndValidate(fifteenEmoji)).isEqualTo(fifteenEmoji);
		assertThatThrownBy(() -> UsernameUtils.normalizeAndValidate("\uD83D\uDE00"))
				.isInstanceOf(SoundConnectException.class);
		assertThatThrownBy(() -> UsernameUtils.normalizeAndValidate(sixteenEmoji))
				.isInstanceOf(SoundConnectException.class);
	}
}
