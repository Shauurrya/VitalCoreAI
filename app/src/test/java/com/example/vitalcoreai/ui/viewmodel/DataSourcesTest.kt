package com.example.vitalcoreai.ui.viewmodel

import android.content.Context
import android.content.SharedPreferences
import androidx.biometric.BiometricManager
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import com.example.vitalcoreai.data.db.dao.SyncStateDao
import com.example.vitalcoreai.data.db.entity.SyncStateEntity
import com.example.vitalcoreai.data.db.entity.DailyMetricsEntity
import com.example.vitalcoreai.data.healthconnect.HealthConnectManager
import com.example.vitalcoreai.data.repository.HealthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.*

class DataSourcePresentationTest {
    private val now = 1_800_000_000_000L
    private fun read(outcome: String? = "SUCCESS_DATA") = SyncStateEntity(
        recordType = "StepsRecord", lastSyncTimestampMs = now,
        outcome = outcome, lastSuccessfulReadMs = now - 1000,
        latestMeasurementMs = now - 3_600_000,
        sourcePackages = "app.source|app.source"
    )
    private fun present(
        value: SyncStateEntity? = read(), permission: Boolean? = true,
        available: Boolean? = true, expectDaily: Boolean = true
    ) = presentDataSource("StepsRecord", "Steps", available, permission, value, now, expectDaily) { "Source label" }

    @Test fun `live permission denial overrides a saved success`() {
        val result = present(permission = false)
        assertEquals(DataReadStatus.PERMISSION_DENIED, result.status)
        assertEquals(now - 1000, result.lastSuccessfulReadMs)
        assertTrue(result.nextAction.contains("allow"))
    }

    @Test fun `regranting access after denial requires a new read`() {
        assertEquals(DataReadStatus.NOT_READ, present(read("PERMISSION_DENIED")).status)
    }

    @Test fun `current denied access overrides an older unsupported read`() {
        val result = present(read("UNSUPPORTED"), permission = false)
        assertEquals(DataReadStatus.PERMISSION_DENIED, result.status)
        assertTrue(result.nextAction.contains("allow"))
    }

    @Test fun `legacy sync rows never establish successful reads`() {
        val result = present(SyncStateEntity("StepsRecord", now))
        assertEquals(DataReadStatus.NOT_READ, result.status)
        assertNull(result.lastSuccessfulReadMs)
        assertNull(result.latestMeasurementMs)
        assertTrue(result.sourceApps.isEmpty())
    }

    @Test fun `successful empty read remains distinct from failure and unsupported`() {
        assertEquals(DataReadStatus.EMPTY, present(read("SUCCESS_EMPTY")).status)
        assertEquals(DataReadStatus.FAILED, present(read("FAILED")).status)
        assertEquals(DataReadStatus.UNSUPPORTED, present(read("UNSUPPORTED")).status)
    }

    @Test fun `recent read of old data does not imply fresh measurements`() {
        val old = read().copy(latestMeasurementMs = now - 72L * 60 * 60 * 1000)
        val result = present(old)
        assertEquals(DataReadStatus.DELAYED, result.status)
        assertEquals(now - 1000, result.lastSuccessfulReadMs)
        assertEquals(old.latestMeasurementMs, result.latestMeasurementMs)
        assertTrue(result.nextAction.contains("source app"))
        assertEquals(DataReadStatus.READ, present(old, expectDaily = false).status)
    }

    @Test fun `source labels come from recorded origins with duplicates removed`() {
        assertEquals(listOf("Source label"), present().sourceApps)
    }

    @Test fun `unavailable platform and unknown access are separate states`() {
        assertEquals(DataReadStatus.UNAVAILABLE, present(available = false).status)
        assertEquals(DataReadStatus.UNKNOWN, present(available = null).status)
        assertEquals(DataReadStatus.UNKNOWN, present(permission = null).status)
    }

    @Test fun `sleep without stages is a dated missing field and stale sleep is not described as fresh`() {
        val metrics = dataSourceMetrics().copy(sleepStagesAvailable = false)
        val notes = dataSourceNotes("SleepSessionRecord", metrics)
        assertTrue(notes.single().contains("sleep stage breakdown"))
        assertTrue(notes.single().contains("2026"))
        assertTrue(dataSourceNotes("SleepSessionRecord", metrics.copy(staleRecordTypes = "SleepSessionRecord")).isEmpty())
        assertTrue(dataSourceNotes("SleepSessionRecord", metrics.copy(sleepStagesAvailable = true, sleepDeepMinutes = 60)).isEmpty())
    }

