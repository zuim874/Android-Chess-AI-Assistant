package com.chessautoplayer.chess

import com.chessautoplayer.chess.ChessBoard.RED
import com.chessautoplayer.chess.ChessBoard.oppositeColor
import com.chessautoplayer.chess.ChessBoard.pieceColor
import com.chessautoplayer.chess.ChessBoard.pieceType
import kotlin.math.abs
import kotlin.math.min

/**
 * AI 象棋引擎
 *
 * 使用 PVS (Principal Variation Search) + Alpha-Beta 剪枝
 * + 迭代加深 + 静态搜索 + 杀手走法 + 历史启发
 * + 置换表 (Transposition Table) + 空着裁剪 +  aspiration 窗口
 *
 * 默认搜索深度 6 层 + 静态搜索（吃子不限制深度）
 */
object ChessEngine {

    private const val DEFAULT_DEPTH = 6
    private const val MAX_DEPTH = 8
    private const val MAX_Q_DEPTH = 8   // 静态搜索最大深度，防止吃子链无限递归
    private const val MATE_SCORE = 50000
    private const val TT_SIZE = 1 shl 20  // ~100万条目

    private const val TT_EXACT: Byte = 0
    private const val TT_LOWER: Byte = 1
    private const val TT_UPPER: Byte = 2

    data class BestMove(val move: Move?, val score: Int)

    // ==================== 置换表 ====================

    private data class TTEntry(
        var hash: Long = 0L,
        var depth: Int = 0,
        var score: Int = 0,
        var flag: Byte = TT_EXACT,
        var bestMove: Move? = null
    )

    private val tt = Array(TT_SIZE) { TTEntry() }
    private val ttMask = (TT_SIZE - 1).toLong()

    /** Zobrist 随机数表 [row][col][pieceCode]，pieceCode = type*2 + color (1..14) */
    private val zobristTable: Array<Array<LongArray>>
    private val zobristSide: Long

    init {
        val rand = java.util.Random(0xDEADBEEFL)
        zobristTable = Array(10) { Array(9) { LongArray(16) { rand.nextLong() } } }
        zobristSide = rand.nextLong()
    }

    private fun computeHash(board: Array<IntArray>): Long {
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
        return h
    }

    private fun ttIndex(hash: Long): Int = (hash and ttMask).toInt()

    private fun storeTT(hash: Long, depth: Int, score: Int, flag: Byte, bestMove: Move?) {
        val idx = ttIndex(hash)
        val entry = tt[idx]
        // 总是替换（或更深的替换）
        if (entry.hash == 0L || depth >= entry.depth) {
            entry.hash = hash
            entry.depth = depth
            entry.score = score
            entry.flag = flag
            entry.bestMove = bestMove
        }
    }

    private fun probeTT(hash: Long, depth: Int, alpha: Int, beta: Int): Int? {
        val entry = tt[ttIndex(hash)]
        if (entry.hash == hash && entry.depth >= depth) {
            when (entry.flag) {
                TT_EXACT -> return entry.score
                TT_LOWER -> if (entry.score >= beta) return entry.score
                TT_UPPER -> if (entry.score <= alpha) return entry.score
            }
        }
        return null
    }

    // ==================== 搜索上下文（每次搜索独立，线程安全） ====================

    private class SearchContext(val maxDepth: Int) {
        val killerMoves = Array<Array<Move?>>(maxDepth + 2) { arrayOfNulls(2) }
        val historyTable = Array(8) { Array(10) { IntArray(9) } }
        var nodesSearched = 0
        var bookMove: Move? = null

        fun clearKillers() {
            for (d in killerMoves.indices) {
                killerMoves[d][0] = null
                killerMoves[d][1] = null
            }
        }

        fun clearHistory() {
            for (t in historyTable.indices)
                for (r in 0..9)
                    for (c in 0..8)
                        historyTable[t][r][c] = 0
        }

