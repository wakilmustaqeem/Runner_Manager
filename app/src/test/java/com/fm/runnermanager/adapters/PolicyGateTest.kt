package com.fm.runnermanager.adapters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyGateTest {
    private val safePolicy = RunnerPolicy(
        mainsPowerOnly = true,
        minimumBatteryPercent = 30,
        maximumTemperatureCelsius = 42f,
        minimumFreeStorageBytes = 1_000L
    )

    private val safeConditions = DeviceConditions(
        isCharging = true,
        batteryPercent = 80,
        temperatureCelsius = 35f,
        freeStorageBytes = 10_000L
    )

    @Test
    fun allowsConditionsAtSafeValues() {
        val result = PolicyGate.evaluate(safePolicy, safeConditions)
        assertTrue(result.allowed)
        assertTrue(result.reasons.isEmpty())
    }

    @Test
    fun deniesWhenNotChargingAndBatteryTooLow() {
        val conditions = safeConditions.copy(isCharging = false, batteryPercent = 29)
        val result = PolicyGate.evaluate(safePolicy, conditions)
        assertFalse(result.allowed)
        assertTrue(result.reasons.contains("Mains power required"))
        assertTrue(result.reasons.contains("Battery below minimum"))
    }

    @Test
    fun deniesWhenTemperatureIsUnavailableForConfiguredLimit() {
        val result = PolicyGate.evaluate(safePolicy, safeConditions.copy(temperatureCelsius = null))
        assertFalse(result.allowed)
        assertTrue(result.reasons.contains("Temperature unavailable; fail closed"))
    }

    @Test
    fun deniesWhenStorageIsBelowMinimum() {
        val result = PolicyGate.evaluate(safePolicy, safeConditions.copy(freeStorageBytes = 999L))
        assertFalse(result.allowed)
        assertTrue(result.reasons.contains("Free storage below minimum"))
    }

    @Test
    fun deniesInvalidPolicyAndDeviceReadings() {
        val policy = safePolicy.copy(minimumBatteryPercent = 101, minimumFreeStorageBytes = -1L)
        val conditions = safeConditions.copy(batteryPercent = -1, freeStorageBytes = -1L)
        val result = PolicyGate.evaluate(policy, conditions)
        assertFalse(result.allowed)
        assertTrue(result.reasons.contains("Invalid battery threshold"))
        assertTrue(result.reasons.contains("Invalid storage threshold"))
        assertTrue(result.reasons.contains("Invalid battery reading"))
        assertTrue(result.reasons.contains("Invalid storage reading"))
    }

    @Test
    fun deniesWhenTemperatureExceedsLimit() {
        val result = PolicyGate.evaluate(safePolicy, safeConditions.copy(temperatureCelsius = 42.1f))
        assertFalse(result.allowed)
        assertEquals(listOf("Temperature above limit"), result.reasons)
    }
}
