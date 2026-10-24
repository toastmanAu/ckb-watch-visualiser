package network.nervos.watchvisualiser.data

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

/**
 * Native CKB Blockchain RPC Client for Wear OS.
 * Queries live chain tips, full block transactions, and extracts Cells according to the CKB Cell Model.
 */
class CkbRpcClient(
    private var rpcUrl: String = "https://mainnet.ckb.dev/",
    private val scope: CoroutineScope
) {
    private var isRunning = false
    private var lastProcessedBlockHash: String? = null
    private var pollJob: Job? = null

    var onBaselineLoaded: ((CkbBlockData) -> Unit)? = null
    var onBlockDetected: ((CkbBlockData) -> Unit)? = null
    var onStatusChanged: ((String) -> Unit)? = null

    fun setEndpoint(url: String) {
        rpcUrl = url.trim()
        lastProcessedBlockHash = null
        restart()
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        restart()
    }

    fun stop() {
        isRunning = false
        pollJob?.cancel()
        pollJob = null
    }

    private fun restart() {
        pollJob?.cancel()
        pollJob = scope.launch(Dispatchers.IO) {
            onStatusChanged?.invoke("CONNECTING")
            // Fetch initial baseline tip (do not trigger new-block animation on load)
            try {
                val tip = getTipHeader()
                if (tip != null) {
                    val hash = tip.optString("hash")
                    val numberHex = tip.optString("number")
                    val blockNum = parseHexLong(numberHex)
                    lastProcessedBlockHash = hash
                    onStatusChanged?.invoke("LIVE: #$blockNum")

                    val fullBlock = getBlock(hash)
                    val baselineData = buildBlockData(tip, fullBlock)
                    withContext(Dispatchers.Main) {
                        onBaselineLoaded?.invoke(baselineData)
                    }
                }
            } catch (e: Exception) {
                onStatusChanged?.invoke("RPC ERROR")
            }

            // Polling loop (every ~3.2s for responsive block arrival)
            while (isActive && isRunning) {
                delay(3200)
                try {
                    val tip = getTipHeader() ?: continue
                    val hash = tip.optString("hash")
                    if (hash.isNotEmpty() && hash != lastProcessedBlockHash) {
                        val wasBaselineMissing = (lastProcessedBlockHash == null)
                        lastProcessedBlockHash = hash
                        val blockNum = parseHexLong(tip.optString("number"))
                        onStatusChanged?.invoke("NEW BLOCK #$blockNum")

                        val fullBlock = getBlock(hash)
                        val blockData = buildBlockData(tip, fullBlock)
                        withContext(Dispatchers.Main) {
                            if (wasBaselineMissing) {
                                onBaselineLoaded?.invoke(blockData)
                            } else {
                                onBlockDetected?.invoke(blockData)
                            }
                            onStatusChanged?.invoke("LIVE: #$blockNum")
                        }
                    }
                } catch (e: Exception) {
                    onStatusChanged?.invoke("RPC TIMEOUT")
                }
            }
        }
    }

    private fun buildBlockData(headerJson: JSONObject, fullBlockJson: JSONObject?): CkbBlockData {
        val hdr = fullBlockJson?.optJSONObject("header") ?: headerJson
        val hash = hdr.optString("hash")
        val blockNum = parseHexLong(hdr.optString("number"))
        val timestampMs = parseHexLong(hdr.optString("timestamp"))
        val epochRaw = parseHexLong(hdr.optString("epoch"))
        val epochNumber = epochRaw and 0xFFFFFFL
        val epochIndex = (epochRaw ushr 24) and 0xFFFFL
        val epochLength = (epochRaw ushr 40) and 0xFFFFL

        val cells = if (fullBlockJson != null) extractCells(fullBlockJson) else emptyList()
        val txArray = fullBlockJson?.optJSONArray("transactions")
        val txCount = txArray?.length() ?: 1
        val proposalsCount = fullBlockJson?.optJSONArray("proposals")?.length() ?: 0

        return CkbBlockData(
            blockNumber = blockNum,
            blockHash = hash,
            txCount = txCount,
            cells = cells,
            timestampMs = timestampMs,
            epochNumber = epochNumber,
            epochIndex = epochIndex,
            epochLength = epochLength,
            parentHash = hdr.optString("parent_hash", "0x0"),
            transactionsRoot = hdr.optString("transactions_root", "0x0"),
            proposalsCount = proposalsCount,
            compactTarget = hdr.optString("compact_target", "0x0"),
            nonce = hdr.optString("nonce", "0x0"),
            version = parseHexLong(hdr.optString("version", "0x0")).toString(),
            isDemo = false
        )
    }

    private fun rpcCall(method: String, params: JSONArray): JSONObject? {
        val jsonReq = JSONObject().apply {
            put("id", System.currentTimeMillis())
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        }

        val url = URL(rpcUrl)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 6000
            readTimeout = 6000
            doOutput = true
        }

        OutputStreamWriter(conn.outputStream).use { it.write(jsonReq.toString()) }

        if (conn.responseCode == 200) {
            val responseText = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            val respJson = JSONObject(responseText)
            return respJson.optJSONObject("result")
        }
        return null
    }

    private fun getTipHeader(): JSONObject? {
        return rpcCall("get_tip_header", JSONArray())
    }

    private fun getBlock(hash: String): JSONObject? {
        val params = JSONArray().apply { put(hash) }
        return rpcCall("get_block", params)
    }

    /**
     * Strict CKB Cell Model Invariant:
     * Every entry in every transaction's outputs array is one newly created CKB Cell.
     */
    private fun extractCells(blockJson: JSONObject): List<CkbCellSpecimen> {
        val cells = mutableListOf<CkbCellSpecimen>()
        val txs = blockJson.optJSONArray("transactions") ?: return cells

        for (t in 0 until txs.length()) {
            val tx = txs.getJSONObject(t)
            val txHash = tx.optString("hash")
            val outputs = tx.optJSONArray("outputs") ?: continue
            val outputsData = tx.optJSONArray("outputs_data")

            for (i in 0 until outputs.length()) {
                val output = outputs.getJSONObject(i)
                val capacityHex = output.optString("capacity", "0x0")
                val hasType = output.optJSONObject("type") != null
                val data = if (outputsData != null && i < outputsData.length()) outputsData.optString(i, "0x") else "0x"
                val dataBytes = if (data.length > 2) (data.length - 2) / 2 else 0

                cells.add(
                    CkbCellSpecimen(
                        txHash = txHash,
                        outputIndex = i,
                        outPoint = "$txHash:$i",
                        capacityHex = capacityHex,
                        capacityCkb = formatCapacity(capacityHex),
                        hasTypeScript = hasType,
                        dataBytes = dataBytes
                    )
                )
            }
        }
        return cells
    }

    fun simulateMockBlock(): CkbBlockData {
        val count = 12 + Random.nextInt(25)
        val mockHash = "0x" + (1..64).map { "0123456789abcdef".random() }.joinToString("")
        val mockCells = mutableListOf<CkbCellSpecimen>()

        for (i in 0 until count) {
            val cap = (Random.nextLong(61, 800) * 100_000_000L).toString(16)
            mockCells.add(
                CkbCellSpecimen(
                    txHash = mockHash,
                    outputIndex = i,
                    outPoint = "$mockHash:$i",
                    capacityHex = "0x$cap",
                    capacityCkb = "${Random.nextInt(61, 800)} CKB",
                    hasTypeScript = Random.nextBoolean(),
                    dataBytes = if (Random.nextBoolean()) Random.nextInt(8, 128) else 0
                )
            )
        }

        val blockNum = 17_234_568L + Random.nextInt(100)
        return CkbBlockData(
            blockNumber = blockNum,
            blockHash = mockHash,
            txCount = (count / 3) + 1,
            cells = mockCells,
            timestampMs = System.currentTimeMillis(),
            epochNumber = 14321L,
            epochIndex = 622L,
            epochLength = 1800L,
            isDemo = true
        )
    }

    private fun parseHexLong(hex: String): Long {
        return try {
            val clean = if (hex.startsWith("0x", ignoreCase = true)) hex.substring(2) else hex
            if (clean.isEmpty()) 0L else BigInteger(clean, 16).toLong()
        } catch (e: Exception) {
            0L
        }
    }

    private fun formatCapacity(hexShannons: String): String {
        return try {
            val cleanHex = if (hexShannons.startsWith("0x", true)) hexShannons.substring(2) else hexShannons
            val shannons = BigInteger(cleanHex, 16)
            val ckbDiv = BigInteger.valueOf(100_000_000L)
            val ckbWhole = shannons.divide(ckbDiv)
            val rem = shannons.remainder(ckbDiv)
            if (rem == BigInteger.ZERO) {
                "$ckbWhole CKB"
            } else {
                val remStr = rem.toString().padStart(8, '0').take(2)
                "$ckbWhole.$remStr CKB"
            }
        } catch (e: Exception) {
            "0 CKB"
        }
    }
}

data class CkbBlockData(
    val blockNumber: Long,
    val blockHash: String,
    val txCount: Int,
    val cells: List<CkbCellSpecimen>,
    val timestampMs: Long = System.currentTimeMillis(),
    val epochNumber: Long = 0L,
    val epochIndex: Long = 0L,
    val epochLength: Long = 1800L,
    val parentHash: String = "0x0",
    val transactionsRoot: String = "0x0",
    val proposalsCount: Int = 0,
    val compactTarget: String = "0x1a08a97e",
    val nonce: String = "0x0",
    val version: String = "0",
    val isDemo: Boolean = false
)

data class CkbCellSpecimen(
    val txHash: String,
    val outputIndex: Int,
    val outPoint: String,
    val capacityHex: String,
    val capacityCkb: String,
    val hasTypeScript: Boolean,
    val dataBytes: Int
)
