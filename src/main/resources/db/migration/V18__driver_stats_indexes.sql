-- Driver dashboards count each driver's finished attempts by day, week, month and year
CREATE INDEX delivery_assignments_driver_delivered ON delivery_assignments (delivery_user_id, delivered_at)
    WHERE status = 'DELIVERED';
CREATE INDEX delivery_assignments_driver_failed ON delivery_assignments (delivery_user_id, failed_at)
    WHERE status = 'FAILED';
CREATE INDEX delivery_assignments_driver_assigned ON delivery_assignments (delivery_user_id, assigned_at);
CREATE INDEX return_pickups_driver_collected ON return_pickups (delivery_user_id, collected_at)
    WHERE status = 'COLLECTED';
CREATE INDEX return_pickups_driver_assigned ON return_pickups (delivery_user_id, assigned_at);
