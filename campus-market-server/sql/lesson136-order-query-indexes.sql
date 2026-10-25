-- Composite indexes for exact participant + status lists and their pagination order.
ALTER TABLE market_orders ADD INDEX idx_order_buyer_status_created(buyer_id,status,created_at DESC,id DESC), ADD INDEX idx_order_seller_status_created(seller_id,status,created_at DESC,id DESC);
