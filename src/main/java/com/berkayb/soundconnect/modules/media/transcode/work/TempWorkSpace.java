package com.berkayb.soundconnect.modules.media.transcode.work;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * her transcode isi icin guvenli bir gecici calisma klasoru olusturur ve is bitince temizler
 * try-with resources ile AutoCloseable kullan: try(TempWorkspace ws = TempWorkspace.create(assetId)) { ... }
 */
@Slf4j
public final class TempWorkSpace implements AutoCloseable {
	
	@Getter
	private final Path dir; // isin kok klasoru
	private boolean keepOnClose = false;
	
	private TempWorkSpace(Path dir) {
		this.dir = dir;
	}
	
	// varsayilan sistem temp alaninda assetId ile isimlendirilmis bir klasor acar
	public static TempWorkSpace create(UUID assetId) throws IOException {
		String prefix = "sc-" +(assetId != null ? assetId : UUID.randomUUID()) + "-";
		Path dir = Files.createTempDirectory(prefix);
		return new TempWorkSpace(dir);
	}
	
	// kok altinda aldizinler olusturu dondurur
	public Path mkdirs(String... parts) throws IOException {
		Path p = resolve(parts);
		return Files.createDirectories(p);
	}
	
	// kokten itibaren path cozer (olusturmaz)
	public Path resolve(String... parts) {
		Path p = dir;
		if (parts != null) {
			for (String part : parts) {
				if (part != null && !part.isBlank()) {
					p = p.resolve(part);
				}
			}
		}
		return p.normalize();
	}
	
	// disaridan gelen bir dosyayi (orn indirilen kaynak) workspace altina tasir/kopyalar
	public Path moveInto(Path src, String... targetParts) throws IOException {
		Path target = resolve(targetParts);
		Files.createDirectories(target.getParent());
		return Files.move(src, target, StandardCopyOption.REPLACE_EXISTING);
	}
	
	// Byttte dizisini workspace altinda yazar (kucuk playlist dosyalari icin pratik)
	public Path writeBytes(byte[] data, String... targetParts) throws IOException {
		Path target = resolve(targetParts);
		Files.createDirectories(target.getParent());
		return Files.write(target, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
	}
	
	// debug icin close cagrildiginda klasoru tutar (silmez)
	public TempWorkSpace keep() {
		this.keepOnClose = true;
		return this;
	}
	
	@Override
	public void close() throws Exception {
		if (keepOnClose) {
			log.info("[workspace] keep enabled; dir={}", dir);
			return;
	}
		deleteRecursively(dir);
}
	/** Güvenli recursive silme (fail-safe; kalan dosyalar loglanır). */
	public static void deleteRecursively(Path root) {
		if (root == null || !Files.exists(root)) return;
		try (Stream<Path> walk = Files.walk(root)) {
			walk.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException e) {
					log.warn("[workspace] delete failed path={} err={}", path, e.toString());
				}
			});
		} catch (IOException e) {
			log.warn("[workspace] walk failed root={} err={}", root, e.toString());
		}
	}
}