-- Runtime override for the text typed into the terminal prompt after an image upload
-- ({path} stands for the file's path inside the container). Lets an operator wrap the
-- path, e.g. in quotes, so a REPL does not read a leading "/" as a slash command.
-- NULL keeps the application.properties default.
ALTER TABLE runtime_settings ADD COLUMN terminal_image_path_template text;