        fun recordKiller(move: Move, depth: Int, piece: Int) {
            if (depth !in killerMoves.indices) return
            val killer = killerMoves[depth]
            if (killer[0] == move) return
            killer[1] = killer[0]
            killer[0] = move

            val pType = pieceType(piece).toInt()
            if (pType in 0..7 && move.toRow in 0..9 && move.toCol in 0..8) {
                historyTable[pType][move.toRow][move.toCol] += depth * depth
                if (historyTable[pType][move.toRow][move.toCol] > 1_000_000) {
                    for (r in 0..9) for (c in 0..8) historyTable[pType][r][c] = historyTable[pType][r][c] shr 1
                }
            }
        }
    }

    // ==================== 主入口 ====================

    fun search(
        board: Array<IntArray>,
        color: Byte,
        depth: Int = DEFAULT_DEPTH
    ): BestMove {
        val ctx = SearchContext(MAX_DEPTH + 2)
        ctx.clearKillers()
        ctx.clearHistory()

        // ── 查询棋谱库作为参考（不直接采用，仅提升排序优先级） ──
        val bookHash = OpeningBook.computeHash(board, color)
        val bookEntry = OpeningBook.query(bookHash)
        ctx.bookMove = bookEntry?.move

        var bestMove: Move? = null
        var bestScore = 0
        var lastScore = 0

        val maxD = min(depth, MAX_DEPTH)
        val rootHash = computeHash(board)

        // ★ 只拷贝一次，全程 in-place 修改，消除 O(90) per-node 分配
        val workBoard = board.map { it.copyOf() }.toTypedArray()

        for (d in 1..maxD) {
            val window = if (d >= 2) 50 else Int.MAX_VALUE
            val alpha = if (d >= 2) lastScore - window else Int.MIN_VALUE + MATE_SCORE
            val beta = if (d >= 2) lastScore + window else Int.MAX_VALUE - MATE_SCORE

            val result = searchRoot(ctx, workBoard, color, d, rootHash, alpha, beta)
            bestMove = result.move
            bestScore = result.score
            lastScore = bestScore

            // aspiration 窗口失败则重搜全窗口
            if (d >= 2 && (bestScore <= alpha || bestScore >= beta)) {
                val fullResult = searchRoot(ctx, workBoard, color, d, rootHash, Int.MIN_VALUE + MATE_SCORE, Int.MAX_VALUE - MATE_SCORE)
                bestMove = fullResult.move
                bestScore = fullResult.score
                lastScore = bestScore
            }

            // 找到杀棋则提前结束
            if (bestScore > MATE_SCORE / 2) break
        }

        return BestMove(bestMove, bestScore)
    }

    // ==================== 根节点搜索 ====================

    private fun searchRoot(
        ctx: SearchContext,
        board: Array<IntArray>,
        color: Byte,
        depth: Int,
        hash: Long,
        alpha: Int,
        beta: Int
    ): BestMove {
        var bestMove: Move? = null
        var bestScore = Int.MIN_VALUE + MATE_SCORE

        // 生成不检查将军的原始走法，in-place 搜索中自行校验合法性
        var moves = orderMoves(ctx, board, MoveGenerator.generateMovesRaw(board, color), 0, tt[ttIndex(hash)].bestMove)

        val bookMove = ctx.bookMove
        if (bookMove != null && bookMove in moves) {
            moves = listOf(bookMove) + moves.filter { it != bookMove }
        }

        if (moves.isEmpty()) {
            return BestMove(null, if (MoveGenerator.isKingInCheck(board, color)) -MATE_SCORE else 0)
        }

        val oppColor = oppositeColor(color)

        for ((i, move) in moves.withIndex()) {
            // 更新 hash（必须在 apply 前，需要读取原棋盘状态）
            val newHash = updateHash(hash, board, move)
            val captured = MoveGenerator.applyMove(board, move)

            // 校验合法性：走完这步后，己方将帅不能被将军
            if (MoveGenerator.isKingInCheck(board, color)) {
                MoveGenerator.undoMove(board, move, captured)
                continue
            }

            val score: Int = if (i == 0) {
                -alphaBeta(ctx, board, depth - 1, -beta, -alpha, oppColor, false, newHash)
            } else {
                val scout = -alphaBeta(ctx, board, depth - 1, -(bestScore + 1), -bestScore, oppColor, false, newHash)
                if (scout > bestScore) {
                    -alphaBeta(ctx, board, depth - 1, -beta, -bestScore, oppColor, false, newHash)
                } else {
                    scout
                }
            }

            MoveGenerator.undoMove(board, move, captured)
            ctx.nodesSearched++

            if (score > bestScore) {
                bestScore = score
                bestMove = move
            }
        }

        storeTT(hash, depth, bestScore, TT_EXACT, bestMove)
        return BestMove(bestMove, bestScore)
    }

