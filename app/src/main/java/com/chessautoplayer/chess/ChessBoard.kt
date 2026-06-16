package com.chessautoplayer.chess

/**
 * 象棋棋盘表示与规则引擎
 * 
 * 棋盘坐标：行 0-9（红方底线为0，黑方底线为9），列 0-8（从左到右）
 * 棋子编码：使用字节表示，高4位表示类型，低4位表示颜色(0=红,1=黑)
 */
object ChessBoard {

    // 棋子类型常量
    const val EMPTY: Byte = 0
    const val KING: Byte = 1      // 帅/将
    const val ADVISOR: Byte = 2   // 仕/士
    const val ELEPHANT: Byte = 3  // 相/象
    const val HORSE: Byte = 4     // 馬/马
    const val ROOK: Byte = 5      // 車/车
    const val CANNON: Byte = 6    // 炮
    const val PAWN: Byte = 7      // 兵/卒

    // 颜色常量
    const val RED: Byte = 0
    const val BLACK: Byte = 1

    /** 棋子名（红方/黑方） */
    val pieceNames = mapOf(
        KING to Pair("帅", "将"),
        ADVISOR to Pair("仕", "士"),
        ELEPHANT to Pair("相", "象"),
        HORSE to Pair("馬", "马"),
        ROOK to Pair("車", "车"),
        CANNON to Pair("炮", "砲"),
        PAWN to Pair("兵", "卒")
    )

    // 棋盘: board[row][col]
    // 初始布局
    private val INIT_BOARD: Array<IntArray> = arrayOf(
        // 红方底线 (row=0)
        intArrayOf(pack(ROOK, RED),   pack(HORSE, RED), pack(ELEPHANT, RED), pack(ADVISOR, RED), pack(KING, RED), pack(ADVISOR, RED), pack(ELEPHANT, RED), pack(HORSE, RED), pack(ROOK, RED)),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),  // row=1
        intArrayOf(0, pack(CANNON, RED), 0, 0, 0, 0, 0, pack(CANNON, RED), 0),  // row=2
        intArrayOf(pack(PAWN, RED), 0, pack(PAWN, RED), 0, pack(PAWN, RED), 0, pack(PAWN, RED), 0, pack(PAWN, RED)),  // row=3
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),  // row=4 (河界)
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),  // row=5 (河界)
        intArrayOf(pack(PAWN, BLACK), 0, pack(PAWN, BLACK), 0, pack(PAWN, BLACK), 0, pack(PAWN, BLACK), 0, pack(PAWN, BLACK)),  // row=6
        intArrayOf(0, pack(CANNON, BLACK), 0, 0, 0, 0, 0, pack(CANNON, BLACK), 0),  // row=7
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),  // row=8
        intArrayOf(pack(ROOK, BLACK), pack(HORSE, BLACK), pack(ELEPHANT, BLACK), pack(ADVISOR, BLACK), pack(KING, BLACK), pack(ADVISOR, BLACK), pack(ELEPHANT, BLACK), pack(HORSE, BLACK), pack(ROOK, BLACK))  // row=9
    )

    /** 打包：类型 + 颜色 */
    fun pack(type: Byte, color: Byte): Int = (type.toInt() shl 1) or color.toInt()

    /** 解包类型 */
    fun pieceType(piece: Int): Byte = ((piece shr 1) and 0x0F).toByte()
    
    /** 解包颜色 */
    fun pieceColor(piece: Int): Byte = (piece and 0x01).toByte()

    /** 判断是否为红方 */
    fun isRed(piece: Int): Boolean = piece != 0 && pieceColor(piece) == RED

    /** 判断是否为黑方 */
    fun isBlack(piece: Int): Boolean = piece != 0 && pieceColor(piece) == BLACK

    /** 相同颜色 */
    fun sameColor(p1: Int, p2: Int): Boolean = pieceColor(p1) == pieceColor(p2)

    /** 对方颜色 */
    fun oppositeColor(color: Byte): Byte = if (color == RED) BLACK else RED

    /** 获取初始棋盘 */
    fun initBoard(): Array<IntArray> = INIT_BOARD.map { it.copyOf() }.toTypedArray()
}