package app.solarma.ui.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.solarma.alarm.AlarmRepository
import app.solarma.data.local.AlarmEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

/**
 * ViewModel for CreateAlarmScreen.
 * Handles alarm creation: converts UI state → entity → DB + schedule.
 */
@HiltViewModel
class CreateAlarmViewModel @Inject constructor(
    private val alarmRepository: AlarmRepository
) : ViewModel() {
    
    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    val saveState: StateFlow<SaveState> = _saveState.asStateFlow()
    
    /**
     * Save alarm from UI state.
     * Converts LocalTime to absolute trigger time, saves to DB, schedules.
     */
    fun save(state: CreateAlarmState) {
        viewModelScope.launch {
            _saveState.value = SaveState.Saving
            
            try {
                // Convert LocalTime to next occurrence
                val triggerAtMillis = calculateNextTrigger(state.time)
                
                // Create entity
                val alarm = AlarmEntity(
                    alarmTimeMillis = triggerAtMillis,
                    label = state.label,
                    isEnabled = true,
                    repeatDays = 0, // TODO: support repeat
                    wakeProofType = state.wakeProofType,
                    targetSteps = state.targetSteps,
                    hasDeposit = state.hasDeposit,
                    // Store deposit info as JSON or separate fields
                    // For now, onchainPubkey will be set after tx confirmation
                )
                
                // Save and schedule
                val id = alarmRepository.createAlarm(alarm)
                
                _saveState.value = SaveState.Success(id)
                
            } catch (e: Exception) {
                _saveState.value = SaveState.Error(e.message ?: "Failed to save alarm")
            }
        }
    }
    
    /**
     * Calculate next trigger time from LocalTime.
     * If time has passed today, schedule for tomorrow.
     */
    private fun calculateNextTrigger(time: LocalTime): Long {
        val now = LocalDateTime.now()
        var targetDateTime = LocalDateTime.of(LocalDate.now(), time)
        
        // If time already passed today, schedule for tomorrow
        if (targetDateTime.isBefore(now)) {
            targetDateTime = targetDateTime.plusDays(1)
        }
        
        return targetDateTime
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }
    
    fun resetState() {
        _saveState.value = SaveState.Idle
    }
}

/**
 * Save operation state.
 */
sealed class SaveState {
    object Idle : SaveState()
    object Saving : SaveState()
    data class Success(val alarmId: Long) : SaveState()
    data class Error(val message: String) : SaveState()
}
