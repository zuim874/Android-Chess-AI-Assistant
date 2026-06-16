package com.chessautoplayer.chess

import android.content.Context
import com.chessautoplayer.chess.ChessBoard.RED
import com.chessautoplayer.chess.ChessBoard.oppositeColor
import com.chessautoplayer.chess.ChessBoard.initBoard
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.random.Random

/**
 * 自对弈训练器
 *
 * 支持多局并发训练，积累棋谱到 OpeningBook。
 * 可开关动画显示，关闭时全速运行。
 */
class Trainer(private val context: Context) {

    /** 训练状态回调 */
    interface Callback {
        fun onProgress(games: Int, bookSize: Int, status: String)
        fun onGameComplete(gameIndex: Int, outcome: String)
        fun onStopped(totalGames: Int)
        /** 每走一步的实时回调，board 为深拷贝 */
        fun onMove(board: Array<IntArray>, move: Move, moveCount: Int, gameIndex: Int)
    }

    @Volatile
    var isRunning = false
        private set

    /** 是否显示动画（开启时每步有延迟和 UI 回调，关闭时全速） */
    @Volatile
    var showAnimation = true

    var gamesPlayed = 0
        private set

    private var callback: Callback? = null
    private val random = Random(System.currentTimeMillis())
    private var executor: ExecutorService? = null
    private val completedGames = AtomicInteger(0)

    /** 搜索深度 */
    private val trainDepth = 5

    /** 并发训练局数（开启动画时强制为1） */
    @Volatile
    var concurrentGames: Int = 1

    /** 随机探索概率（0~1.0），可在 UI 中调整 */
    @Volatile
    var exploreRate = 0.12f

    companion object {
        /** 最大回合数（防止无限循环） */
        private const val MAX_MOVES = 200

        /** 每 N 局自动存档（增大间隔减少IO，停止时强制保存） */
        private const val AUTO_SAVE_INTERVAL = 10

        /** 最大并发数上限：取CPU核心数，至少2个，最多16个 */
        val MAX_CONCURRENT: Int = Runtime.getRuntime().availableProcessors()
            .coerceAtLeast(2)
            .coerceAtMost(16)
    }

    // ==================== 对外接口 ====================

    fun setCallback(cb: Callback?) { callback = cb }

    /**
     * 异步启动训练
     * @param gameCount 训练局数；≤0 表示无限制
     */
    fun start(gameCount: Int = 0) {
        if (isRunning) return
        isRunning = true
        gamesPlayed = 0
        completedGames.set(0)

        val threads = concurrentGames.coerceIn(1, MAX_CONCURRENT)
        executor = Executors.newFixedThreadPool(threads)

        // 每个工作线程自己循环，避免无限任务队列积压
        for (i in 0 until threads) {
            executor?.execute {
                while (isRunning && !Thread.interrupted()) {
                    if (gameCount > 0 && completedGames.get() >= gameCount) break
                    try {
                        val outcome = playOneGame()
                        if (!isRunning || Thread.interrupted()) break
                        val done = completedGames.incrementAndGet()
                        gamesPlayed = done
                        notifyProgress(outcome)

                        if (done % AUTO_SAVE_INTERVAL == 0) {
                            OpeningBook.save(context)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        // 监控线程：等待线程池终止后通知 UI
        Thread {
            try {
                executor?.shutdown()
                executor?.awaitTermination(Long.MAX_VALUE, java.util.concurrent.TimeUnit.NANOSECONDS)
            } catch (_: InterruptedException) {}
            if (isRunning) {
                isRunning = false
                OpeningBook.save(context)
                callback?.onStopped(gamesPlayed)
            }
        }.start()
    }

    fun stop() {
        isRunning = false
        executor?.shutdownNow()
        // 立即保存当前已积累的棋谱，避免用户手动停止后数据丢失
        OpeningBook.save(context)
    }

    // ==================== 单局对弈 ====================

    private fun playOneGame(): String {
        val board = initBoard()
        val history = mutableListOf<Triple<Long, Byte, Move>>()
        val hashes = mutableSetOf<Long>()
        var color: Byte = RED
        var moveCount = 0
        val currentGameIndex = completedGames.get() + 1
        // 本局内动画设置保持不变，避免中途切换导致混乱
        val useAnimation = showAnimation

        while (moveCount < MAX_MOVES && isRunning && !Thread.interrupted()) {
            val hash = OpeningBook.computeHash(board, color)

            if (hashes.contains(hash)) {
                recordHistory(history, 0)
                return "和棋(重复)"
            }
            hashes.add(hash)

            val result = searchWithExplore(board, color)
            val move = result?.move
            if (move == null) {
                val loser = if (color == RED) "黑胜" else "红胜"
                recordHistory(history, if (color == RED) -1 else 1)
                return loser
            }

            history.add(Triple(hash, color, move))

            val captured = board[move.toRow][move.toCol]
            board[move.toRow][move.toCol] = board[move.fromRow][move.fromCol]
            board[move.fromRow][move.fromCol] = 0

            if (useAnimation) {
                callback?.onMove(
                    board.map { it.copyOf() }.toTypedArray(),
                    move, moveCount, currentGameIndex
                )
                try { Thread.sleep(200) } catch (_: InterruptedException) { break }
            }

            moveCount++
            color = oppositeColor(color)
        }

        recordHistory(history, 0)
        return "和棋(回合)"
    }

    /** 搜索 + 随机探索 */
    private fun searchWithExplore(board: Array<IntArray>, color: Byte): ChessEngine.BestMove? {
        if (!isRunning || Thread.interrupted()) return null
        val result = ChessEngine.search(board, color, trainDepth)

        if (random.nextFloat() < exploreRate && result.move != null) {
            val allMoves = MoveGenerator.generateMoves(board, color)
            if (allMoves.size > 1) {
                val others = allMoves.filter { it != result.move }
                if (others.isNotEmpty()) {
                    val picked = others[random.nextInt(others.size)]
                    return ChessEngine.BestMove(picked, 0)
                }
            }
        }
        return result
    }

    /** 回放历史，批量计入 OpeningBook（一局只synchronized一次） */
    private fun recordHistory(
        history: List<Triple<Long, Byte, Move>>,
        finalOutcome: Int
    ) {
        val batch = history.map { (hash, side, move) ->
            val outcomeForMover = when {
                finalOutcome == 0 -> 0
                side == RED -> finalOutcome
                else -> -finalOutcome
            }
            Triple(hash, move, outcomeForMover)
        }
        OpeningBook.recordBatch(batch)
    }

    private fun notifyProgress(outcome: String) {
        callback?.onProgress(
            gamesPlayed,
            OpeningBook.entryCount,
            "第${gamesPlayed}局: $outcome  |  ${OpeningBook.stats()}"
        )
    }
}
