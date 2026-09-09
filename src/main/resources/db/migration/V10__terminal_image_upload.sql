-- Separate runtime override for pasting/dropping images into the terminal, so image
-- attachments (used to hand screenshots to a REPL) can be enabled without opening the
-- general file upload, and vice versa. NULL keeps the application.properties default.
ALTER TABLE runtime_settings ADD COLUMN terminal_image_upload_enabled boolean;
