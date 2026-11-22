-- Live tracking of our own drivers while a parcel is out for delivery, with PostGIS.
--
-- Privacy: a position is accepted only while the driver has an order OUT_FOR_DELIVERY, a customer sees it only
-- for their own order and only during that delivery, and the trail is deleted after a week
-- (orders.live-tracking.trail-kept-for). It is kept that long only to settle "the driver never came".
--
-- Positions are geography(Point, 4326): longitude and latitude on the WGS 84 globe (what phones report), so
-- ST_Distance and ST_DWithin measure real metres on the Earth. ST_MakePoint takes LONGITUDE FIRST.

CREATE EXTENSION IF NOT EXISTS postgis;

-- The centre of each PIN code: the median of its post offices' positions in the India Post directory.
-- The customer's end of the map, the arrival estimate and the "in your area" alert use it; null when unknown.
ALTER TABLE pin_codes ADD COLUMN centre geography(Point, 4326);
CREATE INDEX pin_codes_centre ON pin_codes USING gist (centre);

-- Where each driver was last seen; one row per driver, overwritten by every new position
CREATE TABLE driver_locations (
    driver_id    VARCHAR(255)  PRIMARY KEY REFERENCES app_users (user_id),
    location     geography(Point, 4326) NOT NULL,
    -- How far off the phone says it may be, in metres
    accuracy_m   NUMERIC(7, 1) CHECK (accuracy_m >= 0),
    speed_mps    NUMERIC(6, 2) CHECK (speed_mps >= 0),
    heading_deg  NUMERIC(5, 1) CHECK (heading_deg >= 0 AND heading_deg < 360),
    -- When the phone measured it, and when it reached us
    recorded_at  TIMESTAMP(6)  NOT NULL,
    received_at  TIMESTAMP(6)  NOT NULL DEFAULT now()
);

CREATE INDEX driver_locations_location ON driver_locations USING gist (location);

-- Every position, for the route a driver took
CREATE TABLE driver_location_trail (
    point_id     BIGSERIAL     PRIMARY KEY,
    driver_id    VARCHAR(255)  NOT NULL REFERENCES app_users (user_id),
    location     geography(Point, 4326) NOT NULL,
    accuracy_m   NUMERIC(7, 1),
    recorded_at  TIMESTAMP(6)  NOT NULL,
    received_at  TIMESTAMP(6)  NOT NULL DEFAULT now()
);

CREATE INDEX driver_location_trail_by_driver ON driver_location_trail (driver_id, recorded_at);
-- The clean-up job's search
CREATE INDEX driver_location_trail_by_age ON driver_location_trail (received_at);

-- The customer was told the driver is in their area, once per delivery attempt
ALTER TABLE delivery_assignments ADD COLUMN nearby_notified_at TIMESTAMP(6);