    // ==================== Alpha-Beta 递归搜索 ====================

    private fun alphaBeta(
        ctx: SearchContext,
        board: Array<IntArray>,
        depth: Int,
        alpha: Int,
        beta: Int,
        color: Byte,
        allowNull: Boolean,
        hash: Long
    ): Int {
        if (Thread.interrupted()) return 0

        var a = alpha
        val b = beta

        // 置换表查询
        val ttScore = probeTT(hash, depth, a, b)
        if (ttScore != null) return ttScore

        // ── 叶节点：静态搜索 ──
        if (depth <= 0) {
            return quiescence(ctx, board, a, b, color, hash, MAX_Q_DEPTH)
        }

        // ── 空着裁剪（Null Move Pruning） ──
        if (allowNull && depth >= 3) {
            val oppColor = oppositeColor(color)
            if (!MoveGenerator.isKingInCheck(board, color)) {
                val score = -alphaBeta(ctx, board, depth - 3, -b, -b + 1, oppColor, false, hash xor zobristSide)
                if (score >= b) return b
            }
        }

        val moves = orderMoves(ctx, board, MoveGenerator.generateMovesRaw(board, color), depth, tt[ttIndex(hash)].bestMove)

        if (moves.isEmpty()) {
            return if (MoveGenerator.isKingInCheck(board, color)) {
                -MATE_SCORE + (DEFAULT_DEPTH - depth) * 10
            } else {
                0
            }
        }

        val oppColor = oppositeColor(color)
        var isFirst = true
        var bestMove: Move? = null

        for ((i, move) in moves.withIndex()) {
            val isCapture = board[move.toRow][move.toCol] != 0
            val newHash = updateHash(hash, board, move)
            val captured = MoveGenerator.applyMove(board, move)

            // 校验合法性
            if (MoveGenerator.isKingInCheck(board, color)) {
                MoveGenerator.undoMove(board, move, captured)
                continue
            }

            var score: Int

            // Late Move Reduction (LMR)：对后期非吃子走法减层
            if (!isFirst && depth >= 3 && !isCapture && i > 3) {
                val reducedDepth = depth - 2
                score = -alphaBeta(ctx, board, reducedDepth, -(a + 1), -a, oppColor, true, newHash)
                if (score > a) {
                    score = -alphaBeta(ctx, board, depth - 1, -(a + 1), -a, oppColor, true, newHash)
                    // 若仍优于 alpha，再全窗口重搜以确认真实分数
                    if (score > a) {
                        score = -alphaBeta(ctx, board, depth - 1, -b, -a, oppColor, true, newHash)
                    }
                }
            } else if (isFirst) {
                score = -alphaBeta(ctx, board, depth - 1, -b, -a, oppColor, true, newHash)
                isFirst = false
            } else {
                val scout = -alphaBeta(ctx, board, depth - 1, -(a + 1), -a, oppColor, true, newHash)
                score = if (scout > a && scout < b) {
                    -alphaBeta(ctx, board, depth - 1, -b, -a, oppColor, true, newHash)
                } else {
                    scout
                }
            }

            MoveGenerator.undoMove(board, move, captured)

            if (score >= b) {
                if (!isCapture) {
                    val piece = board[move.fromRow][move.fromCol]
                    if (pieceType(piece).toInt() in 0..7) {
                        ctx.recordKiller(move, depth, piece)
                    }
                }
                storeTT(hash, depth, b, TT_LOWER, move)
                return b
            }

            if (score > a) {
                a = score
                bestMove = move
            }
        }

        val flag = if (bestMove == null) TT_UPPER else TT_EXACT
        storeTT(hash, depth, a, flag, bestMove)
        return a
    }

    // ==================== 静态搜索 ====================

