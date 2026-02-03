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
 * Step counter for wake proof challenge.
 * Uses TYPE_STEP_COUNTER (preferred) with TYPE_STEP_DETECTOR fallback.
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
    
    // Prefer TYPE_STEP_COUNTER (cumulative), fallback to TYPE_STEP_DETECTOR (events)
    private val stepCounterSensor: Sensor? by lazy {
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    }
    
    private val stepDetectorSensor: Sensor? by lazy {
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    }
    
    /**
     * Check if step counting is available.
     */
    fun isAvailable(): Boolean {
        val available = stepCounterSensor != null || stepDetectorSensor != null
        Log.d(TAG, "Step sensor available: $available (counter=${stepCounterSensor != null}, detector=${stepDetectorSensor != null})")
        return available
    }
    
    /**
     * Count steps until target reached.
     * Prefers TYPE_STEP_DETECTOR (real-time) over TYPE_STEP_COUNTER (batched).
     * STEP_DETECTOR fires immediately on each step, while STEP_COUNTER may batch updates.
     */
    fun countSteps(targetSteps: Int): Flow<StepProgress> = callbackFlow {
        var baselineSteps: Float? = null
        var currentSteps = 0
        
        // Prefer STEP_DETECTOR for immediate response (fires on each step)
        // Only fallback to STEP_COUNTER if detector unavailable
        val useDetector = stepDetectorSensor != null
        val sensor = if (useDetector) stepDetectorSensor else stepCounterSensor
        
        if (sensor == null) {
            Log.e(TAG, "No step sensor available")
            trySend(StepProgress(0, targetSteps, 0f, false, error = "No step sensor"))
            close()
            return@callbackFlow
        }
        
        Log.i(TAG, "Starting step count with ${if (useDetector) "STEP_DETECTOR" else "STEP_COUNTER"}")
        
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (useDetector) {
                    // TYPE_STEP_DETECTOR: each event is a single step (immediate)
                    currentSteps++
                } else {
                    // TYPE_STEP_COUNTER: values[0] is cumulative step count since boot (batched)
                    val totalSteps = event.values[0]
                    
                    if (baselineSteps == null) {
                        baselineSteps = totalSteps
                        Log.d(TAG, "Baseline set: $baselineSteps")
                    }
                    
                    currentSteps = (totalSteps - baselineSteps!!).toInt()
                }
                
                val progress = currentSteps.toFloat() / targetSteps
                val isComplete = currentSteps >= targetSteps
                
                Log.d(TAG, "Step: $currentSteps / $targetSteps (complete=$isComplete)")
                
                trySend(StepProgress(
                    currentSteps = currentSteps,
                    targetSteps = targetSteps,
                    progress = progress.coerceAtMost(1f),
                    isComplete = isComplete
                ))
                
                if (isComplete) {
                    Log.i(TAG, "Step challenge complete!")
                }
            }
            
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                Log.d(TAG, "Sensor accuracy changed: $accuracy")
            }
        }
        
        // Register with fastest rate for responsive counting
        val registered = sensorManager.registerListener(
            listener,
            sensor,
            SensorManager.SENSOR_DELAY_FASTEST
        )
        
        if (!registered) {
            Log.e(TAG, "Failed to register sensor listener")
            trySend(StepProgress(0, targetSteps, 0f, false, error = "Sensor registration failed"))
        } else {
            // Send initial state
            trySend(StepProgress(0, targetSteps, 0f, false))
        }
        
        awaitClose {
            Log.d(TAG, "Unregistering step sensor")
            sensorManager.unregisterListener(listener)
        }
    }
}

/**
 * Progress state for step counting.
 */
data class StepProgress(
    val currentSteps: Int,
    val targetSteps: Int,
    val progress: Float,
    val isComplete: Boolean,
    val error: String? = null
)
