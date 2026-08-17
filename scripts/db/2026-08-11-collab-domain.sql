-- SoundConnect Collab v2 domain rollout.
--
-- Run with psql ON_ERROR_STOP=1. The whole migration is transactional and
-- intentionally rerunnable. The v1 listing and its dependent slot/target-role
-- rows are retained under a dated legacy table; no legacy business data is
-- transformed or deleted by this rollout.

BEGIN;

-- ---------------------------------------------------------------------------
-- Preserve the v1 listing table without breaking its existing foreign keys.
-- PostgreSQL foreign keys reference the table object (OID), so renaming the
-- table automatically keeps collab_target_roles and tbl_collab_required_slot
-- attached to the preserved legacy rows.
-- ---------------------------------------------------------------------------

DO $collab_legacy_rollout$
DECLARE
    source_is_legacy boolean;
BEGIN
    IF to_regclass(format('%I.%I', current_schema(), 'tbl_collab')) IS NULL THEN
        RETURN;
    END IF;

    SELECT count(*) = 2
      INTO source_is_legacy
      FROM information_schema.columns
     WHERE table_schema = current_schema()
       AND table_name = 'tbl_collab'
       AND column_name IN ('daily', 'owner_role');

    IF NOT source_is_legacy THEN
        RETURN;
    END IF;

    IF to_regclass(format('%I.%I', current_schema(), 'tbl_collab_legacy_110826')) IS NOT NULL THEN
        RAISE EXCEPTION
            'both legacy-shaped tbl_collab and tbl_collab_legacy_110826 exist; refusing an ambiguous rollout'
            USING ERRCODE = '55000';
    END IF;

    EXECUTE format(
        'ALTER TABLE %I.%I RENAME TO %I',
        current_schema(),
        'tbl_collab',
        'tbl_collab_legacy_110826'
    );
END
$collab_legacy_rollout$;

-- ---------------------------------------------------------------------------
-- Polymorphic Backstage actor projection
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS tbl_collab_actor (
    id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    profile_type varchar(24) NOT NULL,
    source_profile_id uuid NOT NULL,
    display_name varchar(120) NOT NULL,
    avatar_url varchar(1024),
    rating_sum bigint NOT NULL DEFAULT 0,
    review_count bigint NOT NULL DEFAULT 0,
    completed_job_count bigint NOT NULL DEFAULT 0,
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT pk_collab_actor PRIMARY KEY (id),
    CONSTRAINT uk_collab_actor_profile UNIQUE (profile_type, source_profile_id),
    CONSTRAINT ck_collab_actor_profile_type CHECK (
        profile_type IN ('MUSICIAN', 'BAND', 'VENUE', 'STUDIO')
    ),
    CONSTRAINT ck_collab_actor_display_name CHECK (
        char_length(btrim(display_name)) BETWEEN 1 AND 120
    ),
    CONSTRAINT ck_collab_actor_avatar_url CHECK (
        avatar_url IS NULL OR char_length(btrim(avatar_url)) BETWEEN 1 AND 1024
    ),
    CONSTRAINT ck_collab_actor_rating_totals CHECK (
        rating_sum >= 0
        AND review_count >= 0
        AND (
            (review_count = 0 AND rating_sum = 0)
            OR (review_count > 0 AND rating_sum BETWEEN review_count AND review_count * 5)
        )
    ),
    CONSTRAINT ck_collab_actor_completed_jobs CHECK (completed_job_count >= 0),
    CONSTRAINT ck_collab_actor_version CHECK (version >= 0)
);

CREATE INDEX IF NOT EXISTS idx_collab_actor_active_type
    ON tbl_collab_actor (active, profile_type, id);