    @Test fun `derived resting heart rate is distinguished from a source reading`() {
        val metrics = dataSourceMetrics().copy(restingHR = 60, restingHRDerived = true)
        assertTrue(dataSourceNotes("RestingHeartRateRecord", metrics).single().contains("estimated"))
        assertTrue(dataSourceNotes("RestingHeartRateRecord", metrics.copy(staleRecordTypes = "HeartRateRecord")).isEmpty())
    }

    @Test fun `failed workout heart rate explains why saved workout values are stale`() {
        val metrics = dataSourceMetrics().copy(staleRecordTypes = "HeartRateRecord|ExerciseSessionRecord")
        val note = dataSourceNotes("ExerciseSessionRecord", metrics).single()
        assertTrue(note.contains("Workout heart rate could not be refreshed"))
        assertTrue(note.contains("excluded from fresh scores"))
        assertTrue(dataSourceNotes("ExerciseSessionRecord", dataSourceMetrics()).isEmpty())
    }

    @Test fun `read result separates unavailable failed partial and complete`() {
        fun result(successful: Boolean = true, partial: Boolean = false, unavailable: Boolean = false) =
            HealthRepository.SyncResult(null, emptyList(), successful, partial, unavailable)
        assertTrue(dataReadMessage(result(unavailable = true)).contains("unavailable"))
        assertTrue(dataReadMessage(result(successful = false)).contains("No metrics could be read"))
        assertTrue(dataReadMessage(result(partial = true)).contains("partly completed"))
        assertTrue(dataReadMessage(result()).contains("whether newer data arrived"))
    }

    @Test fun `history refresh reports partial counts without a false complete message`() {
        val result = historyRefreshMessage(HealthRepository.BackfillResult(20, 3, 2, false, 5))
        assertTrue(result.contains("20 refreshed"))
        assertTrue(result.contains("5 partial"))
        assertTrue(result.contains("20 refreshed (5 partial)"))
        assertTrue(result.contains("3 skipped"))
        assertTrue(result.contains("2 failed"))
        assertFalse(result.contains("refresh complete"))
    }

    @Test fun `history refresh unavailable never reports success`() {
        val result = historyRefreshMessage(HealthRepository.BackfillResult(unavailable = true))
        assertTrue(result.contains("not refreshed"))
        assertFalse(result.contains("complete"))
    }

    @Test fun `history refresh complete includes counts`() {
        val result = historyRefreshMessage(HealthRepository.BackfillResult(refreshedDays = 30))
        assertTrue(result.contains("refresh complete"))
        assertTrue(result.contains("30 refreshed"))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DataSourcesViewModelTest {
    @Before fun setup() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun teardown() = Dispatchers.resetMain()

    private fun repository(): HealthRepository = mock {
        on { metricsFrom(any()) } doReturn MutableStateFlow(emptyList())
    }

    @Test fun `returning from settings during a permission check queues a fresh snapshot`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val dao: SyncStateDao = mock { on { observeAll() } doReturn MutableStateFlow(emptyList()) }
        val manager: HealthConnectManager = mock { on { isAvailable() } doReturn true }
        val vm = DataSourcesViewModel(dao, manager, repository(), mock<Context>())
        val updatedPermissions = setOf(HealthPermission.getReadPermission(StepsRecord::class))
        whenever(manager.grantedPermissions()).thenAnswer {
            // Model a resume arriving while an older permission snapshot is being read.
            vm.refreshAccess()
            vm.refreshAccess()
            emptySet<String>()
        }.thenReturn(updatedPermissions)

        runCurrent()

        assertFalse(vm.state.value.isChecking)
        assertEquals(DataReadStatus.NOT_READ, vm.state.value.sources.first { it.recordType == "StepsRecord" }.status)
        assertEquals("1 of 16 metric permissions allowed", vm.state.value.connectionLabel)
        verify(manager, times(2)).grantedPermissions()
    }

    @Test fun `queued permission refresh recovers from the preceding check failure`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val dao: SyncStateDao = mock { on { observeAll() } doReturn MutableStateFlow(emptyList()) }
        val manager: HealthConnectManager = mock { on { isAvailable() } doReturn true }
        val vm = DataSourcesViewModel(dao, manager, repository(), mock<Context>())
        whenever(manager.grantedPermissions()).thenAnswer {
            vm.refreshAccess()
            throw IllegalStateException("Old permission request failed")
        }.thenReturn(emptySet())

        runCurrent()

        assertFalse(vm.state.value.isChecking)
        assertNull(vm.state.value.accessError)
        assertEquals(DataReadStatus.PERMISSION_DENIED, vm.state.value.sources.first().status)
        verify(manager, times(2)).grantedPermissions()
    }

