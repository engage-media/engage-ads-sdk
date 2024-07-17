package com.engage.engageadssdk.module

import android.content.Context

class EMAdsModule(private val emAdsModuleInput: EMAdsModuleInput) :
    EMAdsModuleInput by emAdsModuleInput {

    companion object {
        private var instance: EMAdsModule? = null

        val isInitialized: Boolean
            get() = instance != null

        @JvmStatic
        fun getInstance(): EMAdsModule {
            if (instance == null) {
                throw IllegalStateException("EMAdsModule is not initialized")
            }
            return instance!!
        }

        @JvmStatic
        fun init(emAdsModuleInput: EMAdsModuleInput) {
            if (instance != null) {
                throw IllegalStateException("EMAdsModule is already initialized")
            }
            instance = EMAdsModule(emAdsModuleInput)
        }
    }
}