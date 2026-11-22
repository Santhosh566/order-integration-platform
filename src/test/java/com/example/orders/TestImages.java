package com.example.orders;

import org.testcontainers.utility.DockerImageName;

/** Docker images the tests run, the same as compose.yaml. */
final class TestImages {

    /** PostgreSQL 15 with PostGIS (V28 creates the extension) */
    static final DockerImageName POSTGRES =
            DockerImageName.parse("postgis/postgis:15-3.5-alpine").asCompatibleSubstituteFor("postgres");

    private TestImages() {}
}
