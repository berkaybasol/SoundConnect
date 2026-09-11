package com.berkayb.soundconnect.tools.simulation.seed.engagement;

import com.berkayb.soundconnect.tools.simulation.seed.content.social.SimulationEngagementTarget;
import com.berkayb.soundconnect.tools.simulation.seed.engagement.SimulationEngagementPlan.CommentAction;
import com.berkayb.soundconnect.tools.simulation.seed.engagement.SimulationEngagementPlan.LikeAction;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.Account;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.AccountRole;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.EmailVerificationState;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.InstitutionState;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.ListenerVisibilityState;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.ObserverProfile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Pure planner: observers and lifecycle controls never perform autonomous actions. */
@Component
@Profile("local & simulation")
@ConditionalOnProperty(prefix = "app.simulation", name = "enabled", havingValue = "true")
public final class SimulationEngagementPlanner {

	private static final int MAX_LIKES = 2_000;
	private static final int MAX_COMMENTS = 1_000;
	private static final List<String> COMMENT_TEXTS = List.of(
			"Bu detay benim de dikkatimi çekti; özellikle canlı dinleyince daha iyi anlaşılıyor.",
			"Programı kaydettim, denk gelirsek selamlaşalım.",
			"Şehrin bu tarafında böyle işlerin çoğalması çok iyi geliyor.",
			"Düzenlemedeki boşluklar parçanın nefes almasını sağlamış.",
			"Bu ekibi ilk kez dinleyeceğim, öneri için teşekkürler.",
			"Aynı hissi geçen haftaki konserde ben de yaşadım.",
			"Kayıttaki oda karakteri gerçekten sıcak duyuluyor.",
			"Bu buluşmanın sohbeti şimdiden güzel olacak gibi.",
			"Yeni isimler keşfetmek için çok iyi bir seçki olmuş.",
			"Ritim tarafındaki küçük değişim bütün parçayı taşımış.",
			"Takvime ekledim; sahne akışını merak ediyorum.",
			"Böyle yerel öneriler akışta karşıma çıkınca çok seviniyorum.",
			"İkinci dinleyişte bambaşka ayrıntılar duydum.",
			"Bu fikrin devamını okumak isterim, güzel bir yerden açılmış.",
			"Mekân ile performansın uyumu burada asıl farkı yaratabilir.",
			"Uzun zamandır buna benzer bir etkinlik arıyordum.",
			"Ton seçimi çok yerinde; parçanın duygusunu boğmamış.",
			"Masaya katılanların önerilerini sonradan bir listede toplamak harika olur.",
			"Sahne enerjisini kayda taşıyabilen işleri ayrıca seviyorum.",
			"Bu paylaşım sayesinde bugün dinleyecek yeni bir şey buldum."
	);

	public SimulationEngagementPlan plan(
			SimulationWorldManifest manifest,
			Map<String, UUID> accountUserIds,
			List<SimulationEngagementTarget> inputTargets
	) {
		Objects.requireNonNull(manifest, "manifest");
		Objects.requireNonNull(manifest.contentTargets(), "manifest contentTargets");
		return plan(manifest, accountUserIds, inputTargets,
				manifest.contentTargets().likes(), manifest.contentTargets().comments());
	}

	public SimulationEngagementPlan plan(
			SimulationWorldManifest manifest,
			Map<String, UUID> accountUserIds,
			List<SimulationEngagementTarget> inputTargets,
			int requestedLikes,
			int requestedComments
	) {
		Objects.requireNonNull(manifest, "manifest");
		Objects.requireNonNull(accountUserIds, "accountUserIds");
		Objects.requireNonNull(inputTargets, "inputTargets");
		List<Actor> actors = actors(manifest, accountUserIds);
		List<SimulationEngagementTarget> targets = targets(inputTargets);
		if (actors.isEmpty() || targets.isEmpty()) {
			throw new IllegalStateException("Engagement planning requires eligible actors and public targets");
		}
		int likeCount = bounded(requestedLikes, MAX_LIKES, "likes");
		int commentCount = bounded(requestedComments, MAX_COMMENTS, "comments");
		List<Pair> likePairs = pairs(manifest.seed(), "like", actors, targets, likeCount);
		List<Pair> commentPairs = pairs(manifest.seed(), "comment", actors, targets, commentCount);

		List<LikeAction> likes = new ArrayList<>(likePairs.size());
		for (int index = 0; index < likePairs.size(); index++) {
			Pair pair = likePairs.get(index);
			likes.add(new LikeAction(numbered("like", index), pair.actor().key(),
					pair.actor().userId(), pair.target()));
		}
		List<CommentAction> comments = new ArrayList<>(commentPairs.size());
		for (int index = 0; index < commentPairs.size(); index++) {
			Pair pair = commentPairs.get(index);
			String text = COMMENT_TEXTS.get(Math.floorMod(
					stableInt(manifest.seed(), "text", pair.actor().key(), pair.target().logicalKey()),
					COMMENT_TEXTS.size()));
			comments.add(new CommentAction(numbered("comment", index), pair.actor().key(),
					pair.actor().userId(), pair.target(), text));
		}
		return new SimulationEngagementPlan(likes, comments);
	}

