package com.berkayb.soundconnect.modules.location.support;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.Locale;
import java.util.function.Function;

public final class TurkishAlphabeticalOrder {

	private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");
	private static final String TURKISH_ALPHABET = "abcçdefgğhıijklmnoöprsştuüvyz";

	private TurkishAlphabeticalOrder() {}

	public static Comparator<String> textComparator() {
		return TurkishAlphabeticalOrder::compare;
	}

	public static <T> Comparator<T> comparing(Function<? super T, String> nameExtractor) {
		return (left, right) -> compare(nameExtractor.apply(left), nameExtractor.apply(right));
	}

	private static int compare(String left, String right) {
		String normalizedLeft = normalize(left);
		String normalizedRight = normalize(right);
		int leftIndex = 0;
		int rightIndex = 0;
		while (leftIndex < normalizedLeft.length() && rightIndex < normalizedRight.length()) {
			boolean leftIsDigit = isAsciiDigit(normalizedLeft.charAt(leftIndex));
			boolean rightIsDigit = isAsciiDigit(normalizedRight.charAt(rightIndex));
			if (leftIsDigit && rightIsDigit) {
				int leftEnd = digitRunEnd(normalizedLeft, leftIndex);
				int rightEnd = digitRunEnd(normalizedRight, rightIndex);
				int numericComparison = compareDigitRuns(
						normalizedLeft,
						leftIndex,
						leftEnd,
						normalizedRight,
						rightIndex,
						rightEnd
				);
				if (numericComparison != 0) {
					return numericComparison;
				}
				leftIndex = leftEnd;
				rightIndex = rightEnd;
				continue;
			}
			if (leftIsDigit != rightIsDigit) {
				return leftIsDigit ? -1 : 1;
			}

			int weightComparison = Integer.compare(
					turkishWeight(normalizedLeft.charAt(leftIndex)),
					turkishWeight(normalizedRight.charAt(rightIndex))
			);
			if (weightComparison != 0) {
				return weightComparison;
			}
			leftIndex++;
			rightIndex++;
		}

		int lengthComparison = Integer.compare(normalizedLeft.length(), normalizedRight.length());
		if (lengthComparison != 0) {
			return lengthComparison;
		}
		return left.compareTo(right);
	}

	private static String normalize(String value) {
		return Normalizer.normalize(value, Normalizer.Form.NFC)
				.toLowerCase(TURKISH)
				.replace('â', 'a')
				.replace('î', 'i')
				.replace('û', 'u');
	}

	private static int turkishWeight(char value) {
		int alphabetIndex = TURKISH_ALPHABET.indexOf(value);
		if (alphabetIndex >= 0) {
			return 1000 + alphabetIndex;
		}
		if (value == ' ') {
			return 0;
		}
		return 100 + value;
	}

	private static int compareDigitRuns(
			String left,
			int leftStart,
			int leftEnd,
			String right,
			int rightStart,
			int rightEnd
	) {
		int leftSignificantStart = skipLeadingZeros(left, leftStart, leftEnd);
		int rightSignificantStart = skipLeadingZeros(right, rightStart, rightEnd);
		int leftSignificantLength = leftEnd - leftSignificantStart;
		int rightSignificantLength = rightEnd - rightSignificantStart;
		int lengthComparison = Integer.compare(leftSignificantLength, rightSignificantLength);
		if (lengthComparison != 0) {
			return lengthComparison;
		}

		for (int index = 0; index < leftSignificantLength; index++) {
			int digitComparison = Character.compare(
					left.charAt(leftSignificantStart + index),
					right.charAt(rightSignificantStart + index)
			);
			if (digitComparison != 0) {
				return digitComparison;
			}
		}

		return Integer.compare(leftEnd - leftStart, rightEnd - rightStart);
	}

	private static int skipLeadingZeros(String value, int start, int end) {
		int index = start;
		while (index < end - 1 && value.charAt(index) == '0') {
			index++;
		}
		return index;
	}

	private static int digitRunEnd(String value, int start) {
		int index = start;
		while (index < value.length() && isAsciiDigit(value.charAt(index))) {
			index++;
		}
		return index;
	}

	private static boolean isAsciiDigit(char value) {
		return value >= '0' && value <= '9';
	}
}