-- ---------------------------------------------------------------------------
-- Listing and its ordered, maximum-three genre collection
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS tbl_collab (
    id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    owner_user_id uuid NOT NULL,
    publisher_actor_id uuid NOT NULL,
    client_request_id uuid NOT NULL,
    creation_payload_hash varchar(64) NOT NULL,
    cadence varchar(16) NOT NULL,
    wanted_type varchar(16) NOT NULL,
    instrument_id uuid,
    branch varchar(32),
    custom_specialty varchar(80),
    title varchar(100) NOT NULL,
    description varchar(500) NOT NULL,
    city_id uuid NOT NULL,
    scheduled_at timestamp with time zone,
    expires_at timestamp with time zone,
    fee_amount_minor bigint,
    currency varchar(3),
    status varchar(16) NOT NULL DEFAULT 'DRAFT',
    closure_reason varchar(24),
    published_at timestamp with time zone,
    closed_at timestamp with time zone,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT pk_collab PRIMARY KEY (id),
    CONSTRAINT uk_collab_create_request UNIQUE (owner_user_id, client_request_id),
    CONSTRAINT uk_collab_job_publisher_reference
        UNIQUE (id, publisher_actor_id, owner_user_id),
    CONSTRAINT fk_collab_owner
        FOREIGN KEY (owner_user_id) REFERENCES tbl_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_publisher_actor
        FOREIGN KEY (publisher_actor_id) REFERENCES tbl_collab_actor (id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_instrument
        FOREIGN KEY (instrument_id) REFERENCES tbl_instrument (id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_city
        FOREIGN KEY (city_id) REFERENCES tbl_city (id) ON DELETE RESTRICT,
    CONSTRAINT ck_collab_creation_payload_hash CHECK (
        creation_payload_hash ~ '^[0-9A-Fa-f]{64}$'
    ),
    CONSTRAINT ck_collab_cadence CHECK (cadence IN ('REGULAR', 'EXTRA')),
    CONSTRAINT ck_collab_wanted_type CHECK (
        wanted_type IN ('MUSICIAN', 'BAND', 'VENUE', 'STUDIO')
    ),
    CONSTRAINT ck_collab_branch CHECK (
        branch IS NULL OR branch IN ('VOCAL', 'SOUND_ENGINEER', 'PRODUCER', 'DJ', 'OTHER')
    ),
    CONSTRAINT ck_collab_specialty_shape CHECK (
        (
            wanted_type = 'MUSICIAN'
            AND (
                (instrument_id IS NOT NULL AND branch IS NULL AND custom_specialty IS NULL)
                OR
                (
                    instrument_id IS NULL
                    AND branch IS NOT NULL
                    AND (
                        (branch = 'OTHER'
                            AND custom_specialty IS NOT NULL
                            AND char_length(btrim(custom_specialty)) BETWEEN 1 AND 80)
                        OR (branch <> 'OTHER' AND custom_specialty IS NULL)
                    )
                )
            )
        )
        OR
        (
            wanted_type <> 'MUSICIAN'
            AND instrument_id IS NULL
            AND branch IS NULL
            AND custom_specialty IS NULL
        )
    ),
    CONSTRAINT ck_collab_title CHECK (
        char_length(btrim(title)) BETWEEN 5 AND 100
    ),
    CONSTRAINT ck_collab_description CHECK (
        char_length(btrim(description)) BETWEEN 20 AND 500
    ),
    CONSTRAINT ck_collab_schedule_shape CHECK (
        (cadence = 'REGULAR' AND scheduled_at IS NULL AND expires_at IS NULL)
        OR
        (cadence = 'EXTRA'
            AND scheduled_at IS NOT NULL
            AND expires_at IS NOT NULL
            AND expires_at = scheduled_at)
    ),
    CONSTRAINT ck_collab_extra_publication_window CHECK (
        cadence <> 'EXTRA'
        OR published_at IS NULL
        OR (
            scheduled_at >= published_at
            AND scheduled_at <= published_at + INTERVAL '7 days'
        )
    ),
    CONSTRAINT ck_collab_fee_pair CHECK (
        (fee_amount_minor IS NULL AND currency IS NULL)
        OR
        (fee_amount_minor IS NOT NULL
            AND fee_amount_minor BETWEEN 1 AND 100000000
            AND currency IS NOT NULL
            AND currency ~ '^[A-Z]{3}$')
    ),
    CONSTRAINT ck_collab_status CHECK (
        status IN ('DRAFT', 'OPEN', 'CLOSED', 'EXPIRED')
    ),
    CONSTRAINT ck_collab_closure_reason CHECK (
        closure_reason IS NULL
        OR closure_reason IN ('MATCHED', 'OWNER_CLOSED', 'EXPIRED', 'ADMIN_REMOVED')
    ),
    CONSTRAINT ck_collab_lifecycle CHECK (
        (
            status = 'DRAFT'
            AND published_at IS NULL
            AND closed_at IS NULL
            AND closure_reason IS NULL
        )
        OR
        (
            status = 'OPEN'
            AND published_at IS NOT NULL
            AND closed_at IS NULL
            AND closure_reason IS NULL
        )
        OR
        (
            status = 'CLOSED'
            AND published_at IS NOT NULL
            AND closed_at IS NOT NULL
            AND closed_at >= published_at
            AND closure_reason IS NOT NULL
            AND closure_reason IN ('MATCHED', 'OWNER_CLOSED', 'ADMIN_REMOVED')
        )
        OR
        (
            status = 'EXPIRED'
            AND cadence = 'EXTRA'
            AND published_at IS NOT NULL
            AND closed_at IS NOT NULL
            AND closed_at >= published_at
            AND closure_reason IS NOT NULL
            AND closure_reason = 'EXPIRED'
        )
    ),
    CONSTRAINT ck_collab_version CHECK (version >= 0)
);

CREATE TABLE IF NOT EXISTS tbl_collab_genre (
    collab_id uuid NOT NULL,
    position integer NOT NULL,
    genre varchar(40) NOT NULL,
    CONSTRAINT pk_collab_genre PRIMARY KEY (collab_id, position),
    CONSTRAINT uk_collab_genre_value UNIQUE (collab_id, genre),
    CONSTRAINT fk_collab_genre_listing
        FOREIGN KEY (collab_id) REFERENCES tbl_collab (id) ON DELETE CASCADE,
    CONSTRAINT ck_collab_genre_position CHECK (position BETWEEN 0 AND 2),
    CONSTRAINT ck_collab_genre_value CHECK (
        char_length(btrim(genre)) BETWEEN 1 AND 40
    )
);

CREATE INDEX IF NOT EXISTS idx_collab_discovery
    ON tbl_collab (status, cadence, published_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_collab_city_discovery
    ON tbl_collab (city_id, status, cadence, published_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_collab_wanted_instrument
    ON tbl_collab (wanted_type, instrument_id, status, published_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_collab_wanted_branch
    ON tbl_collab (wanted_type, branch, status, published_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_collab_publisher
    ON tbl_collab (publisher_actor_id, status, published_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_collab_owner
    ON tbl_collab (owner_user_id, status, updated_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_collab_expiry
    ON tbl_collab (status, expires_at, id)
    WHERE expires_at IS NOT NULL;

-- Composite reference keys let the job table prove that its denormalized
-- parties are snapshots of the same listing/application, rather than merely
-- four individually valid foreign keys.
DO $collab_job_publisher_reference$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = 'tbl_collab'::regclass
           AND conname = 'uk_collab_job_publisher_reference'
    ) THEN
        ALTER TABLE tbl_collab
            ADD CONSTRAINT uk_collab_job_publisher_reference
            UNIQUE (id, publisher_actor_id, owner_user_id);
    END IF;
END
$collab_job_publisher_reference$;

-- ---------------------------------------------------------------------------
-- Applications. Phone is an immutable, party-visible snapshot. The partial
-- unique index is the database backstop for concurrent accept operations.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS tbl_collab_application (
    id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    collab_id uuid NOT NULL,
    applicant_actor_id uuid NOT NULL,
    applicant_user_id uuid NOT NULL,
    client_request_id uuid NOT NULL,
    request_payload_hash varchar(64) NOT NULL,
    phone_snapshot varchar(32) NOT NULL,
    message varchar(500),
    status varchar(48) NOT NULL DEFAULT 'PENDING',
    submitted_at timestamp with time zone NOT NULL,
    status_changed_at timestamp with time zone NOT NULL,
    decided_at timestamp with time zone,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT pk_collab_application PRIMARY KEY (id),
    CONSTRAINT uk_collab_application_actor UNIQUE (collab_id, applicant_actor_id),
    CONSTRAINT uk_collab_application_user UNIQUE (collab_id, applicant_user_id),
    CONSTRAINT uk_collab_application_request UNIQUE (applicant_user_id, client_request_id),
    CONSTRAINT uk_collab_application_job_reference
        UNIQUE (id, collab_id, applicant_actor_id, applicant_user_id),
    CONSTRAINT fk_collab_application_listing
        FOREIGN KEY (collab_id) REFERENCES tbl_collab (id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_application_actor
        FOREIGN KEY (applicant_actor_id) REFERENCES tbl_collab_actor (id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_application_user
        FOREIGN KEY (applicant_user_id) REFERENCES tbl_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_collab_application_payload_hash CHECK (
        request_payload_hash ~ '^[0-9A-Fa-f]{64}$'
    ),
    CONSTRAINT ck_collab_application_phone CHECK (
        char_length(phone_snapshot) BETWEEN 7 AND 32
        AND phone_snapshot ~ '^\+?[0-9][0-9 ()/.-]*[0-9]$'
        AND char_length(regexp_replace(phone_snapshot, '[^0-9]', '', 'g')) BETWEEN 7 AND 15
    ),
    CONSTRAINT ck_collab_application_message CHECK (
        message IS NULL OR char_length(btrim(message)) BETWEEN 1 AND 500
    ),
    CONSTRAINT ck_collab_application_status CHECK (
        status IN (
            'PENDING',
            'ACCEPTED',
            'REJECTED',
            'WITHDRAWN_BY_APPLICANT',
            'INVALIDATED_BY_LISTING_CLOSURE'
        )
    ),
    CONSTRAINT ck_collab_application_timestamps CHECK (
        status_changed_at >= submitted_at
        AND (
            (status = 'PENDING' AND decided_at IS NULL)
            OR
            (status IN ('ACCEPTED', 'REJECTED')
                AND decided_at IS NOT NULL
                AND decided_at >= submitted_at)
            OR
            (status IN ('WITHDRAWN_BY_APPLICANT', 'INVALIDATED_BY_LISTING_CLOSURE')
                AND decided_at IS NULL)
        )
    ),
    CONSTRAINT ck_collab_application_version CHECK (version >= 0)
);

-- Rerunnable hardening for databases that received an earlier v2 candidate.
DO $collab_application_user_unique$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = 'tbl_collab_application'::regclass
           AND conname = 'uk_collab_application_user'
    ) THEN
        ALTER TABLE tbl_collab_application
            ADD CONSTRAINT uk_collab_application_user UNIQUE (collab_id, applicant_user_id);
    END IF;
END
$collab_application_user_unique$;

DO $collab_application_job_reference$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = 'tbl_collab_application'::regclass
           AND conname = 'uk_collab_application_job_reference'
    ) THEN
        ALTER TABLE tbl_collab_application
            ADD CONSTRAINT uk_collab_application_job_reference
            UNIQUE (id, collab_id, applicant_actor_id, applicant_user_id);
    END IF;
END
$collab_application_job_reference$;

CREATE UNIQUE INDEX IF NOT EXISTS uk_collab_one_accepted_application
    ON tbl_collab_application (collab_id)
    WHERE status = 'ACCEPTED';

CREATE INDEX IF NOT EXISTS idx_collab_application_listing
    ON tbl_collab_application (collab_id, status, submitted_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_collab_application_user
    ON tbl_collab_application (applicant_user_id, submitted_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_collab_application_actor
    ON tbl_collab_application (applicant_actor_id, submitted_at DESC, id DESC);

-- ---------------------------------------------------------------------------
-- Accepted work and bilateral completion
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS tbl_collab_job (
    id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    collab_id uuid NOT NULL,
    application_id uuid NOT NULL,
    publisher_actor_id uuid NOT NULL,
    applicant_actor_id uuid NOT NULL,
    publisher_user_id uuid NOT NULL,
    applicant_user_id uuid NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    publisher_confirmed_at timestamp with time zone,
    applicant_confirmed_at timestamp with time zone,
    completed_at timestamp with time zone,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT pk_collab_job PRIMARY KEY (id),
    CONSTRAINT uk_collab_job_listing UNIQUE (collab_id),
    CONSTRAINT uk_collab_job_application UNIQUE (application_id),
    CONSTRAINT fk_collab_job_listing
        FOREIGN KEY (collab_id) REFERENCES tbl_collab (id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_job_application
        FOREIGN KEY (application_id) REFERENCES tbl_collab_application (id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_job_publisher_consistency
        FOREIGN KEY (collab_id, publisher_actor_id, publisher_user_id)
        REFERENCES tbl_collab (id, publisher_actor_id, owner_user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_job_application_consistency
        FOREIGN KEY (application_id, collab_id, applicant_actor_id, applicant_user_id)
        REFERENCES tbl_collab_application
            (id, collab_id, applicant_actor_id, applicant_user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_job_publisher_actor
        FOREIGN KEY (publisher_actor_id) REFERENCES tbl_collab_actor (id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_job_applicant_actor
        FOREIGN KEY (applicant_actor_id) REFERENCES tbl_collab_actor (id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_job_publisher_user
        FOREIGN KEY (publisher_user_id) REFERENCES tbl_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_job_applicant_user
        FOREIGN KEY (applicant_user_id) REFERENCES tbl_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_collab_job_status CHECK (status IN ('ACTIVE', 'COMPLETED')),
    CONSTRAINT ck_collab_job_distinct_parties CHECK (
        publisher_actor_id <> applicant_actor_id
        AND publisher_user_id <> applicant_user_id
    ),
    CONSTRAINT ck_collab_job_lifecycle CHECK (
        (
            status = 'ACTIVE'
            AND completed_at IS NULL
            AND NOT (publisher_confirmed_at IS NOT NULL AND applicant_confirmed_at IS NOT NULL)
        )
        OR
        (
            status = 'COMPLETED'
            AND publisher_confirmed_at IS NOT NULL
            AND applicant_confirmed_at IS NOT NULL
            AND completed_at IS NOT NULL
            AND completed_at >= publisher_confirmed_at
            AND completed_at >= applicant_confirmed_at
        )
    ),
    CONSTRAINT ck_collab_job_version CHECK (version >= 0)
);

-- Rerunnable hardening for databases that received an earlier v2 candidate.
DO $collab_job_consistency_references$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = 'tbl_collab_job'::regclass
           AND conname = 'fk_collab_job_publisher_consistency'
    ) THEN
        ALTER TABLE tbl_collab_job
            ADD CONSTRAINT fk_collab_job_publisher_consistency
            FOREIGN KEY (collab_id, publisher_actor_id, publisher_user_id)
            REFERENCES tbl_collab (id, publisher_actor_id, owner_user_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = 'tbl_collab_job'::regclass
           AND conname = 'fk_collab_job_application_consistency'
    ) THEN
        ALTER TABLE tbl_collab_job
            ADD CONSTRAINT fk_collab_job_application_consistency
            FOREIGN KEY (application_id, collab_id, applicant_actor_id, applicant_user_id)
            REFERENCES tbl_collab_application
                (id, collab_id, applicant_actor_id, applicant_user_id)
            ON DELETE RESTRICT;
    END IF;
END
$collab_job_consistency_references$;

CREATE INDEX IF NOT EXISTS idx_collab_job_publisher
    ON tbl_collab_job (publisher_user_id, status, id DESC);

CREATE INDEX IF NOT EXISTS idx_collab_job_applicant
    ON tbl_collab_job (applicant_user_id, status, id DESC);

CREATE INDEX IF NOT EXISTS idx_collab_job_completion
    ON tbl_collab_job (status, completed_at DESC, id DESC);

-- ---------------------------------------------------------------------------
-- Reviews, saved listings, and reports
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS tbl_collab_review (
    id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    job_id uuid NOT NULL,
    reviewer_actor_id uuid NOT NULL,
    target_actor_id uuid NOT NULL,
    reviewer_user_id uuid NOT NULL,
    client_request_id uuid NOT NULL,
    request_payload_hash varchar(64) NOT NULL,
    rating integer NOT NULL,
    comment varchar(500),
    submitted_at timestamp with time zone NOT NULL,
    CONSTRAINT pk_collab_review PRIMARY KEY (id),
    CONSTRAINT uk_collab_review_side UNIQUE (job_id, reviewer_actor_id),
    CONSTRAINT uk_collab_review_request UNIQUE (reviewer_user_id, client_request_id),
    CONSTRAINT fk_collab_review_job
        FOREIGN KEY (job_id) REFERENCES tbl_collab_job (id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_review_reviewer_actor
        FOREIGN KEY (reviewer_actor_id) REFERENCES tbl_collab_actor (id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_review_target_actor
        FOREIGN KEY (target_actor_id) REFERENCES tbl_collab_actor (id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_review_reviewer_user
        FOREIGN KEY (reviewer_user_id) REFERENCES tbl_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_collab_review_payload_hash CHECK (
        request_payload_hash ~ '^[0-9A-Fa-f]{64}$'
    ),
    CONSTRAINT ck_collab_review_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT ck_collab_review_distinct_actors CHECK (reviewer_actor_id <> target_actor_id),
    CONSTRAINT ck_collab_review_comment CHECK (
        comment IS NULL OR char_length(btrim(comment)) BETWEEN 1 AND 500
    )
);

CREATE INDEX IF NOT EXISTS idx_collab_review_target
    ON tbl_collab_review (target_actor_id, submitted_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_collab_review_job
    ON tbl_collab_review (job_id, submitted_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS tbl_collab_saved_listing (
    id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    user_id uuid NOT NULL,
    collab_id uuid NOT NULL,
    CONSTRAINT pk_collab_saved_listing PRIMARY KEY (id),
    CONSTRAINT uk_collab_saved_user_listing UNIQUE (user_id, collab_id),
    CONSTRAINT fk_collab_saved_user
        FOREIGN KEY (user_id) REFERENCES tbl_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_collab_saved_listing
        FOREIGN KEY (collab_id) REFERENCES tbl_collab (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_collab_saved_user
    ON tbl_collab_saved_listing (user_id, created_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS tbl_collab_report (
    id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    collab_id uuid NOT NULL,
    reporter_user_id uuid NOT NULL,
    client_request_id uuid NOT NULL,
    request_payload_hash varchar(64) NOT NULL,
    reason varchar(24) NOT NULL,
    details varchar(500),
    reported_at timestamp with time zone NOT NULL,
    CONSTRAINT pk_collab_report PRIMARY KEY (id),
    CONSTRAINT uk_collab_report_user_listing UNIQUE (reporter_user_id, collab_id),
    CONSTRAINT uk_collab_report_request UNIQUE (reporter_user_id, client_request_id),
    CONSTRAINT fk_collab_report_listing
        FOREIGN KEY (collab_id) REFERENCES tbl_collab (id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_report_user
        FOREIGN KEY (reporter_user_id) REFERENCES tbl_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_collab_report_payload_hash CHECK (
        request_payload_hash ~ '^[0-9A-Fa-f]{64}$'
    ),
    CONSTRAINT ck_collab_report_reason CHECK (
        reason IN ('SPAM', 'INAPPROPRIATE', 'MISLEADING', 'OTHER')
    ),
    CONSTRAINT ck_collab_report_details CHECK (
        (reason <> 'OTHER' AND (details IS NULL OR char_length(btrim(details)) BETWEEN 1 AND 500))
        OR
        (reason = 'OTHER'
            AND details IS NOT NULL
            AND char_length(btrim(details)) BETWEEN 1 AND 500)
    )
);

CREATE INDEX IF NOT EXISTS idx_collab_report_listing
    ON tbl_collab_report (collab_id, reported_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_collab_report_reason
    ON tbl_collab_report (reason, reported_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_collab_report_user
    ON tbl_collab_report (reporter_user_id, reported_at DESC, id DESC);

-- ---------------------------------------------------------------------------
-- Hibernate may have created the canonical tables before this migration ran.
-- CREATE TABLE IF NOT EXISTS then cannot add SQL-only domain checks, so every
-- check is reconciled independently by its stable name.
-- ---------------------------------------------------------------------------

DO $collab_domain_check_reconciliation$
DECLARE
    constraint_row record;
BEGIN
    FOR constraint_row IN
        SELECT *
          FROM (VALUES
            ('tbl_collab_actor', 'ck_collab_actor_profile_type',
             $$profile_type IN ('MUSICIAN', 'BAND', 'VENUE', 'STUDIO')$$),
            ('tbl_collab_actor', 'ck_collab_actor_display_name',
             $$char_length(btrim(display_name)) BETWEEN 1 AND 120$$),
            ('tbl_collab_actor', 'ck_collab_actor_avatar_url',
             $$avatar_url IS NULL OR char_length(btrim(avatar_url)) BETWEEN 1 AND 1024$$),
            ('tbl_collab_actor', 'ck_collab_actor_rating_totals',
             $$rating_sum >= 0 AND review_count >= 0 AND ((review_count = 0 AND rating_sum = 0) OR (review_count > 0 AND rating_sum BETWEEN review_count AND review_count * 5))$$),
            ('tbl_collab_actor', 'ck_collab_actor_completed_jobs',
             $$completed_job_count >= 0$$),
            ('tbl_collab_actor', 'ck_collab_actor_version',
             $$version >= 0$$),

            ('tbl_collab', 'ck_collab_creation_payload_hash',
             $$creation_payload_hash ~ '^[0-9A-Fa-f]{64}$'$$),
            ('tbl_collab', 'ck_collab_cadence',
             $$cadence IN ('REGULAR', 'EXTRA')$$),
            ('tbl_collab', 'ck_collab_wanted_type',
             $$wanted_type IN ('MUSICIAN', 'BAND', 'VENUE', 'STUDIO')$$),
            ('tbl_collab', 'ck_collab_branch',
             $$branch IS NULL OR branch IN ('VOCAL', 'SOUND_ENGINEER', 'PRODUCER', 'DJ', 'OTHER')$$),
            ('tbl_collab', 'ck_collab_specialty_shape',
             $$(wanted_type = 'MUSICIAN' AND ((instrument_id IS NOT NULL AND branch IS NULL AND custom_specialty IS NULL) OR (instrument_id IS NULL AND branch IS NOT NULL AND ((branch = 'OTHER' AND custom_specialty IS NOT NULL AND char_length(btrim(custom_specialty)) BETWEEN 1 AND 80) OR (branch <> 'OTHER' AND custom_specialty IS NULL))))) OR (wanted_type <> 'MUSICIAN' AND instrument_id IS NULL AND branch IS NULL AND custom_specialty IS NULL)$$),
            ('tbl_collab', 'ck_collab_title',
             $$char_length(btrim(title)) BETWEEN 5 AND 100$$),
            ('tbl_collab', 'ck_collab_description',
             $$char_length(btrim(description)) BETWEEN 20 AND 500$$),
            ('tbl_collab', 'ck_collab_schedule_shape',
             $$(cadence = 'REGULAR' AND scheduled_at IS NULL AND expires_at IS NULL) OR (cadence = 'EXTRA' AND scheduled_at IS NOT NULL AND expires_at IS NOT NULL AND expires_at = scheduled_at)$$),
            ('tbl_collab', 'ck_collab_extra_publication_window',
             $$cadence <> 'EXTRA' OR published_at IS NULL OR (scheduled_at >= published_at AND scheduled_at <= published_at + INTERVAL '7 days')$$),
            ('tbl_collab', 'ck_collab_fee_pair',
             $$(fee_amount_minor IS NULL AND currency IS NULL) OR (fee_amount_minor IS NOT NULL AND fee_amount_minor BETWEEN 1 AND 100000000 AND currency IS NOT NULL AND currency ~ '^[A-Z]{3}$')$$),
            ('tbl_collab', 'ck_collab_status',
             $$status IN ('DRAFT', 'OPEN', 'CLOSED', 'EXPIRED')$$),
            ('tbl_collab', 'ck_collab_closure_reason',
             $$closure_reason IS NULL OR closure_reason IN ('MATCHED', 'OWNER_CLOSED', 'EXPIRED', 'ADMIN_REMOVED')$$),
            ('tbl_collab', 'ck_collab_lifecycle',
             $$(status = 'DRAFT' AND published_at IS NULL AND closed_at IS NULL AND closure_reason IS NULL) OR (status = 'OPEN' AND published_at IS NOT NULL AND closed_at IS NULL AND closure_reason IS NULL) OR (status = 'CLOSED' AND published_at IS NOT NULL AND closed_at IS NOT NULL AND closed_at >= published_at AND closure_reason IS NOT NULL AND closure_reason IN ('MATCHED', 'OWNER_CLOSED', 'ADMIN_REMOVED')) OR (status = 'EXPIRED' AND cadence = 'EXTRA' AND published_at IS NOT NULL AND closed_at IS NOT NULL AND closed_at >= published_at AND closure_reason = 'EXPIRED')$$),
            ('tbl_collab', 'ck_collab_version',
             $$version >= 0$$),

            ('tbl_collab_genre', 'ck_collab_genre_position',
             $$position BETWEEN 0 AND 2$$),
            ('tbl_collab_genre', 'ck_collab_genre_value',
             $$char_length(btrim(genre)) BETWEEN 1 AND 40$$),

            ('tbl_collab_application', 'ck_collab_application_payload_hash',
             $$request_payload_hash ~ '^[0-9A-Fa-f]{64}$'$$),
            ('tbl_collab_application', 'ck_collab_application_phone',
             $$char_length(phone_snapshot) BETWEEN 7 AND 32 AND phone_snapshot ~ '^\+?[0-9][0-9 ()/.-]*[0-9]$' AND char_length(regexp_replace(phone_snapshot, '[^0-9]', '', 'g')) BETWEEN 7 AND 15$$),
            ('tbl_collab_application', 'ck_collab_application_message',
             $$message IS NULL OR char_length(btrim(message)) BETWEEN 1 AND 500$$),
            ('tbl_collab_application', 'ck_collab_application_status',
             $$status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'WITHDRAWN_BY_APPLICANT', 'INVALIDATED_BY_LISTING_CLOSURE')$$),
            ('tbl_collab_application', 'ck_collab_application_timestamps',
             $$status_changed_at >= submitted_at AND ((status = 'PENDING' AND decided_at IS NULL) OR (status IN ('ACCEPTED', 'REJECTED') AND decided_at IS NOT NULL AND decided_at >= submitted_at) OR (status IN ('WITHDRAWN_BY_APPLICANT', 'INVALIDATED_BY_LISTING_CLOSURE') AND decided_at IS NULL))$$),
            ('tbl_collab_application', 'ck_collab_application_version',
             $$version >= 0$$),

            ('tbl_collab_job', 'ck_collab_job_status',
             $$status IN ('ACTIVE', 'COMPLETED')$$),
            ('tbl_collab_job', 'ck_collab_job_distinct_parties',
             $$publisher_actor_id <> applicant_actor_id AND publisher_user_id <> applicant_user_id$$),
            ('tbl_collab_job', 'ck_collab_job_lifecycle',
             $$(status = 'ACTIVE' AND completed_at IS NULL AND NOT (publisher_confirmed_at IS NOT NULL AND applicant_confirmed_at IS NOT NULL)) OR (status = 'COMPLETED' AND publisher_confirmed_at IS NOT NULL AND applicant_confirmed_at IS NOT NULL AND completed_at IS NOT NULL AND completed_at >= publisher_confirmed_at AND completed_at >= applicant_confirmed_at)$$),
            ('tbl_collab_job', 'ck_collab_job_version',
             $$version >= 0$$),

            ('tbl_collab_review', 'ck_collab_review_payload_hash',
             $$request_payload_hash ~ '^[0-9A-Fa-f]{64}$'$$),
            ('tbl_collab_review', 'ck_collab_review_rating',
             $$rating BETWEEN 1 AND 5$$),
            ('tbl_collab_review', 'ck_collab_review_distinct_actors',
             $$reviewer_actor_id <> target_actor_id$$),
            ('tbl_collab_review', 'ck_collab_review_comment',
             $$comment IS NULL OR char_length(btrim(comment)) BETWEEN 1 AND 500$$),

            ('tbl_collab_report', 'ck_collab_report_payload_hash',
             $$request_payload_hash ~ '^[0-9A-Fa-f]{64}$'$$),
            ('tbl_collab_report', 'ck_collab_report_reason',
             $$reason IN ('SPAM', 'INAPPROPRIATE', 'MISLEADING', 'OTHER')$$),
            ('tbl_collab_report', 'ck_collab_report_details',
             $$(reason <> 'OTHER' AND (details IS NULL OR char_length(btrim(details)) BETWEEN 1 AND 500)) OR (reason = 'OTHER' AND details IS NOT NULL AND char_length(btrim(details)) BETWEEN 1 AND 500)$$)
          ) AS required(table_name, constraint_name, predicate_sql)
    LOOP
        IF NOT EXISTS (
            SELECT 1
              FROM pg_constraint
             WHERE conrelid = to_regclass(format('%I.%I', current_schema(), constraint_row.table_name))
               AND conname = constraint_row.constraint_name
        ) THEN
            EXECUTE format(
                'ALTER TABLE %I ADD CONSTRAINT %I CHECK (%s)',
                constraint_row.table_name,
                constraint_row.constraint_name,
                constraint_row.predicate_sql
            );
        END IF;

        IF EXISTS (
            SELECT 1
              FROM pg_constraint
             WHERE conrelid = to_regclass(format('%I.%I', current_schema(), constraint_row.table_name))
               AND conname = constraint_row.constraint_name
               AND NOT convalidated
        ) THEN
            EXECUTE format(
                'ALTER TABLE %I VALIDATE CONSTRAINT %I',
                constraint_row.table_name,
                constraint_row.constraint_name
            );
        END IF;
    END LOOP;
END
$collab_domain_check_reconciliation$;

DO $collab_genre_unique_reconciliation$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = 'tbl_collab_genre'::regclass
           AND conname = 'uk_collab_genre_value'
    ) THEN
        ALTER TABLE tbl_collab_genre
            ADD CONSTRAINT uk_collab_genre_value UNIQUE (collab_id, genre);
    END IF;
END
$collab_genre_unique_reconciliation$;

DO $collab_genre_position_key_reconciliation$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint key_constraint
         WHERE key_constraint.conrelid = 'tbl_collab_genre'::regclass
           AND key_constraint.contype IN ('p', 'u')
           AND ARRAY(
               SELECT attribute.attname::text
                 FROM unnest(key_constraint.conkey) AS key_column(attnum)
                 JOIN pg_attribute attribute
                   ON attribute.attrelid = key_constraint.conrelid
                  AND attribute.attnum = key_column.attnum
                ORDER BY attribute.attname::text
           ) = ARRAY['collab_id', 'position']::text[]
    ) THEN
        ALTER TABLE tbl_collab_genre
            ADD CONSTRAINT uk_collab_genre_position UNIQUE (collab_id, position);
    END IF;
END
$collab_genre_position_key_reconciliation$;

COMMIT;
