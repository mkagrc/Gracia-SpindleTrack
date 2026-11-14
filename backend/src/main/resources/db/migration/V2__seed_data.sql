INSERT INTO users (id, full_name, email, mobile_number, role, is_active) VALUES
    (1, 'Sample Owner', 'owner@spindletrack.test', '09170000001', 'OWNER', TRUE),
    (2, 'Sample Staff One', 'staff1@spindletrack.test', '09170000002', 'STAFF', TRUE),
    (3, 'Sample Staff Two', 'staff2@spindletrack.test', '09170000003', 'STAFF', TRUE),
    (4, 'Sample Customer One', 'customer1@spindletrack.test', '09170000004', 'CUSTOMER', TRUE),
    (5, 'Sample Customer Two', 'customer2@spindletrack.test', '09170000005', 'CUSTOMER', TRUE),
    (6, 'Sample Customer Three', 'customer3@spindletrack.test', '09170000006', 'CUSTOMER', TRUE);

INSERT INTO services (id, name, description, is_active) VALUES
    (1, 'Wash', 'Standard wash service', TRUE),
    (2, 'Dry', 'Drying service', TRUE),
    (3, 'Fold', 'Folded laundry service', TRUE);

INSERT INTO laundry_orders
    (id, order_code, customer_name, customer_mobile, account_user_id, service_id, kilos, total_price, currency_code, stage, payment_timing, notes, created_by_user_id, created_at, updated_at, ready_at, claimed_at, unclaimed_flagged_at)
VALUES
    (1, 'ST-7K4Q9M2X', 'Alex Rivera', '09170000004', 4, 1, 3.50, 280.00, 'PHP', 'RECEIVED', 'PAY_AT_PICKUP', NULL, 2, NOW() - INTERVAL 2 HOUR, NOW() - INTERVAL 2 HOUR, NULL, NULL, NULL),
    (2, 'ST-3P8D6N5V', 'Jamie Santos', '09170000005', 5, 2, 4.00, 320.00, 'PHP', 'WASHING', 'PAY_AT_PICKUP', 'Separate light items', 2, NOW() - INTERVAL 1 DAY, NOW() - INTERVAL 20 HOUR, NULL, NULL, NULL),
    (3, 'ST-9R2C5W8J', 'Morgan Lee', '09170000006', 6, 3, 2.75, 210.00, 'PHP', 'DRYING', 'PAY_NOW', NULL, 3, NOW() - INTERVAL 2 DAY, NOW() - INTERVAL 18 HOUR, NULL, NULL, NULL),
    (4, 'ST-4H6T1B9Y', 'Taylor Cruz', '09170000007', NULL, 1, 5.25, 420.00, 'PHP', 'FOLDING', 'PAY_AT_PICKUP', NULL, 3, NOW() - INTERVAL 3 DAY, NOW() - INTERVAL 8 HOUR, NULL, NULL, NULL),
    (5, 'ST-8L3F7A2K', 'Casey Lim', '09170000008', NULL, 2, 6.00, 510.00, 'PHP', 'READY', 'PAY_AT_PICKUP', NULL, 2, NOW() - INTERVAL 4 DAY, NOW() - INTERVAL 3 DAY, NOW() - INTERVAL 3 DAY, NULL, NULL),
    (6, 'ST-2M9J4D6Q', 'Riley Tan', '09170000009', NULL, 3, 1.80, 160.00, 'PHP', 'CLAIMED', 'PAY_NOW', NULL, 2, NOW() - INTERVAL 5 DAY, NOW() - INTERVAL 4 DAY, NOW() - INTERVAL 4 DAY, NOW() - INTERVAL 3 DAY, NULL),
    (7, 'ST-5C1V8P3R', 'Avery Dela Cruz', '09170000010', 4, 1, 7.10, 620.00, 'PHP', 'READY', 'PAY_NOW', NULL, 3, NOW() - INTERVAL 6 DAY, NOW() - INTERVAL 4 DAY, NOW() - INTERVAL 4 DAY, NULL, NULL),
    (8, 'ST-6N2Y9G4W', 'Jordan Reyes', '09170000011', 5, 2, 3.20, 250.00, 'PHP', 'WASHING', 'PAY_AT_PICKUP', NULL, 2, NOW() - INTERVAL 8 HOUR, NOW() - INTERVAL 6 HOUR, NULL, NULL, NULL),
    (9, 'ST-1B7K3T8H', 'Quinn Garcia', '09170000012', NULL, 3, 4.60, 390.00, 'PHP', 'CLAIMED', 'PAY_AT_PICKUP', NULL, 3, NOW() - INTERVAL 8 DAY, NOW() - INTERVAL 6 DAY, NOW() - INTERVAL 6 DAY, NOW() - INTERVAL 5 DAY, NULL),
    (10, 'ST-4D8W2M6C', 'Skyler Wong', '09170000013', 6, 1, 2.40, 190.00, 'PHP', 'RECEIVED', 'PAY_AT_PICKUP', NULL, 2, NOW() - INTERVAL 1 HOUR, NOW() - INTERVAL 1 HOUR, NULL, NULL, NULL);

