ALTER TABLE crypto_asset ADD COLUMN provider_asset_id VARCHAR(100) NULL AFTER fireblocks_asset_id;

UPDATE crypto_asset SET provider_asset_id=fireblocks_asset_id WHERE provider_asset_id IS NULL;

ALTER TABLE crypto_asset MODIFY provider_asset_id VARCHAR(100) NOT NULL;
