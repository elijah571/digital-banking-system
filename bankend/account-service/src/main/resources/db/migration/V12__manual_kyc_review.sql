ALTER TABLE account_verification
  MODIFY provider VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
  ADD COLUMN identity_document_image LONGTEXT NULL,
  ADD COLUMN identity_face_video LONGTEXT NULL,
  ADD COLUMN upgrade_document_image LONGTEXT NULL,
  ADD COLUMN upgrade_face_video LONGTEXT NULL,
  ADD COLUMN first_name VARCHAR(80) NULL,
  ADD COLUMN last_name VARCHAR(120) NULL,
  ADD COLUMN residential_address VARCHAR(500) NULL,
  ADD COLUMN review_reason VARCHAR(500) NULL,
  ADD COLUMN reviewed_by VARCHAR(255) NULL,
  ADD COLUMN reviewed_at DATETIME(6) NULL;