    @Test fun `returning from settings rechecks permissions and observes subsequent read outcomes`() = runTest {
        val states = MutableStateFlow(listOf(SyncStateEntity("StepsRecord", 100L, outcome = "SUCCESS_DATA")))
        val dao: SyncStateDao = mock { on { observeAll() } doReturn states }
        val manager: HealthConnectManager = mock {
            on { isAvailable() } doReturn true
            onBlocking { grantedPermissions() } doReturn emptySet()
        }
        val vm = DataSourcesViewModel(dao, manager, repository(), mock<Context>())
        assertEquals(DataReadStatus.PERMISSION_DENIED, vm.state.value.sources.first { it.recordType == "StepsRecord" }.status)

        whenever(manager.grantedPermissions()).thenReturn(setOf(HealthPermission.getReadPermission(StepsRecord::class)))
        states.value = listOf(SyncStateEntity("StepsRecord", 200L, outcome = "PERMISSION_DENIED"))
        vm.refreshAccess()
        assertEquals(DataReadStatus.NOT_READ, vm.state.value.sources.first { it.recordType == "StepsRecord" }.status)
        states.value = listOf(SyncStateEntity("StepsRecord", 300L, outcome = "SUCCESS_EMPTY", lastSuccessfulReadMs = 300L))
        assertEquals(DataReadStatus.EMPTY, vm.state.value.sources.first { it.recordType == "StepsRecord" }.status)
        assertEquals("Not supported on this device", vm.state.value.backgroundAccess)
    }

    @Test fun `permission check failure is not shown as denied`() = runTest {
        val dao: SyncStateDao = mock { on { observeAll() } doReturn MutableStateFlow(emptyList()) }
        val manager: HealthConnectManager = mock {
            on { isAvailable() } doReturn true
            onBlocking { grantedPermissions() } doThrow IllegalStateException("Provider busy")
        }
        val vm = DataSourcesViewModel(dao, manager, repository(), mock<Context>())
        assertFalse(vm.state.value.isLoading)
        assertEquals(DataReadStatus.UNKNOWN, vm.state.value.sources.first().status)
        assertEquals("Could not check current access", vm.state.value.backgroundAccess)
        assertEquals("Could not check current access", vm.state.value.historyAccess)
    }

    @Test fun `failed access refresh clears previously allowed capability labels and recovers`() = runTest {
        val dao: SyncStateDao = mock { on { observeAll() } doReturn MutableStateFlow(emptyList()) }
        val permissions = setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND,
            HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
        val manager: HealthConnectManager = mock {
            on { isAvailable() } doReturn true
            on { supportsBackgroundRead() } doReturn true
            on { supportsHistoryRead() } doReturn true
            onBlocking { grantedPermissions() } doReturn permissions
        }
        val vm = DataSourcesViewModel(dao, manager, repository(), mock<Context>())
        assertEquals("Allowed", vm.state.value.backgroundAccess)
        assertEquals("Allowed", vm.state.value.historyAccess)
        whenever(manager.grantedPermissions()).thenThrow(IllegalStateException("Provider busy"))
        vm.refreshAccess()
        assertEquals("Could not check current access", vm.state.value.backgroundAccess)
        assertEquals("Could not check current access", vm.state.value.historyAccess)
        assertNotNull(vm.state.value.accessError)
        doReturn(permissions).whenever(manager).grantedPermissions()
        vm.refreshAccess()
        assertEquals("Allowed", vm.state.value.backgroundAccess)
        assertNull(vm.state.value.accessError)
    }

