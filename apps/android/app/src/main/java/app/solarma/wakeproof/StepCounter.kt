package app.solarma.wakeproof

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Step counter for Wake Proof challenge.
 * Uses TYPE_STEP_DETECTOR for immediate step detection.
 */
@Singleton
class StepCounter @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "Solarma.StepCounter"
    }
    
    private val sensorManager: SensorManager by lazy {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    
    /**
     * Check if step counter sensor is available.
     */
    fun isAvailable(): Boolean {
        return sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null
    }
    
    /**
     * Start counting steps. Emits step count updates.
     * @param targetSteps Number of steps required to complete challenge
     */
    fun countSteps(targetSteps: Int): Flow<StepProgress> = callbackFlow {
        var stepCount = 0
        
        val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        
        if (stepSensor == null) {
            Log.e(TAG, "Step detector sensor not available")
            trySend(StepProgress(0, targetSteps, false, "Sensor unavailable"))
            close()
            return@callbackFlow
        }
        
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event?.sensor?.type == Sensor.TYPE_STEP_DETECTOR) {
                    stepCount++
                    Log.d(TAG, "Step detected: $stepCount / $targetSteps")
                    
                    val complete = stepCount >= targetSteps
                    trySend(StepProgress(stepCount, targetSteps, complete, null))
                    
                    if (complete) {
                        Log.i(TAG, "Step challenge complete!")
                    }
                }
            }
            
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                Log.d(TAG, "Sensor accuracy changed: $accuracy")
            }
        }
        
        sensorManager.registerListener(
            listener,
            stepSensor,
            SensorManager.SENSOR_DELAY_FASTEST
        )
        
        // Emit initial state
        trySend(StepProgress(0, targetSteps, false, null))
        
        awaitClose {
            sensorManager.unregisterListener(listener)
            Log.d(TAG, "Step counter stopped")
        }
    }
}

/**
 * Progress of step counting challenge.
 */
data class StepProgress(
    val currentSteps: Int,
    val targetSteps: Int,
    val isComplete: Boolean,
    val error: String?
) {
    val progress: Float
        get() = (currentSteps.toFloat() / targetSteps).coerceIn(0f, 1f)
}