INSERT INTO order_status_history (order_id, previous_stage, new_stage, changed_by_user_id, changed_at) VALUES
    (1, NULL, 'RECEIVED', 2, NOW() - INTERVAL 2 HOUR),
    (2, NULL, 'RECEIVED', 2, NOW() - INTERVAL 1 DAY),
    (2, 'RECEIVED', 'WASHING', 2, NOW() - INTERVAL 20 HOUR),
    (3, NULL, 'RECEIVED', 3, NOW() - INTERVAL 2 DAY),
    (3, 'RECEIVED', 'WASHING', 3, NOW() - INTERVAL 1 DAY),
    (3, 'WASHING', 'DRYING', 3, NOW() - INTERVAL 18 HOUR),
    (4, NULL, 'RECEIVED', 3, NOW() - INTERVAL 3 DAY),
    (4, 'RECEIVED', 'WASHING', 3, NOW() - INTERVAL 2 DAY),
    (4, 'WASHING', 'DRYING', 3, NOW() - INTERVAL 1 DAY),
    (4, 'DRYING', 'FOLDING', 3, NOW() - INTERVAL 8 HOUR),
    (5, NULL, 'RECEIVED', 2, NOW() - INTERVAL 4 DAY),
    (5, 'RECEIVED', 'WASHING', 2, NOW() - INTERVAL 3 DAY - INTERVAL 18 HOUR),
    (5, 'WASHING', 'DRYING', 2, NOW() - INTERVAL 3 DAY - INTERVAL 12 HOUR),
    (5, 'DRYING', 'FOLDING', 2, NOW() - INTERVAL 3 DAY - INTERVAL 6 HOUR),
    (5, 'FOLDING', 'READY', 2, NOW() - INTERVAL 3 DAY),
    (6, NULL, 'RECEIVED', 2, NOW() - INTERVAL 5 DAY),
    (6, 'RECEIVED', 'WASHING', 2, NOW() - INTERVAL 4 DAY - INTERVAL 18 HOUR),
    (6, 'WASHING', 'DRYING', 2, NOW() - INTERVAL 4 DAY - INTERVAL 12 HOUR),
    (6, 'DRYING', 'FOLDING', 2, NOW() - INTERVAL 4 DAY - INTERVAL 6 HOUR),
    (6, 'FOLDING', 'READY', 2, NOW() - INTERVAL 4 DAY),
    (6, 'READY', 'CLAIMED', 2, NOW() - INTERVAL 3 DAY),
    (7, NULL, 'RECEIVED', 3, NOW() - INTERVAL 6 DAY),
    (7, 'RECEIVED', 'WASHING', 3, NOW() - INTERVAL 5 DAY - INTERVAL 18 HOUR),
    (7, 'WASHING', 'DRYING', 3, NOW() - INTERVAL 5 DAY - INTERVAL 12 HOUR),
    (7, 'DRYING', 'FOLDING', 3, NOW() - INTERVAL 5 DAY - INTERVAL 6 HOUR),
    (7, 'FOLDING', 'READY', 3, NOW() - INTERVAL 4 DAY),
    (8, NULL, 'RECEIVED', 2, NOW() - INTERVAL 8 HOUR),
    (8, 'RECEIVED', 'WASHING', 2, NOW() - INTERVAL 6 HOUR),
    (9, NULL, 'RECEIVED', 3, NOW() - INTERVAL 8 DAY),
    (9, 'RECEIVED', 'WASHING', 3, NOW() - INTERVAL 7 DAY - INTERVAL 18 HOUR),
    (9, 'WASHING', 'DRYING', 3, NOW() - INTERVAL 7 DAY - INTERVAL 12 HOUR),
    (9, 'DRYING', 'FOLDING', 3, NOW() - INTERVAL 7 DAY - INTERVAL 6 HOUR),
    (9, 'FOLDING', 'READY', 3, NOW() - INTERVAL 6 DAY),
    (9, 'READY', 'CLAIMED', 3, NOW() - INTERVAL 5 DAY),
    (10, NULL, 'RECEIVED', 2, NOW() - INTERVAL 1 HOUR);

