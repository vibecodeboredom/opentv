package com.opentv.player

import android.animation.ValueAnimator
import android.app.Dialog
import android.view.View
import android.view.animation.Interpolator
import androidx.core.view.animation.PathInterpolatorCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.leanback.widget.ShadowOverlayContainer

/**
 * 10.1.0 (Nova): every UI motion constant in the app lives here —
 * durations, the directional easing set, and the focus spring —
 * so the feel is tunable from one place instead of being scattered
 * as magic numbers across fragments (the pre-10.1.0 state: 150ms
 * here, 200/180ms there, each with the platform default curve).
 *
 * The numbers come from the motion study (FULL-STUDY synthesis
 * S1–S3 + the Material 3 Expressive spring tokens), retuned to
 * the house taste: BOUNCY and FAST. Springs overshoot only
 * spatial properties (scale/translation); colour and opacity
 * always ride critically-damped-style tweens and never overshoot.
 */
object AnimConfig {

    // ---- Durations (ms). Exits run at ~70% of their entrance
    // (study S1) — leaving should feel quicker than arriving.
    const val FADE_IN_MS = 150L
    const val FADE_OUT_MS = 105L
    const val SIDEBAR_IN_MS = 220L
    const val SIDEBAR_OUT_MS = 154L

    // ---- Home entrance cascade (study S5): rows rise + fade in
    // at 30ms steps; the per-item step shrinks for long lists so
    // the whole cascade's delays never pass the 500ms cap.
    const val ENTRANCE_MS = 260L
    const val ENTRANCE_STAGGER_MS = 30L
    const val ENTRANCE_STAGGER_CAP_MS = 500L
    const val ENTRANCE_RISE_DP = 24f

    // ---- Focus spring. The TV-standard focus scale is 1.1x.
    // Material 3 Expressive's fast spatial token is ζ0.6 / k800
    // (~380ms settle, 9.5% overshoot); the house taste runs
    // bouncier — ζ0.5 at the same stiffness settles in ~300ms
    // with ~16% overshoot, so the 1.10 target peaks near 1.12
    // and settles back. One spring serves home cards and guide
    // cells alike, so focus feels the same everywhere.
    const val FOCUS_SCALE = 1.10f
    const val FOCUS_SPRING_STIFFNESS = 800f
    const val FOCUS_SPRING_DAMPING_RATIO = 0.5f

    /** Z lift (px) for a focused guide cell, so its spring
     * overshoot draws over neighbouring cells instead of sliding
     * under them. */
    const val FOCUS_LIFT_Z_PX = 16f

    // ---- The directional easing set (study S2), as law:
    // entrances ease out on Emphasized, on-screen moves ride
    // Standard, exits accelerate away. Linear is banned for
    // spatial movement (progress and rotation only).
    val EMPHASIZED: Interpolator = PathInterpolatorCompat.create(0.05f, 0.7f, 0.1f, 1f)
    val STANDARD: Interpolator = PathInterpolatorCompat.create(0.2f, 0f, 0f, 1f)
    val ACCELERATE: Interpolator = PathInterpolatorCompat.create(0.3f, 0f, 1f, 1f)
}

/**
 * 10.1.0 (Nova): the small set of motion helpers that apply
 * [AnimConfig] to views. Springs are stored in a keyed view tag
 * (R.id.nova_motion_springs), so they live and die with the view
 * they animate — recycled leanback cards and rebuilt guide cells
 * take their springs with them, nothing accumulates.
 */
object NovaMotion {

    /** A view's two focus springs (X + Y scale), held in the
     * view's keyed tag. A plain holder class, so reading it back
     * is a checked cast. */
    private class FocusSprings(val x: SpringAnimation, val y: SpringAnimation)

    /** True when the user has turned animations off system-wide.
     * Springs don't reliably follow the animator duration scale,
     * so at scale 0 the helpers below snap instead of animating. */
    private fun animationsOff(): Boolean = ValueAnimator.getDurationScale() == 0f

    private fun springsFor(view: View): FocusSprings {
        (view.getTag(R.id.nova_motion_springs) as? FocusSprings)?.let { return it }
        fun make(prop: DynamicAnimation.ViewProperty, current: Float): SpringAnimation {
            val force = SpringForce(current).apply {
                stiffness = AnimConfig.FOCUS_SPRING_STIFFNESS
                dampingRatio = AnimConfig.FOCUS_SPRING_DAMPING_RATIO
            }
            return SpringAnimation(view, prop).apply { spring = force }
        }
        val springs = FocusSprings(
            make(DynamicAnimation.SCALE_X, view.scaleX),
            make(DynamicAnimation.SCALE_Y, view.scaleY)
        )
        view.setTag(R.id.nova_motion_springs, springs)
        return springs
    }

    /**
     * Spring the view's scale to [target]. Retargeting a running
     * spring keeps its velocity (DynamicAnimation continues from
     * the current value + velocity), so hammering the D-pad
     * redirects the motion instead of restarting it — the whole
     * point of using a spring for focus.
     */
    fun springScale(view: View, target: Float) {
        if (animationsOff()) {
            (view.getTag(R.id.nova_motion_springs) as? FocusSprings)
                ?.let { it.x.cancel(); it.y.cancel() }
            view.scaleX = target
            view.scaleY = target
            return
        }
        val springs = springsFor(view)
        springs.x.animateToFinalPosition(target)
        springs.y.animateToFinalPosition(target)
    }

    /**
     * Give a leanback card the springy focus treatment. The
     * scale lands on the view Leanback itself would have scaled:
     * the ShadowOverlayContainer wrapper when the row presenter
     * wrapped the card, the card itself otherwise. Pair with
     * FocusHighlight.ZOOM_FACTOR_NONE on the row presenter, which
     * leaves the framework's own focus animator uninstalled, so
     * the spring is the only writer of the scale.
     */
    fun attachCardFocus(card: View) {
        card.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            val target = (v.parent as? ShadowOverlayContainer) ?: v
            springScale(target, if (hasFocus) AnimConfig.FOCUS_SCALE else 1f)
        }
    }

    /** Menus get the shared scale+fade window animation
     * (R.style.NovaDialogAnimation) instead of the platform pop. */
    fun polishDialog(dialog: Dialog) {
        dialog.window?.setWindowAnimations(R.style.NovaDialogAnimation)
    }
}
