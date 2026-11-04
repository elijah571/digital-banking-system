package org.elibanking.accountservice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevStackGuardTest {

    @Test
    void theDevStackPortsAreRefused() {
        assertFalse(DevStackGuard.problems("kafka", "localhost:9092").isEmpty());
        assertFalse(DevStackGuard.problems("mysql", "localhost:3306").isEmpty());
        assertFalse(DevStackGuard.problems("redis", "localhost:6379").isEmpty());
    }

    @Test
    void aListWithOneDevStackAddressIsRefused() {
        assertTrue(DevStackGuard.problems("kafka", "kafka:29092,localhost:9092").size() == 1);
    }

    @Test
    void testcontainersPortsAreAccepted() {
        assertTrue(DevStackGuard.problems("kafka", "localhost:32824").isEmpty());
        assertTrue(DevStackGuard.problems("kafka", "kafka:29092").isEmpty());
        assertTrue(DevStackGuard.problems("kafka", "").isEmpty());
        assertTrue(DevStackGuard.problems("kafka", null).isEmpty());
    }

    @Test
    void theJdbcUrlHostPortIsExtracted() {
        assertEquals("localhost:3306",
                DevStackGuard.hostPortOfJdbcUrl("jdbc:mysql://localhost:3306/test?useSSL=false"));
        assertEquals("localhost:32802",
                DevStackGuard.hostPortOfJdbcUrl("jdbc:mysql://localhost:32802/test?useSSL=false"));
        assertEquals("", DevStackGuard.hostPortOfJdbcUrl(null));
    }

    @Test
    void aDevMySqlJdbcUrlIsRefused() {
        String hostPort = DevStackGuard.hostPortOfJdbcUrl("jdbc:mysql://localhost:3306/account_db");
        assertFalse(DevStackGuard.problems("datasource", hostPort).isEmpty());
    }
}
