package com.sdk.glassessdksample.utils

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.sdk.glassessdksample.R

/**
 * Edge-to-edge system bar handling.
 *
 * targetSdk 35 makes edge-to-edge mandatory on Android 15+, so every window now
 * extends behind the status bar and the navigation bar. The layouts were authored
 * against the pre-35 behaviour, where the system insets were subtracted for us:
 * they use fixed paddings at the top (14-20dp) and a fixed 14dp bottom margin on
 * the floating nav pill. Without this, headers slide under the status bar / notch
 * and the nav pill sits under the gesture pill or, on 3-button devices, under the
 * navigation bar itself - which is what made it look tilted and unreachable.
 *
 * Rather than hardcoding inset values into 30+ layouts (which breaks the moment a
 * device has a different bar height), this resolves the real insets at runtime and
 * adds them on top of whatever the layout already specifies.
 */
object SystemBarsInsets {

    /**
     * Applies status-bar and navigation-bar insets to an activity's content.
     *
     * Call right after setContentView. Handles the three layout shapes used in
     * this app:
     *  - a floating BottomNavigationView pill -> nav inset added to its bottom
     *    *margin*, so the pill floats above the system bar instead of under it;
     *  - the scrolling content sibling -> status inset as top padding, and its
     *    bottom margin grown by the nav inset so the last card clears the pill;
     *  - anything else (plain root) -> status inset on top, nav inset on bottom.
     *
     * Left/right insets are applied too, which matters in landscape and on
     * devices that place the navigation bar on the side.
     */
    @JvmStatic
    @JvmOverloads
    fun apply(activity: Activity, fullscreen: Boolean = false) {
        val root = activity.findViewById<View>(android.R.id.content) as? ViewGroup ?: return
        val host = root.getChildAt(0) ?: root

        // Keep the app drawing edge-to-edge, but make the bar icons legible.
        // The app is a dark theme throughout, so light icons on both bars.
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        WindowCompat.getInsetsController(activity.window, host).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        if (fullscreen) {
            // Media viewers deliberately run under the bars; nothing to inset.
            return
        }

        applyToView(host)
    }

    private fun applyToView(host: View) {
        // Snapshot the layout-authored values once. Listeners can fire repeatedly
        // (rotation, keyboard, multi-window) and reading the live values would
        // compound the insets on every pass.
        val basePaddingTop = host.paddingTop
        val basePaddingBottom = host.paddingBottom
        val basePaddingLeft = host.paddingLeft
        val basePaddingRight = host.paddingRight

        val navPill = findBottomNav(host)
        val navBaseMargin = navPill?.marginBottomOrZero() ?: 0

        // The scrolling sibling that was given a fixed bottom margin to clear the pill.
        val content = navPill?.let { findContentSibling(it) }
        val contentBaseMarginBottom = content?.marginBottomOrZero() ?: 0
        val contentBasePaddingTop = content?.paddingTop ?: 0

        ViewCompat.setOnApplyWindowInsetsListener(host) { view, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )

            if (navPill != null) {
                // Float the pill above the navigation bar / gesture handle.
                navPill.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = navBaseMargin + bars.bottom
                    leftMargin = leftMargin.coerceAtLeast(bars.left)
                    rightMargin = rightMargin.coerceAtLeast(bars.right)
                }
                // Push the scrolling content down past the status bar, and grow its
                // bottom margin by the same amount the pill moved up.
                content?.let { c ->
                    c.updatePadding(top = contentBasePaddingTop + bars.top)
                    c.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                        bottomMargin = contentBaseMarginBottom + bars.bottom
                    }
                }
                if (content == null) {
                    view.updatePadding(top = basePaddingTop + bars.top)
                }
            } else {
                // Plain screen: inset the root on all four sides.
                view.updatePadding(
                    left = basePaddingLeft + bars.left,
                    top = basePaddingTop + bars.top,
                    right = basePaddingRight + bars.right,
                    bottom = basePaddingBottom + bars.bottom
                )
            }

            // Consumed here; children keep their own layout untouched.
            WindowInsetsCompat.CONSUMED
        }

        // The listener only fires on the next dispatch. If the view is already
        // attached with insets available, ask for a pass now so the first frame
        // is correct rather than visibly jumping.
        if (host.isAttachedToWindow) {
            ViewCompat.requestApplyInsets(host)
        } else {
            host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    ViewCompat.requestApplyInsets(v)
                    v.removeOnAttachStateChangeListener(this)
                }

                override fun onViewDetachedFromWindow(v: View) = Unit
            })
        }
    }

    /** The floating nav pill, if this screen has one. */
    private fun findBottomNav(host: View): View? {
        val nav = host.findViewById<View>(R.id.bottomNavigation) ?: return null
        return if (nav.layoutParams is ViewGroup.MarginLayoutParams) nav else null
    }

    /**
     * The nav pill's sibling holding the screen content - the scroll view that was
     * given a fixed bottom margin to sit above the pill.
     */
    private fun findContentSibling(nav: View): View? {
        val parent = nav.parent as? ViewGroup ?: return null
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child !== nav && child.layoutParams is ViewGroup.MarginLayoutParams) {
                return child
            }
        }
        return null
    }

    private fun View.marginBottomOrZero(): Int =
        (layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
}
