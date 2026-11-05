package dev.tinycast.android

import android.inputmethodservice.InputMethodService

/** Explicitly enabled by the owned runner; never edits, reads, or commits text. */
class NativeInputTestIme : InputMethodService() {
    override fun onEvaluateInputViewShown() = false
    override fun onEvaluateFullscreenMode() = false
}
