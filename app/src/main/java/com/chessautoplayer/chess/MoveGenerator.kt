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
import com.chessautoplayer.chess.ChessBoard.oppositeColor
import com.chessautoplayer.chess.ChessBoard.pieceColor
import com.chessautoplayer.chess.ChessBoard.pieceNames
import com.chessautoplayer.chess.ChessBoard.pieceType
import com.chessautoplayer.chess.ChessBoard.sameColor

/**
 * 走法生成器 - 生成所有合法走法
 * 
 * 象棋走法规则：
 * - 帅/将：九宫格内，上下左右一步
 * - 仕/士：九宫格内，斜走一步
 * - 相/象：田字对角，不可过河，注意塞象眼
 * - 馬/马：日字形，注意蹩马脚
 * - 車/车：直线走任意格
 * - 炮：直线走任意格（不吃子），隔一子吃子
 * - 兵/卒：未过河只能前进，过河可左右前
 */
object MoveGenerator {

    // 方向数组
    private val KING_DIRS = arrayOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
    private val ADVISOR_DIRS = arrayOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)
    private val ELEPHANT_EYES = arrayOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)
    private val ELEPHANT_DSTS = arrayOf(-2 to -2, -2 to 2, 2 to -2, 2 to 2)
    private val HORSE_LEGS = arrayOf(-1 to 0, -1 to 0, 1 to 0, 1 to 0, 0 to -1, 0 to -1, 0 to 1, 0 to 1)
    private val HORSE_DSTS = arrayOf(-2 to -1, -2 to 1, 2 to -1, 2 to 1, -1 to -2, 1 to -2, -1 to 2, 1 to 2)
    private val STRAIGHT_DIRS = arrayOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)

    /** 生成指定颜色的所有合法走法（供 UI 调用，含将军过滤） */
    fun generateMoves(board: Array<IntArray>, color: Byte): List<Move> {
        return generateMovesRaw(board, color).filter { !isKingInCheck(makeMove(board, it), color) }
    }

    /** 生成所有符合走子规则的走法（不检查是否将军，供引擎内部使用） */
    fun generateMovesRaw(board: Array<IntArray>, color: Byte): List<Move> {
        val moves = mutableListOf<Move>()
        for (r in 0..9) {
            for (c in 0..8) {
                val piece = board[r][c]
                if (piece == 0 || pieceColor(piece) != color) continue
                when (pieceType(piece)) {
                    KING -> generateKingMoves(board, r, c, piece, moves)
                    ADVISOR -> generateAdvisorMoves(board, r, c, piece, moves)
                    ELEPHANT -> generateElephantMoves(board, r, c, piece, moves)
                    HORSE -> generateHorseMoves(board, r, c, piece, moves)
                    ROOK -> generateRookMoves(board, r, c, piece, moves)
                    CANNON -> generateCannonMoves(board, r, c, piece, moves)
                    PAWN -> generatePawnMoves(board, r, c, piece, moves)
                }
            }
        }
        return moves
    }

    /** 在棋盘上原地执行走法，返回被吃的子（0=没吃），用于引擎 in-place 搜索 */
    fun applyMove(board: Array<IntArray>, move: Move): Int {
        val captured = board[move.toRow][move.toCol]
        board[move.toRow][move.toCol] = board[move.fromRow][move.fromCol]
        board[move.fromRow][move.fromCol] = 0
        return captured
    }

    /** 撤销原地走法 */
    fun undoMove(board: Array<IntArray>, move: Move, capturedPiece: Int) {
        board[move.fromRow][move.fromCol] = board[move.toRow][move.toCol]
        board[move.toRow][move.toCol] = capturedPiece
    }

    /** 帅/将走法 */
    private fun generateKingMoves(board: Array<IntArray>, r: Int, c: Int, piece: Int, moves: MutableList<Move>) {
        val color = pieceColor(piece)
        for ((dr, dc) in KING_DIRS) {
            val nr = r + dr
            val nc = c + dc
            if (!inPalace(nr, nc, color)) continue
            val target = board[nr][nc]
            if (target == 0 || !sameColor(piece, target)) {
                moves.add(Move(r, c, nr, nc))
            }
        }
        // 将帅对脸：如果两将同列且之间无遮挡，可以直接吃掉对方将
        val oppositeColor = oppositeColor(color)
        val kingCol = findKing(board, oppositeColor)
        if (kingCol != null && kingCol.second == c) {
            val minR = minOf(r, kingCol.first) + 1
            val maxR = maxOf(r, kingCol.first)
            if ((minR until maxR).all { board[it][c] == 0 }) {
                moves.add(Move(r, c, kingCol.first, kingCol.second))
            }
        }
    }

    /** 仕/士走法 */
    private fun generateAdvisorMoves(board: Array<IntArray>, r: Int, c: Int, piece: Int, moves: MutableList<Move>) {
        val color = pieceColor(piece)
        for ((dr, dc) in ADVISOR_DIRS) {
            val nr = r + dr
            val nc = c + dc
            if (!inPalace(nr, nc, color)) continue
            val target = board[nr][nc]
            if (target == 0 || !sameColor(piece, target)) {
                moves.add(Move(r, c, nr, nc))
            }
        }
    }

    /** 相/象走法 */
    private fun generateElephantMoves(board: Array<IntArray>, r: Int, c: Int, piece: Int, moves: MutableList<Move>) {
        val color = pieceColor(piece)
        for (i in ELEPHANT_EYES.indices) {
            val (er, ec) = ELEPHANT_EYES[i]
            val eyeR = r + er
            val eyeC = c + ec
            if (eyeR !in 0..9 || eyeC !in 0..8) continue
            if (board[eyeR][eyeC] != 0) continue // 塞象眼
            
            val (dr, dc) = ELEPHANT_DSTS[i]
            val nr = r + dr
            val nc = c + dc
            if (nr !in 0..9 || nc !in 0..8) continue
            if (!onOwnSide(nr, color)) continue // 不可过河
            
            val target = board[nr][nc]
            if (target == 0 || !sameColor(piece, target)) {
                moves.add(Move(r, c, nr, nc))
            }
        }
    }

    /** 馬/马走法 */
    private fun generateHorseMoves(board: Array<IntArray>, r: Int, c: Int, piece: Int, moves: MutableList<Move>) {
        for (i in HORSE_LEGS.indices) {
            val (lr, lc) = HORSE_LEGS[i]
            val legR = r + lr
            val legC = c + lc
            if (legR !in 0..9 || legC !in 0..8) continue
            if (board[legR][legC] != 0) continue // 蹩马脚
            
            val (dr, dc) = HORSE_DSTS[i]
            val nr = r + dr
            val nc = c + dc
            if (nr !in 0..9 || nc !in 0..8) continue
            
            val target = board[nr][nc]
            if (target == 0 || !sameColor(piece, target)) {
                moves.add(Move(r, c, nr, nc))
            }
        }
    }

    /** 車/车走法 */
    private fun generateRookMoves(board: Array<IntArray>, r: Int, c: Int, piece: Int, moves: MutableList<Move>) {
        generateStraightMoves(board, r, c, piece, moves)
    }

    /** 炮走法 */
    private fun generateCannonMoves(board: Array<IntArray>, r: Int, c: Int, piece: Int, moves: MutableList<Move>) {
        for ((dr, dc) in STRAIGHT_DIRS) {
            var nr = r + dr
            var nc = c + dc
            // 不吃子的移动
            while (nr in 0..9 && nc in 0..8 && board[nr][nc] == 0) {
                moves.add(Move(r, c, nr, nc))
                nr += dr
                nc += dc
            }
            // 跳过第一个棋子找炮架
            if (nr in 0..9 && nc in 0..8) {
                nr += dr
                nc += dc
                while (nr in 0..9 && nc in 0..8 && board[nr][nc] == 0) {
                    nr += dr
                    nc += dc
                }
                if (nr in 0..9 && nc in 0..8) {
                    val target = board[nr][nc]
                    if (!sameColor(piece, target)) {
                        moves.add(Move(r, c, nr, nc))
                    }
                }
            }
        }
    }

    /** 兵/卒走法 */
    private fun generatePawnMoves(board: Array<IntArray>, r: Int, c: Int, piece: Int, moves: MutableList<Move>) {
        val color = pieceColor(piece)
        val forward = if (color == RED) 1 else -1
        val crossedRiver = !onOwnSide(r, color)
        
        // 前进
        val nr = r + forward
        if (nr in 0..9) {
            val target = board[nr][c]
            if (target == 0 || !sameColor(piece, target)) {
                moves.add(Move(r, c, nr, c))
            }
        }
        
        // 过河后可以左右走
        if (crossedRiver) {
            for (dc in intArrayOf(-1, 1)) {
                val nc = c + dc
                if (nc in 0..8) {
                    val target = board[r][nc]
                    if (target == 0 || !sameColor(piece, target)) {
                        moves.add(Move(r, c, r, nc))
                    }
                }
            }
        }
    }

    /** 直线走法（車用） */
    private fun generateStraightMoves(board: Array<IntArray>, r: Int, c: Int, piece: Int, moves: MutableList<Move>) {
        for ((dr, dc) in STRAIGHT_DIRS) {
            var nr = r + dr
            var nc = c + dc
            while (nr in 0..9 && nc in 0..8) {
                val target = board[nr][nc]
                if (target == 0) {
                    moves.add(Move(r, c, nr, nc))
                } else {
                    if (!sameColor(piece, target)) {
                        moves.add(Move(r, c, nr, nc))
                    }
                    break
                }
                nr += dr
                nc += dc
            }
        }
    }

    /** 判断坐标是否在九宫格内 */
    private fun inPalace(r: Int, c: Int, color: Byte): Boolean {
        if (c !in 3..5) return false
        return if (color == RED) r in 0..2 else r in 7..9
    }

    /** 判断是否在己方半场 */
    private fun onOwnSide(r: Int, color: Byte): Boolean {
        return if (color == RED) r in 0..4 else r in 5..9
    }

    /** 查找将/帅位置（将帅只能在九宫，只扫描9格而非全盘90格） */
    fun findKing(board: Array<IntArray>, color: Byte): Pair<Int, Int>? {
        val rows = if (color == RED) 0..2 else 7..9
        for (r in rows) {
            for (c in 3..5) {
                val piece = board[r][c]
                if (piece != 0 && pieceType(piece) == KING && pieceColor(piece) == color) {
                    return r to c
                }
            }
        }
        return null
    }

    /** 检查某方是否被将军 */
    fun isKingInCheck(board: Array<IntArray>, color: Byte): Boolean {
        val kingPos = findKing(board, color) ?: return true // 将被吃=被将军
        val oppositeColor = oppositeColor(color)
        
        // 检查車/将直线攻击
        for ((dr, dc) in STRAIGHT_DIRS) {
            var nr = kingPos.first + dr
            var nc = kingPos.second + dc
            while (nr in 0..9 && nc in 0..8) {
                val p = board[nr][nc]
                if (p != 0) {
                    val t = pieceType(p)
                    val col = pieceColor(p)
                    if (col == oppositeColor && (t == ROOK || t == KING)) return true
                    // 炮：需要跳过一子
                    if (col == oppositeColor && t == CANNON) {
                        nr += dr; nc += dc
                        while (nr in 0..9 && nc in 0..8 && board[nr][nc] == 0) { nr += dr; nc += dc }
                        if (nr in 0..9 && nc in 0..8) {
                            val p2 = board[nr][nc]
                            if (p2 != 0 && pieceType(p2) == KING && pieceColor(p2) == color) return true
                        }
                    }
                    break
                }
                nr += dr
                nc += dc
            }
        }
        
        // 检查马攻击
        for (i in HORSE_LEGS.indices) {
            val (lr, lc) = HORSE_LEGS[i]
            val legR = kingPos.first + lr
            val legC = kingPos.second + lc
            if (legR !in 0..9 || legC !in 0..8) continue
            if (board[legR][legC] != 0) continue
            
            val (dr, dc) = HORSE_DSTS[i]
            val nr = kingPos.first + dr
            val nc = kingPos.second + dc
            if (nr !in 0..9 || nc !in 0..8) continue
            val p = board[nr][nc]
            if (p != 0 && pieceColor(p) == oppositeColor && pieceType(p) == HORSE) return true
        }
        
        // 检查兵/卒攻击
        val pawnForward = if (color == RED) 1 else -1
        for (dc in intArrayOf(-1, 1, 0)) {
            val dr = if (dc == 0) pawnForward else 0
            val nr = kingPos.first + dr
            val nc = kingPos.second + dc
            if (nr !in 0..9 || nc !in 0..8) continue
            val p = board[nr][nc]
            if (p != 0 && pieceColor(p) == oppositeColor && pieceType(p) == PAWN) return true
        }
        
        return false
    }

    /** 直接生成所有吃子走法（用于静态搜索，不经过全量生成+过滤） */
    fun generateCaptures(board: Array<IntArray>, color: Byte): List<Move> {
        val moves = mutableListOf<Move>()
        for (r in 0..9) {
            for (c in 0..8) {
                val piece = board[r][c]
                if (piece == 0 || pieceColor(piece) != color) continue
                when (pieceType(piece)) {
                    KING -> {
                        for ((dr, dc) in KING_DIRS) {
                            val nr = r + dr; val nc = c + dc
                            if (!inPalace(nr, nc, color)) continue
                            if (board[nr][nc] != 0 && !sameColor(piece, board[nr][nc]))
                                moves.add(Move(r, c, nr, nc))
                        }
                        val oppositeColor = oppositeColor(color)
                        val kingCol = findKing(board, oppositeColor)
                        if (kingCol != null && kingCol.second == c) {
                            val minR = minOf(r, kingCol.first) + 1
                            val maxR = maxOf(r, kingCol.first)
                            if ((minR < maxR) && (minR until maxR).all { board[it][c] == 0 })
                                moves.add(Move(r, c, kingCol.first, kingCol.second))
                        }
                    }
                    ADVISOR -> {
                        for ((dr, dc) in ADVISOR_DIRS) {
                            val nr = r + dr; val nc = c + dc
                            if (!inPalace(nr, nc, color)) continue
                            if (board[nr][nc] != 0 && !sameColor(piece, board[nr][nc]))
                                moves.add(Move(r, c, nr, nc))
                        }
                    }
                    ELEPHANT -> {
                        for (i in ELEPHANT_EYES.indices) {
                            val (er, ec) = ELEPHANT_EYES[i]; val eyeR = r + er; val eyeC = c + ec
                            if (eyeR !in 0..9 || eyeC !in 0..8 || board[eyeR][eyeC] != 0) continue
                            val (dr, dc) = ELEPHANT_DSTS[i]; val nr = r + dr; val nc = c + dc
                            if (nr !in 0..9 || nc !in 0..8 || !onOwnSide(nr, color)) continue
                            if (board[nr][nc] != 0 && !sameColor(piece, board[nr][nc]))
                                moves.add(Move(r, c, nr, nc))
                        }
                    }
                    HORSE -> {
                        for (i in HORSE_LEGS.indices) {
                            val (lr, lc) = HORSE_LEGS[i]; val legR = r + lr; val legC = c + lc
                            if (legR !in 0..9 || legC !in 0..8 || board[legR][legC] != 0) continue
                            val (dr, dc) = HORSE_DSTS[i]; val nr = r + dr; val nc = c + dc
                            if (nr !in 0..9 || nc !in 0..8) continue
                            if (board[nr][nc] != 0 && !sameColor(piece, board[nr][nc]))
                                moves.add(Move(r, c, nr, nc))
                        }
                    }
                    ROOK -> {
                        for ((dr, dc) in STRAIGHT_DIRS) {
                            var nr = r + dr; var nc = c + dc
                            while (nr in 0..9 && nc in 0..8) {
                                val t = board[nr][nc]
                                if (t != 0) {
                                    if (!sameColor(piece, t)) moves.add(Move(r, c, nr, nc))
                                    break
                                }
                                nr += dr; nc += dc
                            }
                        }
                    }
                    CANNON -> {
                        for ((dr, dc) in STRAIGHT_DIRS) {
                            var nr = r + dr; var nc = c + dc
                            while (nr in 0..9 && nc in 0..8 && board[nr][nc] == 0) { nr += dr; nc += dc }
                            if (nr in 0..9 && nc in 0..8) {
                                nr += dr; nc += dc
                                while (nr in 0..9 && nc in 0..8 && board[nr][nc] == 0) { nr += dr; nc += dc }
                                if (nr in 0..9 && nc in 0..8) {
                                    if (!sameColor(piece, board[nr][nc])) moves.add(Move(r, c, nr, nc))
                                }
                            }
                        }
                    }
                    PAWN -> {
                        val forward = if (color == RED) 1 else -1
                        val crossed = !onOwnSide(r, color)
                        val nr = r + forward
                        if (nr in 0..9) {
                            val t = board[nr][c]
                            if (t != 0 && !sameColor(piece, t)) moves.add(Move(r, c, nr, c))
                        }
                        if (crossed) {
                            for (dc in intArrayOf(-1, 1)) {
                                val nc = c + dc
                                if (nc in 0..8) {
                                    val t = board[r][nc]
                                    if (t != 0 && !sameColor(piece, t)) moves.add(Move(r, c, r, nc))
                                }
                            }
                        }
                    }
                }
            }
        }
        // ★ in-place 验证吃子走法合法性，避免 makeMove 拷贝整个棋盘
        return moves.filter { move ->
            val captured = applyMove(board, move)
            val safe = !isKingInCheck(board, color)
            undoMove(board, move, captured)
            safe
        }
    }

    /** 执行走法，返回新棋盘（不改原棋盘） */
    fun makeMove(board: Array<IntArray>, move: Move): Array<IntArray> {
        val newBoard = board.map { it.copyOf() }.toTypedArray()
        newBoard[move.toRow][move.toCol] = newBoard[move.fromRow][move.fromCol]
        newBoard[move.fromRow][move.fromCol] = 0
        return newBoard
    }

    /** 判断某方是否被将死（无合法走法） */
    fun isCheckmate(board: Array<IntArray>, color: Byte): Boolean {
        return generateMoves(board, color).isEmpty()
    }

    /** 获取走法的中文描述 */
    fun moveToString(board: Array<IntArray>, move: Move): String {
        val piece = board[move.fromRow][move.fromCol]
        val color = pieceColor(piece)
        val type = pieceType(piece)
        val names = pieceNames[type] ?: Pair("?", "?")
        val name = if (color == RED) names.first else names.second
        val from = "(${move.fromRow},${move.fromCol})"
        val to = "(${move.toRow},${move.toCol})"
        return "$name$from→$to"
    }
}

/** 走法数据类 */
data class Move(
    val fromRow: Int,
    val fromCol: Int,
    val toRow: Int,
    val toCol: Int
)