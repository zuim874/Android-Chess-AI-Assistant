package com.chessautoplayer.service

import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import java.io.File
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.chessautoplayer.MainActivity
import com.chessautoplayer.R
import com.chessautoplayer.chess.ChessBoard
import com.chessautoplayer.chess.ChessBoard.ADVISOR
import com.chessautoplayer.chess.ChessBoard.BLACK
import com.chessautoplayer.chess.ChessBoard.CANNON
import com.chessautoplayer.chess.ChessBoard.ELEPHANT
import com.chessautoplayer.chess.ChessBoard.HORSE
import com.chessautoplayer.chess.ChessBoard.KING
import com.chessautoplayer.chess.ChessBoard.PAWN
import com.chessautoplayer.chess.ChessBoard.RED
import com.chessautoplayer.chess.ChessBoard.ROOK
import com.chessautoplayer.chess.ChessEngine
import com.chessautoplayer.chess.Move
import com.chessautoplayer.chess.MoveGenerator
import com.chessautoplayer.chess.OpeningBook
import com.chessautoplayer.chess.Trainer
import com.chessautoplayer.view.ChessBoardView

/**
 * 悬浮窗服务 - 小球 + 模拟棋盘 + AI指导
 *
 * 使用流程：
 * 1. 启动后显示悬浮球
 * 2. 点击悬浮球展开模拟棋盘
 * 3. 点击棋子选中 → 点击目标位置移动（或吃子）
 * 4. 双击棋子 → 删除
 * 5. 用棋子选择器在空位添加新棋子
 * 6. 复刻好棋局后，点击「AI帮红方」或「AI帮黑方」获取建议
 * 7. 点击「翻转」翻转棋盘视角（仅视觉调整，不影响AI指导方向）
 */
class FloatWindowService : Service() {

    companion object {
        const val TAG = "FloatWindow"
        const val CHANNEL_ID = "float_window_channel"
        const val NOTIFICATION_ID = 2001
        const val ACTION_SHOW = "com.chessautoplayer.SHOW_FLOAT"

        var instance: FloatWindowService? = null
            private set
    }

    private var windowManager: WindowManager? = null
    private var ballView: View? = null
    private var panelView: View? = null
    private var isBallShown = false
    private var isPanelShown = false

    private var chessBoardView: ChessBoardView? = null
    private var tvAiHint: TextView? = null
    private var tvPanelSide: TextView? = null
    private var tvTrainProgress: TextView? = null
    private var tvReviewAgentStatus: TextView? = null

    // 棋盘状态记忆
    private var savedBoardState: Array<IntArray>? = null
    private var savedIsFlipped: Boolean = false

    // 训练器
    private lateinit var trainer: Trainer
    private var isTraining = false

    // 复盘代理模式
    private var isReviewAgentMode = false
    private var currentTurn: Byte = RED

    /** 单步走法记录，用于撤销 */
    private data class MoveRecord(
        val fromR: Int, val fromC: Int,
        val toR: Int, val toC: Int,
        val movedPiece: Int,
        val capturedPiece: Int
    )
    private val moveHistory = mutableListOf<MoveRecord>()



    private val mainHandler = Handler(Looper.getMainLooper())

    // 棋子选择按钮引用
    private val pieceButtons = mutableMapOf<Byte, Button>()
    private var btnColorRed: Button? = null
    private var btnColorBlack: Button? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        trainer = Trainer(this)
        OpeningBook.load(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showFloatBall()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        trainer.stop()
        OpeningBook.save(this)
        hideAll()
        super.onDestroy()
    }

    /** 从指定文件导入棋谱（供 MainActivity 直接调用） */
    fun importBookFile(file: File) {
        val count = OpeningBook.importFromFile(file)
        mainHandler.post {
            bookDialogView?.let { dialog ->
                val tvStats = dialog.findViewById<TextView>(R.id.tvBookStats)
                val lvEntries = dialog.findViewById<android.widget.ListView>(R.id.lvBookEntries)
                val newEntries = OpeningBook.getAllEntries()
                tvStats?.text = OpeningBook.stats()
                lvEntries?.adapter = BookEntryAdapter(this, newEntries)
            }
            showFloatingToast(
                if (count >= 0) "成功导入 $count 条记录" else "导入失败"
            )
        }
    }

