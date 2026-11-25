package com.berkayb.soundconnect.modules.collab.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CollabAccountLifecycle {
    private final JdbcTemplate jdbc;

    /** Run in the identity erasure transaction, before profile or identity cleanup. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void purgeAccount(UUID userId) {
        jdbc.queryForObject("select soundconnect_purge_collab_account(?)", Object.class, userId);
    }
}
