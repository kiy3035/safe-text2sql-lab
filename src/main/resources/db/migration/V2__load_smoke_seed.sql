INSERT INTO categories (category_id, name) VALUES
    (1, 'BOOKS'),
    (2, 'ELECTRONICS'),
    (3, 'HOME'),
    (4, 'SPORTS'),
    (5, 'FOOD');

INSERT INTO products (product_id, category_id, name, price, active)
SELECT product_id,
       ((product_id - 1) % 5) + 1,
       'Synthetic Product ' || lpad(product_id::text, 2, '0'),
       (1000 + product_id * 375)::numeric(12, 2),
       product_id % 7 <> 0
FROM generate_series(1, 20) AS product_id;

INSERT INTO customers (customer_id, region, grade, created_at, email, phone)
SELECT customer_id,
       (ARRAY['SEOUL', 'BUSAN', 'DAEGU', 'INCHEON', 'GWANGJU'])[((customer_id - 1) % 5) + 1],
       (ARRAY['BASIC', 'SILVER', 'GOLD'])[((customer_id - 1) % 3) + 1],
       TIMESTAMPTZ '2025-01-01 00:00:00+00' + customer_id * INTERVAL '1 day',
       'synthetic' || customer_id || '@example.invalid',
       '000-0000-' || lpad(customer_id::text, 4, '0')
FROM generate_series(1, 50) AS customer_id;

INSERT INTO orders (order_id, customer_id, status, ordered_at, total_amount)
SELECT order_id,
       ((order_id - 1) % 50) + 1,
       (ARRAY['PAID', 'SHIPPED', 'CANCELLED', 'PENDING'])[((order_id - 1) % 4) + 1],
       TIMESTAMPTZ '2026-07-01 00:00:00+00' + (order_id - 1) * INTERVAL '3 hour',
       (5000 + order_id * 125)::numeric(14, 2)
FROM generate_series(1, 100) AS order_id;

INSERT INTO order_items (order_item_id, order_id, product_id, quantity, unit_price)
SELECT order_id,
       order_id,
       ((order_id - 1) % 20) + 1,
       ((order_id - 1) % 3) + 1,
       (1000 + (((order_id - 1) % 20) + 1) * 375)::numeric(12, 2)
FROM generate_series(1, 100) AS order_id;

INSERT INTO payments (payment_id, order_id, status, paid_at, amount)
SELECT order_id,
       order_id,
       CASE WHEN order_id % 4 IN (1, 2) THEN 'COMPLETED' ELSE 'NOT_COMPLETED' END,
       CASE
           WHEN order_id % 4 IN (1, 2)
               THEN TIMESTAMPTZ '2026-07-01 01:00:00+00' + (order_id - 1) * INTERVAL '3 hour'
           ELSE NULL
       END,
       (5000 + order_id * 125)::numeric(14, 2)
FROM generate_series(1, 100) AS order_id;

INSERT INTO admin_users (admin_id, email, password_hash)
VALUES (1, 'synthetic-admin@example.invalid', 'not-a-real-password-hash');

INSERT INTO audit_logs (audit_id, event_type, occurred_at, details)
VALUES (1, 'SYNTHETIC_SEED_CREATED', TIMESTAMPTZ '2026-01-01 00:00:00+00', '{"synthetic": true}');