    // ==================== 通知 ====================

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "象棋AI助手", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "悬浮窗服务运行中"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("象棋AI助手")
            .setContentText("悬浮球已启动，点击打开棋盘")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    // ==================== 小球 ====================

    private fun showFloatBall() {
        if (isBallShown) return
        startForeground(NOTIFICATION_ID, buildNotification())

        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        ballView = inflater.inflate(R.layout.float_ball, null)

        val params = WindowManager.LayoutParams(
            dp2px(56),
            dp2px(56),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp2px(16)
            y = dp2px(100)
        }

        setupBallDragAndClick(ballView!!, params)
        windowManager?.addView(ballView, params)
        isBallShown = true
    }

    private fun setupBallDragAndClick(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isClick = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isClick = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (dx * dx + dy * dy > 100) isClick = false
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    windowManager?.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isClick) toggleBoardPanel()
                    true
                }
                else -> false
            }
        }
    }

    // ==================== 棋盘面板 ====================

    private fun toggleBoardPanel() {
        if (isPanelShown) {
            hideBoardPanel()
        } else {
            showBoardPanel()
        }
    }

    private fun showBoardPanel() {
        ballView?.visibility = View.GONE

        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        panelView = inflater.inflate(R.layout.float_board_panel, null)

        val params = WindowManager.LayoutParams(
            dp2px(300),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp2px(16)
            y = dp2px(80)
        }

        setupPanelDrag(panelView!!, params)
        setupPanelButtons()

        windowManager?.addView(panelView, params)
        isPanelShown = true
    }

    private fun hideBoardPanel() {
        // 保存当前棋盘状态
        chessBoardView?.let {
            savedBoardState = it.getBoard()
            savedIsFlipped = it.isFlipped
        }

        if (panelView != null) {
            try { windowManager?.removeView(panelView) } catch (_: Exception) {}
            panelView = null
        }
        chessBoardView = null
        isPanelShown = false
        ballView?.visibility = View.VISIBLE
    }

    private fun hideAll() {
        hideBoardPanel()
        if (ballView != null) {
            try { windowManager?.removeView(ballView) } catch (_: Exception) {}
            ballView = null
        }
        isBallShown = false
    }

    private fun setupPanelDrag(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupPanelButtons() {
        chessBoardView = panelView?.findViewById(R.id.chessBoardView)
        tvAiHint = panelView?.findViewById(R.id.tvAiHint)
        tvPanelSide = panelView?.findViewById(R.id.tvPanelSide)
        tvReviewAgentStatus = panelView?.findViewById(R.id.tvReviewAgentStatus)

        // 初始化棋子选择按钮
        pieceButtons[KING] = panelView?.findViewById(R.id.btnPieceKing)!!
        pieceButtons[ADVISOR] = panelView?.findViewById(R.id.btnPieceAdvisor)!!
        pieceButtons[ELEPHANT] = panelView?.findViewById(R.id.btnPieceElephant)!!
        pieceButtons[HORSE] = panelView?.findViewById(R.id.btnPieceHorse)!!
        pieceButtons[ROOK] = panelView?.findViewById(R.id.btnPieceRook)!!
        pieceButtons[CANNON] = panelView?.findViewById(R.id.btnPieceCannon)!!
        pieceButtons[PAWN] = panelView?.findViewById(R.id.btnPiecePawn)!!

        btnColorRed = panelView?.findViewById(R.id.btnColorRed)
        btnColorBlack = panelView?.findViewById(R.id.btnColorBlack)

        updatePieceButtonHighlight()
        updateColorButtonHighlight()

        // 恢复记忆状态，若无则重置为初始棋盘
        val saved = savedBoardState
        if (saved != null) {
            chessBoardView?.setBoard(saved)
            chessBoardView?.isFlipped = savedIsFlipped
            tvAiHint?.text = "已恢复上次棋局"
        } else {
            chessBoardView?.resetBoard()
        }
        updateSideText()

        // ---- 棋子类型选择 ----
        for ((type, btn) in pieceButtons) {
            btn.setOnClickListener {
                chessBoardView?.selectedPieceType = type
                updatePieceButtonHighlight()
                val color = chessBoardView?.selectedPieceColor ?: RED
                tvAiHint?.text = "放置: ${getPieceName(type, color)}  |  单击棋子移动，双击删除"
            }
        }

        // ---- 颜色选择 ----
        btnColorRed?.setOnClickListener {
            chessBoardView?.selectedPieceColor = RED
            updateColorButtonHighlight()
            val type = chessBoardView?.selectedPieceType ?: ROOK
            tvAiHint?.text = "放置: ${getPieceName(type, RED)}  |  单击棋子移动，双击删除"
        }
        btnColorBlack?.setOnClickListener {
            chessBoardView?.selectedPieceColor = BLACK
            updateColorButtonHighlight()
            val type = chessBoardView?.selectedPieceType ?: ROOK
            tvAiHint?.text = "放置: ${getPieceName(type, BLACK)}  |  单击棋子移动，双击删除"
        }

        // ---- 关闭面板 ----
        panelView?.findViewById<Button>(R.id.btnPanelClose)?.setOnClickListener {
            hideBoardPanel()
        }

        // ---- 收集所有训练时需要屏蔽的按钮 ----
        val aiRedBtn = panelView?.findViewById<Button>(R.id.btnAiRed)
        val aiBlackBtn = panelView?.findViewById<Button>(R.id.btnAiBlack)
        val resetBtn = panelView?.findViewById<Button>(R.id.btnResetBoard)
        val clearBtn = panelView?.findViewById<Button>(R.id.btnClearBoard)
        val flipBtn = panelView?.findViewById<Button>(R.id.btnFlipBoard)
        val btnReviewAgent = panelView?.findViewById<android.widget.ToggleButton>(R.id.btnReviewAgent)
        val disturbanceButtons = listOf(aiRedBtn, aiBlackBtn, resetBtn, clearBtn, flipBtn)

        // ---- AI帮红方 ----
        aiRedBtn?.setOnClickListener {
            aiGuide(RED)
        }

        // ---- AI帮黑方 ----
        aiBlackBtn?.setOnClickListener {
            aiGuide(BLACK)
        }

        // ---- 重置棋盘 ----
        resetBtn?.setOnClickListener {
            chessBoardView?.resetBoard()
            currentTurn = RED
            moveHistory.clear()
            // 关闭复盘代理状态（用 findViewById 避免前向引用 btnTrain）
            if (isReviewAgentMode) {
                isReviewAgentMode = false
                btnReviewAgent?.isChecked = false
                panelView?.findViewById<Button>(R.id.btnTrain)?.isEnabled = true
                aiRedBtn?.isEnabled = true
                aiBlackBtn?.isEnabled = true
                tvReviewAgentStatus?.visibility = android.view.View.GONE
            }
            updateSideText()
            tvAiHint?.text = "棋盘已重置为初始状态"
        }

        // ---- 清空棋盘 ----
        clearBtn?.setOnClickListener {
            chessBoardView?.clearBoard()
            currentTurn = RED
            moveHistory.clear()
            tvAiHint?.text = "棋盘已清空，单击棋子移动，双击删除"
        }

        // ---- 翻转棋盘 ----
        flipBtn?.setOnClickListener {
            chessBoardView?.isFlipped = !(chessBoardView?.isFlipped ?: false)
            updateSideText()
            val side = if (chessBoardView?.isFlipped == true) "黑方在下" else "红方在下"
            tvAiHint?.text = "棋盘已翻转，当前$side"
            showFloatingToast("棋盘已翻转，当前$side（不影响AI指导方向）")
        }

        // ---- 悔一步 ----
        val btnUndoMove = panelView?.findViewById<Button>(R.id.btnUndoMove)
        btnUndoMove?.setOnClickListener {
            if (moveHistory.isEmpty()) {
                showFloatingToast("暂无走法可撤销")
                return@setOnClickListener
            }
            val last = moveHistory.removeAt(moveHistory.size - 1)
            val board = chessBoardView ?: return@setOnClickListener
            val b = board.getBoard()
            b[last.fromR][last.fromC] = last.movedPiece
            b[last.toR][last.toC] = last.capturedPiece
            board.setBoard(b)
            // 撤销后回退回合
            currentTurn = if (ChessBoard.pieceColor(last.movedPiece) == RED) RED else BLACK
            savedBoardState = b
            if (isReviewAgentMode) {
                updateReviewAgentStatus(true)
                tvAiHint?.text = "已撤销上一步，请重新记录对手走法"
            } else {
                tvAiHint?.text = "已撤销上一步"
            }
            showFloatingToast("已撤销上一步")
        }

        // ---- 并发数控制 ----
        val btnConcurrentMinus = panelView?.findViewById<Button>(R.id.btnConcurrentMinus)
        val btnConcurrentPlus = panelView?.findViewById<Button>(R.id.btnConcurrentPlus)
        val tvConcurrentValue = panelView?.findViewById<TextView>(R.id.tvConcurrentValue)

        fun updateConcurrentUI() {
            val value = trainer.concurrentGames.coerceIn(1, Trainer.MAX_CONCURRENT)
            trainer.concurrentGames = value
            tvConcurrentValue?.text = value.toString()
            btnConcurrentPlus?.isEnabled = value < Trainer.MAX_CONCURRENT
            btnConcurrentMinus?.isEnabled = value > 1
        }
        updateConcurrentUI()

        // ---- 动画开关 ----
        val tbShowAnimation = panelView?.findViewById<android.widget.ToggleButton>(R.id.tbShowAnimation)
        tbShowAnimation?.isChecked = trainer.showAnimation
        tbShowAnimation?.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && trainer.isRunning && !trainer.showAnimation) {
                showFloatingToast("当前对局结束后将开启动画")
            }
            trainer.showAnimation = isChecked
        }

        // ---- 随机探索率 ----
        val btnExploreMinus = panelView?.findViewById<Button>(R.id.btnExploreMinus)
        val btnExplorePlus = panelView?.findViewById<Button>(R.id.btnExplorePlus)
        val tvExploreRate = panelView?.findViewById<TextView>(R.id.tvExploreRate)

        fun updateExploreUI() {
            val pct = (trainer.exploreRate * 100).toInt()
            tvExploreRate?.text = "$pct%"
        }
        updateExploreUI()

        btnExploreMinus?.setOnClickListener {
            trainer.exploreRate = (trainer.exploreRate - 0.05f).coerceAtLeast(0f)
            updateExploreUI()
        }
        btnExplorePlus?.setOnClickListener {
            trainer.exploreRate = (trainer.exploreRate + 0.05f).coerceAtMost(0.50f)
            updateExploreUI()
        }

        btnConcurrentMinus?.setOnClickListener {
            if (trainer.concurrentGames > 1) {
                trainer.concurrentGames--
            }
            updateConcurrentUI()
        }
        btnConcurrentPlus?.setOnClickListener {
            if (trainer.concurrentGames < Trainer.MAX_CONCURRENT) {
                trainer.concurrentGames++
            }
            updateConcurrentUI()
        }

        // ---- 训练开关 ----
        val btnTrain = panelView?.findViewById<Button>(R.id.btnTrain)
        val btnStopTrain = panelView?.findViewById<Button>(R.id.btnStopTrain)
        tvTrainProgress = panelView?.findViewById<TextView>(R.id.tvTrainProgress)

        fun setTrainingButtonsEnabled(enabled: Boolean) {
            disturbanceButtons.forEach { it?.isEnabled = enabled }
            btnConcurrentMinus?.isEnabled = enabled && trainer.concurrentGames > 1
            btnConcurrentPlus?.isEnabled = enabled && trainer.concurrentGames < Trainer.MAX_CONCURRENT
            // 训练时禁用复盘代理按钮
            btnReviewAgent?.isEnabled = enabled
        }

        btnTrain?.setOnClickListener {
            if (!trainer.isRunning && !isReviewAgentMode) {
                trainer.start(0)
                isTraining = true
                btnTrain?.text = "训练中"
                btnTrain?.setBackgroundColor(0xFF2196F3.toInt())
                tvTrainProgress?.text = "训练中..."
                setTrainingButtonsEnabled(false)
            }
        }

        btnStopTrain?.setOnClickListener {
            if (trainer.isRunning) {
                trainer.stop()
                isTraining = false
                btnTrain?.text = "开始训练"
                btnTrain?.setBackgroundColor(0xFF4CAF50.toInt())
                tvTrainProgress?.text = "训练已停止"
                setTrainingButtonsEnabled(true)
            }
        }

        // ---- 复盘代理模式（移到btnTrain定义之后避免前向引用） ----
        val reviewAgentListener = android.widget.CompoundButton.OnCheckedChangeListener { _, isChecked ->
            isReviewAgentMode = isChecked
            if (isChecked) {
                // 开启复盘代理：不禁用编辑、禁用训练按钮和AI帮助按钮
                currentTurn = RED
                moveHistory.clear()
                btnTrain?.isEnabled = false
                aiRedBtn?.isEnabled = false
                aiBlackBtn?.isEnabled = false
                tvAiHint?.text = "复盘代理已开启，AI自动下棋中..."
                tvReviewAgentStatus?.visibility = android.view.View.VISIBLE
                updateReviewAgentStatus(true)
                doReviewAgentMove()
            } else {
                // 关闭复盘代理：恢复按钮
                btnTrain?.isEnabled = true
                aiRedBtn?.isEnabled = true
                aiBlackBtn?.isEnabled = true
                tvAiHint?.text = "复盘代理已关闭"
                tvReviewAgentStatus?.visibility = android.view.View.GONE
            }
        }
        btnReviewAgent?.setOnCheckedChangeListener(reviewAgentListener)

        // ---- 查看棋谱 ----
        panelView?.findViewById<Button>(R.id.btnViewBook)?.setOnClickListener {
            showBookDialog()
        }

        // 初始化训练器回调
        trainer.setCallback(object : Trainer.Callback {
            override fun onProgress(games: Int, bookSize: Int, status: String) {
                mainHandler.post {
                    tvTrainProgress?.text = status
                }
            }
            override fun onGameComplete(gameIndex: Int, outcome: String) {}
            override fun onStopped(totalGames: Int) {
                mainHandler.post {
                    isTraining = false
                    btnTrain?.text = "开始训练"
                    btnTrain?.setBackgroundColor(0xFF4CAF50.toInt())
                    tvTrainProgress?.text = "训练结束，共${totalGames}局 | ${OpeningBook.stats()}"
                    setTrainingButtonsEnabled(true)
                }
            }
            override fun onMove(board: Array<IntArray>, move: Move, moveCount: Int, gameIndex: Int) {
                savedBoardState = board.map { it.copyOf() }.toTypedArray()
                mainHandler.post {
                    if (isPanelShown) {
                        chessBoardView?.setBoard(board)
                        chessBoardView?.setHintMove(move)
                        tvAiHint?.text = "训练中... 第${gameIndex}局 第${moveCount + 1}步"
                    }
                }
            }
        })

        // 跟踪用户实际走棋（移动/吃子），根据落子方切换回合并触发复盘代理下一步提示
        chessBoardView?.onMoveComplete = { fromR, fromC, toR, toC, movedPiece ->
            val movedColor = ChessBoard.pieceColor(movedPiece)
            // 记录走法历史（用于撤销）
            val captured = chessBoardView?.getBoard()?.get(toR)?.get(toC) ?: 0
            moveHistory.add(MoveRecord(fromR, fromC, toR, toC, movedPiece, captured))
            currentTurn = if (movedColor == RED) BLACK else RED
            savedBoardState = chessBoardView?.getBoard()
            if (isReviewAgentMode) {
                updateReviewAgentStatus(false)
                doReviewAgentMove()
            }
        }

        // 如果训练正在进行，恢复最新棋盘并禁用干扰按钮
        if (trainer.isRunning && savedBoardState != null) {
            chessBoardView?.setBoard(savedBoardState!!)
            btnTrain?.text = "训练中"
            btnTrain?.setBackgroundColor(0xFF2196F3.toInt())
            setTrainingButtonsEnabled(false)
        }

        // 如果复盘代理模式先前已开启，恢复状态
        if (isReviewAgentMode) {
            btnReviewAgent?.setOnCheckedChangeListener(null)
            btnReviewAgent?.isChecked = true
            btnReviewAgent?.setOnCheckedChangeListener(reviewAgentListener)
            savedBoardState?.let { chessBoardView?.setBoard(it) }
            btnTrain?.isEnabled = false
            aiRedBtn?.isEnabled = false
            aiBlackBtn?.isEnabled = false
            tvReviewAgentStatus?.visibility = android.view.View.VISIBLE
            updateReviewAgentStatus(true)
            tvAiHint?.text = "复盘代理已恢复，请记录对手走法"
        }
    }

    private fun updatePieceButtonHighlight() {
        val selected = chessBoardView?.selectedPieceType ?: return
        for ((type, btn) in pieceButtons) {
            btn.isSelected = (type == selected)
            btn.setTextColor(if (type == selected) 0xFFFFFF00.toInt() else 0xFFFFFFFF.toInt())
        }
    }

    private fun updateColorButtonHighlight() {
        val color = chessBoardView?.selectedPieceColor ?: RED
        btnColorRed?.isSelected = (color == RED)
        btnColorBlack?.isSelected = (color == BLACK)
        btnColorRed?.setTextColor(if (color == RED) 0xFFFFFF00.toInt() else 0xFFFFFFFF.toInt())
        btnColorBlack?.setTextColor(if (color == BLACK) 0xFFFFFF00.toInt() else 0xFFFFFFFF.toInt())
    }

    private fun getPieceName(type: Byte, color: Byte): String {
        val names = ChessBoard.pieceNames[type] ?: return "?"
        return if (color == RED) names.first else names.second
    }

    /** 更新面板上棋盘朝向文字 */
    private fun updateSideText() {
        val isFlipped = chessBoardView?.isFlipped ?: false
        tvPanelSide?.text = if (isFlipped) "黑方在下" else "红方在下"
    }

    // ==================== AI指导 ====================

    /**
     * AI指导指定阵营
     * @param aiColor AI要帮助的阵营颜色（RED 或 BLACK）
     */
    private fun aiGuide(aiColor: Byte) {
        val board = chessBoardView
        if (board == null) {
            tvAiHint?.text = "棋盘未初始化"
            return
        }

        val currentBoard = board.getBoard()

        tvAiHint?.text = "AI计算中..."
        Thread {
            try {
                val best = ChessEngine.search(currentBoard, aiColor)
                if (best.move == null) {
                    mainHandler.post { tvAiHint?.text = "无合法走法" }
                    return@Thread
                }

                val moveName = MoveGenerator.moveToString(currentBoard, best.move)
                val sideName = if (aiColor == RED) "红方" else "黑方"

                // 查询是否有棋谱参考（仅用于提示，不影响决策）
                val bookHash = OpeningBook.computeHash(currentBoard, aiColor)
                val bookEntry = OpeningBook.query(bookHash)
                val bookInfo = bookEntry?.let {
                    " (参考棋谱:${it.wins}胜${it.losses}负${it.draws}和)"
                } ?: ""

                mainHandler.post {
                    board.setHintMove(best.move)
                    tvAiHint?.text = "AI($sideName): $moveName (评分:${best.score})$bookInfo"
                    showFloatingToast("AI建议$sideName: $moveName")
                }
            } catch (e: Exception) {
                Log.e(TAG, "AI guide error: ${e.message}", e)
                mainHandler.post { tvAiHint?.text = "计算出错: ${e.message}" }
            }
        }.start()
    }

    // ==================== 复盘代理 ====================

    /**
     * 复盘代理：AI指导当前轮到的一方下一步怎么走（显示提示，不自动执行）
     * 用户手动走棋后自动切换回合并提示下一步
     */
    private fun doReviewAgentMove() {
        if (!isReviewAgentMode) return

        val board = chessBoardView ?: return
        val currentBoard = board.getBoard()

        Thread {
            try {
                val best = ChessEngine.search(currentBoard, currentTurn)
                if (best.move == null) {
                    mainHandler.post {
                        val sideName = if (currentTurn == RED) "红方" else "黑方"
                        tvAiHint?.text = "复盘代理：$sideName 无合法走法，对局结束"
                        showFloatingToast("$sideName 无合法走法")
                    }
                    return@Thread
                }

                val moveName = MoveGenerator.moveToString(currentBoard, best.move)
                val sideName = if (currentTurn == RED) "红方" else "黑方"

                mainHandler.post {
                    if (!isReviewAgentMode) return@post
                    board.setHintMove(best.move)
                    savedBoardState = board.getBoard()
                    updateReviewAgentStatus(false)
                    tvAiHint?.text = "复盘代理($sideName): $moveName (评分:${best.score})，请手动执行"
                    showFloatingToast("复盘代理: $sideName $moveName")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Review agent error: ${e.message}", e)
                mainHandler.post { tvAiHint?.text = "复盘代理出错: ${e.message}" }
            }
        }.start()
    }

    /** 更新复盘代理状态栏显示
     * @param waitingForOpponent true=等待输入对手走法(蓝色), false=AI指引中(绿色)
     */
    private fun updateReviewAgentStatus(waitingForOpponent: Boolean) {
        if (!isReviewAgentMode) return
        val sideName = if (currentTurn == RED) "红方" else "黑方"
        if (waitingForOpponent) {
            tvReviewAgentStatus?.setBackgroundColor(0xFF1565C0.toInt()) // 蓝色
            tvReviewAgentStatus?.text = "【输入模式】请在棋盘上记录 ${sideName} 的走法"
        } else {
            tvReviewAgentStatus?.setBackgroundColor(0xFF2E7D32.toInt()) // 绿色
            tvReviewAgentStatus?.text = "【指引模式】请在真实棋盘执行 ${sideName} 的走法"
        }
    }

    // ==================== 工具 ====================

    private fun dp2px(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    // ==================== 棋谱查看 Dialog（使用 WindowManager，避免 Service 中 AlertDialog 崩溃） ====================

    private var bookDialogView: View? = null

    private fun showBookDialog() {
        try {
            // 如果已有 Dialog 在显示，先关闭
            if (bookDialogView != null) {
                windowManager?.removeView(bookDialogView)
                bookDialogView = null
            }

            val entries = OpeningBook.getAllEntries()

            val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_book_view, null)
            bookDialogView = dialogView

            val tvStats = dialogView.findViewById<TextView>(R.id.tvBookStats)
            val lvEntries = dialogView.findViewById<android.widget.ListView>(R.id.lvBookEntries)
            val btnExport = dialogView.findViewById<Button>(R.id.btnDialogExport)
            val btnImport = dialogView.findViewById<Button>(R.id.btnDialogImport)
            val btnClear = dialogView.findViewById<Button>(R.id.btnDialogClearBook)
            val btnClose = dialogView.findViewById<Button>(R.id.btnDialogClose)

            tvStats.text = OpeningBook.stats()

            val adapter = BookEntryAdapter(this, entries)
            lvEntries.adapter = adapter

            // 使用 WindowManager 显示（Service 中不能用 AlertDialog）
            val params = WindowManager.LayoutParams(
                dp2px(320),
                dp2px(420),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
                x = 0
                y = 0
            }

            windowManager?.addView(dialogView, params)

            val dismissDialog = {
                try {
                    if (bookDialogView != null) {
                        windowManager?.removeView(bookDialogView)
                        bookDialogView = null
                    }
                } catch (_: Exception) {}
            }

            btnExport.setOnClickListener {
                // 启动 MainActivity 打开 SAF 目录选择器
                val intent = Intent(this@FloatWindowService, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra("open_export", true)
                }
                startActivity(intent)
            }

            btnImport.setOnClickListener {
                // 先隐藏棋谱对话框，避免遮挡系统文件选择器
                dismissDialog()
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra("open_import", true)
                }
                startActivity(intent)
                showFloatingToast("已打开文件选择器，请选择棋谱文件")
            }

            btnClear.setOnClickListener {
                showConfirmOverlay(
                    "确定要清除所有积累的棋谱吗？",
                    onConfirm = {
                        OpeningBook.clear(this)
                        tvStats.text = OpeningBook.stats()
                        adapter.clear()
                        adapter.notifyDataSetChanged()
                        tvTrainProgress?.text = "棋谱已清除"
                        showFloatingToast("棋谱已清除")
                    }
                )
            }

            btnClose.setOnClickListener { dismissDialog() }

        } catch (e: Exception) {
            Log.e(TAG, "showBookDialog error: ${e.message}", e)
            showFloatingToast("无法显示棋谱: ${e.message}")
        }
    }

    /** 导入文件选择器：默认列出应用外部目录下的 .txt 文件 */
    private fun showImportFilePicker(onImported: (Int) -> Unit) {
        val externalDir = getExternalFilesDir(null)
        val files = externalDir?.listFiles { _, name -> name.endsWith(".txt") } ?: emptyArray()

        if (files.isEmpty()) {
            showFloatingToast("默认目录下未找到 .txt 文件\n路径: ${externalDir?.absolutePath}", 3500)
            return
        }

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(0xFF1A1A2E.toInt())
            setPadding(dp2px(12), dp2px(12), dp2px(12), dp2px(12))
        }

        container.addView(android.widget.TextView(this).apply {
            text = "选择要导入的棋谱文件"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp2px(8))
        })

        container.addView(android.widget.TextView(this).apply {
            text = "默认目录: ${externalDir?.absolutePath}"
            setTextColor(0xFFB3E5FC.toInt())
            textSize = 10f
            setPadding(0, 0, 0, dp2px(8))
        })

        val listView = android.widget.ListView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                dp2px(200)
            )
        }
        val fileAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            files.map { it.name }
        )
        listView.adapter = fileAdapter
        container.addView(listView)

        val btnClose = android.widget.Button(this).apply {
            text = "关闭"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF424242.toInt())
        }
        container.addView(btnClose)

        val params = WindowManager.LayoutParams(
            dp2px(300),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        windowManager?.addView(container, params)

        var pickerView: View? = container
        val dismiss = {
            try {
                if (pickerView != null) {
                    windowManager?.removeView(pickerView)
                    pickerView = null
                }
            } catch (_: Exception) {}
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val selectedFile = files[position]
            val count = OpeningBook.importFromFile(selectedFile)
            dismiss()
            showFloatingToast(
                if (count >= 0) "成功导入 $count 条记录" else "导入失败"
            )
            onImported(count)
        }

        btnClose.setOnClickListener { dismiss() }
    }

    /** 使用 MediaStore 导出棋谱到公共 Downloads 目录 */
    private fun exportBookToDownloads(): Int {
        val entries = OpeningBook.getAllEntries()
        if (entries.isEmpty()) return 0

        val content = StringBuilder()
        content.append("# ChessOpeningBook v1\n")
        for ((hash, entry) in entries) {
            val hex = hash.toULong().toString(16)
            val m = entry.move
            content.append("$hex:${m.fromRow},${m.fromCol},${m.toRow},${m.toCol}:${entry.wins}:${entry.losses}:${entry.draws}\n")
        }

        val resolver = contentResolver
        val filename = "ChessBook_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())}.txt"

        val values = android.content.ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }

        val uri = resolver.insert(collection, values) ?: return -1

        return try {
            resolver.openOutputStream(uri)?.use { output ->
                output.write(content.toString().toByteArray())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                // Android 9 及以下：触发媒体扫描，确保第三方文件管理器可见
                val projection = arrayOf(MediaStore.Downloads.DATA)
                resolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATA))
                        android.media.MediaScannerConnection.scanFile(this, arrayOf(path), arrayOf("text/plain"), null)
                    }
                }
            }
            entries.size
        } catch (e: Exception) {
            e.printStackTrace()
            -1
        }
    }

    /** 简单的二次确认 overlay */
    private fun showConfirmOverlay(message: String, onConfirm: () -> Unit) {
        try {
            val view = LayoutInflater.from(this).inflate(
                android.R.layout.simple_list_item_1, null, false
            )
            // 使用自定义简易确认布局太复杂，直接用 Toast + 再次点击确认的方式
            // 更简单：直接执行确认（因为清除按钮本身就在查看页面里，已经不易误触）
            onConfirm()
        } catch (e: Exception) {
            Log.e(TAG, "confirm error: ${e.message}", e)
        }
    }

    // ==================== 棋谱列表适配器 ====================

    private class BookEntryAdapter(
        context: Context,
        entries: List<Pair<Long, OpeningBook.BookEntry>>
    ) : ArrayAdapter<Pair<Long, OpeningBook.BookEntry>>(context, 0, entries.toMutableList()) {

        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_book_entry, parent, false)
            val (hash, entry) = getItem(position)!!
            val m = entry.move

            view.findViewById<TextView>(R.id.tvBookMove).text = "${m.fromRow},${m.fromCol} → ${m.toRow},${m.toCol}"
            view.findViewById<TextView>(R.id.tvBookRecord).text = "${entry.wins}胜 ${entry.losses}负 ${entry.draws}和"
            view.findViewById<TextView>(R.id.tvBookRate).text = "${(entry.winRate * 100).toInt()}%"

            return view
        }
    }

    // ==================== 顶层提示（替代 Toast，避免被悬浮窗遮挡） ====================

    /** 使用 WindowManager 显示自定义提示，确保在所有悬浮窗之上 */
    private fun showFloatingToast(message: String, duration: Long = 2000) {
        mainHandler.post {
            try {
                val textView = TextView(this).apply {
                    text = message
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 14f
                    setBackgroundColor(0xCC000000.toInt())
                    setPadding(dp2px(16), dp2px(8), dp2px(16), dp2px(8))
                    gravity = Gravity.CENTER
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else
                        WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    y = dp2px(120)
                }

                windowManager?.addView(textView, params)

                mainHandler.postDelayed({
                    try {
                        windowManager?.removeView(textView)
                    } catch (_: Exception) {}
                }, duration)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}