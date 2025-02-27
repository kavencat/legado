package io.legado.app.ui.book.manga

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.integration.recyclerview.RecyclerViewPreloader
import com.bumptech.glide.request.target.Target.SIZE_ORIGINAL
import com.bumptech.glide.util.FixedPreloadSizeProvider
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.databinding.ActivityMangeBinding
import io.legado.app.databinding.ViewLoadMoreBinding
import io.legado.app.help.book.isImage
import io.legado.app.help.config.AppConfig
import io.legado.app.help.storage.Backup
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.ReadManga
import io.legado.app.model.ReadManga.mFirstLoading
import io.legado.app.model.recyclerView.MangeContent
import io.legado.app.model.recyclerView.ReaderLoading
import io.legado.app.receiver.NetworkChangedListener
import io.legado.app.ui.book.changesource.ChangeBookSourceDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.manga.rv.MangaAdapter
import io.legado.app.ui.book.read.MangaMenu
import io.legado.app.ui.book.read.ReadBookActivity.Companion.RESULT_DELETED
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.widget.recycler.LoadMoreView
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.gone
import io.legado.app.utils.immersionFullScreen
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import splitties.dimensions.dp

class ReadMangaActivity : VMBaseActivity<ActivityMangeBinding, MangaViewModel>(),
    ReadManga.Callback, ChangeBookSourceDialog.CallBack, MangaMenu.CallBack {

    private val mLayoutManager by lazy {
        LinearLayoutManager(this@ReadMangaActivity)
    }
    private val mAdapter: MangaAdapter by lazy {
        MangaAdapter(this@ReadMangaActivity)
    }
    private val mSizeProvider by lazy {
        FixedPreloadSizeProvider<Any>(
            this@ReadMangaActivity.resources.displayMetrics.widthPixels,
            SIZE_ORIGINAL
        )
    }

    private val mRecyclerViewPreloader by lazy {
        RecyclerViewPreloader(Glide.with(this), mAdapter, mSizeProvider, 10)
    }

    private val networkChangedListener by lazy {
        NetworkChangedListener(this)
    }

    private var justInitData: Boolean = false
    private var syncDialog: AlertDialog? = null

    private val loadMoreView by lazy {
        LoadMoreView(this).apply {
            setBackgroundColor(getCompatColor(R.color.book_ant_10))
            getLoading().loadingColor = getCompatColor(R.color.white)
            getLoadingText().setTextColor(getCompatColor(R.color.white))
        }
    }

    private val windowInsetsControllerCompat by lazy {
        WindowInsetsControllerCompat(window, binding.root)
    }

    //打开目录返回选择章节返回结果
    private val tocActivity =
        registerForActivityResult(TocActivityResult()) {
            it?.let {
                binding.flLoading.isVisible = true
                viewModel.openChapter(it.first, it.second)
            }
        }
    private val bookInfoActivity =
        registerForActivityResult(StartActivityContract(BookInfoActivity::class.java)) {
            if (it.resultCode == RESULT_OK) {
                setResult(RESULT_DELETED)
                super.finish()
            }
        }
    override val binding by viewBinding(ActivityMangeBinding::inflate)
    override val viewModel by viewModels<MangaViewModel>()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        immersionFullScreen(windowInsetsControllerCompat)
        ReadManga.register(this)
        binding.mRecyclerMange.run {
            adapter = mAdapter
            itemAnimator = null
            layoutManager = mLayoutManager
            setHasFixedSize(true)
            mLayoutManager.initialPrefetchItemCount = 4
            mLayoutManager.isItemPrefetchEnabled = true
            setItemViewCacheSize(AppConfig.preDownloadNum)
            setPreScrollListener { _, _, dy, position ->
                if (dy > 0 && position + 2 > mAdapter.getCurrentList().size - 3) {
                    if (mAdapter.getCurrentList().last() is ReaderLoading) {
                        val nextIndex =
                            (mAdapter.getCurrentList().last() as ReaderLoading).mNextChapterIndex
                        if (nextIndex != -1) {
                            scrollToBottom(false, nextIndex)
                        }
                    }
                }
            }
            setNestedPreScrollListener { _, _, _, position ->
                if (mAdapter.getCurrentList()
                        .isNotEmpty() && position <= mAdapter.getCurrentList().lastIndex
                ) {
                    try {
                        val content = mAdapter.getCurrentList()[position]
                        if (content is MangeContent) {
                            ReadManga.durChapterPos = content.mDurChapterPos.minus(1)
                            upText(
                                content.mChapterPagePos,
                                content.mChapterPageCount,
                                content.mDurChapterPos,
                                content.mDurChapterCount
                            )
                        }
                    } catch (e: Exception) {
                        e.printOnDebug()
                    }

                }
            }
            addOnScrollListener(mRecyclerViewPreloader)
            onToucheMiddle {
                if (!binding.mangaMenu.isVisible) {
                    binding.mangaMenu.runMenuIn()
                }
            }
        }
        binding.retry.setOnClickListener {
            binding.llLoading.isVisible = true
            binding.llRetry.isGone = true
            mFirstLoading = false
            ReadManga.loadContent()
        }

        mAdapter.addFooterView {
            ViewLoadMoreBinding.bind(loadMoreView)
        }
        loadMoreView.setOnClickListener {
            if (!loadMoreView.isLoading && !ReadManga.gameOver) {
                scrollToBottom(true, ReadManga.durChapterPagePos)
            }
        }
        loadMoreView.gone()
        binding.mangaMenu.setTitleBarPadding(
            ViewCompat.getRootWindowInsets(findViewById(android.R.id.content))?.getInsets(
                WindowInsetsCompat.Type.statusBars()
            )?.top ?: dp(25)
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        viewModel.initData(intent)
    }

    private fun scrollToBottom(forceLoad: Boolean = false, index: Int) {
        if ((loadMoreView.hasMore && !loadMoreView.isLoading) && !ReadManga.gameOver || forceLoad) {
            loadMoreView.hasMore()
            ReadManga.moveToNextChapter(index)
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        Looper.myQueue().addIdleHandler {
            viewModel.initData(intent)
            false
        }
        justInitData = true
    }

    override fun loadContentFinish(list: MutableList<Any>) {
        if (!this.isDestroyed) {
            mAdapter.submitList(list) {
                if (!mFirstLoading) {
                    if (list.size > 1) {
                        binding.infobar.isVisible = true
                        upText(
                            ReadManga.durChapterPagePos,
                            ReadManga.durChapterPageCount,
                            ReadManga.durChapterPos,
                            ReadManga.durChapterCount
                        )
                    }

                    if (ReadManga.durChapterPos + 2 > mAdapter.getCurrentList().size - 3) {
                        val nextIndex =
                            (mAdapter.getCurrentList().last() as ReaderLoading).mNextChapterIndex
                        scrollToBottom(index = nextIndex)
                    } else {
                        binding.mRecyclerMange.scrollToPosition(ReadManga.durChapterPos)
                    }
                }

                if (ReadManga.chapterChanged) {
                    binding.mRecyclerMange.scrollToPosition(ReadManga.durChapterPos)
                }

                ReadManga.chapterChanged = false
                loadMoreView.visible()
                mFirstLoading = true
                loadMoreView.stopLoad()
            }
        }
    }

    private fun upText(
        chapterPagePos: Int, chapterPageCount: Int, chapterPos: Int, chapterCount: Int,
    ) {
        binding.infobar.update(
            chapterPagePos,
            chapterPageCount,
            chapterPagePos.minus(1f).div(chapterPageCount.minus(1f)),
            chapterPos,
            chapterCount
        )
    }

    override fun onResume() {
        super.onResume()
        networkChangedListener.register()
        networkChangedListener.onNetworkChanged = {
            // 当网络是可用状态且无需初始化时同步进度（初始化中已有同步进度逻辑）
            if (AppConfig.syncBookProgressPlus && NetworkUtils.isAvailable() && !justInitData) {
                ReadManga.syncProgress({ progress -> sureNewProgress(progress) })
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (ReadManga.inBookshelf) {
            ReadManga.saveRead()
            if (!BuildConfig.DEBUG) {
                if (AppConfig.syncBookProgressPlus) {
                    ReadManga.syncProgress()
                } else {
                    ReadManga.uploadProgress()
                }
                Backup.autoBack(this)
            }
        }
        networkChangedListener.unRegister()
    }

    override fun loadComplete() {
        binding.flLoading.isGone = true
    }

    override fun loadFail(msg: String) {
        if (!mFirstLoading || ReadManga.chapterChanged) {
            binding.llLoading.isGone = true
            binding.llRetry.isVisible = true
            binding.tvMsg.text = msg
        } else {
            loadMoreView.error(null, "加载失败，点击重试")
        }
    }

    override fun noData() {
        loadMoreView.noMore("暂无章节了！")
    }

    override fun adjustmentProgress() {
        if (ReadManga.chapterChanged) {
            binding.mRecyclerMange.scrollToPosition(ReadManga.durChapterPos)
            binding.flLoading.isGone = true
        }
    }

    override val chapterList: MutableList<Any>
        get() = mAdapter.getCurrentList()

    override fun onDestroy() {
        ReadManga.unregister()
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Glide.get(this).clearMemory()
    }

    override fun sureNewProgress(progress: BookProgress) {
        syncDialog?.dismiss()
        syncDialog = alert(R.string.get_book_progress) {
            setMessage(R.string.cloud_progress_exceeds_current)
            okButton {
                binding.flLoading.isVisible = true
                ReadManga.setProgress(progress)
            }
            noButton()
        }
    }

    override val oldBook: Book?
        get() = ReadManga.book

    override fun changeTo(source: BookSource, book: Book, toc: List<BookChapter>) {
        if (book.isImage) {
            binding.flLoading.isVisible = true
            ReadManga.chapterChanged = true
            viewModel.changeTo(book, toc)
        } else {
            toastOnUi("所选择的源不是漫画源")
        }
    }


    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.book_manga, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    /**
     * 菜单
     */
    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_change_source -> {
                binding.mangaMenu.runMenuOut()
                ReadManga.book?.let {
                    showDialogFragment(ChangeBookSourceDialog(it.name, it.author))
                }
            }

            R.id.menu_catalog -> {
                ReadManga.book?.let {
                    tocActivity.launch(it.bookUrl)
                }
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun openBookInfoActivity() {
        ReadManga.book?.let {
            bookInfoActivity.launch {
                putExtra("name", it.name)
                putExtra("author", it.author)
            }
        }
    }

    override fun upSystemUiVisibility(value: Boolean) {
        binding.mangaMenu.isVisible = value
        immersionFullScreen(WindowInsetsControllerCompat(window, binding.root))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action
        val isDown = action == 0

        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (isDown && !binding.mangaMenu.canShowMenu) {
                binding.mangaMenu.runMenuIn()
                return true
            }
            if (!isDown && !binding.mangaMenu.canShowMenu) {
                binding.mangaMenu.canShowMenu = true
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

}