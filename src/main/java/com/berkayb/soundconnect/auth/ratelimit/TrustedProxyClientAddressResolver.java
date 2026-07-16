package com.berkayb.soundconnect.auth.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * Resolves a client address without trusting caller-controlled forwarding
 * headers. A forwarding chain is considered only when the direct peer belongs
 * to an explicitly configured proxy CIDR. The chain is then walked from right
 * to left and the first untrusted hop is used, matching common reverse-proxy
 * append semantics.
 */
public final class TrustedProxyClientAddressResolver {

	private static final int MAX_HEADER_LENGTH = 8_192;
	private static final int MAX_HOPS = 32;

	private final List<Cidr> trustedProxies;
	private final AuthRateLimitProperties.ForwardedHeader forwardedHeader;

	public TrustedProxyClientAddressResolver(List<String> trustedProxyCidrs) {
		this(trustedProxyCidrs, AuthRateLimitProperties.ForwardedHeader.FORWARDED);
	}

	public TrustedProxyClientAddressResolver(
			List<String> trustedProxyCidrs,
			AuthRateLimitProperties.ForwardedHeader forwardedHeader
	) {
		this.trustedProxies = trustedProxyCidrs == null
				? List.of()
				: trustedProxyCidrs.stream()
						.filter(value -> value != null && !value.isBlank())
						.map(String::trim)
						.map(Cidr::parse)
						.toList();
		this.forwardedHeader = forwardedHeader == null
				? AuthRateLimitProperties.ForwardedHeader.FORWARDED
				: forwardedHeader;
	}

	public String resolve(HttpServletRequest request) {
		Address remote = Address.parse(request.getRemoteAddr());
		if (remote == null) {
			return "unknown";
		}
		if (!isTrusted(remote)) {
			return remote.canonical();
		}

		List<Address> forwarded = forwardedChain(request);
		if (forwarded == null || forwarded.isEmpty()) {
			return remote.canonical();
		}

		List<Address> hops = new ArrayList<>(forwarded.size() + 1);
		hops.addAll(forwarded);
		hops.add(remote);
		int index = hops.size() - 1;
		while (index > 0 && isTrusted(hops.get(index))) {
			index--;
		}
		return hops.get(index).canonical();
	}

	private List<Address> forwardedChain(HttpServletRequest request) {
		String headerName = forwardedHeader == AuthRateLimitProperties.ForwardedHeader.FORWARDED
				? "Forwarded"
				: "X-Forwarded-For";
		String value = combinedHeaderValues(request, headerName);
		if (value == null || value.isBlank()) return List.of();
		return forwardedHeader == AuthRateLimitProperties.ForwardedHeader.FORWARDED
				? parseForwarded(value)
				: parseXForwardedFor(value);
	}

	private static String combinedHeaderValues(HttpServletRequest request, String headerName) {
		Enumeration<String> values = request.getHeaders(headerName);
		if (values != null) {
			List<String> allValues = Collections.list(values);
			if (!allValues.isEmpty()) return String.join(",", allValues);
		}
		// Mockito-based unit requests and a few servlet wrappers expose only the
		// singular accessor. Real servlet requests use the multi-value branch.
		return request.getHeader(headerName);
	}

	private List<Address> parseForwarded(String header) {
		if (header.length() > MAX_HEADER_LENGTH) return null;
		List<String> elements = splitOutsideQuotes(header, ',');
		if (elements == null || elements.isEmpty() || elements.size() > MAX_HOPS) return null;

		List<Address> result = new ArrayList<>(elements.size());
		for (String element : elements) {
			List<String> parameters = splitOutsideQuotes(element, ';');
			if (parameters == null) return null;
			String forValue = null;
			for (String parameter : parameters) {
				int equals = parameter.indexOf('=');
				if (equals <= 0) continue;
				if (!"for".equals(parameter.substring(0, equals).trim().toLowerCase(Locale.ROOT))) continue;
				if (forValue != null) return null;
				forValue = unquote(parameter.substring(equals + 1).trim());
			}
			if (forValue == null || forValue.equalsIgnoreCase("unknown") || forValue.startsWith("_")) {
				return null;
			}
			Address address = Address.parse(forValue);
			if (address == null) return null;
			result.add(address);
		}
		return result;
	}

	private List<Address> parseXForwardedFor(String header) {
		if (header.length() > MAX_HEADER_LENGTH) return null;
		String[] values = header.split(",", -1);
		if (values.length == 0 || values.length > MAX_HOPS) return null;
		List<Address> result = new ArrayList<>(values.length);
		for (String value : values) {
			Address address = Address.parse(value.trim());
			if (address == null) return null;
			result.add(address);
		}
		return result;
	}

	private boolean isTrusted(Address address) {
		return trustedProxies.stream().anyMatch(cidr -> cidr.contains(address.bytes()));
	}

