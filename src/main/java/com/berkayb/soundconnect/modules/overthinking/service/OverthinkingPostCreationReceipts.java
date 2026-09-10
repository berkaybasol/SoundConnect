package com.berkayb.soundconnect.modules.overthinking.service;

import com.berkayb.soundconnect.modules.overthinking.dto.request.OverthinkingPostSaveRequestDto;
import com.berkayb.soundconnect.shared.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** The actor eligibility lock plus a transaction-scoped operation lock serialize retries across nodes. */
@Service @RequiredArgsConstructor @Transactional(propagation = Propagation.MANDATORY)
public class OverthinkingPostCreationReceipts {
    private final JdbcTemplate jdbc;

    public Optional<UUID> findMatching(UUID owner, OverthinkingPostSaveRequestDto command) {
        UUID key = command.clientRequestId();
        if (key == null) return Optional.empty(); // Backward compatible older client; new clients always supply a key.
        if (key.equals(new UUID(0, 0))) throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
        // Actor eligibility uses FOR SHARE: use a separate operation lock, never upgrade that shared row lock.
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))", Object.class,
                "overthinking:create:" + owner + ":" + key);
        var rows = jdbc.query("""
                select request_hash, post_id from tbl_overthinking_create_receipt
                where owner_user_id=? and client_request_id=?
                """, (row, index) -> new Receipt(row.getString(1), row.getObject(2, UUID.class)), owner, key);
        if (rows.isEmpty()) return Optional.empty();
        Receipt receipt = rows.getFirst();
        if (!receipt.hash().equals(fingerprint(command)))
            throw new SoundConnectException(ErrorType.OVERTHINKING_CREATE_KEY_CONFLICT);
        return Optional.of(receipt.postId());
    }

    public void record(UUID owner, OverthinkingPostSaveRequestDto command, UUID postId) {
        if (command.clientRequestId() == null) return;
        jdbc.update("""
                insert into tbl_overthinking_create_receipt(owner_user_id,client_request_id,request_hash,post_id,created_at)
                values (?,?,?,?,clock_timestamp())
                """, owner, command.clientRequestId(), fingerprint(command), postId);
    }

    static String fingerprint(OverthinkingPostSaveRequestDto command) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // Length framing distinguishes null/empty and embedded separators; UTF-16 preserves exact Java payloads.
            for (Object field : Arrays.asList(command.title(), command.content(), command.visibilityType(),
                    command.spotifyTrackUrl(), command.spotifyArtistId(), command.spotifyTrackName(), command.spotifyArtistName(),
                    command.spotifyAlbumImageUrl(), command.musicianTrackId(), command.bandTrackId())) {
                byte[] bytes = field == null ? null : field.toString().getBytes(StandardCharsets.UTF_16BE);
                digest.update(ByteBuffer.allocate(4).putInt(bytes == null ? -1 : bytes.length).array());
                if (bytes != null) digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private record Receipt(String hash, UUID postId) { }
}
