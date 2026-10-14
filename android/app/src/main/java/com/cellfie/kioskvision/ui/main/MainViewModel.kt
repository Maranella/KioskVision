package com.cellfie.kioskvision.ui.main

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class MainViewModel : ViewModel() {

    // Nothing is persisted: permission and camera state are re-derived from the system
    // every time the screen starts, so restored values could only be stale.
    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    fun onIntent(intent: MainIntent) {
        _state.update { reduce(it, intent) }
    }
}
