package com.chessautoplayer.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.chessautoplayer.chess.ChessBoard
import com.chessautoplayer.chess.Move
import com.chessautoplayer.chess.MoveGenerator

/**
 * 象棋棋盘视图 - 支持手动摆放和移动棋子
 *
 * 交互方式：
 * - 单击棋子 → 选中（黄色高亮），单击空位/敌子 → 移动/吃子
 * - 双击棋子 → 删除该棋子
 * - 配合外部棋子选择器，可在空位放置新棋子
 * - 支持翻转棋盘（下方始终为"我方"阵营）
 */
class ChessBoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val COLS = 9
        const val ROWS = 10
    }

    // ===== 画笔 =====
    private val bgPaint = Paint().apply { color = Color.parseColor("#FFECB3") }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8D6E63")
        strokeWidth = 2f
    }

    private val boldLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5D4037")
        strokeWidth = 3f
    }

    private val riverTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8D6E63")
        textAlign = Paint.Align.CENTER
    }

    private val redPieceFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D32F2F")
        style = Paint.Style.FILL
    }

    private val blackPieceFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#212121")
        style = Paint.Style.FILL
    }

    private val redPieceInner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E57373")
        style = Paint.Style.FILL
    }

    private val blackPieceInner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#616161")
        style = Paint.Style.FILL
    }

    private val pieceTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFEB3B")
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50")
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50")
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }

    // 动画复用画笔（避免每帧创建）
    private val animSourcePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFB300")
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val animDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        style = Paint.Style.FILL
    }
    private val animTargetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50")
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val animRipplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    // ===== 状态 =====
    private var board: Array<IntArray> = ChessBoard.initBoard()

    /** 当前选中的棋子数据坐标（row, col），-1=未选中 */
    private var selectedDataRow = -1
    private var selectedDataCol = -1

    /** 棋子选择器状态（外部控制） */
    var selectedPieceType: Byte = ChessBoard.ROOK
    var selectedPieceColor: Byte = ChessBoard.RED

    /** 是否允许编辑棋盘（复盘代理模式下禁用放置和删除棋子） */
    var allowEditBoard = true

    /** AI建议走法 */
    private var hintMove: Move? = null

    /** AI提示动画（0.0→1.0 循环） */
    private var animProgress = 0f
    private var animHandler: Handler? = null
    private var animRunnable: Runnable? = null
    private val animFrameDelay = 16L  // ~60fps
    private val animDuration = 1500L  // 1.5秒一个周期

    /** 棋子点击/移动回调 */
    var onCellClick: ((row: Int, col: Int, piece: Int) -> Unit)? = null

    /** 棋子实际走棋完成回调（从A移动到B，包括吃子；放置/删除不触发） */
    var onMoveComplete: ((fromRow: Int, fromCol: Int, toRow: Int, toCol: Int, piece: Int) -> Unit)? = null

    /** 是否翻转棋盘（true=黑方在下方，即"我方"执黑） */
    var isFlipped = false
        set(value) {
            if (field == value) return
            field = value
            selectedDataRow = -1
            selectedDataCol = -1
            hintMove = null
            stopHintAnimation()
            invalidate()
        }

    // 双击检测
    private var lastDownTime = 0L
    private var lastDownDataRow = -1
    private var lastDownDataCol = -1

    private var cellSize = 0f
    private var boardLeft = 0f
    private var boardTop = 0f
    private var pieceRadius = 0f

    // ==================== 布局 & 绘制 ====================

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val width = when (widthMode) {
            MeasureSpec.EXACTLY, MeasureSpec.AT_MOST -> widthSize
            else -> (resources.displayMetrics.density * 280).toInt()
        }

        // 棋盘内容宽高比：(COLS-1) : (ROWS-1) = 8 : 9
        val desiredHeight = (width * (ROWS - 1) / (COLS - 1).toFloat()).toInt()

        val height = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> desiredHeight.coerceAtMost(heightSize)
            else -> desiredHeight
        }

        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val minSide = w.coerceAtMost(h)
        val padding = minSide * 0.05f
        val boardWidth = minSide - padding * 2
        cellSize = boardWidth / (COLS - 1)
        pieceRadius = cellSize * 0.38f

        boardLeft = (w - cellSize * (COLS - 1)) / 2f
        boardTop = (h - cellSize * (ROWS - 1)) / 2f

        riverTextPaint.textSize = cellSize * 0.42f
        pieceTextPaint.textSize = cellSize * 0.45f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(bgPaint.color)

        val l = boardLeft
        val t = boardTop
        val r = l + cellSize * (COLS - 1)
        val b = t + cellSize * (ROWS - 1)

        // 外边框
        canvas.drawRect(l, t, r, b, boldLinePaint)

        // 横线
        for (vr in 0 until ROWS) {
            val y = t + vr * cellSize
            canvas.drawLine(l, y, r, y, linePaint)
        }

        // 竖线（河界断开）
        for (c in 0 until COLS) {
            val x = l + c * cellSize
            if (c == 0 || c == COLS - 1) {
                canvas.drawLine(x, t, x, b, linePaint)
            } else {
                canvas.drawLine(x, t, x, t + cellSize * 4, linePaint)
                canvas.drawLine(x, t + cellSize * 5, x, b, linePaint)
            }
        }

        // 九宫格斜线
        drawPalaceCross(canvas, l, t, 3, 0, 5, 2)
        drawPalaceCross(canvas, l, t, 3, 7, 5, 9)

        // 楚河汉界
        val ry = t + cellSize * 4.5f
        val leftText = if (isFlipped) "汉  界" else "楚  河"
        val rightText = if (isFlipped) "楚  河" else "汉  界"
        canvas.drawText(leftText, l + cellSize * 2.2f, ry + riverTextPaint.textSize * 0.35f, riverTextPaint)
        canvas.drawText(rightText, l + cellSize * 6.2f, ry + riverTextPaint.textSize * 0.35f, riverTextPaint)

        // 炮位兵位
        drawPositionMarks(canvas, l, t)

        // 棋子
        for (vr in 0 until ROWS) {
            for (c in 0 until COLS) {
                val dr = toDataRow(vr)
                val piece = board[dr][c]
                if (piece != 0) {
                    drawPiece(canvas, vr, c, piece, l, t)
                }
            }
        }

        // 选中高亮
        if (selectedDataRow >= 0 && selectedDataCol >= 0) {
            val vr = toVisualRow(selectedDataRow)
            val cx = l + selectedDataCol * cellSize
            val cy = t + vr * cellSize
            canvas.drawCircle(cx, cy, pieceRadius + 6f, selectedPaint)
        }

        // AI建议走法（带动画预览）
        hintMove?.let { move ->
            if (move.fromRow in 0 until ROWS && move.fromCol in 0 until COLS &&
                move.toRow in 0 until ROWS && move.toCol in 0 until COLS) {
                drawHintAnimation(canvas, l, t, move)
            }
        }
    }

    private fun drawPalaceCross(c: Canvas, l: Float, t: Float, c1: Int, r1: Int, c2: Int, r2: Int) {
        val vr1 = toVisualRow(r1)
        val vr2 = toVisualRow(r2)
        c.drawLine(l + c1 * cellSize, t + vr1 * cellSize, l + c2 * cellSize, t + vr2 * cellSize, linePaint)
        c.drawLine(l + c2 * cellSize, t + vr1 * cellSize, l + c1 * cellSize, t + vr2 * cellSize, linePaint)
    }

    private fun drawPositionMarks(canvas: Canvas, l: Float, t: Float) {
        val markLen = cellSize * 0.1f
        val off = cellSize * 0.07f
        val mp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#8D6E63")
            strokeWidth = 1.5f
        }
        // 炮位(数据行2/7) 和 兵/卒位(数据行3/6)，绘制时转换为视觉坐标
        data class Pos(val col: Int, val dRow: Int)
        val positions = listOf(
            Pos(1, 2), Pos(7, 2),  // 炮位
            Pos(0, 3), Pos(2, 3), Pos(4, 3), Pos(6, 3), Pos(8, 3),  // 兵位
            Pos(1, 7), Pos(7, 7),  // 炮位
            Pos(0, 6), Pos(2, 6), Pos(4, 6), Pos(6, 6), Pos(8, 6)   // 卒位
        )
        for ((c, dRow) in positions) {
            val vr = toVisualRow(dRow)
            val x = l + c * cellSize
            val y = t + vr * cellSize
            if (c > 0) {
                canvas.drawLine(x - off - markLen, y - off, x - off, y - off, mp)
                canvas.drawLine(x - off, y - off - markLen, x - off, y - off, mp)
            }
            if (c < COLS - 1) {
                canvas.drawLine(x + off, y - off, x + off + markLen, y - off, mp)
                canvas.drawLine(x + off, y - off - markLen, x + off, y - off, mp)
            }
            // 兵行(dRow=3)和卒行(dRow=6)下方靠近河界，不画下侧标记
            val noBottomMark = (dRow == 3 || dRow == 6)
            if (c > 0 && !noBottomMark) {
                canvas.drawLine(x - off - markLen, y + off, x - off, y + off, mp)
                canvas.drawLine(x - off, y + off, x - off, y + off + markLen, mp)
            }
            if (c < COLS - 1 && !noBottomMark) {
                canvas.drawLine(x + off, y + off, x + off + markLen, y + off, mp)
                canvas.drawLine(x + off, y + off, x + off, y + off + markLen, mp)
            }
        }
    }

    private fun drawPiece(canvas: Canvas, vRow: Int, col: Int, piece: Int, l: Float, t: Float) {
        val cx = l + col * cellSize
        val cy = t + vRow * cellSize
        val isRed = ChessBoard.isRed(piece)
        val fill = if (isRed) redPieceFill else blackPieceFill
        val inner = if (isRed) redPieceInner else blackPieceInner

        canvas.drawCircle(cx, cy, pieceRadius, fill)
        canvas.drawCircle(cx, cy, pieceRadius * 0.82f, inner)

        val type = ChessBoard.pieceType(piece)
        val name = ChessBoard.pieceNames[type]?.let { if (isRed) it.first else it.second } ?: "?"
        canvas.drawText(name, cx, cy + pieceTextPaint.textSize * 0.35f, pieceTextPaint)
    }

    /** 绘制AI建议走法的动画预览 */
    private fun drawHintAnimation(canvas: Canvas, l: Float, t: Float, move: Move) {
        val vfr = toVisualRow(move.fromRow)
        val vtr = toVisualRow(move.toRow)
        val fx = l + move.fromCol * cellSize
        val fy = t + vfr * cellSize
        val tx = l + move.toCol * cellSize
        val ty = t + vtr * cellSize

        val progress = animProgress  // 0.0 → 1.0

        // ── 0. 粗箭头背景（高可见度） ──
        val bgArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#81C784")
            strokeWidth = 14f
            strokeCap = Paint.Cap.ROUND
            alpha = (80 + 40 * kotlin.math.sin((progress * Math.PI * 2).toDouble())).toInt()
        }
        canvas.drawLine(fx, fy, tx, ty, bgArrowPaint)

        // ── 1. 起始棋子脉冲高亮（金色呼吸圈，更大更明显） ──
        val sinVal = kotlin.math.sin((progress * 2.0 * Math.PI).toDouble()).toFloat()
        val pulseAlpha = (0.5f + 0.5f * kotlin.math.abs(sinVal.toDouble()).toFloat()).coerceIn(0f, 1f)
        val pulseRadius = pieceRadius + 6f + 14f * pulseAlpha
        animSourcePaint.alpha = (pulseAlpha * 255).toInt()
        animSourcePaint.strokeWidth = 7f
        canvas.drawCircle(fx, fy, pulseRadius, animSourcePaint)

        // 实心金色填充覆盖层
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFB300")
            style = Paint.Style.FILL
            alpha = (pulseAlpha * 60).toInt()
        }
        canvas.drawCircle(fx, fy, pulseRadius * 0.7f, glowPaint)

        // ── 2. 主箭头连线 ──
        canvas.drawLine(fx, fy, tx, ty, arrowPaint)

        // ── 3. 移动幽灵棋子 ──
        val ghostProgress = progress
        val ghostX = fx + (tx - fx) * ghostProgress
        val ghostY = fy + (ty - fy) * ghostProgress

        // 获取原棋子信息以绘制同色幽灵
        val srcPiece = board[move.fromRow][move.fromCol]
        if (srcPiece != 0) {
            val isRed = ChessBoard.isRed(srcPiece)
            val ghostFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (isRed) Color.parseColor("#E53935") else Color.parseColor("#212121")
                style = Paint.Style.FILL
                alpha = (180 * kotlin.math.sin((ghostProgress * Math.PI).toDouble())).toInt().coerceIn(0, 180)
            }
            val ghostInner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (isRed) Color.parseColor("#EF5350") else Color.parseColor("#616161")
                style = Paint.Style.FILL
                alpha = ghostFill.alpha
            }
            canvas.drawCircle(ghostX, ghostY, pieceRadius * 0.85f, ghostFill)
            canvas.drawCircle(ghostX, ghostY, pieceRadius * 0.7f, ghostInner)
        } else {
            // 若原棋子已被移除，绘制白色幽灵
            val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
                alpha = (120 * kotlin.math.sin((ghostProgress * Math.PI).toDouble())).toInt().coerceIn(0, 120)
            }
            canvas.drawCircle(ghostX, ghostY, pieceRadius * 0.5f, ghostPaint)
        }

        // ── 4. 目标位置强标记 ──
        // 绿色实心圈（低透明度）
        val targetGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4CAF50")
            style = Paint.Style.FILL
            alpha = 60
        }
        canvas.drawCircle(tx, ty, pieceRadius + 8f, targetGlow)
        // 绿色描边圈
        canvas.drawCircle(tx, ty, pieceRadius + 8f, animTargetPaint)

        // 波纹：从棋子中心向外扩散，最多3圈
        for (i in 0..2) {
            val rippleProgress = (progress + i * 0.33f) % 1.0f
            val rippleRadius = pieceRadius + 10f + rippleProgress * cellSize * 1.8f
            val rippleAlpha = ((1f - rippleProgress) * 220).toInt()
            if (rippleAlpha > 10) {
                animRipplePaint.alpha = rippleAlpha
                animRipplePaint.strokeWidth = 4f
                canvas.drawCircle(tx, ty, rippleRadius, animRipplePaint)
            }
        }

        // ── 5. 箭头头部三角指示（更大更明显） ──
        val arrowLen = pieceRadius * 1.2f
        val angle = kotlin.math.atan2((ty - fy).toDouble(), (tx - fx).toDouble())
        val headAngle1 = (angle + Math.PI * 0.85).toFloat()
        val headAngle2 = (angle - Math.PI * 0.85).toFloat()
        val aLen = arrowLen
        val hx1 = tx - aLen * kotlin.math.cos(headAngle1.toDouble()).toFloat()
        val hy1 = ty - aLen * kotlin.math.sin(headAngle1.toDouble()).toFloat()
        val hx2 = tx - aLen * kotlin.math.cos(headAngle2.toDouble()).toFloat()
        val hy2 = ty - aLen * kotlin.math.sin(headAngle2.toDouble()).toFloat()
        canvas.drawLine(tx, ty, hx1, hy1, arrowPaint)
        canvas.drawLine(tx, ty, hx2, hy2, arrowPaint)

        // 箭头头部实心填充
        val headFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4CAF50")
            style = Paint.Style.FILL
        }
        val path = android.graphics.Path().apply {
            moveTo(tx, ty)
            lineTo(hx1, hy1)
            lineTo(hx2, hy2)
            close()
        }
        canvas.drawPath(path, headFill)
    }

    // ==================== 坐标转换 ====================

    /** 视觉行 → 数据行 */
    private fun toDataRow(vRow: Int): Int = if (isFlipped) vRow else ROWS - 1 - vRow

    /** 数据行 → 视觉行 */
    private fun toVisualRow(dRow: Int): Int = if (isFlipped) dRow else ROWS - 1 - dRow

    // ==================== 触摸交互 ====================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return false

        val vCol = ((event.x - boardLeft + cellSize / 2) / cellSize).toInt().coerceIn(0, COLS - 1)
        val vRow = ((event.y - boardTop + cellSize / 2) / cellSize).toInt().coerceIn(0, ROWS - 1)

        val dRow = toDataRow(vRow)
        val dCol = vCol
        val piece = board[dRow][dCol]
        val now = System.currentTimeMillis()

        // ── 双击检测 ──
        val isDoubleClick = (dRow == lastDownDataRow && dCol == lastDownDataCol &&
                (now - lastDownTime) < 400L && piece != 0)

        if (isDoubleClick) {
            if (!allowEditBoard) return true  // 复盘代理模式禁用双击删除
            // 双击：删除棋子
            board[dRow][dCol] = 0
            if (selectedDataRow == dRow && selectedDataCol == dCol) {
                selectedDataRow = -1; selectedDataCol = -1
            }
            onCellClick?.invoke(dRow, dCol, 0)
            hintMove = null
            stopHintAnimation()
            lastDownTime = 0
            invalidate()
            return true
        }

        lastDownTime = now
        lastDownDataRow = dRow
        lastDownDataCol = dCol

        // ── 单击处理 ──
        if (piece == 0) {
            // 空位
            if (selectedDataRow >= 0 && selectedDataCol >= 0) {
                // 移动选中的棋子到空位
                val fromR = selectedDataRow; val fromC = selectedDataCol
                if (!isLegalMove(fromR, fromC, dRow, dCol)) {
                    selectedDataRow = -1; selectedDataCol = -1
                    invalidate()
                    return true
                }
                val movedPiece = board[fromR][fromC]
                board[dRow][dCol] = movedPiece
                board[fromR][fromC] = 0
                onCellClick?.invoke(dRow, dCol, board[dRow][dCol])
                onMoveComplete?.invoke(fromR, fromC, dRow, dCol, movedPiece)
                selectedDataRow = -1; selectedDataCol = -1
            } else if (allowEditBoard) {
                // 无选中棋子 → 从选择器放置新棋子（复盘代理模式禁用）
                val newPiece = ChessBoard.pack(selectedPieceType, selectedPieceColor)
                board[dRow][dCol] = newPiece
                onCellClick?.invoke(dRow, dCol, newPiece)
            } else {
                // 复盘代理模式：无选中棋子点击空位时不做任何事
                return true
            }
            hintMove = null
            stopHintAnimation()
            invalidate()
        } else {
            // 有棋子
            if (selectedDataRow == dRow && selectedDataCol == dCol) {
                // 单击已选中的棋子 → 取消选中
                selectedDataRow = -1; selectedDataCol = -1
            } else if (selectedDataRow >= 0 && selectedDataCol >= 0 &&
                ChessBoard.pieceColor(piece) != ChessBoard.pieceColor(board[selectedDataRow][selectedDataCol])) {
                // 选中了己方棋子，点击的是敌方棋子 → 吃子
                val fromR = selectedDataRow; val fromC = selectedDataCol
                if (!isLegalMove(fromR, fromC, dRow, dCol)) {
                    selectedDataRow = -1; selectedDataCol = -1
                    invalidate()
                    return true
                }
                val movedPiece = board[fromR][fromC]
                board[dRow][dCol] = movedPiece
                board[fromR][fromC] = 0
                onCellClick?.invoke(dRow, dCol, board[dRow][dCol])
                onMoveComplete?.invoke(fromR, fromC, dRow, dCol, movedPiece)
                selectedDataRow = -1; selectedDataCol = -1
            } else {
                // 选中新棋子
                selectedDataRow = dRow; selectedDataCol = dCol
            }
            hintMove = null
            stopHintAnimation()
            invalidate()
        }

        return true
    }

    /** 校验从 (fromR,fromC) 到 (toR,toC) 的走法是否合法 */
    private fun isLegalMove(fromR: Int, fromC: Int, toR: Int, toC: Int): Boolean {
        val piece = board[fromR][fromC]
        if (piece == 0) return false
        val color = ChessBoard.pieceColor(piece)
        val legalMoves = MoveGenerator.generateMoves(board, color)
        return legalMoves.any { it.fromRow == fromR && it.fromCol == fromC && it.toRow == toR && it.toCol == toC }
    }

    // ==================== 公共方法 ====================

    fun setBoard(newBoard: Array<IntArray>) {
        board = newBoard.map { it.copyOf() }.toTypedArray()
        selectedDataRow = -1; selectedDataCol = -1
        hintMove = null
        stopHintAnimation()
        invalidate()
    }

    fun getBoard(): Array<IntArray> = board.map { it.copyOf() }.toTypedArray()

    fun setHintMove(move: Move?) {
        hintMove = move
        if (move != null) {
            startHintAnimation()
        } else {
            stopHintAnimation()
        }
        invalidate()
    }

    fun clearBoard() {
        board = Array(ROWS) { IntArray(COLS) { 0 } }
        selectedDataRow = -1; selectedDataCol = -1
        hintMove = null
        stopHintAnimation()
        invalidate()
    }

    fun resetBoard() {
        board = ChessBoard.initBoard()
        selectedDataRow = -1; selectedDataCol = -1
        hintMove = null
        stopHintAnimation()
        invalidate()
    }

    private fun startHintAnimation() {
        stopHintAnimation()
        val startTime = System.currentTimeMillis()
        val handler = Handler(Looper.getMainLooper())
        animHandler = handler
        animRunnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - startTime
                animProgress = ((elapsed % animDuration).toFloat() / animDuration)
                invalidate()
                handler.postDelayed(this, animFrameDelay)
            }
        }
        handler.post(animRunnable!!)
    }

    private fun stopHintAnimation() {
        animRunnable?.let { animHandler?.removeCallbacks(it) }
        animRunnable = null
        animHandler = null
        animProgress = 0f
    }

    /** 获取当前下方阵营颜色 */
    fun bottomColor(): Byte = if (isFlipped) ChessBoard.BLACK else ChessBoard.RED

    /** AI自动执行走法（复盘代理模式用） */
    fun applyMove(move: Move) {
        if (move.fromRow in 0 until ROWS && move.fromCol in 0 until COLS &&
            move.toRow in 0 until ROWS && move.toCol in 0 until COLS) {
            board[move.toRow][move.toCol] = board[move.fromRow][move.fromCol]
            board[move.fromRow][move.fromCol] = 0
        }
        selectedDataRow = -1; selectedDataCol = -1
        hintMove = null
        stopHintAnimation()
        invalidate()
    }
}