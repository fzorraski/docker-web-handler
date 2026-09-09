-- Runtime override for the directory inside the container that receives images pasted or
-- dropped into the terminal. NULL keeps the application.properties default (/tmp).
ALTER TABLE runtime_settings ADD COLUMN terminal_image_upload_path text;
