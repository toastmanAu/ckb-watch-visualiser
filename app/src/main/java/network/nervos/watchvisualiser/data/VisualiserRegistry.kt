package network.nervos.watchvisualiser.data

import network.nervos.watchvisualiser.R
import network.nervos.watchvisualiser.model.VisualiserInfo

/**
 * Registry containing all available and upcoming Nervos Network visualizers.
 * New visualizers can easily be added to this registry to appear in the Wear OS launcher menu.
 */
object VisualiserRegistry {

    private val visualisers = mutableListOf(
        VisualiserInfo(
            id = "ckb_cell_lab",
            title = "Cell Laboratory",
            subtitle = "Microscopic UTXO organisms absorbed into the Liquid of Knowledge",
            badge = "FLAGSHIP",
            iconRes = R.drawable.ic_cell_lab,
            assetPath = "file:///android_asset/visualisers/ckb-cell-lab/index.html",
            isAvailable = true
        ),
        VisualiserInfo(
            id = "ckb_bonsai",
            title = "Maple Bonsai",
            subtitle = "New CKB Cells falling as autumn leaves from a Japanese maple bonsai",
            badge = "ACTIVE",
            iconRes = R.drawable.ic_bonsai,
            assetPath = "file:///android_asset/visualisers/ckb-bonsai/index.html",
            isAvailable = true
        ),
        VisualiserInfo(
            id = "ckb_cuckoo",
            title = "Cuckoo Clock",
            subtitle = "Handcrafted 3D Black Forest clock that chimes on every canonical block",
            badge = "ACTIVE",
            iconRes = R.drawable.ic_clock,
            assetPath = "file:///android_asset/visualisers/ckb-cuckoo/index.html",
            isAvailable = true
        ),
        VisualiserInfo(
            id = "ckb_bricks",
            title = "Epoch Brick Tower",
            subtitle = "Live CKB Epoch constructed block-by-block from interlocking 3D plastic bricks",
            badge = "ACTIVE",
            iconRes = R.drawable.ic_bricks,
            assetPath = "file:///android_asset/visualisers/ckb-bricks/index.html",
            isAvailable = true
        ),
        VisualiserInfo(
            id = "fiber_network",
            title = "Fiber Network",
            subtitle = "Lightning-fast P2P state channels & peer routing topologies",
            badge = "COMING SOON",
            iconRes = R.drawable.ic_fiber,
            assetPath = "file:///android_asset/visualisers/fiber-network/index.html",
            isAvailable = false
        ),
        VisualiserInfo(
            id = "mempool_collider",
            title = "Mempool Collider",
            subtitle = "Unconfirmed transactions accelerating towards block inclusion",
            badge = "COMING SOON",
            iconRes = R.drawable.ic_mempool,
            assetPath = "file:///android_asset/visualisers/mempool-collider/index.html",
            isAvailable = false
        )
    )

    fun getAll(): List<VisualiserInfo> = visualisers.toList()

    fun getById(id: String): VisualiserInfo? = visualisers.find { it.id == id }

    fun registerVisualiser(info: VisualiserInfo) {
        visualisers.add(info)
    }
}
