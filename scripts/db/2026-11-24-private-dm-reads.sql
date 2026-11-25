ALTER TABLE tbl_dm_message
    ADD COLUMN IF NOT EXISTS read_receipt_hidden boolean NOT NULL DEFAULT false;
