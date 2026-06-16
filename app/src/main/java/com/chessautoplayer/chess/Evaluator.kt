package com.chessautoplayer.chess

import com.chessautoplayer.chess.ChessBoard.ADVISOR
import com.chessautoplayer.chess.ChessBoard.BLACK
import com.chessautoplayer.chess.ChessBoard.CANNON
import com.chessautoplayer.chess.ChessBoard.ELEPHANT
import com.chessautoplayer.chess.ChessBoard.HORSE
import com.chessautoplayer.chess.ChessBoard.KING
import com.chessautoplayer.chess.ChessBoard.PAWN
import com.chessautoplayer.chess.ChessBoard.RED
import com.chessautoplayer.chess.ChessBoard.ROOK
import com.chessautoplayer.chess.ChessBoard.pieceColor
import com.chessautoplayer.chess.ChessBoard.pieceType

/**
 * 局面评估器
 * 
 * 评估要素：
 * - 棋子基础价值
 * - 棋子位置加分（位置表）
 * - 灵活性（可走位置数）
 */
object Evaluator {

    // 棋子基础价值
    internal val PIECE_VALUES = mapOf(
        KING to 10000,
        ROOK to 600,
        CANNON to 300,
        HORSE to 270,
        ELEPHANT to 120,
        ADVISOR to 120,
        PAWN to 30
    )

    // 过河兵价值加倍
    private const val CROSSED_PAWN_BONUS = 50

    // 兵过河后位置价值表（红方视角，行=row 列=col）
    private val PAWN_POS_RED = arrayOf(
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(10, 10, 20, 30, 40, 30, 20, 10, 10),   // 过河
        intArrayOf(20, 20, 30, 40, 50, 40, 30, 20, 20),
        intArrayOf(20, 25, 35, 45, 55, 45, 35, 25, 20),
        intArrayOf(30, 40, 50, 60, 70, 60, 50, 40, 30),
        intArrayOf(50, 60, 70, 80, 90, 80, 70, 60, 50),
        intArrayOf(70, 80, 90, 100, 110, 100, 90, 80, 70)  // 底线
    )

    // 马位置价值表
    private val HORSE_POS_RED = arrayOf(
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 10, 10, 10, 10, 10, 0, 0),
        intArrayOf(5, 10, 20, 20, 20, 20, 20, 10, 5),
        intArrayOf(5, 10, 20, 30, 30, 30, 20, 10, 5),
        intArrayOf(5, 10, 25, 30, 35, 30, 25, 10, 5),
        intArrayOf(5, 10, 25, 30, 35, 30, 25, 10, 5),
        intArrayOf(5, 10, 20, 25, 25, 25, 20, 10, 5),
        intArrayOf(5, 10, 15, 15, 15, 15, 15, 10, 5),
        intArrayOf(0, 5, 5, 10, 10, 10, 5, 5, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0)
    )

    // 车位置价值表
    private val ROOK_POS_RED = arrayOf(
        intArrayOf(10, 20, 20, 30, 30, 30, 20, 20, 10),
        intArrayOf(20, 30, 30, 40, 40, 40, 30, 30, 20),
        intArrayOf(20, 30, 30, 40, 40, 40, 30, 30, 20),
        intArrayOf(20, 30, 30, 40, 40, 40, 30, 30, 20),
        intArrayOf(20, 30, 30, 40, 40, 40, 30, 30, 20),
        intArrayOf(20, 30, 30, 40, 40, 40, 30, 30, 20),
        intArrayOf(20, 30, 30, 40, 40, 40, 30, 30, 20),
        intArrayOf(20, 30, 30, 40, 40, 40, 30, 30, 20),
        intArrayOf(20, 30, 30, 40, 40, 40, 30, 30, 20),
        intArrayOf(10, 20, 20, 30, 30, 30, 20, 20, 10)
    )

    // 炮位置价值表
    private val CANNON_POS_RED = arrayOf(
        intArrayOf(0, 5, 5, 0, 0, 0, 5, 5, 0),
        intArrayOf(5, 10, 10, 5, 5, 5, 10, 10, 5),
        intArrayOf(5, 10, 15, 10, 10, 10, 15, 10, 5),
        intArrayOf(5, 10, 10, 15, 15, 15, 10, 10, 5),
        intArrayOf(5, 10, 15, 20, 20, 20, 15, 10, 5),
        intArrayOf(5, 10, 15, 20, 20, 20, 15, 10, 5),
        intArrayOf(5, 10, 10, 15, 15, 15, 10, 10, 5),
        intArrayOf(5, 10, 10, 10, 10, 10, 10, 10, 5),
        intArrayOf(5, 5, 5, 5, 5, 5, 5, 5, 5),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0)
    )

    /**
     * 评估局面分数，正数=红优，负数=黑优
     * @param board 当前棋盘
     */
    fun evaluate(board: Array<IntArray>): Int {
        var score = 0
        for (r in 0..9) {
            for (c in 0..8) {
                val piece = board[r][c]
                if (piece == 0) continue

                val type = pieceType(piece)
                val color = pieceColor(piece)
                val baseValue = PIECE_VALUES[type] ?: 0
                val posValue = getPositionValue(type, r, c, color)

                var pieceScore = baseValue + posValue
                // 过河兵额外加分（合并到主循环，避免二次扫描）
                if (type == PAWN && !onOwnSide(r, color)) {
                    pieceScore += CROSSED_PAWN_BONUS
                }

                if (color == RED) {
                    score += pieceScore
                } else {
                    score -= pieceScore
                }
            }
        }

        return score
    }

    /** 辅助判断是否在己方半场 */
    private fun onOwnSide(r: Int, color: Byte): Boolean {
        return if (color == RED) r in 0..4 else r in 5..9
    }

    /** 获取位置价值（红方视角） */
    private fun getPositionValue(type: Byte, row: Int, col: Int, color: Byte): Int {
        val table = when (type) {
            PAWN -> PAWN_POS_RED
            HORSE -> HORSE_POS_RED
            ROOK -> ROOK_POS_RED
            CANNON -> CANNON_POS_RED
            else -> return 0
        }
        // 黑方需要翻转行
        val r = if (color == RED) row else 9 - row
        return table[r][col]
    }
}