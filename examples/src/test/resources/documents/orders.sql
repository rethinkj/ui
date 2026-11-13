SELECT id, customer, total
FROM orders
WHERE placed > now() - interval '7 days'
ORDER BY total DESC;
