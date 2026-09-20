-- Independent Backstage classifieds. Apply with psql ON_ERROR_STOP=1 before API/worker rollout.
-- Rerunnable, additive migration; existing Backline and Instrument data are untouched.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

CREATE TABLE IF NOT EXISTS public.tbl_marketplace_category (
    id uuid PRIMARY KEY, code varchar(80) NOT NULL UNIQUE, name varchar(120) NOT NULL,
    parent_id uuid REFERENCES public.tbl_marketplace_category(id),
    sort_order integer NOT NULL, active boolean NOT NULL DEFAULT true,
    CHECK (id IS DISTINCT FROM parent_id), CHECK (sort_order >= 0),
    CHECK (code ~ '^[A-Z][A-Z0-9_]{1,79}$'), CHECK (char_length(btrim(name)) BETWEEN 1 AND 120)
);
CREATE INDEX IF NOT EXISTS idx_marketplace_category_parent ON public.tbl_marketplace_category(parent_id,sort_order,id);

CREATE TABLE IF NOT EXISTS public.tbl_marketplace_listing (
    id uuid PRIMARY KEY,
    owner_user_id uuid NOT NULL REFERENCES public.tbl_user(id),
    seller_profile_type varchar(16) NOT NULL CHECK (seller_profile_type IN ('MUSICIAN','STUDIO','VENUE')),
    seller_profile_id uuid NOT NULL,
    client_request_id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
    status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','PUBLISHED','SOLD','WITHDRAWN','MODERATED')),
    title varchar(120), description varchar(4000),
    category_id uuid REFERENCES public.tbl_marketplace_category(id),
    brand varchar(80), model varchar(100),
    condition varchar(8) CHECK(condition IN ('NEW','USED')),
    price_minor bigint CHECK(price_minor BETWEEN 1 AND 100000000000),
    currency varchar(3) NOT NULL DEFAULT 'TRY' CHECK(currency='TRY'),
    district_id uuid REFERENCES public.tbl_district(id),
    negotiable boolean NOT NULL DEFAULT false,
    delivery_method varchar(8) CHECK(delivery_method IN ('PICKUP','SHIPPING','BOTH')),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at timestamptz,
    CONSTRAINT uk_marketplace_create_request UNIQUE(owner_user_id,client_request_id),
    CONSTRAINT ck_marketplace_published_fields CHECK(status='DRAFT' OR (
        title IS NOT NULL AND char_length(btrim(title)) BETWEEN 5 AND 120
        AND description IS NOT NULL AND char_length(btrim(description)) BETWEEN 10 AND 4000
        AND category_id IS NOT NULL AND condition IS NOT NULL AND price_minor IS NOT NULL
        AND district_id IS NOT NULL AND delivery_method IS NOT NULL AND published_at IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS idx_marketplace_discovery ON public.tbl_marketplace_listing(published_at DESC,id DESC) WHERE status='PUBLISHED';
CREATE INDEX IF NOT EXISTS idx_marketplace_price ON public.tbl_marketplace_listing(price_minor,id) WHERE status='PUBLISHED';
CREATE INDEX IF NOT EXISTS idx_marketplace_category_discovery ON public.tbl_marketplace_listing(category_id,published_at DESC,id DESC) WHERE status='PUBLISHED';
CREATE INDEX IF NOT EXISTS idx_marketplace_location_discovery ON public.tbl_marketplace_listing(district_id,published_at DESC,id DESC) WHERE status='PUBLISHED';
CREATE INDEX IF NOT EXISTS idx_marketplace_owner ON public.tbl_marketplace_listing(owner_user_id,status,created_at DESC,id DESC);
CREATE INDEX IF NOT EXISTS idx_marketplace_draft_cleanup ON public.tbl_marketplace_listing(updated_at,id) WHERE status='DRAFT';

CREATE TABLE IF NOT EXISTS public.tbl_marketplace_listing_photo (
    listing_id uuid NOT NULL REFERENCES public.tbl_marketplace_listing(id) ON DELETE CASCADE,
    media_asset_id uuid NOT NULL REFERENCES public.tbl_media_asset(id),
    position integer NOT NULL CHECK(position BETWEEN 0 AND 7),
    PRIMARY KEY(listing_id,media_asset_id), UNIQUE(listing_id,position), UNIQUE(media_asset_id)
);
CREATE TABLE IF NOT EXISTS public.tbl_marketplace_saved (
    user_id uuid NOT NULL REFERENCES public.tbl_user(id) ON DELETE CASCADE,
    listing_id uuid NOT NULL REFERENCES public.tbl_marketplace_listing(id) ON DELETE CASCADE,
    saved_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(user_id,listing_id)
);
CREATE INDEX IF NOT EXISTS idx_marketplace_saved_page ON public.tbl_marketplace_saved(user_id,saved_at DESC,listing_id DESC);

CREATE TABLE IF NOT EXISTS public.tbl_marketplace_report (
    id uuid PRIMARY KEY,
    listing_id uuid REFERENCES public.tbl_marketplace_listing(id) ON DELETE SET NULL,
    reporter_user_id uuid REFERENCES public.tbl_user(id) ON DELETE SET NULL,
    client_request_id uuid NOT NULL,
    reason varchar(16) NOT NULL CHECK(reason IN ('SCAM','MISLEADING','PROHIBITED','SPAM','OTHER')),
    description varchar(1000),
    evidence jsonb NOT NULL CHECK(jsonb_typeof(evidence)='object'),
    status varchar(16) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','DISMISSED','ACTIONED')),
    version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
    reported_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_by_user_id uuid,
    reviewed_at timestamptz,
    decision varchar(16) CHECK(decision IN ('DISMISS','REMOVE_LISTING')),
    resolution_note varchar(1000),
    UNIQUE(reporter_user_id,listing_id), UNIQUE(reporter_user_id,client_request_id),
    CHECK((status='OPEN' AND reviewed_by_user_id IS NULL AND reviewed_at IS NULL AND decision IS NULL AND resolution_note IS NULL)
       OR (status<>'OPEN' AND reviewed_by_user_id IS NOT NULL AND reviewed_at IS NOT NULL
           AND resolution_note IS NOT NULL AND char_length(btrim(resolution_note)) BETWEEN 5 AND 1000
           AND ((status='DISMISSED' AND decision='DISMISS') OR (status='ACTIONED' AND decision='REMOVE_LISTING'))))
);
CREATE INDEX IF NOT EXISTS idx_marketplace_report_queue ON public.tbl_marketplace_report(status,reported_at DESC,id DESC);
CREATE TABLE IF NOT EXISTS public.tbl_marketplace_report_photo (
    report_id uuid NOT NULL REFERENCES public.tbl_marketplace_report(id) ON DELETE CASCADE,
    media_asset_id uuid NOT NULL REFERENCES public.tbl_media_asset(id),
    PRIMARY KEY(report_id,media_asset_id)
);
CREATE INDEX IF NOT EXISTS idx_marketplace_report_photo_asset ON public.tbl_marketplace_report_photo(media_asset_id);
CREATE TABLE IF NOT EXISTS public.tbl_marketplace_report_audit (
    id uuid PRIMARY KEY, report_id uuid NOT NULL REFERENCES public.tbl_marketplace_report(id) ON DELETE CASCADE,
    actor_user_id uuid NOT NULL, decision varchar(16) NOT NULL CHECK(decision IN ('DISMISS','REMOVE_LISTING')),
    resolution_note varchar(1000) NOT NULL, occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resulting_version bigint NOT NULL CHECK(resulting_version>0), UNIQUE(report_id,resulting_version)
);

DO $permission$
BEGIN
    IF to_regclass('public.tbl_permissions') IS NOT NULL AND to_regclass('public.role_permissions') IS NOT NULL THEN
        INSERT INTO public.tbl_permissions(id,name,created_at,updated_at)
        VALUES('6f4043f9-1d92-4e4f-b8c6-412bbdd20970','MANAGE_MARKETPLACE_REPORTS',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
        ON CONFLICT(name) DO NOTHING;
        INSERT INTO public.role_permissions(role_id,permission_id)
        SELECT r.id,p.id FROM public.tbl_role r CROSS JOIN public.tbl_permissions p
        WHERE r.name IN ('ROLE_ADMIN','ROLE_OWNER') AND p.name='MANAGE_MARKETPLACE_REPORTS'
        ON CONFLICT DO NOTHING;
    END IF;
END $permission$;

-- CATALOG_SEED: generated from marketplace-category-seed.json; stable IDs derive from immutable codes.
INSERT INTO public.tbl_marketplace_category(id,code,name,parent_id,sort_order,active) VALUES
(md5('marketplace:GUITARS')::uuid,'GUITARS','Gitar ve Bas',NULL,0,true),
(md5('marketplace:ELECTRIC_GUITAR')::uuid,'ELECTRIC_GUITAR','Elektro gitar',md5('marketplace:GUITARS')::uuid,0,true),
(md5('marketplace:ACOUSTIC_GUITAR')::uuid,'ACOUSTIC_GUITAR','Akustik / elektro akustik gitar',md5('marketplace:GUITARS')::uuid,1,true),
(md5('marketplace:CLASSICAL_GUITAR')::uuid,'CLASSICAL_GUITAR','Klasik / flamenko gitar',md5('marketplace:GUITARS')::uuid,2,true),
(md5('marketplace:BASS_GUITAR')::uuid,'BASS_GUITAR','Bas gitar',md5('marketplace:GUITARS')::uuid,3,true),
(md5('marketplace:UKULELE')::uuid,'UKULELE','Ukulele',md5('marketplace:GUITARS')::uuid,4,true),
(md5('marketplace:MANDOLIN_BANJO')::uuid,'MANDOLIN_BANJO','Mandolin / banjo',md5('marketplace:GUITARS')::uuid,5,true),
(md5('marketplace:KEYBOARDS')::uuid,'KEYBOARDS','Piyano ve Tuşlu Çalgılar',NULL,1,true),
(md5('marketplace:ACOUSTIC_PIANO')::uuid,'ACOUSTIC_PIANO','Akustik piyano',md5('marketplace:KEYBOARDS')::uuid,0,true),
(md5('marketplace:DIGITAL_PIANO')::uuid,'DIGITAL_PIANO','Dijital piyano',md5('marketplace:KEYBOARDS')::uuid,1,true),
(md5('marketplace:ARRANGER_KEYBOARD')::uuid,'ARRANGER_KEYBOARD','Org / aranjör klavye',md5('marketplace:KEYBOARDS')::uuid,2,true),
(md5('marketplace:SYNTH_WORKSTATION')::uuid,'SYNTH_WORKSTATION','Synthesizer / workstation',md5('marketplace:KEYBOARDS')::uuid,3,true),
(md5('marketplace:MIDI_KEYBOARD')::uuid,'MIDI_KEYBOARD','MIDI klavye',md5('marketplace:KEYBOARDS')::uuid,4,true),
(md5('marketplace:ACCORDION')::uuid,'ACCORDION','Akordeon',md5('marketplace:KEYBOARDS')::uuid,5,true),
(md5('marketplace:STRINGS')::uuid,'STRINGS','Yaylı Çalgılar',NULL,2,true),
(md5('marketplace:VIOLIN')::uuid,'VIOLIN','Keman',md5('marketplace:STRINGS')::uuid,0,true),
(md5('marketplace:VIOLA')::uuid,'VIOLA','Viyola',md5('marketplace:STRINGS')::uuid,1,true),
(md5('marketplace:CELLO')::uuid,'CELLO','Çello',md5('marketplace:STRINGS')::uuid,2,true),
(md5('marketplace:DOUBLE_BASS')::uuid,'DOUBLE_BASS','Kontrbas',md5('marketplace:STRINGS')::uuid,3,true),
(md5('marketplace:STRING_BOW')::uuid,'STRING_BOW','Yay',md5('marketplace:STRINGS')::uuid,4,true),
(md5('marketplace:WIND')::uuid,'WIND','Nefesli Çalgılar',NULL,3,true),
(md5('marketplace:FLUTE')::uuid,'FLUTE','Yan flüt / pikolo',md5('marketplace:WIND')::uuid,0,true),
(md5('marketplace:CLARINET')::uuid,'CLARINET','Klarnet',md5('marketplace:WIND')::uuid,1,true),
(md5('marketplace:SAXOPHONE')::uuid,'SAXOPHONE','Saksafon',md5('marketplace:WIND')::uuid,2,true),
(md5('marketplace:OBOE_BASSOON')::uuid,'OBOE_BASSOON','Obua / fagot',md5('marketplace:WIND')::uuid,3,true),
(md5('marketplace:TRUMPET')::uuid,'TRUMPET','Trompet / kornet / flugelhorn',md5('marketplace:WIND')::uuid,4,true),
(md5('marketplace:TROMBONE')::uuid,'TROMBONE','Trombon',md5('marketplace:WIND')::uuid,5,true),
(md5('marketplace:HORN_TUBA')::uuid,'HORN_TUBA','Korno / tuba',md5('marketplace:WIND')::uuid,6,true),
(md5('marketplace:RECORDER_PANFLUTE')::uuid,'RECORDER_PANFLUTE','Blok flüt / pan flüt',md5('marketplace:WIND')::uuid,7,true),
(md5('marketplace:HARMONICA')::uuid,'HARMONICA','Mızıka',md5('marketplace:WIND')::uuid,8,true),
(md5('marketplace:MELODICA')::uuid,'MELODICA','Melodika',md5('marketplace:WIND')::uuid,9,true),
(md5('marketplace:TRADITIONAL')::uuid,'TRADITIONAL','Geleneksel Çalgılar',NULL,4,true),
(md5('marketplace:BAGLAMA')::uuid,'BAGLAMA','Bağlama / saz',md5('marketplace:TRADITIONAL')::uuid,0,true),
(md5('marketplace:OUD')::uuid,'OUD','Ud',md5('marketplace:TRADITIONAL')::uuid,1,true),
(md5('marketplace:QANUN')::uuid,'QANUN','Kanun',md5('marketplace:TRADITIONAL')::uuid,2,true),
(md5('marketplace:NEY_KAVAL')::uuid,'NEY_KAVAL','Ney / kaval',md5('marketplace:TRADITIONAL')::uuid,3,true),
(md5('marketplace:KEMENCE')::uuid,'KEMENCE','Kemençe',md5('marketplace:TRADITIONAL')::uuid,4,true),
(md5('marketplace:CUMBUS_TANBUR')::uuid,'CUMBUS_TANBUR','Cümbüş / tanbur',md5('marketplace:TRADITIONAL')::uuid,5,true),
(md5('marketplace:OTHER_TRADITIONAL')::uuid,'OTHER_TRADITIONAL','Diğer geleneksel çalgılar',md5('marketplace:TRADITIONAL')::uuid,6,true),
(md5('marketplace:DRUMS')::uuid,'DRUMS','Davul ve Perküsyon',NULL,5,true),
(md5('marketplace:ACOUSTIC_DRUMS')::uuid,'ACOUSTIC_DRUMS','Akustik davul seti',md5('marketplace:DRUMS')::uuid,0,true),
(md5('marketplace:ELECTRONIC_DRUMS')::uuid,'ELECTRONIC_DRUMS','Elektronik davul seti',md5('marketplace:DRUMS')::uuid,1,true),
(md5('marketplace:SNARE_TOM_KICK')::uuid,'SNARE_TOM_KICK','Trampet / tom / kick',md5('marketplace:DRUMS')::uuid,2,true),
(md5('marketplace:CYMBALS')::uuid,'CYMBALS','Zil',md5('marketplace:DRUMS')::uuid,3,true),
(md5('marketplace:DRUM_HARDWARE')::uuid,'DRUM_HARDWARE','Davul sehpası / donanımı',md5('marketplace:DRUMS')::uuid,4,true),
(md5('marketplace:DRUM_PEDAL')::uuid,'DRUM_PEDAL','Davul pedalı',md5('marketplace:DRUMS')::uuid,5,true),
(md5('marketplace:DRUM_PAD')::uuid,'DRUM_PAD','Davul pedi / modülü',md5('marketplace:DRUMS')::uuid,6,true),
(md5('marketplace:CAJON')::uuid,'CAJON','Cajon',md5('marketplace:DRUMS')::uuid,7,true),
(md5('marketplace:DARBUKA_DJEMBE')::uuid,'DARBUKA_DJEMBE','Darbuka / djembe',md5('marketplace:DRUMS')::uuid,8,true),
(md5('marketplace:CONGA_BONGO_TIMBALE')::uuid,'CONGA_BONGO_TIMBALE','Conga / bongo / timbal',md5('marketplace:DRUMS')::uuid,9,true),
(md5('marketplace:HAND_PERCUSSION')::uuid,'HAND_PERCUSSION','El perküsyonu',md5('marketplace:DRUMS')::uuid,10,true),
(md5('marketplace:MALLET_ORFF')::uuid,'MALLET_ORFF','Kalimba / melodik perküsyon / Orff',md5('marketplace:DRUMS')::uuid,11,true),
(md5('marketplace:ORCHESTRAL_PERCUSSION')::uuid,'ORCHESTRAL_PERCUSSION','Orkestra perküsyonu',md5('marketplace:DRUMS')::uuid,12,true),
(md5('marketplace:AMPS_EFFECTS')::uuid,'AMPS_EFFECTS','Amfi ve Efekt',NULL,6,true),
(md5('marketplace:GUITAR_AMP')::uuid,'GUITAR_AMP','Gitar amfisi',md5('marketplace:AMPS_EFFECTS')::uuid,0,true),
(md5('marketplace:BASS_AMP')::uuid,'BASS_AMP','Bas amfisi',md5('marketplace:AMPS_EFFECTS')::uuid,1,true),
(md5('marketplace:ACOUSTIC_AMP')::uuid,'ACOUSTIC_AMP','Akustik enstrüman amfisi',md5('marketplace:AMPS_EFFECTS')::uuid,2,true),
(md5('marketplace:KEYBOARD_AMP')::uuid,'KEYBOARD_AMP','Klavye / çok amaçlı amfi',md5('marketplace:AMPS_EFFECTS')::uuid,3,true),
(md5('marketplace:AMP_CABINET')::uuid,'AMP_CABINET','Amfi kabini',md5('marketplace:AMPS_EFFECTS')::uuid,4,true),
(md5('marketplace:EFFECT_PEDAL')::uuid,'EFFECT_PEDAL','Efekt pedalı',md5('marketplace:AMPS_EFFECTS')::uuid,5,true),
(md5('marketplace:MULTI_EFFECTS')::uuid,'MULTI_EFFECTS','Prosesör / çoklu efekt',md5('marketplace:AMPS_EFFECTS')::uuid,6,true),
(md5('marketplace:PEDALBOARD_POWER')::uuid,'PEDALBOARD_POWER','Pedalboard / pedal güç kaynağı',md5('marketplace:AMPS_EFFECTS')::uuid,7,true),
(md5('marketplace:MICROPHONES')::uuid,'MICROPHONES','Mikrofon',NULL,7,true),
(md5('marketplace:DYNAMIC_MIC')::uuid,'DYNAMIC_MIC','Dinamik mikrofon',md5('marketplace:MICROPHONES')::uuid,0,true),
(md5('marketplace:CONDENSER_MIC')::uuid,'CONDENSER_MIC','Condenser / ribbon mikrofon',md5('marketplace:MICROPHONES')::uuid,1,true),
(md5('marketplace:USB_MIC')::uuid,'USB_MIC','USB mikrofon',md5('marketplace:MICROPHONES')::uuid,2,true),
(md5('marketplace:WIRELESS_MIC')::uuid,'WIRELESS_MIC','Kablosuz mikrofon sistemi',md5('marketplace:MICROPHONES')::uuid,3,true),
(md5('marketplace:LAVALIER_HEADSET_MIC')::uuid,'LAVALIER_HEADSET_MIC','Yaka / headset mikrofon',md5('marketplace:MICROPHONES')::uuid,4,true),
(md5('marketplace:INSTRUMENT_MIC')::uuid,'INSTRUMENT_MIC','Enstrüman mikrofonu / mikrofon seti',md5('marketplace:MICROPHONES')::uuid,5,true),
(md5('marketplace:SHOTGUN_CONFERENCE_MIC')::uuid,'SHOTGUN_CONFERENCE_MIC','Kamera / konferans mikrofonu',md5('marketplace:MICROPHONES')::uuid,6,true),
(md5('marketplace:RECORDING')::uuid,'RECORDING','Stüdyo ve Kayıt',NULL,8,true),
(md5('marketplace:AUDIO_INTERFACE')::uuid,'AUDIO_INTERFACE','Ses kartı',md5('marketplace:RECORDING')::uuid,0,true),
(md5('marketplace:STUDIO_MONITORS')::uuid,'STUDIO_MONITORS','Stüdyo monitörü / subwoofer',md5('marketplace:RECORDING')::uuid,1,true),
(md5('marketplace:STUDIO_HEADPHONES')::uuid,'STUDIO_HEADPHONES','Stüdyo kulaklığı',md5('marketplace:RECORDING')::uuid,2,true),
(md5('marketplace:MIC_PREAMP')::uuid,'MIC_PREAMP','Mikrofon preampı',md5('marketplace:RECORDING')::uuid,3,true),
(md5('marketplace:OUTBOARD')::uuid,'OUTBOARD','Harici kompresör / EQ / işlemci',md5('marketplace:RECORDING')::uuid,4,true),
(md5('marketplace:AUDIO_RECORDER')::uuid,'AUDIO_RECORDER','Ses kayıt cihazı',md5('marketplace:RECORDING')::uuid,5,true),
(md5('marketplace:CONTROL_SURFACE')::uuid,'CONTROL_SURFACE','MIDI kontrolcü / kontrol yüzeyi',md5('marketplace:RECORDING')::uuid,6,true),
(md5('marketplace:HEADPHONE_AMP')::uuid,'HEADPHONE_AMP','Kulaklık amfisi',md5('marketplace:RECORDING')::uuid,7,true),
(md5('marketplace:RECORDING_BUNDLE')::uuid,'RECORDING_BUNDLE','Kayıt ekipmanı seti',md5('marketplace:RECORDING')::uuid,8,true),
(md5('marketplace:ACOUSTIC_TREATMENT')::uuid,'ACOUSTIC_TREATMENT','Akustik panel / izolasyon ekipmanı',md5('marketplace:RECORDING')::uuid,9,true),
(md5('marketplace:DJ')::uuid,'DJ','DJ Ekipmanı',NULL,9,true),
(md5('marketplace:DJ_CONTROLLER')::uuid,'DJ_CONTROLLER','DJ kontrolcü',md5('marketplace:DJ')::uuid,0,true),
(md5('marketplace:DJ_PLAYER')::uuid,'DJ_PLAYER','DJ medya çalar / CDJ',md5('marketplace:DJ')::uuid,1,true),
(md5('marketplace:DJ_TURNTABLE')::uuid,'DJ_TURNTABLE','DJ pikabı',md5('marketplace:DJ')::uuid,2,true),
(md5('marketplace:DJ_MIXER')::uuid,'DJ_MIXER','DJ mikseri',md5('marketplace:DJ')::uuid,3,true),
(md5('marketplace:DJ_SAMPLER')::uuid,'DJ_SAMPLER','Sampler / groovebox',md5('marketplace:DJ')::uuid,4,true),
(md5('marketplace:DJ_HEADPHONES')::uuid,'DJ_HEADPHONES','DJ kulaklığı',md5('marketplace:DJ')::uuid,5,true),
(md5('marketplace:DJ_CARTRIDGE')::uuid,'DJ_CARTRIDGE','Pikap iğnesi / kartuşu',md5('marketplace:DJ')::uuid,6,true),
(md5('marketplace:LIVE_SOUND')::uuid,'LIVE_SOUND','Ses Sistemi',NULL,10,true),
(md5('marketplace:ACTIVE_SPEAKER')::uuid,'ACTIVE_SPEAKER','Aktif hoparlör',md5('marketplace:LIVE_SOUND')::uuid,0,true),
(md5('marketplace:PASSIVE_SPEAKER')::uuid,'PASSIVE_SPEAKER','Pasif hoparlör',md5('marketplace:LIVE_SOUND')::uuid,1,true),
(md5('marketplace:PA_SUBWOOFER')::uuid,'PA_SUBWOOFER','PA subwoofer',md5('marketplace:LIVE_SOUND')::uuid,2,true),
(md5('marketplace:STAGE_MONITOR')::uuid,'STAGE_MONITOR','Sahne monitörü',md5('marketplace:LIVE_SOUND')::uuid,3,true),
(md5('marketplace:ANALOG_MIXER')::uuid,'ANALOG_MIXER','Analog mikser',md5('marketplace:LIVE_SOUND')::uuid,4,true),
(md5('marketplace:DIGITAL_MIXER')::uuid,'DIGITAL_MIXER','Dijital mikser',md5('marketplace:LIVE_SOUND')::uuid,5,true),
(md5('marketplace:POWER_AMP')::uuid,'POWER_AMP','Güç amfisi',md5('marketplace:LIVE_SOUND')::uuid,6,true),
(md5('marketplace:PA_PROCESSOR')::uuid,'PA_PROCESSOR','Sistem işlemcisi / crossover',md5('marketplace:LIVE_SOUND')::uuid,7,true),
(md5('marketplace:IEM_SYSTEM')::uuid,'IEM_SYSTEM','In-ear monitör sistemi',md5('marketplace:LIVE_SOUND')::uuid,8,true),
(md5('marketplace:PORTABLE_PA')::uuid,'PORTABLE_PA','Taşınabilir ses sistemi',md5('marketplace:LIVE_SOUND')::uuid,9,true),
(md5('marketplace:DI_STAGEBOX')::uuid,'DI_STAGEBOX','DI box / splitter / stagebox',md5('marketplace:LIVE_SOUND')::uuid,10,true),
(md5('marketplace:LIGHTING_STAGE')::uuid,'LIGHTING_STAGE','Işık ve Sahne Ekipmanı',NULL,11,true),
(md5('marketplace:STAGE_LIGHT')::uuid,'STAGE_LIGHT','Sahne ışığı / LED PAR',md5('marketplace:LIGHTING_STAGE')::uuid,0,true),
(md5('marketplace:MOVING_HEAD')::uuid,'MOVING_HEAD','Moving head / efekt ışığı',md5('marketplace:LIGHTING_STAGE')::uuid,1,true),
(md5('marketplace:DMX_CONTROL')::uuid,'DMX_CONTROL','DMX kontrolcü / arayüz',md5('marketplace:LIGHTING_STAGE')::uuid,2,true),
(md5('marketplace:STAGE_EFFECT')::uuid,'STAGE_EFFECT','Sis / haze / sahne efekt cihazı',md5('marketplace:LIGHTING_STAGE')::uuid,3,true),
(md5('marketplace:TRUSS_STAGE')::uuid,'TRUSS_STAGE','Truss / sahne platformu',md5('marketplace:LIGHTING_STAGE')::uuid,4,true),
(md5('marketplace:RIGGING_STAND')::uuid,'RIGGING_STAND','Işık sehpası / askı ekipmanı',md5('marketplace:LIGHTING_STAGE')::uuid,5,true),
(md5('marketplace:POWER_DISTRIBUTION')::uuid,'POWER_DISTRIBUTION','Sahne güç dağıtımı',md5('marketplace:LIGHTING_STAGE')::uuid,6,true),
(md5('marketplace:ACCESSORIES')::uuid,'ACCESSORIES','Aksesuar ve Yedek Parça',NULL,12,true),
(md5('marketplace:INSTRUMENT_STRINGS')::uuid,'INSTRUMENT_STRINGS','Enstrüman teli',md5('marketplace:ACCESSORIES')::uuid,0,true),
(md5('marketplace:PICKS_CAPOS_SLIDES')::uuid,'PICKS_CAPOS_SLIDES','Pena / kapo / slide',md5('marketplace:ACCESSORIES')::uuid,1,true),
(md5('marketplace:STRAPS')::uuid,'STRAPS','Askı / kayış',md5('marketplace:ACCESSORIES')::uuid,2,true),
(md5('marketplace:TUNER_METRONOME')::uuid,'TUNER_METRONOME','Akort cihazı / metronom',md5('marketplace:ACCESSORIES')::uuid,3,true),
(md5('marketplace:CASES_BAGS')::uuid,'CASES_BAGS','Kılıf / çanta / hardcase',md5('marketplace:ACCESSORIES')::uuid,4,true),
(md5('marketplace:STANDS_BENCHES')::uuid,'STANDS_BENCHES','Enstrüman standı / sehpa / tabure',md5('marketplace:ACCESSORIES')::uuid,5,true),
(md5('marketplace:MUSIC_STANDS')::uuid,'MUSIC_STANDS','Nota sehpası',md5('marketplace:ACCESSORIES')::uuid,6,true),
(md5('marketplace:CABLES_CONNECTORS')::uuid,'CABLES_CONNECTORS','Kablo / adaptör / konnektör',md5('marketplace:ACCESSORIES')::uuid,7,true),
(md5('marketplace:MIC_ACCESSORIES')::uuid,'MIC_ACCESSORIES','Mikrofon standı / filtre / aksesuar',md5('marketplace:ACCESSORIES')::uuid,8,true),
(md5('marketplace:DRUM_ACCESSORIES')::uuid,'DRUM_ACCESSORIES','Baget / fırça / davul derisi',md5('marketplace:ACCESSORIES')::uuid,9,true),
(md5('marketplace:WIND_ACCESSORIES')::uuid,'WIND_ACCESSORIES','Kamış / ağızlık / nefesli aksesuarı',md5('marketplace:ACCESSORIES')::uuid,10,true),
(md5('marketplace:PARTS_PICKUPS')::uuid,'PARTS_PICKUPS','Manyetik / elektronik / yedek parça',md5('marketplace:ACCESSORIES')::uuid,11,true),
(md5('marketplace:RACKS_FLIGHTCASES')::uuid,'RACKS_FLIGHTCASES','Rack / flightcase',md5('marketplace:ACCESSORIES')::uuid,12,true),
(md5('marketplace:MAINTENANCE_TOOLS')::uuid,'MAINTENANCE_TOOLS','Bakım ürünü / aleti',md5('marketplace:ACCESSORIES')::uuid,13,true),
(md5('marketplace:KEYBOARD_PEDALS')::uuid,'KEYBOARD_PEDALS','Klavye pedalı / aksesuarı',md5('marketplace:ACCESSORIES')::uuid,14,true)
ON CONFLICT(code) DO UPDATE SET name=EXCLUDED.name,parent_id=EXCLUDED.parent_id,sort_order=EXCLUDED.sort_order;
-- CATALOG_SEED_END
COMMIT;
