INSERT INTO orders (order_id, customer_id, status, ordered_at, total_amount)
SELECT order_id,
       ((order_id - 1) % 50) + 1,
       (ARRAY['PAID', 'SHIPPED', 'CANCELLED', 'PENDING'])[((order_id - 1) % 4) + 1],
       TIMESTAMPTZ '2024-01-01 00:00:00+00' + (order_id - 1001) * INTERVAL '10 minute',
       (5000 + (order_id % 1000) * 125)::numeric(14, 2)
FROM generate_series(1001, 51000) AS order_id
ON CONFLICT (order_id) DO NOTHING;

INSERT INTO order_items (order_item_id, order_id, product_id, quantity, unit_price)
SELECT order_id,
       order_id,
       ((order_id - 1) % 20) + 1,
       ((order_id - 1) % 3) + 1,
       (1000 + (((order_id - 1) % 20) + 1) * 375)::numeric(12, 2)
FROM generate_series(1001, 51000) AS order_id
ON CONFLICT (order_item_id) DO NOTHING;

INSERT INTO payments (payment_id, order_id, status, paid_at, amount)
SELECT order_id,
       order_id,
       CASE WHEN order_id % 4 IN (1, 2) THEN 'COMPLETED' ELSE 'NOT_COMPLETED' END,
       CASE
           WHEN order_id % 4 IN (1, 2)
               THEN TIMESTAMPTZ '2024-01-01 01:00:00+00' + (order_id - 1001) * INTERVAL '10 minute'
           ELSE NULL
       END,
       (5000 + (order_id % 1000) * 125)::numeric(14, 2)
FROM generate_series(1001, 51000) AS order_id
ON CONFLICT (payment_id) DO NOTHING;
