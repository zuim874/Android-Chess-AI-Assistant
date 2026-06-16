package com.chessautoplayer.chess

import android.content.Context
import com.chessautoplayer.chess.ChessBoard.pieceColor
import com.chessautoplayer.chess.ChessBoard.pieceType
import java.io.*

/**
 * 开局库 / 棋谱库
 *
 * 通过 Zobrist 哈希记录每个局面的最佳走法及其胜负统计。
 * 持久化到文件，Service 重启后自动加载。
 *
 * 文件格式（每行一条）：
 *   hexHash:fromRow,fromCol,toRow,toCol:wins:losses:draws
 */
object OpeningBook {

    private const val FILE_NAME = "opening_book.dat"
    private const val MIN_GAMES_FOR_USE = 3            // 至少对弈 N 局才参考
    private const val MIN_WIN_RATE_FOR_USE = 0.35f     // 胜率太低不采用
    private const val MAX_ENTRIES = 2_000_000          // 内存上限≈1GB（约400-600 bytes/局面）
    private const val EVICT_BATCH = 50_000             // 每次淘汰数量，避免频繁进入淘汰逻辑

    /** 单条棋谱记录 */
    data class BookEntry(
        val move: Move,
        var wins: Int = 0,
        var losses: Int = 0,
        var draws: Int = 0
    ) {
        val total: Int get() = wins + losses + draws
        val winRate: Float get() = if (total > 0) wins.toFloat() / total else 0f
    }

    // Long（Zobrist 哈希） → 走法列表
    private val book = LinkedHashMap<Long, MutableList<BookEntry>>()

    var entryCount: Int = 0
        private set

    /** 缓存总走法记录数，避免 stats() 遍历 */
    private var totalMoveRecords: Int = 0

    /** Zobrist 随机数表（与 ChessEngine 同种子，确保哈希一致） */
    private val zobristTable: Array<Array<LongArray>>
    private val zobristSide: Long

    init {
        val rand = java.util.Random(0xDEADBEEFL)
        zobristTable = Array(10) { Array(9) { LongArray(16) { rand.nextLong() } } }
        zobristSide = rand.nextLong()
    }

    // ==================== 哈希计算 ====================

    fun computeHash(board: Array<IntArray>, side: Byte): Long {
        var h = 0L
        for (r in 0..9) {
            for (c in 0..8) {
                val p = board[r][c]
                if (p != 0) {
                    val code = (pieceType(p).toInt() shl 1) or pieceColor(p).toInt()
                    h = h xor zobristTable[r][c][code]
                }
            }
        }
        if (side == ChessBoard.BLACK) h = h xor zobristSide
        return h
    }

    // ==================== 查询 ====================

    /** 线程安全锁 */
    private val lock = Any()

    /**
     * 查询棋谱库中的最佳走法
     * @return BookEntry 或 null
     */
    fun query(hash: Long): BookEntry? = synchronized(lock) {
        val entries = book[hash] ?: return@synchronized null
        return@synchronized entries
            .filter { it.total >= MIN_GAMES_FOR_USE && it.winRate >= MIN_WIN_RATE_FOR_USE }
            .maxByOrNull { it.winRate }
            ?: entries.maxByOrNull { it.total }  // 无达标项则用次数最多的
    }

    /**
     * 快速查询：是否包含该局面
     */
    fun contains(hash: Long): Boolean = synchronized(lock) { book.containsKey(hash) }

    // ==================== 记录 ====================

    /**
     * 记录一局中某一步的结果。
     * @param hash  走子前的局面哈希
     * @param move  实际走法
     * @param outcome  -1=负, 0=和, 1=胜（从走子方视角）
     */
    fun record(hash: Long, move: Move, outcome: Int) = synchronized(lock) {
        doRecord(hash, move, outcome)
    }

    /** 批量记录：一局只 synchronized 一次，大幅减少锁竞争 */
    fun recordBatch(batch: List<Triple<Long, Move, Int>>) = synchronized(lock) {
        for ((hash, move, outcome) in batch) {
            doRecord(hash, move, outcome)
        }
    }

    private fun doRecord(hash: Long, move: Move, outcome: Int) {
        val entries = book.getOrPut(hash) { mutableListOf() }

        val existing = entries.find { it.move == move }
        if (existing != null) {
            when {
                outcome > 0 -> existing.wins++
                outcome < 0 -> existing.losses++
                else -> existing.draws++
            }
        } else {
            val entry = BookEntry(move = move)
            when {
                outcome > 0 -> entry.wins = 1
                outcome < 0 -> entry.losses = 1
                else -> entry.draws = 1
            }
            entries.add(entry)
            totalMoveRecords++
        }
        entryCount = book.size

        // 内存上限保护：超过上限时优先淘汰数据量最少的局面（统计价值最低）
        if (book.size > MAX_ENTRIES) {
            // 按每条局面的走法总数升序排列，优先淘汰最"冷门"的局面
            val toRemove = book.entries
                .sortedBy { it.value.sumOf { e -> e.total } }
                .take(EVICT_BATCH)
            for ((key, entries) in toRemove) {
                totalMoveRecords -= entries.size
                book.remove(key)
            }
        }
    }

    // ==================== 持久化 ====================

