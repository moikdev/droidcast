package dev.tinycast.android.extensions

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/** The floating launcher's theme disables dimming. Picker windows need their
 * own backdrop so enlarged headings remain distinct from the palette behind.
 * This window belongs exclusively to the Dialog and is destroyed with it.
 */
@Composable
internal fun FormPickerBackdrop() {
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window != null && ((window.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND) == 0 || window.attributes.dimAmount != .55f)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setDimAmount(.55f)
        }
    }
}
