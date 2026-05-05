package com.projecttemp.project

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class SplashActivity : AppCompatActivity() {
    private lateinit var rootView: View
    private lateinit var splashGlow: View
    private lateinit var splashRing: View
    private lateinit var splashPlate: View
    private lateinit var splashIcon: View
    private lateinit var splashTitle: TextView
    private lateinit var splashSubtitle: TextView

    private val runningAnimators = mutableListOf<Animator>()
    private val openMainRunnable = Runnable { openMainScreen() }
    private var hasNavigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_splash)

        bindViews()
        applyWindowInsets()
        startEntranceAnimation()
        scheduleMainScreen()
    }

    override fun onDestroy() {
        rootView.removeCallbacks(openMainRunnable)
        runningAnimators.forEach(Animator::cancel)
        runningAnimators.clear()
        super.onDestroy()
    }

    private fun bindViews() {
        rootView = findViewById(R.id.splashRoot)
        splashGlow = findViewById(R.id.splashGlow)
        splashRing = findViewById(R.id.splashRing)
        splashPlate = findViewById(R.id.splashPlate)
        splashIcon = findViewById(R.id.splashIcon)
        splashTitle = findViewById(R.id.splashTitle)
        splashSubtitle = findViewById(R.id.splashSubtitle)
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun startEntranceAnimation() {
        splashGlow.alpha = 0f
        splashGlow.scaleX = 0.72f
        splashGlow.scaleY = 0.72f

        splashRing.alpha = 0f
        splashRing.scaleX = 0.45f
        splashRing.scaleY = 0.45f

        splashPlate.alpha = 0f
        splashPlate.scaleX = 0.62f
        splashPlate.scaleY = 0.62f
        splashPlate.translationY = 28f

        splashIcon.alpha = 0f
        splashIcon.scaleX = 0.5f
        splashIcon.scaleY = 0.5f
        splashIcon.rotation = -14f

        splashTitle.alpha = 0f
        splashTitle.translationY = 26f

        splashSubtitle.alpha = 0f
        splashSubtitle.translationY = 32f

        val settleInterpolator = OvershootInterpolator(1.15f)

        playAnimator(
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofPropertyValuesHolder(
                        splashGlow,
                        PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 0.95f),
                        PropertyValuesHolder.ofFloat(View.SCALE_X, 0.72f, 1f),
                        PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.72f, 1f)
                    ),
                    ObjectAnimator.ofPropertyValuesHolder(
                        splashRing,
                        PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
                        PropertyValuesHolder.ofFloat(View.SCALE_X, 0.45f, 1f),
                        PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.45f, 1f)
                    ),
                    ObjectAnimator.ofPropertyValuesHolder(
                        splashPlate,
                        PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
                        PropertyValuesHolder.ofFloat(View.SCALE_X, 0.62f, 1f),
                        PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.62f, 1f),
                        PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 28f, 0f)
                    ),
                    ObjectAnimator.ofPropertyValuesHolder(
                        splashIcon,
                        PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
                        PropertyValuesHolder.ofFloat(View.SCALE_X, 0.5f, 1f),
                        PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.5f, 1f),
                        PropertyValuesHolder.ofFloat(View.ROTATION, -14f, 5f, 0f)
                    )
                )
                duration = 860L
                interpolator = settleInterpolator
            }
        )

        playAnimator(
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofPropertyValuesHolder(
                        splashTitle,
                        PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
                        PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 26f, 0f)
                    ),
                    ObjectAnimator.ofPropertyValuesHolder(
                        splashSubtitle,
                        PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
                        PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 32f, 0f)
                    )
                )
                startDelay = 230L
                duration = 620L
                interpolator = DecelerateInterpolator()
            }
        )

        playAnimator(
            ObjectAnimator.ofFloat(splashRing, View.ROTATION, 0f, 180f).apply {
                duration = 1900L
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
            }
        )

        playAnimator(
            ObjectAnimator.ofPropertyValuesHolder(
                splashGlow,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.08f, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.08f, 1f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.95f, 0.72f, 0.95f)
            ).apply {
                duration = 2100L
                interpolator = AccelerateDecelerateInterpolator()
                repeatCount = ValueAnimator.INFINITE
            }
        )

        playAnimator(
            ObjectAnimator.ofPropertyValuesHolder(
                splashPlate,
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, -10f, 0f)
            ).apply {
                duration = 1800L
                interpolator = AccelerateDecelerateInterpolator()
                repeatCount = ValueAnimator.INFINITE
            }
        )
    }

    private fun scheduleMainScreen() {
        rootView.postDelayed(openMainRunnable, 1650L)
    }

    private fun openMainScreen() {
        if (hasNavigated || isFinishing) {
            return
        }

        hasNavigated = true
        val options =
            ActivityOptions.makeCustomAnimation(
                this,
                android.R.anim.fade_in,
                android.R.anim.fade_out
            )
        startActivity(Intent(this, MainActivity::class.java), options.toBundle())
        finish()
    }

    private fun playAnimator(animator: Animator) {
        runningAnimators += animator
        animator.start()
    }
}