INSERT INTO order_price_log (order_id, old_price, new_price, changed_by_user_id, changed_at) VALUES
    (1, NULL, 280.00, 2, NOW() - INTERVAL 2 HOUR),
    (2, NULL, 320.00, 2, NOW() - INTERVAL 1 DAY),
    (3, NULL, 210.00, 3, NOW() - INTERVAL 2 DAY),
    (4, NULL, 420.00, 3, NOW() - INTERVAL 3 DAY),
    (5, NULL, 510.00, 2, NOW() - INTERVAL 4 DAY),
    (6, NULL, 160.00, 2, NOW() - INTERVAL 5 DAY),
    (7, NULL, 620.00, 3, NOW() - INTERVAL 6 DAY),
    (8, NULL, 250.00, 2, NOW() - INTERVAL 8 HOUR),
    (9, NULL, 390.00, 3, NOW() - INTERVAL 8 DAY),
    (10, NULL, 190.00, 2, NOW() - INTERVAL 1 HOUR);

INSERT INTO payments (order_id, method, status, amount, currency_code, external_reference, created_by_user_id, created_at, paid_at) VALUES
    (1, 'CASH', 'UNPAID', 280.00, 'PHP', NULL, 2, NOW() - INTERVAL 2 HOUR, NULL),
    (2, 'CASH', 'UNPAID', 320.00, 'PHP', NULL, 2, NOW() - INTERVAL 1 DAY, NULL),
    (3, 'ONLINE', 'PENDING', 210.00, 'PHP', 'TEST-PAYMENT-0003', 3, NOW() - INTERVAL 2 DAY, NULL),
    (4, 'CASH', 'UNPAID', 420.00, 'PHP', NULL, 3, NOW() - INTERVAL 3 DAY, NULL),
    (5, 'CASH', 'UNPAID', 510.00, 'PHP', NULL, 2, NOW() - INTERVAL 4 DAY, NULL),
    (6, 'ONLINE', 'PAID', 160.00, 'PHP', 'TEST-PAYMENT-0006', 2, NOW() - INTERVAL 5 DAY, NOW() - INTERVAL 3 DAY),
    (7, 'ONLINE', 'PAID', 620.00, 'PHP', 'TEST-PAYMENT-0007', 3, NOW() - INTERVAL 6 DAY, NOW() - INTERVAL 5 DAY),
    (8, 'CASH', 'PAID', 250.00, 'PHP', NULL, 2, NOW() - INTERVAL 8 HOUR, NOW() - INTERVAL 5 HOUR),
    (9, 'CASH', 'PAID', 390.00, 'PHP', NULL, 3, NOW() - INTERVAL 8 DAY, NOW() - INTERVAL 5 DAY),
    (10, 'CASH', 'UNPAID', 190.00, 'PHP', NULL, 2, NOW() - INTERVAL 1 HOUR, NULL);
