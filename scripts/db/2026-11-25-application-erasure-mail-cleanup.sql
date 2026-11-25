-- Application-mail jobs retain the applicant's contact details. Delete the
-- matching polymorphic children with their application, regardless of which
-- profile or onboarding path initiated the deletion.
BEGIN;

CREATE OR REPLACE FUNCTION soundconnect_delete_application_mail_intents()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME = 'tbl_venue_applications' THEN
        DELETE FROM tbl_application_mail_intent
         WHERE application_id = OLD.id AND purpose = 'VENUE_APPLICATION_ADMIN';
    ELSIF TG_TABLE_NAME = 'tbl_studio_applications' THEN
        DELETE FROM tbl_application_mail_intent
         WHERE application_id = OLD.id
           AND purpose IN ('STUDIO_APPLICATION_ADMIN', 'STUDIO_APPLICATION_DECISION');
    END IF;
    RETURN NULL;
END
$$;

DROP TRIGGER IF EXISTS delete_application_mail_intents ON tbl_venue_applications;
CREATE TRIGGER delete_application_mail_intents AFTER DELETE ON tbl_venue_applications
    FOR EACH ROW EXECUTE FUNCTION soundconnect_delete_application_mail_intents();

DROP TRIGGER IF EXISTS delete_application_mail_intents ON tbl_studio_applications;
CREATE TRIGGER delete_application_mail_intents AFTER DELETE ON tbl_studio_applications
    FOR EACH ROW EXECUTE FUNCTION soundconnect_delete_application_mail_intents();

COMMIT;