	private static List<String> splitOutsideQuotes(String value, char delimiter) {
		List<String> parts = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean quoted = false;
		boolean escaped = false;
		for (int i = 0; i < value.length(); i++) {
			char character = value.charAt(i);
			if (escaped) {
				current.append(character);
				escaped = false;
				continue;
			}
			if (quoted && character == '\\') {
				current.append(character);
				escaped = true;
				continue;
			}
			if (character == '"') {
				quoted = !quoted;
				current.append(character);
				continue;
			}
			if (!quoted && character == delimiter) {
				if (current.toString().trim().isEmpty()) return null;
				parts.add(current.toString().trim());
				current.setLength(0);
			} else {
				current.append(character);
			}
		}
		if (quoted || escaped || current.toString().trim().isEmpty()) return null;
		parts.add(current.toString().trim());
		return parts;
	}

	private static String unquote(String value) {
		if (value.length() < 2 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') {
			return value;
		}
		StringBuilder result = new StringBuilder(value.length() - 2);
		boolean escaped = false;
		for (int i = 1; i < value.length() - 1; i++) {
			char character = value.charAt(i);
			if (escaped) {
				result.append(character);
				escaped = false;
			} else if (character == '\\') {
				escaped = true;
			} else {
				result.append(character);
			}
		}
		return escaped ? "" : result.toString();
	}

	private record Address(byte[] bytes, String canonical) {
		private static Address parse(String raw) {
			if (raw == null) return null;
			String value = raw.trim();
			if (value.isEmpty() || value.indexOf('%') >= 0) return null;

			if (value.startsWith("[")) {
				int closingBracket = value.indexOf(']');
				if (closingBracket <= 1) return null;
				String suffix = value.substring(closingBracket + 1);
				if (!suffix.isEmpty() && !isPort(suffix)) return null;
				value = value.substring(1, closingBracket);
			} else if (value.chars().filter(character -> character == ':').count() == 1
					&& value.indexOf('.') >= 0) {
				int colon = value.lastIndexOf(':');
				String suffix = value.substring(colon);
				if (isPort(suffix)) value = value.substring(0, colon);
			}

			if (!looksLikeIpLiteral(value)) return null;
			try {
				InetAddress address = InetAddress.getByName(value);
				if (value.indexOf(':') >= 0 && !(address instanceof Inet6Address)) return null;
				return new Address(address.getAddress(), address.getHostAddress());
			} catch (UnknownHostException exception) {
				return null;
			}
		}

		private static boolean isPort(String suffix) {
			if (suffix.length() < 2 || suffix.charAt(0) != ':') return false;
			try {
				int port = Integer.parseInt(suffix.substring(1));
				return port >= 0 && port <= 65_535;
			} catch (NumberFormatException exception) {
				return false;
			}
		}

		private static boolean looksLikeIpLiteral(String value) {
			if (value.indexOf(':') >= 0) {
				return value.matches("[0-9A-Fa-f:.]+");
			}
			String[] octets = value.split("\\.", -1);
			if (octets.length != 4) return false;
			for (String octet : octets) {
				if (octet.isEmpty() || octet.length() > 3
						|| !octet.chars().allMatch(character -> character >= '0' && character <= '9')) {
					return false;
				}
				if (Integer.parseInt(octet) > 255) return false;
			}
			return true;
		}
	}

	private record Cidr(byte[] network, int prefixLength) {
		private static Cidr parse(String value) {
			String[] parts = value.split("/", -1);
			if (parts.length > 2 || parts[0].isBlank()) {
				throw new IllegalArgumentException("Invalid trusted proxy CIDR");
			}
			Address address = Address.parse(parts[0]);
			if (address == null) throw new IllegalArgumentException("Invalid trusted proxy CIDR");
			int maxBits = address.bytes().length * 8;
			int prefix = parts.length == 1 ? maxBits : parsePrefix(parts[1], maxBits);
			byte[] network = Arrays.copyOf(address.bytes(), address.bytes().length);
			mask(network, prefix);
			return new Cidr(network, prefix);
		}

		private boolean contains(byte[] candidate) {
			if (candidate.length != network.length) return false;
			byte[] masked = Arrays.copyOf(candidate, candidate.length);
			mask(masked, prefixLength);
			return Arrays.equals(network, masked);
		}

		private static int parsePrefix(String value, int maxBits) {
			try {
				int prefix = Integer.parseInt(value);
				if (prefix < 0 || prefix > maxBits) throw new IllegalArgumentException("Invalid trusted proxy CIDR");
				return prefix;
			} catch (NumberFormatException exception) {
				throw new IllegalArgumentException("Invalid trusted proxy CIDR", exception);
			}
		}

		private static void mask(byte[] bytes, int prefixLength) {
			int fullBytes = prefixLength / 8;
			int remainingBits = prefixLength % 8;
			if (remainingBits != 0 && fullBytes < bytes.length) {
				bytes[fullBytes] &= (byte) (0xFF << (8 - remainingBits));
				fullBytes++;
			}
			Arrays.fill(bytes, fullBytes, bytes.length, (byte) 0);
		}
	}
}