    @Test fun `availability lookup failure does not claim platform is unavailable`() = runTest {
        val dao: SyncStateDao = mock { on { observeAll() } doReturn MutableStateFlow(emptyList()) }
        val manager: HealthConnectManager = mock { on { isAvailable() } doThrow IllegalStateException("Provider busy") }
        val vm = DataSourcesViewModel(dao, manager, repository(), mock<Context>())
        assertNull(vm.state.value.available)
        assertEquals(DataReadStatus.UNKNOWN, vm.state.value.sources.first().status)
    }

    @Test fun `read action reports repository partial result and keeps it after refreshing access`() = runTest {
        val dao: SyncStateDao = mock { on { observeAll() } doReturn MutableStateFlow(emptyList()) }
        val manager: HealthConnectManager = mock {
            on { isAvailable() } doReturn true
            onBlocking { grantedPermissions() } doReturn emptySet()
        }
        val prefs: SharedPreferences = mock {
            on { getInt(any(), any()) } doAnswer { it.getArgument<Int>(1) }
        }
        val context: Context = mock { on { getSharedPreferences(any(), any()) } doReturn prefs }
        val repo = repository()
        whenever(repo.syncToday(any(), any())).thenReturn(HealthRepository.SyncResult(null, emptyList(), partial = true))
        val vm = DataSourcesViewModel(dao, manager, repo, context)
        vm.readData()
        assertFalse(vm.state.value.isSyncing)
        assertTrue(vm.state.value.message.orEmpty().contains("partly completed"))
        verify(repo).syncToday(30, 190)
    }

    @Test fun `changing cached sleep details updates the missing field note`() = runTest {
        val dao: SyncStateDao = mock { on { observeAll() } doReturn MutableStateFlow(emptyList()) }
        val manager: HealthConnectManager = mock {
            on { isAvailable() } doReturn true
            onBlocking { grantedPermissions() } doReturn emptySet()
        }
        val metrics = MutableStateFlow(listOf(dataSourceMetrics()))
        val repo: HealthRepository = mock { on { metricsFrom(any()) } doReturn metrics }
        val vm = DataSourcesViewModel(dao, manager, repo, mock<Context>())
        assertTrue(vm.state.value.sources.first { it.recordType == "SleepSessionRecord" }.dataNotes.single().contains("sleep stage breakdown"))
        metrics.value = listOf(dataSourceMetrics().copy(sleepStagesAvailable = true, sleepDeepMinutes = 60))
        assertTrue(vm.state.value.sources.first { it.recordType == "SleepSessionRecord" }.dataNotes.isEmpty())
    }
}

private fun dataSourceMetrics() = DailyMetricsEntity(
    dateEpochDay = java.time.LocalDate.of(2026, 9, 26).toEpochDay(),
    restingHR = null, steps = null, distanceMeters = null, caloriesBurned = null,
    weightKg = null, bodyFatPercent = null, spO2Percent = null, sleepDurationMinutes = 420,
    sleepEfficiencyPercent = null, sleepDeepMinutes = null, sleepRemMinutes = null,
    sleepLightMinutes = null, sleepAwakeMinutes = null, bedtimeMinuteOfDay = null,
    wakeTimeMinuteOfDay = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsHistoryRefreshTest {
    @Before fun setup() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun teardown() = Dispatchers.resetMain()

    @Test fun `settings refresh forces existing history and reports partial result`() = runTest {
        val prefs: SharedPreferences = mock {
            on { getInt(any(), any()) } doAnswer { it.getArgument<Int>(1) }
        }
        val context: Context = mock { on { getSharedPreferences(any(), any()) } doReturn prefs }
        val repo: HealthRepository = mock {
            onBlocking { backfillHistory(any(), any(), any()) } doReturn
                HealthRepository.BackfillResult(refreshedDays = 28, partialDays = 2)
        }
        mockStatic(BiometricManager::class.java).use { biometric ->
            biometric.`when`<BiometricManager> { BiometricManager.from(context) }.thenReturn(mock())
            val vm = SettingsViewModel(context, repo, mock())
            vm.forceBackfill()
            verify(repo).backfillHistory(userAge = 30, userMaxHR = 190, force = true)
            assertFalse(vm.state.value.isBackfilling)
            assertTrue(vm.state.value.backfillNeedsAttention)
            assertTrue(vm.state.value.backfillResult.orEmpty().contains("2 partial"))
            verify(prefs, never()).edit()
        }
    }
}
