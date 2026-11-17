-- Days nothing is delivered. A national holiday (state_code null) also stops dispatch from the hubs;
-- a state's holiday only stops deliveries in that state. Sundays are never working days and are not listed.

CREATE TABLE public_holidays (
    holiday_id BIGSERIAL    PRIMARY KEY,
    day        DATE         NOT NULL,
    name       VARCHAR(100) NOT NULL,
    -- GST state code, as in IndianState; null for the whole country
    state_code CHAR(2),
    created_at TIMESTAMP(6) NOT NULL DEFAULT now(),
    created_by VARCHAR(255)
);

-- Once per day for the country and once per day for each state
CREATE UNIQUE INDEX public_holidays_once ON public_holidays (day, coalesce(state_code, '00'));

-- The three national holidays, fixed by law. Festivals move every year (Diwali, Holi, Eid, ...): an admin
-- adds them under Delivery holidays once the government publishes the year's list.
INSERT INTO public_holidays (day, name, created_by) VALUES
    ('2026-01-26', 'Republic Day', 'flyway'),
    ('2026-08-15', 'Independence Day', 'flyway'),
    ('2026-10-02', 'Gandhi Jayanti', 'flyway'),
    ('2027-01-26', 'Republic Day', 'flyway'),
    ('2027-08-15', 'Independence Day', 'flyway'),
    ('2027-10-02', 'Gandhi Jayanti', 'flyway'),
    ('2028-01-26', 'Republic Day', 'flyway'),
    ('2028-08-15', 'Independence Day', 'flyway'),
    ('2028-10-02', 'Gandhi Jayanti', 'flyway');
