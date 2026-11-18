-- Keep verification files within the application upload policy at the database boundary too.
ALTER TABLE shop_documents
    ADD CONSTRAINT chk_shop_documents_max_size CHECK (size_bytes <= 5242880),
    ADD CONSTRAINT chk_shop_documents_content_type CHECK (content_type IN ('image/jpeg', 'image/png', 'application/pdf')),
    ADD KEY idx_shop_documents_shop_uploader_uploaded (shop_id, uploaded_by, uploaded_at);
