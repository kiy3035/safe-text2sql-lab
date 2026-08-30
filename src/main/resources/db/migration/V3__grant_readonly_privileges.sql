REVOKE ALL ON SCHEMA analytics FROM PUBLIC;
GRANT USAGE ON SCHEMA analytics TO text2sql_ro;

REVOKE ALL ON ALL TABLES IN SCHEMA analytics FROM text2sql_ro;

GRANT SELECT (category_id, name) ON categories TO text2sql_ro;
GRANT SELECT (product_id, category_id, name, price, active) ON products TO text2sql_ro;
GRANT SELECT (customer_id, region, grade, created_at) ON customers TO text2sql_ro;
GRANT SELECT (order_id, customer_id, status, ordered_at, total_amount) ON orders TO text2sql_ro;
GRANT SELECT (order_item_id, order_id, product_id, quantity, unit_price) ON order_items TO text2sql_ro;
GRANT SELECT (payment_id, order_id, status, paid_at, amount) ON payments TO text2sql_ro;

ALTER DEFAULT PRIVILEGES IN SCHEMA analytics REVOKE ALL ON TABLES FROM text2sql_ro;
