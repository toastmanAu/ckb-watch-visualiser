package network.nervos.watchvisualiser

import android.os.Bundle
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import network.nervos.watchvisualiser.data.AppPreferences
import network.nervos.watchvisualiser.data.CkbRpcClient
import network.nervos.watchvisualiser.ui.BonsaiVisualiserView
import network.nervos.watchvisualiser.ui.CuckooVisualiserView
import network.nervos.watchvisualiser.ui.LabVisualiserView

/**
 * Native Wear OS Activity housing the visualizer selected by the user.
 * Seamlessly routes to LabVisualiserView, BonsaiVisualiserView, or CuckooVisualiserView
 * with full Pixel Watch 3 Digital Crown / Rotary Encoder support and live CKB RPC streaming.
 */
class VisualiserActivity : ComponentActivity() {

    private var labView: LabVisualiserView? = null
    private var bonsaiView: BonsaiVisualiserView? = null
    private var cuckooView: CuckooVisualiserView? = null
    private lateinit var preferences: AppPreferences
    private lateinit var rpcClient: CkbRpcClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        preferences = AppPreferences(this)

        if (preferences.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val visId = intent.getStringExtra(EXTRA_VISUALISER_ID) ?: "ckb_cell_lab"

        when (visId) {
            "ckb_bonsai" -> {
                bonsaiView = BonsaiVisualiserView(this)
                setContentView(bonsaiView)
            }
            "ckb_cuckoo" -> {
                cuckooView = CuckooVisualiserView(this)
                setContentView(cuckooView)
            }
            else -> {
                labView = LabVisualiserView(this)
                setContentView(labView)
            }
        }

        // Connect to live CKB Blockchain via native RPC client
        rpcClient = CkbRpcClient(preferences.ckbRpcUrl, lifecycleScope).apply {
            onBaselineLoaded = { baseline ->
                cuckooView?.onBaselineLoaded(baseline)
            }
            onBlockDetected = { blockData ->
                labView?.onBlockArrived(blockData)
                bonsaiView?.onBlockArrived(blockData)
                cuckooView?.onBlockArrived(blockData)
            }
            onStatusChanged = { status ->
                cuckooView?.onStatusUpdated(status)
            }
            start()
        }
    }

    /**
     * Intercept Pixel Watch 3 physical digital crown / rotary encoder
     * to orbit the camera smoothly around the 3D scene.
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL &&
            event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)
        ) {
            val delta = -event.getAxisValue(MotionEvent.AXIS_SCROLL) * preferences.rotarySensitivity
            labView?.handleRotaryScroll(delta)
            bonsaiView?.handleRotaryScroll(delta)
            cuckooView?.handleRotaryScroll(delta)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onDestroy() {
        rpcClient.stop()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_VISUALISER_ID = "extra_visualiser_id"
        const val EXTRA_ASSET_PATH = "extra_asset_path"
        const val EXTRA_VISUALISER_TITLE = "extra_visualiser_title"
    }
}