	private static List<Pair> pairs(
			long seed,
			String action,
			List<Actor> actors,
			List<SimulationEngagementTarget> targets,
			int count
	) {
		long capacity = targets.stream()
				.mapToLong(target -> actors.stream()
						.filter(actor -> !actor.userId().equals(target.ownerUserId())).count())
				.sum();
		if (count > capacity) {
			throw new IllegalStateException("Engagement target " + action + " exceeds unique actor-target capacity");
		}
		Map<String, List<Actor>> rankings = new LinkedHashMap<>();
		for (SimulationEngagementTarget target : targets) {
			List<Actor> ranked = actors.stream()
					.filter(actor -> !actor.userId().equals(target.ownerUserId()))
					.sorted(Comparator
							.comparing((Actor actor) -> actor.scene() != target.scene())
							.thenComparing(actor -> digestHex(seed, action,
									target.logicalKey(), actor.key())))
					.toList();
			if (ranked.isEmpty()) throw new IllegalStateException("Target has no external engagement actor");
			rankings.put(target.logicalKey(), ranked);
		}

		List<Pair> result = new ArrayList<>(count);
		Set<String> seen = new HashSet<>();
		int cursor = 0;
		while (result.size() < count) {
			SimulationEngagementTarget target = targets.get(cursor % targets.size());
			List<Actor> ranked = rankings.get(target.logicalKey());
			int round = cursor / targets.size();
			Actor actor = ranked.get(round % ranked.size());
			String identity = actor.key() + "|" + target.targetType() + "|" + target.targetId();
			if (seen.add(identity)) result.add(new Pair(actor, target));
			cursor++;
			if (cursor > capacity * 2L + targets.size()) {
				throw new IllegalStateException("Unable to construct unique " + action + " plan");
			}
		}
		return List.copyOf(result);
	}

	private static List<Actor> actors(
			SimulationWorldManifest manifest,
			Map<String, UUID> accountUserIds
	) {
		return manifest.accounts().stream()
				.filter(SimulationEngagementPlanner::autonomous)
				.map(account -> new Actor(account.key(), requireId(accountUserIds, account.key()), account.scene()))
				.sorted(Comparator.comparing(Actor::key))
				.toList();
	}

	private static boolean autonomous(Account account) {
		if (account.emailVerification() != EmailVerificationState.VERIFIED
				|| account.observer() != ObserverProfile.NONE) return false;
		return switch (account.role()) {
			case MUSICIAN -> account.musicianProfile() != null;
			case LISTENER -> account.listenerVisibility() == ListenerVisibilityState.STANDARD;
			case VENUE, STUDIO -> account.institutionState() == InstitutionState.APPROVED;
		};
	}

	private static List<SimulationEngagementTarget> targets(List<SimulationEngagementTarget> values) {
		Map<String, SimulationEngagementTarget> byKey = new LinkedHashMap<>();
		Set<String> identities = new HashSet<>();
		for (SimulationEngagementTarget target : values) {
			if (target == null) throw new IllegalArgumentException("Engagement target cannot be null");
			if (byKey.putIfAbsent(target.logicalKey(), target) != null) {
				throw new IllegalArgumentException("Duplicate engagement logical key: " + target.logicalKey());
			}
			String identity = target.targetType() + "|" + target.targetId();
			if (!identities.add(identity)) {
				throw new IllegalArgumentException("Duplicate engagement target identity: " + identity);
			}
		}
		return byKey.values().stream().sorted(Comparator.comparing(SimulationEngagementTarget::logicalKey)).toList();
	}

	private static int bounded(int value, int max, String field) {
		if (value < 0 || value > max) throw new IllegalStateException(field + " is outside the simulation bound");
		return value;
	}

	private static UUID requireId(Map<String, UUID> ids, String key) {
		UUID value = ids.get(key);
		if (value == null) throw new IllegalStateException("No registered account for manifest key: " + key);
		return value;
	}

	private static int stableInt(long seed, String... values) {
		byte[] digest = digest(seed, values);
		return ((digest[0] & 0xff) << 24) | ((digest[1] & 0xff) << 16)
				| ((digest[2] & 0xff) << 8) | (digest[3] & 0xff);
	}

	private static String digestHex(long seed, String... values) {
		return java.util.HexFormat.of().formatHex(digest(seed, values));
	}

	private static byte[] digest(long seed, String... values) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(Long.toString(seed).getBytes(StandardCharsets.UTF_8));
			for (String value : values) {
				digest.update((byte) 0);
				digest.update(value.getBytes(StandardCharsets.UTF_8));
			}
			return digest.digest();
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is unavailable", impossible);
		}
	}

	private static String numbered(String prefix, int index) {
		return prefix + "-" + String.format(java.util.Locale.ROOT, "%03d", index + 1);
	}

	private record Actor(String key, UUID userId, SimulationWorldManifest.Scene scene) {
	}

	private record Pair(Actor actor, SimulationEngagementTarget target) {
	}
}
