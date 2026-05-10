package com.example.first.engine

import android.content.Context

object AudioEngineFactory {

    enum class EngineType(val key: String) {
        NORMAL("NORMAL"),
        HI_RES("HI_RES")
    }

    /**
     * Determines the appropriate audio engine based on the device class.
     * 0: Speaker/Internal/Wired -> Standard MediaPlayer Engine
     * 2: Bluetooth -> Native Hi-Res Engine (Oboe without resampling)
     */
    fun getEngineForClassification(deviceClass: DeviceHelper.DeviceClass): EngineType {
        return when (deviceClass) {
            DeviceHelper.DeviceClass.BLUETOOTH -> EngineType.HI_RES      // Classification 2
            DeviceHelper.DeviceClass.INTERNAL_SPEAKER -> EngineType.NORMAL   // Classification 0
            else -> EngineType.NORMAL        // Fallback to 0
        }
    }

    fun createEngine(context: Context, type: EngineType): IAudioEngine {
        return when (type) {
            EngineType.NORMAL  -> NativeHiResEngine() // V10: Migrated Internal to Oboe
            EngineType.HI_RES  -> NativeHiResEngine()
        }
    }

    @Deprecated("Engine routing is now strictly hardware-based. Do not use preferences.")
    fun getEngineTypeFromString(value: String?): EngineType {
        return when (value) {
            "HI_RES"  -> EngineType.HI_RES
            else      -> EngineType.NORMAL
        }
    }
}