    fun save(context: Context) = synchronized(lock) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            BufferedWriter(FileWriter(file)).use { writer ->
                for ((hash, entries) in book) {
                    val hex = hash.toULong().toString(16)
                    for (entry in entries) {
                        val m = entry.move
                        writer.write("$hex:${m.fromRow},${m.fromCol},${m.toRow},${m.toCol}:${entry.wins},${entry.losses},${entry.draws}")
                        writer.newLine()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun load(context: Context): Int = synchronized(lock) {
        book.clear()
        totalMoveRecords = 0
        var count = 0
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return@synchronized 0

            BufferedReader(FileReader(file)).use { reader ->
                var line: String? = reader.readLine()
                while (line != null) {
                    val parts = line!!.split(":")
                    if (parts.size == 3) {
                        val hash = parts[0].toULong(16).toLong()
                        val coords = parts[1].split(",")
                        if (coords.size == 4) {
                            val move = Move(
                                coords[0].toInt(), coords[1].toInt(),
                                coords[2].toInt(), coords[3].toInt()
                            )
                            val stats = parts[2].split(",")
                            val entry = BookEntry(
                                move = move,
                                wins = stats[0].toInt(),
                                losses = stats[1].toInt(),
                                draws = stats[2].toInt()
                            )
                            book.getOrPut(hash) { mutableListOf() }.add(entry)
                            count++
                        }
                    }
                    line = reader.readLine()
                }
            }
            entryCount = book.size
            totalMoveRecords = book.values.sumOf { it.size }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@synchronized count
    }

    /** 清空棋谱 */
    fun clear(context: Context) = synchronized(lock) {
        book.clear()
        entryCount = 0
        totalMoveRecords = 0
        save(context)
    }

    /** 导出棋谱到指定文件（便于用户备份/分享） */
    fun exportToFile(destFile: File): Int = synchronized(lock) {
        var count = 0
        try {
            BufferedWriter(FileWriter(destFile)).use { writer ->
                writer.write("# ChessOpeningBook v1\n")
                for ((hash, entries) in book) {
                    val hex = hash.toULong().toString(16)
                    for (entry in entries) {
                        val m = entry.move
                        writer.write("$hex:${m.fromRow},${m.fromCol},${m.toRow},${m.toCol}:${entry.wins},${entry.losses},${entry.draws}")
                        writer.newLine()
                        count++
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@synchronized -1
        }
        return@synchronized count
    }

    /** 从指定文件导入棋谱（合并到现有库） */
    fun importFromFile(srcFile: File): Int = synchronized(lock) {
        if (!srcFile.exists()) return@synchronized -1
        var count = 0
        try {
            BufferedReader(FileReader(srcFile)).use { reader ->
                var line: String? = reader.readLine()
                while (line != null) {
                    if (line.startsWith("#")) { line = reader.readLine(); continue }
                    val parts = line!!.split(":")
                    // 兼容两种格式：
                    //   3 parts -> hash:coords:wins,losses,draws（内部导出格式）
                    //   5 parts -> hash:coords:wins:losses:draws（下载目录导出格式）
                    var hash: Long? = null
                    var move: Move? = null
                    var wins = 0
                    var losses = 0
                    var draws = 0
                    var valid = false

                    if (parts.size == 3) {
                        hash = parts[0].toULong(16).toLong()
                        val coords = parts[1].split(",")
                        if (coords.size == 4) {
                            move = Move(
                                coords[0].toInt(), coords[1].toInt(),
                                coords[2].toInt(), coords[3].toInt()
                            )
                            val stats = parts[2].split(",")
                            if (stats.size == 3) {
                                wins = stats[0].toInt()
                                losses = stats[1].toInt()
                                draws = stats[2].toInt()
                                valid = true
                            }
                        }
                    } else if (parts.size == 5) {
                        hash = parts[0].toULong(16).toLong()
                        val coords = parts[1].split(",")
                        if (coords.size == 4) {
                            move = Move(
                                coords[0].toInt(), coords[1].toInt(),
                                coords[2].toInt(), coords[3].toInt()
                            )
                            wins = parts[2].toInt()
                            losses = parts[3].toInt()
                            draws = parts[4].toInt()
                            valid = true
                        }
                    }

                    if (valid && hash != null && move != null) {
                        val entries = book.getOrPut(hash) { mutableListOf() }
                        val existing = entries.find { it.move == move }
                        if (existing != null) {
                            existing.wins += wins
                            existing.losses += losses
                            existing.draws += draws
                        } else {
                            entries.add(BookEntry(move, wins, losses, draws))
                            totalMoveRecords++
                        }
                        count++
                    }
                    line = reader.readLine()
                }
            }
            entryCount = book.size
        } catch (e: Exception) {
            e.printStackTrace()
            return@synchronized -1
        }
        return@synchronized count
    }

    /** 获取统计信息（使用缓存计数器，O(1)） */
    fun stats(): String = synchronized(lock) {
        return@synchronized "${book.size}个局面, ${totalMoveRecords}条走法记录"
    }

    /** 获取所有棋谱条目，按总场次降序 */
    fun getAllEntries(): List<Pair<Long, BookEntry>> = synchronized(lock) {
        val result = mutableListOf<Pair<Long, BookEntry>>()
        for ((hash, entries) in book) {
            for (entry in entries) {
                result.add(hash to entry)
            }
        }
        return@synchronized result.sortedByDescending { it.second.total }
    }
}