    private fun quiescence(
        ctx: SearchContext,
        board: Array<IntArray>,
        alpha: Int,
        beta: Int,
        color: Byte,
        hash: Long,
        qDepth: Int
    ): Int {
        var a = alpha

        val standPat = Evaluator.evaluate(board).let { if (color == RED) it else -it }
        if (standPat >= beta) return beta
        if (standPat > a) a = standPat

        // 静态搜索深度耗尽，直接返回站位分
        if (qDepth <= 0) return a

        // ☆ 使用直接捕获生成器，避免生成全量走法再过滤
        val captures = MoveGenerator.generateCaptures(board, color)

        // 快速 MVV-LVA 排序
        val ordered = captures.sortedByDescending { move ->
            val victim = board[move.toRow][move.toCol]
            val attacker = board[move.fromRow][move.fromCol]
            (Evaluator.PIECE_VALUES[pieceType(victim)] ?: 0) * 10 -
            (Evaluator.PIECE_VALUES[pieceType(attacker)] ?: 0)
        }

        val oppColor = oppositeColor(color)

        for (move in ordered) {
            // 更强的 delta pruning：站位分 + 子力价值 + 安全边际 < alpha 则跳过
            val victimValue = Evaluator.PIECE_VALUES[pieceType(board[move.toRow][move.toCol])] ?: 0
            if (standPat + victimValue + 300 < a) continue

            val newHash = updateHash(hash, board, move)
            val captured = MoveGenerator.applyMove(board, move)

            if (MoveGenerator.isKingInCheck(board, color)) {
                MoveGenerator.undoMove(board, move, captured)
                continue
            }

            val score = -quiescence(ctx, board, -beta, -a, oppColor, newHash, qDepth - 1)
            ctx.nodesSearched++
            MoveGenerator.undoMove(board, move, captured)

            if (score >= beta) return beta
            if (score > a) a = score
        }

        return a
    }

    // ==================== 走法排序 ====================

    private fun orderMoves(
        ctx: SearchContext,
        board: Array<IntArray>,
        moves: List<Move>,
        depth: Int,
        hashMove: Move?
    ): List<Move> {
        return moves.sortedByDescending { move ->
            var score = 0

            if (move == hashMove) score += 2_000_000

            if (depth in ctx.killerMoves.indices) {
                if (move == ctx.killerMoves[depth][0]) score += 900_000
                if (move == ctx.killerMoves[depth][1]) score += 800_000
            }

            val targetPiece = board[move.toRow][move.toCol]
            if (targetPiece != 0) {
                val victimValue = Evaluator.PIECE_VALUES[pieceType(targetPiece)] ?: 0
                val attackerType = pieceType(board[move.fromRow][move.fromCol])
                val attackerValue = Evaluator.PIECE_VALUES[attackerType] ?: 0
                score += 700_000 + victimValue * 10 - attackerValue
            }

            val piece = board[move.fromRow][move.fromCol]
            val pType = pieceType(piece).toInt()
            if (pType in 0..7 && move.toRow in 0..9 && move.toCol in 0..8) {
                score += ctx.historyTable[pType][move.toRow][move.toCol] / 100
            }

            score += (4 - abs(move.toCol - 4)) * 2
            score
        }
    }

    // ==================== Zobrist 增量更新 ====================

    private fun updateHash(hash: Long, board: Array<IntArray>, move: Move): Long {
        var h = hash
        val movedPiece = board[move.fromRow][move.fromCol]
        val capturedPiece = board[move.toRow][move.toCol]

        // 移除原位置的移动棋子
        if (movedPiece != 0) {
            val code = (pieceType(movedPiece).toInt() shl 1) or pieceColor(movedPiece).toInt()
            h = h xor zobristTable[move.fromRow][move.fromCol][code]
        }
        // 移除目标位置的被吃棋子
        if (capturedPiece != 0) {
            val code = (pieceType(capturedPiece).toInt() shl 1) or pieceColor(capturedPiece).toInt()
            h = h xor zobristTable[move.toRow][move.toCol][code]
        }
        // 在目标位置放置移动棋子
        if (movedPiece != 0) {
            val code = (pieceType(movedPiece).toInt() shl 1) or pieceColor(movedPiece).toInt()
            h = h xor zobristTable[move.toRow][move.toCol][code]
        }
        // 换手
        h = h xor zobristSide
        return h
    }
}
