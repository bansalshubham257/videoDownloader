package com.quicksavevideos.app.ads

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.FrameLayout
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

object AdsManager {

    private const val TAG = "AdsManager"
    private const val BANNER_AD_UNIT_ID = "ca-app-pub-1394062189372273/2631175957"
    private const val REWARDED_AD_UNIT_ID = "ca-app-pub-1394062189372273/9865421307"
    private const val MAX_RETRIES = 3
    private const val RETRY_DELAY_MS = 2000L

    private var rewardedAd: RewardedAd? = null
    private var isRewardedLoading = false
    private var retryCount = 0

    private data class PendingAdRequest(
        val activity: Activity,
        val onEarned: () -> Unit,
        val onDismissed: (() -> Unit)?
    )

    private var pendingRequest: PendingAdRequest? = null

    fun init(context: Context) {
        MobileAds.initialize(context) { initStatus ->
            Log.d(TAG, "AdMob initialized: $initStatus")
        }
        loadRewardedAd(context)
    }

    private fun loadRewardedAd(context: Context) {
        if (isRewardedLoading) return
        isRewardedLoading = true
        val adRequest = AdRequest.Builder().build()
        RewardedAd.load(context, REWARDED_AD_UNIT_ID, adRequest, object : RewardedAdLoadCallback() {
            override fun onAdLoaded(ad: RewardedAd) {
                rewardedAd = ad
                isRewardedLoading = false
                retryCount = 0
                Log.d(TAG, "Rewarded ad loaded")
                pendingRequest?.let { request ->
                    pendingRequest = null
                    showRewardedAd(request.activity, request.onEarned, request.onDismissed)
                }
            }

            override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                rewardedAd = null
                isRewardedLoading = false
                Log.w(TAG, "Rewarded ad failed to load: ${loadAdError.message}")
                pendingRequest?.let { request ->
                    if (retryCount < MAX_RETRIES) {
                        retryCount++
                        Handler(Looper.getMainLooper()).postDelayed({
                            loadRewardedAd(context)
                        }, RETRY_DELAY_MS)
                    } else {
                        retryCount = 0
                        pendingRequest = null
                        request.onDismissed?.invoke()
                    }
                }
            }
        })
    }

    fun showRewardedAd(activity: Activity, onEarned: () -> Unit, onDismissed: (() -> Unit)? = null) {
        rewardedAd?.let { ad ->
            retryCount = 0
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    rewardedAd = null
                    loadRewardedAd(activity)
                    onDismissed?.invoke()
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    rewardedAd = null
                    loadRewardedAd(activity)
                    onDismissed?.invoke()
                }

                override fun onAdShowedFullScreenContent() {
                    // Ad shown
                }
            }
            ad.show(activity) { onEarned() }
        } ?: run {
            pendingRequest = PendingAdRequest(activity, onEarned, onDismissed)
            if (!isRewardedLoading) {
                loadRewardedAd(activity)
            }
        }
    }

    fun loadBannerAd(container: FrameLayout, context: Context) {
        val adView = AdView(context)
        adView.adUnitId = BANNER_AD_UNIT_ID
        adView.setAdSize(AdSize.BANNER)
        container.addView(adView)
        val adRequest = AdRequest.Builder().build()
        adView.loadAd(adRequest)
    }
}
